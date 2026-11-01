import { FieldPath, FieldValue, Firestore } from "firebase-admin/firestore";

/**
 * Field on schools/{schoolId}/users/{teacherUid} that the Firestore read rules take a teacher's scope
 * from: each of their teacherAssignments ids mapped to that assignment's section id. Rules cannot list
 * teacherAssignments, so "does this teacher teach anything in section X" (students) has to be a lookup
 * on a document the rules already read; the profile is that document.
 */
export const ASSIGNMENT_SECTIONS_FIELD = "assignmentSections";

/**
 * The section that teacherAssignments/{assignmentId} gives [teacherUid] read access to, or null when it
 * gives them nothing: it is missing, names another teacher, has a blank field, or its id is not
 * <sectionId>_<subjectId>_<teacherUid> (the id the score write rules look up).
 */
export function assignedSection(
  assignmentId: string,
  data: Record<string, unknown> | undefined,
  teacherUid: string,
): string | null {
  if (!data) return null;
  const { sectionId, subjectId, teacherUid: assignedUid } = data;
  if (typeof sectionId !== "string" || typeof subjectId !== "string" || typeof assignedUid !== "string") return null;
  if (!sectionId || !subjectId || !assignedUid || assignedUid !== teacherUid) return null;
  return assignmentId === `${sectionId}_${subjectId}_${assignedUid}` ? sectionId : null;
}

/** Non-blank uids, once each. */
function uidsOf(values: unknown[]): string[] {
  return Array.from(new Set(values.filter((value): value is string => typeof value === "string" && value.length > 0)));
}

/**
 * Makes the profiles of [teacherUids] agree with teacherAssignments/{assignmentId} as it is now: the
 * assignment's key is set on the profile of the teacher it names and removed from any other. The
 * assignment is re-read inside the transaction, so triggers that run late or out of order still leave
 * the latest state. A teacher without a profile is skipped (without one the rules grant nothing).
 */
export async function syncTeacherAssignment(
  db: Firestore,
  schoolId: string,
  assignmentId: string,
  teacherUids: unknown[],
): Promise<void> {
  const uids = uidsOf(teacherUids);
  if (!uids.length) return;
  const school = db.collection("schools").doc(schoolId);
  const assignmentRef = school.collection("teacherAssignments").doc(assignmentId);
  const field = new FieldPath(ASSIGNMENT_SECTIONS_FIELD, assignmentId);

  await db.runTransaction(async (tx) => {
    const assignment = await tx.get(assignmentRef);
    const profiles = await tx.getAll(...uids.map((uid) => school.collection("users").doc(uid)));
    profiles.forEach((profile, index) => {
      if (!profile.exists) return;
      const sectionId = assignedSection(assignmentId, assignment.data(), uids[index]);
      const current = profile.get(field);
      if (sectionId !== null && current !== sectionId) tx.update(profile.ref, field, sectionId);
      if (sectionId === null && current !== undefined) tx.update(profile.ref, field, FieldValue.delete());
    });
  });
}

/**
 * Rewrites assignmentSections on every TEACHER profile of [schoolIds] (every school when omitted) from
 * that school's teacherAssignments. Run once after deploying onTeacherAssignmentWrite, before the read
 * rules that depend on it; safe to repeat.
 */
export async function rebuildTeacherScopes(
  db: Firestore,
  schoolIds?: string[],
): Promise<{ schools: number; teachers: number; assignments: number }> {
  const ids = schoolIds ?? (await db.collection("schools").listDocuments()).map((ref) => ref.id);
  let teachers = 0;
  let assignments = 0;
  for (const schoolId of ids) {
    const school = db.collection("schools").doc(schoolId);
    const [teacherSnap, assignmentSnap] = await Promise.all([
      school.collection("users").where("role", "==", "TEACHER").get(),
      school.collection("teacherAssignments").get(),
    ]);
    const scopes = new Map<string, Record<string, string>>(teacherSnap.docs.map((doc) => [doc.id, {}]));
    for (const doc of assignmentSnap.docs) {
      const uid = doc.get("teacherUid");
      const scope = typeof uid === "string" ? scopes.get(uid) : undefined;
      const sectionId = scope ? assignedSection(doc.id, doc.data(), uid) : null;
      if (scope && sectionId !== null) {
        scope[doc.id] = sectionId;
        assignments++;
      }
    }
    const entries = Array.from(scopes.entries());
    for (let start = 0; start < entries.length; start += 400) {
      const batch = db.batch();
      for (const [uid, scope] of entries.slice(start, start + 400)) {
        batch.update(school.collection("users").doc(uid), ASSIGNMENT_SECTIONS_FIELD, scope);
      }
      await batch.commit();
    }
    teachers += entries.length;
  }
  return { schools: ids.length, teachers, assignments };
}
