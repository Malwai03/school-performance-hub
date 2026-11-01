import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import {
  collection, doc, documentId, Firestore, getDoc, getDocFromServer, getDocsFromServer, limit, orderBy, query,
  QueryConstraint, startAfter, Timestamp, where,
} from "firebase/firestore";
import { createRulesEnvironment, SCHOOL, OTHER_SCHOOL } from "./rules-fixtures";

// The Android app's inbound sync (PR #15) as Firestore queries. Firestore refuses a whole query unless the
// rules allow every document it could return, so each query is mirrored here with the app's exact
// filters, ordering and limits (FirestoreInboundRemote, InboundScope, getStudentTrend).
describe("android inbound sync queries", () => {
  let env: Awaited<ReturnType<typeof createRulesEnvironment>>;

  beforeAll(async () => { env = await createRulesEnvironment(); });
  afterAll(async () => { await env?.cleanup(); });

  const at = (seconds: number) => new Timestamp(1_760_000_000 + seconds, 0);

  beforeEach(async () => {
    await env.clearFirestore();
    await env.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      const profile = (uid: string, role: string, extra: Record<string, unknown> = {}) => ({
        uid, schoolId: SCHOOL, role, displayName: uid, code: `${uid}-code`, active: true, ...extra,
      });
      const fixtures: Record<string, Record<string, unknown>> = {
        "users/admin-uid": profile("admin-uid", "ADMIN"),
        "users/teacher-uid": profile("teacher-uid", "TEACHER"),
        "users/other-teacher": profile("other-teacher", "TEACHER"),
        // As createManagedUserAccount writes a student: auth uid = student id, linked by linkedStudentId.
        "users/student-x": profile("student-x", "STUDENT", {
          mustChangePassword: true, linkedStudentId: "student-x", sectionId: "g1_a",
        }),
        "sections/g1_a": { sectionId: "g1_a", grade: 1, sectionName: "A" },
        "sections/g1_b": { sectionId: "g1_b", grade: 1, sectionName: "B" },
        "sections/g2_a": { sectionId: "g2_a", grade: 2, sectionName: "A" },
        "subjects/math": { subjectId: "math", grade: 1, name: "Math" },
        "subjects/science": { subjectId: "science", grade: 1, name: "Science" },
        "subjects/history": { subjectId: "history", grade: 2, name: "History" },
        "students/student-x": { studentId: "student-x", sectionId: "g1_a", authUid: "student-x" },
        "students/student-y": { studentId: "student-y", sectionId: "g1_a" },
        "students/student-z": { studentId: "student-z", sectionId: "g1_b" },
        "students/student-w": { studentId: "student-w", sectionId: "g2_a" },
        "assessmentCycles/c1": { cycleId: "c1", name: "Term 1", published: true },
        "assessmentCycles/c2": { cycleId: "c2", name: "Term 2", published: true },
        "assessmentCycles/c3": { cycleId: "c3", name: "Term 3", published: false },
        "assessments/math-g1a": { assessmentId: "math-g1a", cycleId: "c1", sectionId: "g1_a", subjectId: "math", maxMarks: 100 },
        "assessments/science-g1a": { assessmentId: "science-g1a", cycleId: "c1", sectionId: "g1_a", subjectId: "science", maxMarks: 100 },
        "assessments/science-g1b": { assessmentId: "science-g1b", cycleId: "c1", sectionId: "g1_b", subjectId: "science", maxMarks: 100 },
        "assessments/history-g2a": { assessmentId: "history-g2a", cycleId: "c1", sectionId: "g2_a", subjectId: "history", maxMarks: 100 },
        "teacherAssignments/g1_a_math_teacher-uid": {
          assignmentId: "g1_a_math_teacher-uid", sectionId: "g1_a", subjectId: "math", teacherUid: "teacher-uid",
        },
        "teacherAssignments/g1_b_science_teacher-uid": {
          assignmentId: "g1_b_science_teacher-uid", sectionId: "g1_b", subjectId: "science", teacherUid: "teacher-uid",
        },
        "teacherAssignments/g2_a_history_other-teacher": {
          assignmentId: "g2_a_history_other-teacher", sectionId: "g2_a", subjectId: "history", teacherUid: "other-teacher",
        },
        "scores/x-math": { scoreId: "x-math", assessmentId: "math-g1a", studentId: "student-x", obtainedMarks: 70, status: "PRESENT" },
        "scores/y-math": { scoreId: "y-math", assessmentId: "math-g1a", studentId: "student-y", obtainedMarks: 60, status: "PRESENT" },
        "scores/x-science": { scoreId: "x-science", assessmentId: "science-g1a", studentId: "student-x", obtainedMarks: 50, status: "PRESENT" },
        "scores/z-science": { scoreId: "z-science", assessmentId: "science-g1b", studentId: "student-z", obtainedMarks: 40, status: "PRESENT" },
        "scores/w-history": { scoreId: "w-history", assessmentId: "history-g2a", studentId: "student-w", obtainedMarks: 30, status: "PRESENT" },
      };
      const snapshot = (cycleId: string, studentId: string, sectionId: string, seconds: number) => ({
        snapshotId: `${cycleId}_${studentId}`, cycleId, sectionId, studentId,
        overallPercentage: 70, subjectPercentages: { math: 70 }, updatedAt: at(seconds),
      });
      fixtures["resultSnapshots/c1_student-x"] = snapshot("c1", "student-x", "g1_a", 1);
      fixtures["resultSnapshots/c2_student-x"] = snapshot("c2", "student-x", "g1_a", 2);
      fixtures["resultSnapshots/c3_student-x"] = snapshot("c3", "student-x", "g1_a", 3);
      fixtures["resultSnapshots/c1_student-y"] = snapshot("c1", "student-y", "g1_a", 2);
      await Promise.all(Object.entries(fixtures).map(([relativePath, data]) =>
        db.doc(`schools/${SCHOOL}/${relativePath}`).set(data),
      ));
      await db.doc(`schools/${OTHER_SCHOOL}/users/other-admin`).set({ uid: "other-admin", schoolId: OTHER_SCHOOL, role: "ADMIN" });
      await db.doc(`schools/${OTHER_SCHOOL}/teacherAssignments/g1_a_math_teacher-uid`).set({ teacherUid: "teacher-uid" });
    });
  });

  // ---- The app's Firestore calls --------------------------------------------------------------------

  /** SyncLimits.pageSize: the largest limit a page asks for. */
  const PAGE = 500;

  const as = (uid: string) => env.authenticatedContext(uid).firestore() as unknown as Firestore;
  const col = (db: Firestore, name: string, school = SCHOOL) => collection(db, `schools/${school}/${name}`);

  /** RemoteQuery with docIds: one document get per id, from the server. */
  async function byIds(db: Firestore, name: string, ids: string[], school = SCHOOL) {
    const docs = await Promise.all(ids.map((id) => getDocFromServer(doc(col(db, name, school), id))));
    return docs.filter((snap) => snap.exists()).map((snap) => snap.id);
  }

  /**
   * FirestoreInboundRemote in ScanMode.FULL: the query's filters, orderBy(documentId()), startAfter(the
   * last id) and limit, from the server, page after page until an empty page.
   */
  async function fullScan(db: Firestore, name: string, filters: QueryConstraint[] = [], school = SCHOOL) {
    const ids: string[] = [];
    let afterId: string | undefined;
    for (;;) {
      const page = await getDocsFromServer(query(col(db, name, school), ...filters, orderBy(documentId()),
        ...(afterId === undefined ? [] : [startAfter(afterId)]), limit(PAGE)));
      if (page.empty) return ids;
      ids.push(...page.docs.map((snap) => snap.id));
      afterId = page.docs[page.docs.length - 1].id;
    }
  }

  /**
   * FirestoreInboundRemote in ScanMode.CHANGES (an incremental query): orderBy("updatedAt"),
   * orderBy(documentId()), startAfter(updatedAt, id) and limit, until an empty page.
   */
  async function changesScan(db: Firestore, name: string, after?: { updatedAt: Timestamp; id: string }) {
    const ids: string[] = [];
    let cursor = after;
    for (;;) {
      const page = await getDocsFromServer(query(col(db, name), orderBy("updatedAt"), orderBy(documentId()),
        ...(cursor === undefined ? [] : [startAfter(cursor.updatedAt, cursor.id)]), limit(PAGE)));
      if (page.empty) return ids;
      ids.push(...page.docs.map((snap) => snap.id));
      const last = page.docs[page.docs.length - 1];
      cursor = { updatedAt: last.get("updatedAt") as Timestamp, id: last.id };
    }
  }

  /** getStudentTrend (fetchServerResults): published cycles, then one result query per published cycle. */
  async function serverResults(db: Firestore, studentId: string) {
    const cycles = await getDocsFromServer(query(col(db, "assessmentCycles"), where("published", "==", true)));
    const ids: string[] = [];
    for (const cycle of cycles.docs) {
      const page = await getDocsFromServer(query(col(db, "resultSnapshots"),
        where("studentId", "==", studentId), where("cycleId", "==", cycle.id)));
      ids.push(...page.docs.map((snap) => snap.id));
    }
    return ids;
  }

  /** LoginViewModel: the profile in the school named by the token's schoolId claim. */
  const signInProfile = (db: Firestore, uid: string) => getDoc(doc(col(db, "users"), uid));

  const sorted = (ids: string[]) => [...ids].sort();

  // ---- Admin: the whole school ----------------------------------------------------------------------

  describe("admin of the school", () => {
    const db = () => as("admin-uid");

    test("sign-in profile get", async () => {
      expect((await assertSucceeds(signInProfile(db(), "admin-uid"))).exists()).toBe(true);
    });

    test.each([
      ["sections", 3], ["subjects", 3], ["students", 4], ["assessmentCycles", 3], ["assessments", 4],
      ["teacherAssignments", 3], ["scores", 5], ["resultSnapshots", 4],
    ])("%s: whole collection, orderBy(__name__), startAfter, limit 500 (FULL)", async (name, count) => {
      expect(await assertSucceeds(fullScan(db(), name))).toHaveLength(count);
    });

    test("resultSnapshots: orderBy(updatedAt), orderBy(__name__), limit 500 (CHANGES, first run)", async () => {
      expect(await assertSucceeds(changesScan(db(), "resultSnapshots")))
        .toEqual(["c1_student-x", "c1_student-y", "c2_student-x", "c3_student-x"]);
    });

    test("resultSnapshots: startAfter(updatedAt, id) cursor (CHANGES, later runs)", async () => {
      expect(await assertSucceeds(changesScan(db(), "resultSnapshots", { updatedAt: at(2), id: "c1_student-y" })))
        .toEqual(["c2_student-x", "c3_student-x"]);
    });

    test("student trend: published cycles, then studentId == X and cycleId == C per cycle", async () => {
      expect(sorted(await assertSucceeds(serverResults(db(), "student-x")))).toEqual(["c1_student-x", "c2_student-x"]);
    });

    test.each(["sections", "students", "scores", "resultSnapshots", "teacherAssignments"])(
      "another school's admin cannot scan this school's %s", async (name) => {
        await assertFails(fullScan(env.authenticatedContext("other-admin").firestore() as unknown as Firestore, name));
      });
  });

  // ---- Teacher: their assignments and what those cover -------------------------------------------------

  describe("assigned teacher", () => {
    const db = () => as("teacher-uid");
    // What phases 0 and 1 bring into Room for this teacher (TeacherScope over the seeded assignments).
    const sectionIds = ["g1_a", "g1_b"];
    const subjectIds = ["math", "science"];
    const assessmentIds = ["math-g1a", "science-g1b"];

    test("sign-in profile get", async () => {
      expect((await assertSucceeds(signInProfile(db(), "teacher-uid"))).exists()).toBe(true);
    });

    test("phase 0 teacherAssignments: teacherUid == uid, orderBy(__name__), limit 500", async () => {
      expect(await assertSucceeds(fullScan(db(), "teacherAssignments", [where("teacherUid", "==", "teacher-uid")])))
        .toEqual(["g1_a_math_teacher-uid", "g1_b_science_teacher-uid"]);
    });

    test("phase 1 sections: document gets by assigned section id", async () => {
      expect(await assertSucceeds(byIds(db(), "sections", sectionIds))).toEqual(sectionIds);
    });

    test("phase 1 subjects: document gets by assigned subject id", async () => {
      expect(await assertSucceeds(byIds(db(), "subjects", subjectIds))).toEqual(subjectIds);
    });

    test("phase 1 assessmentCycles: whole collection, orderBy(__name__), limit 500", async () => {
      expect(await assertSucceeds(fullScan(db(), "assessmentCycles"))).toEqual(["c1", "c2", "c3"]);
    });

    test("phase 1 students: sectionId in [assigned sections], orderBy(__name__), limit 500", async () => {
      expect(await assertSucceeds(fullScan(db(), "students", [where("sectionId", "in", sectionIds)])))
        .toEqual(["student-x", "student-y", "student-z"]);
    });

    test("phase 1 assessments: sectionId in [assigned sections], orderBy(__name__), limit 500", async () => {
      expect(await assertSucceeds(fullScan(db(), "assessments", [where("sectionId", "in", sectionIds)])))
        .toEqual(["math-g1a", "science-g1a", "science-g1b"]);
    });

    test("phase 2 scores: assessmentId in [assigned assessments], orderBy(__name__), limit 500", async () => {
      expect(await assertSucceeds(fullScan(db(), "scores", [where("assessmentId", "in", assessmentIds)])))
        .toEqual(["x-math", "y-math", "z-science"]);
    });

    test("over-broad: cannot list the school's users", async () => {
      await assertFails(fullScan(db(), "users"));
      await assertFails(getDocsFromServer(query(col(db(), "users"), where("role", "==", "TEACHER"))));
    });

    test("over-broad: cannot query its assignments in another school", async () => {
      await assertFails(fullScan(db(), "teacherAssignments", [where("teacherUid", "==", "teacher-uid")], OTHER_SCHOOL));
    });
  });

  // ---- Student: own record, section, grade subjects, cycles; results per published cycle --------------

  describe("student with a backend-shaped profile (linkedStudentId, uid = student id)", () => {
    const db = () => as("student-x");

    test("sign-in profile get", async () => {
      expect((await assertSucceeds(signInProfile(db(), "student-x"))).exists()).toBe(true);
    });

    test("phase 0 students: document get of students/{uid}", async () => {
      expect(await assertSucceeds(byIds(db(), "students", ["student-x"]))).toEqual(["student-x"]);
    });

    test("phase 0 assessmentCycles: whole collection, orderBy(__name__), limit 500", async () => {
      expect(await assertSucceeds(fullScan(db(), "assessmentCycles"))).toEqual(["c1", "c2", "c3"]);
    });

    test("phase 1 sections: document get of its own section", async () => {
      expect(await assertSucceeds(byIds(db(), "sections", ["g1_a"]))).toEqual(["g1_a"]);
    });

    test("phase 2 subjects: grade == its section's grade, orderBy(__name__), limit 500", async () => {
      expect(await assertSucceeds(fullScan(db(), "subjects", [where("grade", "==", 1)]))).toEqual(["math", "science"]);
    });

    test("results: assessmentCycles where published == true", async () => {
      const cycles = await assertSucceeds(getDocsFromServer(query(col(db(), "assessmentCycles"), where("published", "==", true))));
      expect(cycles.docs.map((snap) => snap.id)).toEqual(["c1", "c2"]);
    });

    test("results: resultSnapshots studentId == uid and cycleId == C, one query per published cycle", async () => {
      expect(sorted(await assertSucceeds(serverResults(db(), "student-x")))).toEqual(["c1_student-x", "c2_student-x"]);
    });

    test("over-broad: its results across all cycles (no cycleId filter) are refused", async () => {
      await assertFails(getDocsFromServer(query(col(db(), "resultSnapshots"), where("studentId", "==", "student-x"))));
    });

    test("over-broad: its results in an unpublished cycle are refused", async () => {
      await assertFails(getDocsFromServer(query(col(db(), "resultSnapshots"),
        where("studentId", "==", "student-x"), where("cycleId", "==", "c3"))));
    });

    test("over-broad: another student's results in a published cycle are refused", async () => {
      await assertFails(getDocsFromServer(query(col(db(), "resultSnapshots"),
        where("studentId", "==", "student-y"), where("cycleId", "==", "c1"))));
    });

    test("over-broad: the admin's incremental resultSnapshots query is refused", async () => {
      await assertFails(changesScan(db(), "resultSnapshots"));
    });

    test.each(["students", "scores", "resultSnapshots", "teacherAssignments", "users"])(
      "over-broad: whole %s collection is refused", async (name) => {
        await assertFails(fullScan(db(), name));
      });

    test("over-broad: its classmates (students where sectionId == own section) are refused", async () => {
      await assertFails(fullScan(db(), "students", [where("sectionId", "==", "g1_a")]));
    });
  });
});
