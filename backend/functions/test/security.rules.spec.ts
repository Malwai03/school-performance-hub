import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { createRulesEnvironment, seedSchool, SCHOOL, OTHER_SCHOOL } from "./rules-fixtures";

describe("firestore security rules", () => {
  let env: Awaited<ReturnType<typeof createRulesEnvironment>>;

  beforeAll(async () => { env = await createRulesEnvironment(); });
  beforeEach(async () => {
    await env.clearFirestore();
    await seedSchool(env);
  });
  afterAll(async () => { await env?.cleanup(); });

  const section = { sectionId: "g1_a", grade: 1, sectionName: "A" };

  test("admin can create sections with a school user record", async () => {
    const db = env.authenticatedContext("admin-uid", { role: "ADMIN", schoolId: SCHOOL }).firestore();
    await assertSucceeds(db.doc(`schools/${SCHOOL}/sections/g1_a`).set(section));
  });

  test("ADMIN claim alone cannot create sections without a school user record", async () => {
    const db = env.authenticatedContext("missing-admin", { role: "ADMIN", schoolId: SCHOOL }).firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/sections/g1_a`).set(section));
  });

  test("ADMIN claim cannot override a student user record", async () => {
    const db = env.authenticatedContext("student-uid", { role: "ADMIN", schoolId: SCHOOL }).firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/sections/g1_a`).set(section));
  });

  test("school admin cannot read or create sections in another school", async () => {
    const db = env.authenticatedContext("other-admin", { role: "ADMIN", schoolId: OTHER_SCHOOL }).firestore();
    await assertSucceeds(db.doc(`schools/${OTHER_SCHOOL}/sections/g1_a`).set(section));
    await assertFails(db.doc(`schools/${SCHOOL}/sections/existing`).get());
    await assertFails(db.doc(`schools/${SCHOOL}/sections/g1_a`).set(section));
  });

  test("anonymous user cannot read or write school sections", async () => {
    const db = env.unauthenticatedContext().firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/sections/existing`).get());
    await assertFails(db.doc(`schools/${SCHOOL}/sections/g1_a`).set(section));
  });

  test("student can read own scores but cannot read or modify another student's score", async () => {
    const db = env.authenticatedContext("student-uid").firestore();
    await assertSucceeds(db.doc(`schools/${SCHOOL}/scores/own`).get());
    await assertFails(db.doc(`schools/${SCHOOL}/scores/other`).get());
    await assertFails(db.doc(`schools/${SCHOOL}/scores/other`).update({ obtainedMarks: 100 }));
    await assertFails(db.doc(`schools/${SCHOOL}/scores/new`).set({
      assessmentId: "math", studentId: "student-2", obtainedMarks: 100,
    }));
  });

  test("student cannot modify even their own score", async () => {
    const db = env.authenticatedContext("student-uid").firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/scores/own`).update({ obtainedMarks: 100 }));
    await assertFails(db.doc(`schools/${SCHOOL}/scores/own`).delete());
  });

  test("assigned teacher can create and update scores", async () => {
    const db = env.authenticatedContext("teacher-uid").firestore();
    await assertSucceeds(db.doc(`schools/${SCHOOL}/scores/new`).set({
      scoreId: "new", assessmentId: "math", studentId: "student-2", obtainedMarks: 90,
    }));
    await assertSucceeds(db.doc(`schools/${SCHOOL}/scores/other`).update({ obtainedMarks: 90 }));
  });

  test.each([-1, 101])("assigned teacher cannot write marks outside assessment bounds: %s", async (marks) => {
    const db = env.authenticatedContext("teacher-uid").firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/scores/other`).update({ obtainedMarks: marks }));
  });

  test("unassigned teacher cannot create or update scores", async () => {
    const db = env.authenticatedContext("unassigned-teacher").firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/scores/new`).set({
      assessmentId: "math", studentId: "student-2", obtainedMarks: 90,
    }));
    await assertFails(db.doc(`schools/${SCHOOL}/scores/other`).update({ obtainedMarks: 90 }));
  });

  test("teacher claim and assignment without a school user record cannot write scores", async () => {
    const db = env.authenticatedContext("missing-teacher", { role: "TEACHER" }).firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/scores/other`).update({ obtainedMarks: 90 }));
  });

  test("student can read their own published result", async () => {
    const db = env.authenticatedContext("student-uid").firestore();
    await assertSucceeds(db.doc(`schools/${SCHOOL}/resultSnapshots/published-own`).get());
  });

  test.each(["draft-own", "missing-cycle", "other"])('student cannot read result "%s"', async (id) => {
    const db = env.authenticatedContext("student-uid").firestore();
    await assertFails(db.doc(`schools/${SCHOOL}/resultSnapshots/${id}`).get());
  });

  test("admin can inspect unpublished results", async () => {
    await assertSucceeds(env.authenticatedContext("admin-uid").firestore()
      .doc(`schools/${SCHOOL}/resultSnapshots/draft-own`).get());
  });

  // A snapshot holds every subject's result; teachers see marks only through their assignments.
  test.each(["draft-own", "published-own"])('teacher cannot read result "%s"', async (id) => {
    await assertFails(env.authenticatedContext("teacher-uid").firestore()
      .doc(`schools/${SCHOOL}/resultSnapshots/${id}`).get());
  });

  test("different-school admin cannot read published results", async () => {
    await assertFails(env.authenticatedContext("other-admin", { role: "ADMIN", schoolId: OTHER_SCHOOL })
      .firestore().doc(`schools/${SCHOOL}/resultSnapshots/published-own`).get());
  });

  // Profiles written by createManagedUserAccount: { role: "STUDENT", linkedStudentId: "student-x", ... }.
  describe.each(["student-x", "student-x-login"])("backend-shaped student profile %s (linkedStudentId)", (uid) => {
    const studentDb = () => env.authenticatedContext(uid).firestore();

    test("can read its own student record, scores and published result", async () => {
      const db = studentDb();
      await assertSucceeds(db.doc(`schools/${SCHOOL}/students/student-x`).get());
      await assertSucceeds(db.doc(`schools/${SCHOOL}/scores/x-own`).get());
      await assertSucceeds(db.doc(`schools/${SCHOOL}/resultSnapshots/x-published`).get());
    });

    test("can query only its own scores", async () => {
      const scores = studentDb().collection(`schools/${SCHOOL}/scores`);
      const own = await assertSucceeds(scores.where("studentId", "==", "student-x").get());
      expect(own.docs.map((doc) => doc.id)).toEqual(["x-own"]);
      await assertFails(scores.where("studentId", "==", "student-1").get());
      await assertFails(scores.get());
    });

    test.each(["students/student-1", "students/student-2", "scores/own", "scores/other",
      "resultSnapshots/published-own", "resultSnapshots/other"])("cannot read another student's %s", async (doc) => {
      await assertFails(studentDb().doc(`schools/${SCHOOL}/${doc}`).get());
    });

    test.each(["x-draft", "x-missing-cycle"])('cannot read its own unpublished result "%s"', async (id) => {
      await assertFails(studentDb().doc(`schools/${SCHOOL}/resultSnapshots/${id}`).get());
    });

    test("cannot read its own student id's score in another school", async () => {
      await assertFails(studentDb().doc(`schools/${OTHER_SCHOOL}/scores/x-elsewhere`).get());
    });

    test("cannot write its own score or result", async () => {
      const db = studentDb();
      await assertFails(db.doc(`schools/${SCHOOL}/scores/x-own`).update({ obtainedMarks: 100 }));
      await assertFails(db.doc(`schools/${SCHOOL}/resultSnapshots/x-published`).update({ overallPercentage: 100 }));
    });
  });

  test.each(["scores/no-student", "resultSnapshots/no-student", "scores/x-own", "students/student-x"])(
    "student profile with no linked student cannot read %s", async (doc) => {
      await assertFails(env.authenticatedContext("unlinked-student").firestore().doc(`schools/${SCHOOL}/${doc}`).get());
    });

  test.each(["scores/no-student", "resultSnapshots/no-student"])("legacy studentId profile cannot read %s", async (doc) => {
    await assertFails(env.authenticatedContext("student-uid").firestore().doc(`schools/${SCHOOL}/${doc}`).get());
  });

  test.each(["admin-uid", "teacher-uid", "student-uid"])("%s cannot create, update or delete backend results", async (uid) => {
    const db = env.authenticatedContext(uid).firestore();
    const results = db.collection(`schools/${SCHOOL}/resultSnapshots`);
    await assertFails(results.doc("new").set({ studentId: "student-1", cycleId: "published" }));
    await assertFails(results.doc("published-own").update({ overallPercentage: 100 }));
    await assertFails(results.doc("published-own").delete());
  });
});
