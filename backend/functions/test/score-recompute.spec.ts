import { getFirestore, Firestore, DocumentReference, DocumentSnapshot } from "firebase-admin/firestore";

const describeWithEmulator = process.env.FIRESTORE_EMULATOR_HOST ? describe : describe.skip;

// Run against Firestore only: invoke the exported v2 handler explicitly with
// real before/after snapshots so delivery timing cannot make assertions flaky.
describeWithEmulator("score result recomputation", () => {
  let db: Firestore;
  let school: DocumentReference;
  let trigger: typeof import("../src/index").onScoreWriteRecompute;
  let sequence = 0;

  beforeAll(async () => {
    process.env.GCLOUD_PROJECT ||= "demo-school-score-results";
    ({ onScoreWriteRecompute: trigger } = await import("../src/index"));
    db = getFirestore();
  });

  beforeEach(async () => {
    school = db.collection("schools").doc(`score-test-${process.pid}-${Date.now()}-${sequence++}`);
    await Promise.all([
      school.collection("subjects").doc("math").set({ mandatory: true, passThresholdPct: 33 }),
      school.collection("subjects").doc("science").set({ mandatory: true, passThresholdPct: 33 }),
      school.collection("students").doc("alice").set({ sectionId: "s1", active: true }),
      school.collection("students").doc("bob").set({ sectionId: "s1", active: true }),
      school.collection("assessments").doc("a1").set({
        cycleId: "c1", sectionId: "s1", subjectId: "math", maxMarks: 100, weight: 1,
      }),
      school.collection("assessmentCycles").doc("c1").set({ published: true }),
    ]);
  });

  afterEach(async () => {
    await db.recursiveDelete(school);
  });

  afterAll(async () => {
    await db.terminate();
  });

  function deliver(before: DocumentSnapshot, after: DocumentSnapshot) {
    return trigger.run({
      params: { schoolId: school.id, scoreId: before.id },
      data: { before, after },
    } as Parameters<typeof trigger.run>[0]);
  }

  async function writeScore(scoreId: string, studentId: string, obtainedMarks: number, assessmentId = "a1") {
    const ref = school.collection("scores").doc(scoreId);
    const before = await ref.get();
    await ref.set({ scoreId, assessmentId, studentId, obtainedMarks, status: "PRESENT" });
    const after = await ref.get();
    await deliver(before, after);
    return { before, after };
  }

  async function snapshot(studentId: string, cycleId = "c1", sectionId = "s1") {
    return (await school.collection("resultSnapshots").doc(`${cycleId}_${sectionId}_${studentId}`).get()).data();
  }

  async function analytics(cycleId = "c1", sectionId = "s1") {
    return (await school.collection("sectionAnalytics").doc(`${cycleId}_${sectionId}`).get()).data();
  }

  test("create, update and delete refresh published results and section statistics", async () => {
    await writeScore("alice-math", "alice", 80);
    await writeScore("bob-math", "bob", 60);
    expect(await analytics()).toMatchObject({ passCount: 2, failCount: 0, incompleteCount: 0, averagePct: 70, topperStudentId: "alice" });

    await writeScore("alice-math", "alice", 20);
    expect(await snapshot("alice")).toMatchObject({ overallPercentage: 20, passStatus: "FAIL", riskLevel: "HIGH" });
    expect(await analytics()).toMatchObject({ passCount: 1, failCount: 1, averagePct: 40, topperStudentId: "bob" });

    const ref = school.collection("scores").doc("alice-math");
    const before = await ref.get();
    await ref.delete();
    const after = await ref.get();
    await deliver(before, after);
    expect(await snapshot("alice")).toMatchObject({ subjectPercentages: {}, overallPercentage: null, passStatus: "INCOMPLETE", riskLevel: "HIGH" });
    expect(await analytics()).toMatchObject({
      passCount: 1, failCount: 0, incompleteCount: 1, averagePct: 60, topperStudentId: "bob",
      riskDistribution: { LOW: 1, MEDIUM: 0, HIGH: 1 },
    });

    // Duplicate delete and delayed create deliveries must not resurrect marks.
    const result = await snapshot("alice");
    const summary = await analytics();
    await deliver(before, after);
    await deliver(after, before);
    const { updatedAt: resultTime, ...resultFields } = result!;
    const { updatedAt: summaryTime, ...summaryFields } = summary!;
    expect(await snapshot("alice")).toMatchObject(resultFields);
    expect(await analytics()).toMatchObject(summaryFields);
    expect((await school.collection("assessmentCycles").doc("c1").get()).get("published")).toBe(true);
  });

  test("removing one subject clears its map entry while keeping other percentages", async () => {
    await school.collection("assessments").doc("a2").set({
      cycleId: "c1", sectionId: "s1", subjectId: "science", maxMarks: 100,
    });
    await writeScore("math", "alice", 80);
    await writeScore("science", "alice", 60, "a2");
    expect((await snapshot("alice"))?.subjectPercentages).toEqual({ math: 80, science: 60 });

    const ref = school.collection("scores").doc("math");
    const before = await ref.get();
    await ref.delete();
    await deliver(before, await ref.get());
    expect((await snapshot("alice"))?.subjectPercentages).toEqual({ science: 60 });
    expect((await snapshot("alice"))?.passStatus).toBe("INCOMPLETE");
  });

  test("student reassignment refreshes both students and is replay safe", async () => {
    await writeScore("score", "alice", 80);
    const event = await writeScore("score", "bob", 80);
    expect((await snapshot("alice"))?.passStatus).toBe("INCOMPLETE");
    expect((await snapshot("bob"))?.overallPercentage).toBe(80);
    expect(await analytics()).toMatchObject({ passCount: 1, incompleteCount: 1, averagePct: 80, topperStudentId: "bob" });
    await deliver(event.before, event.after);
    expect((await snapshot("alice"))?.passStatus).toBe("INCOMPLETE");
    expect((await snapshot("bob"))?.overallPercentage).toBe(80);
  });

  test("assessment reassignment refreshes both cycles and sections", async () => {
    await school.collection("students").doc("bob").set({ sectionId: "s2", active: true });
    await school.collection("assessments").doc("a2").set({
      cycleId: "c2", sectionId: "s2", subjectId: "math", maxMarks: 100,
    });
    await school.collection("assessmentCycles").doc("c2").set({ published: true });
    await writeScore("score", "alice", 80);
    await writeScore("score", "bob", 70, "a2");
    expect((await snapshot("alice"))?.passStatus).toBe("INCOMPLETE");
    expect((await snapshot("bob", "c2", "s2"))?.overallPercentage).toBe(70);
    expect(await analytics()).toMatchObject({ passCount: 0, incompleteCount: 1, averagePct: 0, topperStudentId: null });
    expect(await analytics("c2", "s2")).toMatchObject({ passCount: 1, incompleteCount: 0, averagePct: 70, topperStudentId: "bob" });
  });

  test.each(["deleted", "moved", "invalid"])("recovers existing targets when an assessment is %s before delivery", async (state) => {
    await writeScore("score", "alice", 80);
    const ref = school.collection("scores").doc("score");
    const before = await ref.get();
    if (state === "deleted") {
      await school.collection("assessments").doc("a1").delete();
    } else {
      await school.collection("assessments").doc("a1").update({
        cycleId: state === "moved" ? "c2" : "", sectionId: "s2",
      });
    }
    await ref.delete();
    await deliver(before, await ref.get());
    expect(await snapshot("alice")).toMatchObject({ subjectPercentages: {}, overallPercentage: null, passStatus: "INCOMPLETE" });
    expect(await analytics()).toMatchObject({ passCount: 0, incompleteCount: 2, averagePct: 0, topperStudentId: null });
  });

  test("draft cycles refresh student results but leave section analytics to publish time", async () => {
    await school.collection("assessmentCycles").doc("c1").set({ published: false });
    await writeScore("alice-math", "alice", 80);
    expect((await snapshot("alice"))?.overallPercentage).toBe(80);
    expect((await school.collection("sectionAnalytics").get()).empty).toBe(true);
  });

  test("missing assessment without cached targets is a safe no-op", async () => {
    await writeScore("orphan", "alice", 80, "missing");
    expect((await school.collection("resultSnapshots").get()).empty).toBe(true);
    expect((await school.collection("sectionAnalytics").get()).empty).toBe(true);
  });
});
