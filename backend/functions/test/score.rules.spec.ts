import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { createRulesEnvironment } from "./rules-fixtures";

// Score write rules (issue #6): enrolled students only, numeric marks within bounds, scoreId equal to
// the document id, and score identity fixed on update. Runs with the other rules suites (npm run test:rules).
describe("score write rules", () => {
  let env: Awaited<ReturnType<typeof createRulesEnvironment>>;

  beforeAll(async () => { env = await createRulesEnvironment(); });
  afterAll(async () => { await env?.cleanup(); });

  beforeEach(async () => {
    await env.clearFirestore();
    await env.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      const fixtures: Record<string, Record<string, unknown>> = {
        "users/admin-uid": { role: "ADMIN" },
        "users/teacher-uid": { role: "TEACHER" },
        "users/unassigned-teacher": { role: "TEACHER" },
        "users/student-uid": { role: "STUDENT", studentId: "student-a1" },
        "students/student-a1": { studentId: "student-a1", sectionId: "section-a" },
        "students/student-a2": { studentId: "student-a2", sectionId: "section-a" },
        "students/student-b": { studentId: "student-b", sectionId: "section-b" },
        "students/student-c": { studentId: "student-c", sectionId: "section-c" },
        "students/no-section": { studentId: "no-section" },
        "assessments/math-a": {
          assessmentId: "math-a", sectionId: "section-a", subjectId: "math", maxMarks: 100,
        },
        "assessments/science-a": {
          assessmentId: "science-a", sectionId: "section-a", subjectId: "science", maxMarks: 50,
        },
        "assessments/history-a": {
          assessmentId: "history-a", sectionId: "section-a", subjectId: "history", maxMarks: 100,
        },
        "assessments/math-b": {
          assessmentId: "math-b", sectionId: "section-b", subjectId: "math", maxMarks: 100,
        },
        "assessments/math-c": {
          assessmentId: "math-c", sectionId: "section-c", subjectId: "math", maxMarks: 100,
        },
        "assessments/no-section": { subjectId: "math", maxMarks: 100 },
        "teacherAssignments/section-a_math_teacher-uid": {},
        "teacherAssignments/section-a_science_teacher-uid": {},
        "teacherAssignments/section-c_math_teacher-uid": {},
      };
      await Promise.all(Object.entries(fixtures).map(([relativePath, data]) =>
        db.doc(`schools/school_001/${relativePath}`).set(data),
      ));
      // Referenced IDs must exist in this school, not merely in another tenant.
      await db.doc("schools/other-school/students/foreign-student").set({ sectionId: "section-a" });
      await db.doc("schools/other-school/assessments/foreign-assessment").set({
        sectionId: "section-a", subjectId: "math", maxMarks: 100,
      });
    });
  });

  function score(overrides: Record<string, unknown> = {}) {
    return {
      scoreId: "score-1", assessmentId: "math-a", studentId: "student-a1",
      obtainedMarks: 75, status: "PRESENT", ...overrides,
    };
  }

  function scoreRef(uid: string, id = "score-1") {
    return env.authenticatedContext(uid).firestore().doc(`schools/school_001/scores/${id}`);
  }

  async function seedScore(data = score()) {
    await env.withSecurityRulesDisabled(async (context) => {
      await context.firestore().doc("schools/school_001/scores/score-1").set(data);
    });
  }

  describe.each(["teacher-uid", "admin-uid"])("score validation for %s", (uid) => {
    test.each([0, 49.5, 100])("can create an enrolled student's score with %s marks", async (marks) => {
      await assertSucceeds(scoreRef(uid).set(score({ obtainedMarks: marks })));
    });

    test("can grade with update and merge while preserving identity", async () => {
      await seedScore();
      const ref = scoreRef(uid);
      await assertSucceeds(ref.update({ obtainedMarks: 0, status: "ABSENT" }));
      await assertSucceeds(ref.set({ obtainedMarks: 80, status: "PRESENT" }, { merge: true }));
      await assertSucceeds(ref.set(score({ obtainedMarks: 81 })));
    });

    test.each<[string, Record<string, unknown>]>([
      ["student in another section", { studentId: "student-b" }],
      ["missing student", { studentId: "missing" }],
      ["student in another school only", { studentId: "foreign-student" }],
      ["student without a section", { studentId: "no-section" }],
      ["missing assessment", { assessmentId: "missing" }],
      ["assessment in another school only", { assessmentId: "foreign-assessment" }],
      ["missing sections on both references", { assessmentId: "no-section", studentId: "no-section" }],
      ["mismatched document ID", { scoreId: "another-score" }],
      ["empty student ID", { studentId: "" }],
      ["empty assessment ID", { assessmentId: "" }],
      ["negative marks", { obtainedMarks: -1 }],
      ["marks above assessment maximum", { obtainedMarks: 101 }],
      ["marks above a different assessment maximum", { assessmentId: "science-a", obtainedMarks: 51 }],
      ["text marks", { obtainedMarks: "75" }],
      ["null marks", { obtainedMarks: null }],
      ["NaN marks", { obtainedMarks: Number.NaN }],
      ["infinite marks", { obtainedMarks: Number.POSITIVE_INFINITY }],
    ])("rejects create with %s without persisting a score", async (_label, overrides) => {
      await assertFails(scoreRef(uid).set(score(overrides)));
      expect((await scoreRef("admin-uid").get()).exists).toBe(false);
    });

    test.each(["scoreId", "assessmentId", "studentId", "obtainedMarks"])(
      "rejects a missing %s field", async (field) => {
        const data: Record<string, unknown> = score();
        delete data[field];
        await assertFails(scoreRef(uid).set(data));
      },
    );

    test.each<[string, Record<string, unknown>]>([
      ["student", { studentId: "student-a2" }],
      ["assessment with authorization for both", { assessmentId: "science-a", obtainedMarks: 40 }],
      ["assessment and student with authorization for both sections", {
        assessmentId: "math-c", studentId: "student-c",
      }],
      ["score ID", { scoreId: "another-score" }],
      ["invalid marks", { obtainedMarks: 101 }],
    ])("rejects update or replacement changing %s and leaves the original intact", async (_label, patch) => {
      const original = score();
      await seedScore(original);
      await assertFails(scoreRef(uid).update(patch));
      await assertFails(scoreRef(uid).set(score(patch)));
      expect((await scoreRef("admin-uid").get()).data()).toEqual(original);
    });

    test("cannot move an unassigned assessment's score into an assigned assessment", async () => {
      const original = score({ assessmentId: "math-b", studentId: "student-b" });
      await seedScore(original);
      await assertFails(scoreRef(uid).set(score()));
      expect((await scoreRef("admin-uid").get()).data()).toEqual(original);
    });

    test("cannot edit a score after its student leaves the assessment section", async () => {
      await seedScore();
      await env.withSecurityRulesDisabled(async (context) => {
        await context.firestore().doc("schools/school_001/students/student-a1").update({ sectionId: "section-b" });
      });
      await assertFails(scoreRef(uid).update({ obtainedMarks: 80 }));
    });
  });

  test.each([
    ["unassigned-teacher", "math-a", "student-a1"],
    ["teacher-uid", "math-b", "student-b"],
    ["teacher-uid", "history-a", "student-a1"],
    ["student-uid", "math-a", "student-a1"],
  ])("%s cannot grade unassigned assessment %s", async (uid, assessmentId, studentId) => {
    const data = score({ assessmentId, studentId });
    await assertFails(scoreRef(uid).set(data));
    await seedScore(data);
    await assertFails(scoreRef(uid).update({ obtainedMarks: 80 }));
  });

  test("admin can grade a section without a teacher assignment", async () => {
    await assertSucceeds(scoreRef("admin-uid").set(score({ assessmentId: "math-b", studentId: "student-b" })));
  });

  test.each([
    ["science-a", "student-a2", 50],
    ["math-c", "student-c", 100],
  ])("teacher can grade another assigned assessment %s", async (assessmentId, studentId, obtainedMarks) => {
    await assertSucceeds(scoreRef("teacher-uid").set(score({ assessmentId, studentId, obtainedMarks })));
  });

  test("role claims alone and unauthenticated clients cannot write scores", async () => {
    const claimedAdmin = env.authenticatedContext("no-user-record", { role: "ADMIN" }).firestore();
    await assertFails(claimedAdmin.doc("schools/school_001/scores/score-1").set(score()));
    await assertFails(env.unauthenticatedContext().firestore().doc("schools/school_001/scores/score-1").set(score()));
  });

  test("a rejected batch persists neither the invalid score nor its valid sibling", async () => {
    const db = env.authenticatedContext("teacher-uid").firestore();
    const batch = db.batch();
    batch.set(db.doc("schools/school_001/scores/score-1"), score());
    batch.set(db.doc("schools/school_001/scores/score-2"), score({ scoreId: "score-2", studentId: "student-b" }));
    await assertFails(batch.commit());
    expect((await scoreRef("admin-uid").get()).exists).toBe(false);
    expect((await scoreRef("admin-uid", "score-2").get()).exists).toBe(false);
  });

  test("a batch of enrolled students' scores succeeds", async () => {
    const db = env.authenticatedContext("teacher-uid").firestore();
    const batch = db.batch();
    batch.set(db.doc("schools/school_001/scores/score-1"), score());
    batch.set(db.doc("schools/school_001/scores/score-2"), score({ scoreId: "score-2", studentId: "student-a2" }));
    await assertSucceeds(batch.commit());
    expect((await scoreRef("admin-uid").get()).data()).toEqual(score());
    expect((await scoreRef("admin-uid", "score-2").get()).exists).toBe(true);
  });

  test("only admins retain score deletion permission", async () => {
    await seedScore();
    await assertFails(scoreRef("teacher-uid").delete());
    await assertSucceeds(scoreRef("admin-uid").delete());
  });
});
