import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { deleteApp, initializeApp, App } from "firebase-admin/app";
import { getFirestore as getAdminFirestore } from "firebase-admin/firestore";
import {
  collection, doc, documentId, Firestore, getDocFromServer, getDocsFromServer, limit, orderBy, query,
  QueryConstraint, where,
} from "firebase/firestore";
import { createRulesEnvironment, PROJECT, SCHOOL } from "./rules-fixtures";
import { ASSIGNMENT_SECTIONS_FIELD, rebuildTeacherScopes, syncTeacherAssignment } from "../src/teacherScope";

// Teachers read only what their assignments cover: their own teacherAssignments, the students of sections
// they teach in, and the assessments and marks of their section + subject pairs. No result snapshots.
describe("teacher read scope", () => {
  let env: Awaited<ReturnType<typeof createRulesEnvironment>>;
  let adminApp: App;

  beforeAll(async () => {
    env = await createRulesEnvironment();
    adminApp = initializeApp({ projectId: PROJECT }, "teacher-scope-sync");
  });
  afterAll(async () => {
    await env?.cleanup();
    if (adminApp) await deleteApp(adminApp);
  });

  /** 30 sections "s00".."s29": the most an `in` filter takes. */
  const MANY = Array.from({ length: 30 }, (_, i) => `s${String(i).padStart(2, "0")}`);

  beforeEach(async () => {
    await env.clearFirestore();
    await env.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      const assignment = (sectionId: string, subjectId: string, teacherUid: string) => ({
        [`teacherAssignments/${sectionId}_${subjectId}_${teacherUid}`]: { sectionId, subjectId, teacherUid },
      });
      const fixtures: Record<string, Record<string, unknown>> = {
        "users/admin-uid": { role: "ADMIN" },
        // assignmentSections as onTeacherAssignmentWrite writes it for the assignments below.
        "users/teacher-uid": { role: "TEACHER", assignmentSections: { "g1_a_math_teacher-uid": "g1_a" } },
        "users/other-teacher": { role: "TEACHER", assignmentSections: { "g2_a_history_other-teacher": "g2_a" } },
        "users/busy-teacher": {
          role: "TEACHER", assignmentSections: Object.fromEntries(MANY.map((s) => [`${s}_math_busy-teacher`, s])),
        },
        "users/student-x": { role: "STUDENT", linkedStudentId: "student-x" },
        ...assignment("g1_a", "math", "teacher-uid"),
        ...assignment("g2_a", "history", "other-teacher"),
        "students/student-x": { studentId: "student-x", sectionId: "g1_a" },
        "students/a1": { studentId: "a1", sectionId: "g1_a" },
        "students/b1": { studentId: "b1", sectionId: "g2_a" },
        "assessmentCycles/c1": { published: true },
        "assessments/math-g1a": { cycleId: "c1", sectionId: "g1_a", subjectId: "math", maxMarks: 100 },
        // Same section, a subject teacher-uid is not assigned.
        "assessments/science-g1a": { cycleId: "c1", sectionId: "g1_a", subjectId: "science", maxMarks: 100 },
        // A section teacher-uid does not teach in at all.
        "assessments/history-g2a": { cycleId: "c1", sectionId: "g2_a", subjectId: "history", maxMarks: 100 },
        "scores/a1-math": { scoreId: "a1-math", assessmentId: "math-g1a", studentId: "a1", obtainedMarks: 70 },
        "scores/a1-science": { scoreId: "a1-science", assessmentId: "science-g1a", studentId: "a1", obtainedMarks: 60 },
        "scores/b1-history": { scoreId: "b1-history", assessmentId: "history-g2a", studentId: "b1", obtainedMarks: 50 },
        "resultSnapshots/c1_a1": { cycleId: "c1", sectionId: "g1_a", studentId: "a1", overallPercentage: 65 },
        "resultSnapshots/c1_b1": { cycleId: "c1", sectionId: "g2_a", studentId: "b1", overallPercentage: 50 },
      };
      MANY.forEach((s, i) => {
        Object.assign(fixtures, assignment(s, "math", "busy-teacher"));
        fixtures[`students/many-${s}`] = { studentId: `many-${s}`, sectionId: s };
        fixtures[`assessments/many-${s}`] = { cycleId: "c1", sectionId: s, subjectId: "math", maxMarks: 100 };
        fixtures[`scores/many-${s}`] = {
          scoreId: `many-${s}`, assessmentId: `many-${s}`, studentId: `many-${s}`, obtainedMarks: i,
        };
      });
      await Promise.all(Object.entries(fixtures).map(([relative, data]) => db.doc(`schools/${SCHOOL}/${relative}`).set(data)));
    });
  });

  const as = (uid: string) => env.authenticatedContext(uid).firestore() as unknown as Firestore;
  const col = (db: Firestore, name: string) => collection(db, `schools/${SCHOOL}/${name}`);
  const get = (db: Firestore, path: string) => getDocFromServer(doc(db, `schools/${SCHOOL}/${path}`));
  /** The app's query shape: filters, orderBy(__name__), limit 500, from the server. */
  const list = async (db: Firestore, name: string, ...filters: QueryConstraint[]) =>
    (await getDocsFromServer(query(col(db, name), ...filters, orderBy(documentId()), limit(500)))).docs.map((d) => d.id);

  describe("assigned teacher (teacher-uid teaches math in g1_a)", () => {
    const db = () => as("teacher-uid");

    test.each([
      "teacherAssignments/g1_a_math_teacher-uid", "students/a1", "assessments/math-g1a", "scores/a1-math",
    ])("can get %s", async (path) => {
      expect((await assertSucceeds(get(db(), path))).exists()).toBe(true);
    });

    test("can query its own assignments", async () => {
      expect(await assertSucceeds(list(db(), "teacherAssignments", where("teacherUid", "==", "teacher-uid"))))
        .toEqual(["g1_a_math_teacher-uid"]);
    });

    test("can query the students of its section, by == and by in", async () => {
      expect(await assertSucceeds(list(db(), "students", where("sectionId", "==", "g1_a")))).toEqual(["a1", "student-x"]);
      expect(await assertSucceeds(list(db(), "students", where("sectionId", "in", ["g1_a"])))).toEqual(["a1", "student-x"]);
    });

    test("can query the assessments of its pair (subjectId and sectionId both pinned)", async () => {
      expect(await assertSucceeds(list(db(), "assessments", where("subjectId", "==", "math"), where("sectionId", "==", "g1_a"))))
        .toEqual(["math-g1a"]);
      expect(await assertSucceeds(list(db(), "assessments", where("subjectId", "==", "math"), where("sectionId", "in", ["g1_a"]))))
        .toEqual(["math-g1a"]);
    });

    test("can query the marks of its assessments", async () => {
      expect(await assertSucceeds(list(db(), "scores", where("assessmentId", "==", "math-g1a")))).toEqual(["a1-math"]);
      expect(await assertSucceeds(list(db(), "scores", where("assessmentId", "in", ["math-g1a"])))).toEqual(["a1-math"]);
    });
  });

  describe("unassigned section (g2_a belongs to other-teacher)", () => {
    const db = () => as("teacher-uid");

    test.each([
      "students/b1", "assessments/history-g2a", "scores/b1-history", "resultSnapshots/c1_b1",
      "teacherAssignments/g2_a_history_other-teacher",
    ])("cannot get %s", async (path) => {
      await assertFails(get(db(), path));
    });

    test("cannot query that section's students, assessments, marks, snapshots or assignments", async () => {
      await assertFails(list(db(), "students", where("sectionId", "==", "g2_a")));
      await assertFails(list(db(), "students", where("sectionId", "in", ["g1_a", "g2_a"])));
      await assertFails(list(db(), "assessments", where("subjectId", "==", "history"), where("sectionId", "==", "g2_a")));
      await assertFails(list(db(), "scores", where("assessmentId", "==", "history-g2a")));
      await assertFails(list(db(), "scores", where("assessmentId", "in", ["math-g1a", "history-g2a"])));
      await assertFails(list(db(), "resultSnapshots", where("sectionId", "==", "g2_a")));
      await assertFails(list(db(), "teacherAssignments", where("teacherUid", "==", "other-teacher")));
    });

    test.each(["students", "assessments", "scores", "resultSnapshots", "teacherAssignments"])(
      "cannot query the whole %s collection", async (name) => {
        await assertFails(list(db(), name));
      });

    test("the other teacher still reads its own section", async () => {
      const other = as("other-teacher");
      expect(await assertSucceeds(list(other, "students", where("sectionId", "==", "g2_a")))).toEqual(["b1"]);
      expect((await assertSucceeds(get(other, "scores/b1-history"))).exists()).toBe(true);
      await assertFails(get(other, "students/a1"));
    });
  });

  describe("assigned section, unassigned subject (science in g1_a)", () => {
    const db = () => as("teacher-uid");

    test.each(["assessments/science-g1a", "scores/a1-science"])("cannot get %s", async (path) => {
      await assertFails(get(db(), path));
    });

    test("cannot query that subject's assessments or marks", async () => {
      await assertFails(list(db(), "assessments", where("subjectId", "==", "science"), where("sectionId", "==", "g1_a")));
      await assertFails(list(db(), "assessments", where("sectionId", "==", "g1_a")));
      await assertFails(list(db(), "scores", where("assessmentId", "==", "science-g1a")));
    });
  });

  describe("result snapshots", () => {
    test("a teacher cannot read any, not even its own section's", async () => {
      const db = as("teacher-uid");
      await assertFails(get(db, "resultSnapshots/c1_a1"));
      await assertFails(list(db, "resultSnapshots", where("sectionId", "==", "g1_a")));
      await assertFails(list(db, "resultSnapshots", where("studentId", "==", "a1"), where("cycleId", "==", "c1")));
    });

    test("an admin still reads them all", async () => {
      expect(await assertSucceeds(list(as("admin-uid"), "resultSnapshots"))).toEqual(["c1_a1", "c1_b1"]);
    });
  });

  describe("admin and student access is unchanged", () => {
    test.each(["students", "assessments", "scores", "teacherAssignments"])("admin reads the whole %s collection", async (name) => {
      await assertSucceeds(list(as("admin-uid"), name));
    });

    test("a student reads its own record and any assessment, but no one else's record or marks", async () => {
      const db = as("student-x");
      await assertSucceeds(get(db, "students/student-x"));
      await assertSucceeds(get(db, "assessments/history-g2a"));
      await assertFails(get(db, "students/a1"));
      await assertFails(get(db, "scores/a1-math"));
      await assertFails(list(db, "teacherAssignments"));
    });
  });

  // The app sends `in` filters of up to 30 values. Section and pair checks read only the caller's profile,
  // so 30 values are fine. A score check also reads its assessment: one access call per assessmentId on
  // top of the profile's two (exists + get). Firestore documents 10 calls per query (this emulator allows
  // 20: 18 ids pass, 19 fail), so the app should ask for at most 8 assessment ids per scores query.
  describe("query cost for a teacher with 30 assignments", () => {
    const db = () => as("busy-teacher");

    test("students and assessments: 30 sections in one query", async () => {
      expect(await assertSucceeds(list(db(), "students", where("sectionId", "in", MANY)))).toHaveLength(30);
      expect(await assertSucceeds(list(db(), "assessments", where("subjectId", "==", "math"), where("sectionId", "in", MANY))))
        .toHaveLength(30);
    });

    test("scores: 8 assessment ids per query fit a 10-call budget", async () => {
      const ids = MANY.slice(0, 8).map((s) => `many-${s}`);
      expect(await assertSucceeds(list(db(), "scores", where("assessmentId", "in", ids)))).toHaveLength(8);
    });

    test("scores: 30 assessment ids in one query exceed the rules' access-call limit", async () => {
      await assertFails(list(db(), "scores", where("assessmentId", "in", MANY.map((s) => `many-${s}`))));
    });
  });

  // onTeacherAssignmentWrite runs syncTeacherAssignment; here it is called directly with the Admin SDK.
  describe("the profile scope follows teacherAssignments", () => {
    const adminDb = () => getAdminFirestore(adminApp);
    const school = () => adminDb().collection("schools").doc(SCHOOL);

    beforeEach(async () => {
      await school().collection("users").doc("new-teacher").set({ role: "TEACHER" });
    });

    test("assigning grants the section and pair, unassigning takes them back", async () => {
      const db = () => as("new-teacher");
      await assertFails(list(db(), "students", where("sectionId", "==", "g2_a")));

      const ref = school().collection("teacherAssignments").doc("g2_a_history_new-teacher");
      await ref.set({ sectionId: "g2_a", subjectId: "history", teacherUid: "new-teacher" });
      await syncTeacherAssignment(adminDb(), SCHOOL, ref.id, [undefined, "new-teacher"]);
      expect((await school().collection("users").doc("new-teacher").get()).get(ASSIGNMENT_SECTIONS_FIELD))
        .toEqual({ "g2_a_history_new-teacher": "g2_a" });
      expect(await assertSucceeds(list(db(), "students", where("sectionId", "==", "g2_a")))).toEqual(["b1"]);
      expect((await assertSucceeds(get(db(), "scores/b1-history"))).exists()).toBe(true);

      await ref.delete();
      await syncTeacherAssignment(adminDb(), SCHOOL, ref.id, ["new-teacher", undefined]);
      expect((await school().collection("users").doc("new-teacher").get()).get(ASSIGNMENT_SECTIONS_FIELD)).toEqual({});
      await assertFails(list(db(), "students", where("sectionId", "==", "g2_a")));
      await assertFails(get(db(), "scores/b1-history"));
    });

    test("an assignment whose id does not match its fields grants nothing", async () => {
      const ref = school().collection("teacherAssignments").doc("g1_a_math_teacher-uid-copy");
      await ref.set({ sectionId: "g2_a", subjectId: "history", teacherUid: "new-teacher" });
      await syncTeacherAssignment(adminDb(), SCHOOL, ref.id, ["new-teacher"]);
      expect((await school().collection("users").doc("new-teacher").get()).get(ASSIGNMENT_SECTIONS_FIELD)).toBeUndefined();
    });

    test("re-pointing an assignment's teacherUid without changing its id revokes it from both teachers", async () => {
      const ref = school().collection("teacherAssignments").doc("g2_a_history_other-teacher");
      await ref.set({ sectionId: "g2_a", subjectId: "history", teacherUid: "new-teacher" });
      await syncTeacherAssignment(adminDb(), SCHOOL, ref.id, ["other-teacher", "new-teacher"]);
      // The id still names other-teacher and the fields name new-teacher: neither gets it from this document.
      expect((await school().collection("users").doc("other-teacher").get()).get(ASSIGNMENT_SECTIONS_FIELD)).toEqual({});
      expect((await school().collection("users").doc("new-teacher").get()).get(ASSIGNMENT_SECTIONS_FIELD)).toBeUndefined();
    });

    test("rebuildTeacherScopes restores every teacher's map from teacherAssignments", async () => {
      await school().collection("users").doc("teacher-uid").update({ [ASSIGNMENT_SECTIONS_FIELD]: { stale_key_teacher: "g2_a" } });
      const summary = await rebuildTeacherScopes(adminDb(), [SCHOOL]);
      expect(summary).toEqual({ schools: 1, teachers: 4, assignments: 32 });
      const scopeOf = async (uid: string) => (await school().collection("users").doc(uid).get()).get(ASSIGNMENT_SECTIONS_FIELD);
      expect(await scopeOf("teacher-uid")).toEqual({ "g1_a_math_teacher-uid": "g1_a" });
      expect(await scopeOf("other-teacher")).toEqual({ "g2_a_history_other-teacher": "g2_a" });
      expect(await scopeOf("new-teacher")).toEqual({});
      expect(Object.keys(await scopeOf("busy-teacher"))).toHaveLength(30);
      expect(await scopeOf("admin-uid")).toBeUndefined();
    });
  });
});
