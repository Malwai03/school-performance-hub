import fs from "node:fs";
import path from "node:path";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";

const describeWithEmulator = process.env.FIRESTORE_EMULATOR_HOST ? describe : describe.skip;

describeWithEmulator("firestore security rules", () => {
  let env: RulesTestEnvironment;

  beforeAll(async () => {
    env = await initializeTestEnvironment({
      projectId: "school-app-test",
      firestore: {
        rules: fs.readFileSync(path.join(__dirname, "../../firestore/firestore.rules"), "utf8"),
      },
    });
  });

  afterAll(async () => {
    await env.cleanup();
  });

  test("student cannot write result snapshots", async () => {
    const student = env.authenticatedContext("student-uid", { role: "STUDENT" }).firestore();
    await assertFails(
      student
        .collection("schools")
        .doc("school_001")
        .collection("resultSnapshots")
        .doc("x")
        .set({ studentId: "student-1" }),
    );
  });

  test("admin can create sections", async () => {
    const admin = env.authenticatedContext("admin-uid", { role: "ADMIN" }).firestore();
    await assertSucceeds(
      admin
        .collection("schools")
        .doc("school_001")
        .collection("sections")
        .doc("g1_a")
        .set({ sectionId: "g1_a", grade: 1, sectionName: "A" }),
    );
  });
});
