import fs from "node:fs";
import path from "node:path";
import { initializeTestEnvironment, RulesTestEnvironment } from "@firebase/rules-unit-testing";

export const SCHOOL = "school_001";
export const OTHER_SCHOOL = "school_002";
export const PROJECT = "demo-school-rules";

export function createRulesEnvironment() {
  return initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: fs.readFileSync(path.join(__dirname, "../../firestore/firestore.rules"), "utf8") },
    storage: { rules: fs.readFileSync(path.join(__dirname, "../../storage/storage.rules"), "utf8") },
  });
}

export async function seedSchool(env: RulesTestEnvironment) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const batch = db.batch();
    const put = (relative: string, data: Record<string, unknown>, school = SCHOOL) => {
      batch.set(db.doc(`schools/${school}/${relative}`), data);
    };
    put("users/admin-uid", { role: "ADMIN" });
    put("users/teacher-uid", { role: "TEACHER" });
    put("users/unassigned-teacher", { role: "TEACHER" });
    put("users/student-uid", { role: "STUDENT", studentId: "student-1" });
    put("users/other-admin", { role: "ADMIN" }, OTHER_SCHOOL);
    put("sections/existing", { grade: 1, sectionName: "A" });
    put("assessments/math", { sectionId: "g1_a", subjectId: "math", maxMarks: 100 });
    put("teacherAssignments/g1_a_math_teacher-uid", { teacherId: "teacher-uid" });
    put("teacherAssignments/g1_a_math_missing-teacher", { teacherId: "missing-teacher" });
    // Score writes need an enrolled student in the assessment's section and scoreId == doc id (issue #6).
    put("students/student-1", { studentId: "student-1", sectionId: "g1_a" });
    put("students/student-2", { studentId: "student-2", sectionId: "g1_a" });
    put("scores/own", { scoreId: "own", assessmentId: "math", studentId: "student-1", obtainedMarks: 50 });
    put("scores/other", { scoreId: "other", assessmentId: "math", studentId: "student-2", obtainedMarks: 50 });
    put("assessmentCycles/published", { published: true });
    put("assessmentCycles/draft", { published: false });
    put("resultSnapshots/published-own", { studentId: "student-1", cycleId: "published" });
    put("resultSnapshots/draft-own", { studentId: "student-1", cycleId: "draft" });
    put("resultSnapshots/missing-cycle", { studentId: "student-1", cycleId: "absent" });
    put("resultSnapshots/other", { studentId: "student-2", cycleId: "published" });

    // Student accounts as createManagedUserAccount writes them: the auth uid is the student id and the
    // profile links the student through linkedStudentId (there is no studentId field).
    const backendStudent = (uid: string, linkedStudentId: string | null) => ({
      uid, schoolId: SCHOOL, role: "STUDENT", displayName: `Student ${uid}`, code: `${uid}-code`,
      active: true, mustChangePassword: true, linkedStudentId, sectionId: "g1_a",
    });
    put("users/student-x", backendStudent("student-x", "student-x"));
    // Same link under a uid that is not the student id, so the linkedStudentId match is tested on its own.
    put("users/student-x-login", backendStudent("student-x-login", "student-x"));
    put("users/unlinked-student", backendStudent("unlinked-student", null));
    put("students/student-x", { studentId: "student-x", sectionId: "g1_a", authUid: "student-x" });
    put("scores/x-own", { scoreId: "x-own", assessmentId: "math", studentId: "student-x", obtainedMarks: 70 });
    put("scores/no-student", { scoreId: "no-student", assessmentId: "math", studentId: null, obtainedMarks: 0 });
    put("resultSnapshots/x-published", { studentId: "student-x", cycleId: "published" });
    put("resultSnapshots/x-draft", { studentId: "student-x", cycleId: "draft" });
    put("resultSnapshots/x-missing-cycle", { studentId: "student-x", cycleId: "absent" });
    put("resultSnapshots/no-student", { studentId: null, cycleId: "published" });
    put("scores/x-elsewhere", { scoreId: "x-elsewhere", assessmentId: "math", studentId: "student-x" }, OTHER_SCHOOL);
    await batch.commit();
  });
}
