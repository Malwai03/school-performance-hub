import fs from "node:fs";
import path from "node:path";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";

// Storage rules read Firestore membership, so both emulators must be running.
const withEmulators =
  process.env.FIREBASE_STORAGE_EMULATOR_HOST && process.env.FIRESTORE_EMULATOR_HOST ? describe : describe.skip;

withEmulators("storage rules: imports are per school", () => {
  let env: RulesTestEnvironment;

  beforeAll(async () => {
    env = await initializeTestEnvironment({
      projectId: "demo-school-rules", // matches rules-fixtures.ts and firebase.rules-test.json
      firestore: { rules: fs.readFileSync(path.join(__dirname, "../../firestore/firestore.rules"), "utf8") },
      storage: { rules: fs.readFileSync(path.join(__dirname, "../../storage/storage.rules"), "utf8") },
    });
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await db.collection("schools").doc("school_a").collection("users").doc("admin-a").set({ role: "ADMIN" });
      await db.collection("schools").doc("school_b").collection("users").doc("admin-b").set({ role: "ADMIN" });
      await db.collection("schools").doc("school_a").collection("users").doc("admin-on").set({ role: "ADMIN", active: true });
      await db.collection("schools").doc("school_a").collection("users").doc("admin-off").set({ role: "ADMIN", active: false });
    });
  });

  afterAll(async () => {
    await env.cleanup();
  });

  // An UploadTask is thenable but not a Promise; .then() gives assertFails/assertSucceeds one.
  const upload = (uid: string, claims: Record<string, unknown>, file: string) =>
    env.authenticatedContext(uid, claims).storage().ref(file).putString("id,name\n1,x").then((snap) => snap);

  test("school A admin can upload to school A", async () => {
    await assertSucceeds(upload("admin-a", { role: "ADMIN", schoolId: "school_a" }, "imports/school_a/a.csv"));
  });

  test("school A admin cannot read or write school B imports", async () => {
    await assertFails(upload("admin-a", { role: "ADMIN", schoolId: "school_a" }, "imports/school_b/a.csv"));
    // Forged claim: membership in school_b is still missing.
    await assertFails(upload("admin-a", { role: "ADMIN", schoolId: "school_b" }, "imports/school_b/a.csv"));
    await assertFails(upload("admin-a", { role: "ADMIN" }, "imports/school_b/a.csv"));
    await assertFails(
      env.authenticatedContext("admin-a", { role: "ADMIN", schoolId: "school_a" }).storage().ref("imports/school_b/a.csv").getDownloadURL(),
    );
  });

  test("a deactivated admin cannot read or write the school's imports; an active one still can", async () => {
    const claims = { role: "ADMIN", schoolId: "school_a" };
    const file = "imports/school_a/roster.csv";
    await assertSucceeds(upload("admin-on", claims, file));
    await assertSucceeds(env.authenticatedContext("admin-on", claims).storage().ref(file).getDownloadURL());
    // Same claims and ADMIN membership, but the record says active: false (as requireAdminForSchool refuses).
    await assertFails(upload("admin-off", claims, file));
    await assertFails(env.authenticatedContext("admin-off", claims).storage().ref(file).getDownloadURL());
  });

  test("non-admins and signed-out users are refused", async () => {
    await assertFails(upload("teacher-a", { role: "TEACHER", schoolId: "school_a" }, "imports/school_a/a.csv"));
    await assertFails(env.unauthenticatedContext().storage().ref("imports/school_a/a.csv").putString("x").then((snap) => snap));
  });
});
