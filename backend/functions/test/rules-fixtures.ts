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
    put("scores/own", { assessmentId: "math", studentId: "student-1", obtainedMarks: 50 });
    put("scores/other", { assessmentId: "math", studentId: "student-2", obtainedMarks: 50 });
    put("assessmentCycles/published", { published: true });
    put("assessmentCycles/draft", { published: false });
    put("resultSnapshots/published-own", { studentId: "student-1", cycleId: "published" });
    put("resultSnapshots/draft-own", { studentId: "student-1", cycleId: "draft" });
    put("resultSnapshots/missing-cycle", { studentId: "student-1", cycleId: "absent" });
    put("resultSnapshots/other", { studentId: "student-2", cycleId: "published" });
    await batch.commit();
  });
}
