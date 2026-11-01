import { HttpsError } from "firebase-functions/v2/https";

export type CallerAuth = { uid?: string; token?: Record<string, unknown> } | null | undefined;

/** Reads schools/{schoolId}/users/{uid}; null when there is no such record. */
export type MembershipLookup = (schoolId: string, uid: string) => Promise<Record<string, unknown> | null>;

const SCHOOL_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const MANAGED_ROLES = new Set(["STUDENT", "TEACHER"]);

/**
 * The school a callable acts on: the requested one, else the caller's schoolId claim.
 * There is no default school, so a missing or malformed id fails instead of landing in school_001.
 */
export function resolveSchoolId(auth: CallerAuth, requested: unknown): string {
  const schoolId = String(requested ?? "").trim() || String(auth?.token?.schoolId ?? "").trim();
  if (!SCHOOL_ID_PATTERN.test(schoolId)) {
    throw new HttpsError("invalid-argument", "Valid schoolId is required");
  }
  return schoolId;
}

/**
 * Admin of *this* school, checked before any Auth, Storage or Firestore work:
 * - signed in with the ADMIN role claim (as before)
 * - a schoolId claim, when the token has one, must match the school
 * - schools/{schoolId}/users/{uid} must say ADMIN: the same membership the Firestore rules use
 *   (isAdmin), so existing admins need no claim migration.
 * Returns the authorized schoolId.
 */
export async function requireAdminForSchool(
  auth: CallerAuth,
  requestedSchoolId: unknown,
  lookupMembership: MembershipLookup,
): Promise<string> {
  if (!auth || !auth.uid) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }
  if (String(auth.token?.role ?? "") !== "ADMIN") {
    throw new HttpsError("permission-denied", "Admin role required");
  }
  const schoolId = resolveSchoolId(auth, requestedSchoolId);
  const claimed = auth.token?.schoolId;
  if (claimed !== undefined && claimed !== null && String(claimed) !== schoolId) {
    throw new HttpsError("permission-denied", "Not an admin of this school");
  }
  const member = await lookupMembership(schoolId, auth.uid);
  if (!member || String(member.role ?? "") !== "ADMIN" || member.active === false) {
    throw new HttpsError("permission-denied", "Not an admin of this school");
  }
  return schoolId;
}

/** Import files must be the school's own uploads: imports/{schoolId}/<name>, no dot segments. */
export function requireImportFileRef(schoolId: string, fileRef: unknown): string {
  const ref = String(fileRef ?? "").trim();
  const prefix = `imports/${schoolId}/`;
  const segments = ref.startsWith(prefix) ? ref.slice(prefix.length).split("/") : [];
  const clean = segments.length > 0 && segments.every((s) => s !== "" && s !== "." && s !== "..");
  if (!clean || /[\\\u0000-\u001f]/.test(ref)) {
    throw new HttpsError("permission-denied", "fileRef must be a file under this school's imports folder");
  }
  return ref;
}

/**
 * Password resets only touch a student or teacher login that belongs to this school. Admins can
 * write any user record in their school, so the record alone can't be trusted to point at the
 * right login: check the login's own claims.
 */
export function requireManagedLoginInSchool(claims: Record<string, unknown> | undefined, schoolId: string): void {
  const role = String(claims?.role ?? "");
  if (String(claims?.schoolId ?? "") !== schoolId || !MANAGED_ROLES.has(role)) {
    throw new HttpsError("permission-denied", "Only this school's student and teacher accounts can be reset");
  }
}
