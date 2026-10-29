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

  test.each(["admin-uid", "teacher-uid"])("staff %s can inspect unpublished results", async (uid) => {
    await assertSucceeds(env.authenticatedContext(uid).firestore()
      .doc(`schools/${SCHOOL}/resultSnapshots/draft-own`).get());
  });

  test("different-school admin cannot read published results", async () => {
    await assertFails(env.authenticatedContext("other-admin", { role: "ADMIN", schoolId: OTHER_SCHOOL })
      .firestore().doc(`schools/${SCHOOL}/resultSnapshots/published-own`).get());
  });

  test.each(["admin-uid", "teacher-uid", "student-uid"])("%s cannot create, update or delete backend results", async (uid) => {
    const db = env.authenticatedContext(uid).firestore();
    const results = db.collection(`schools/${SCHOOL}/resultSnapshots`);
    await assertFails(results.doc("new").set({ studentId: "student-1", cycleId: "published" }));
    await assertFails(results.doc("published-own").update({ overallPercentage: 100 }));
    await assertFails(results.doc("published-own").delete());
  });
});
