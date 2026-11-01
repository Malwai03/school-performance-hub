import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  MembershipLookup,
  requireAdminForSchool,
  requireImportFileRef,
  requireManagedLoginInSchool,
  resolveSchoolId,
} from "../src/schoolAuth";

// school_a has one admin (admin-a); school_b has its own (admin-b).
const members: Record<string, Record<string, Record<string, unknown>>> = {
  school_a: { "admin-a": { role: "ADMIN" }, "teacher-a": { role: "TEACHER" }, "former-a": { role: "ADMIN", active: false } },
  school_b: { "admin-b": { role: "ADMIN" } },
};
const lookup: jest.MockedFunction<MembershipLookup> = jest.fn(async (schoolId, uid) => members[schoolId]?.[uid] ?? null);

const adminA = { uid: "admin-a", token: { role: "ADMIN", schoolId: "school_a" } };
const adminANoClaim = { uid: "admin-a", token: { role: "ADMIN" } };

beforeEach(() => lookup.mockClear());

describe("requireAdminForSchool", () => {
  test("same-school admin is allowed and gets the school back", async () => {
    await expect(requireAdminForSchool(adminA, "school_a", lookup)).resolves.toBe("school_a");
    await expect(requireAdminForSchool(adminANoClaim, "school_a", lookup)).resolves.toBe("school_a");
  });

  test("school A admin cannot act on school B", async () => {
    await expect(requireAdminForSchool(adminA, "school_b", lookup)).rejects.toMatchObject({ code: "permission-denied" });
    expect(lookup).not.toHaveBeenCalled(); // claim mismatch fails before any read
    // Without a schoolId claim the membership record decides: admin-a has none in school_b.
    await expect(requireAdminForSchool(adminANoClaim, "school_b", lookup)).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("needs a signed-in ADMIN who is an active admin member of the school", async () => {
    await expect(requireAdminForSchool(null, "school_a", lookup)).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(requireAdminForSchool({ token: { role: "ADMIN" } }, "school_a", lookup)).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(
      requireAdminForSchool({ uid: "teacher-a", token: { role: "TEACHER", schoolId: "school_a" } }, "school_a", lookup),
    ).rejects.toMatchObject({ code: "permission-denied" });
    // An ADMIN role claim alone isn't enough when the school record says otherwise.
    await expect(requireAdminForSchool({ uid: "teacher-a", token: { role: "ADMIN" } }, "school_a", lookup)).rejects.toMatchObject({ code: "permission-denied" });
    await expect(requireAdminForSchool({ uid: "former-a", token: { role: "ADMIN" } }, "school_a", lookup)).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("missing school falls back to the claim, never to a default school", async () => {
    await expect(requireAdminForSchool(adminA, undefined, lookup)).resolves.toBe("school_a");
    await expect(requireAdminForSchool(adminANoClaim, "", lookup)).rejects.toMatchObject({ code: "invalid-argument" });
    expect(() => resolveSchoolId(adminANoClaim, "school_a/users/x")).toThrow();
    expect(() => resolveSchoolId(adminANoClaim, "../school_b")).toThrow();
  });
});

describe("requireImportFileRef", () => {
  test("accepts the school's own uploads", () => {
    expect(requireImportFileRef("school_a", "imports/school_a/0b7c.csv")).toBe("imports/school_a/0b7c.csv");
  });

  test.each([
    "imports/school_b/0b7c.csv",
    "imports/school_ab/0b7c.csv",
    "imports/school_a/",
    "imports/school_a/../school_b/x.csv",
    "imports/school_a/./x.csv",
    "imports/school_a//x.csv",
    "/imports/school_a/x.csv",
    "imports\\school_a\\x.csv",
    "exports/school_a/x.csv",
    "",
  ])("rejects %p", (ref) => {
    expect(() => requireImportFileRef("school_a", ref)).toThrow(expect.objectContaining({ code: "permission-denied" }));
  });
});

describe("requireManagedLoginInSchool", () => {
  test("only this school's student and teacher logins can be reset", () => {
    expect(() => requireManagedLoginInSchool({ role: "STUDENT", schoolId: "school_a" }, "school_a")).not.toThrow();
    expect(() => requireManagedLoginInSchool({ role: "TEACHER", schoolId: "school_a" }, "school_a")).not.toThrow();
    // A user record in school_a pointing at another school's login, or at an admin, is refused.
    expect(() => requireManagedLoginInSchool({ role: "STUDENT", schoolId: "school_b" }, "school_a")).toThrow();
    expect(() => requireManagedLoginInSchool({ role: "ADMIN", schoolId: "school_a" }, "school_a")).toThrow();
    expect(() => requireManagedLoginInSchool(undefined, "school_a")).toThrow();
  });
});

test("every callable authorizes the school before doing anything else", () => {
  const source = readFileSync(join(__dirname, "..", "src", "index.ts"), "utf8");
  const bodies = source.split(/onCall\(\{ region: REGION \}, async \(request\) => \{\r?\n/).slice(1);
  expect(bodies).toHaveLength(5);
  for (const body of bodies) {
    expect(body.trimStart()).toMatch(/^const schoolId = await requireAdminForSchool\(request\.auth, request\.data\?\.schoolId, adminMembership\);/);
  }
  expect(source).not.toMatch(/requireAdmin\(/);
  expect(source).not.toMatch(/school_001/);
  expect(source.match(/requireImportFileRef\(schoolId, request\.data\?\.fileRef\)/g)).toHaveLength(2);
  expect(source).toMatch(/requireManagedLoginInSchool\(loginClaims, schoolId\);\s+const temporaryPassword/);
});
