// An in-memory Firestore adapter keeps these regressions offline while exercising
// the actual callable, grading/persistence path, cycle event, and FCM batching.
const mockRecords = new Map<string, Record<string, any>>();
const mockSend = jest.fn();
let mockTransactionTail = Promise.resolve();

function mockSnapshot(path: string) {
  const data = mockRecords.get(path);
  return {
    id: path.split("/").pop()!, exists: data !== undefined,
    data: () => data,
    get: (key: string) => data?.[key],
    ref: mockReference(path),
  };
}

function mockReference(path: string): any {
  const filters: Array<(data: Record<string, any>) => boolean> = [];
  const ref: any = {
    collection: (name: string) => mockReference(`${path}/${name}`),
    doc: (id: string) => mockReference(`${path}/${id}`),
    where: (key: string, op: string, value: any) => {
      filters.push((data) => op === "in" ? value.includes(data[key]) :
        op === "<" ? data[key] < value : data[key] === value);
      return ref;
    },
    orderBy: () => ref,
    limit: () => ref,
    get: async () => {
      // All document IDs used by this fixture contain no slash.
      if (path.split("/").length % 2 === 0) return mockSnapshot(path);
      const docs = Array.from(mockRecords.keys())
        .filter((key) => key.startsWith(`${path}/`) && !key.slice(path.length + 1).includes("/"))
        .filter((key) => filters.every((filter) => filter(mockRecords.get(key)!)))
        .map(mockSnapshot);
      return { docs, empty: docs.length === 0 };
    },
    set: async (data: Record<string, any>, options?: { merge: boolean }) => {
      mockRecords.set(path, { ...(options?.merge ? mockRecords.get(path) : {}), ...data });
    },
  };
  return ref;
}

jest.mock("firebase-admin/firestore", () => ({
  getFirestore: () => ({
    collection: (name: string) => mockReference(name),
    getAll: (...refs: any[]) => Promise.all(refs.map((ref) => ref.get())),
    runTransaction: (callback: (transaction: any) => Promise<any>) => {
      // Serialize claims to model transaction conflict/retry behavior.
      const result = mockTransactionTail.then(() => callback({
        get: (ref: any) => ref.get(),
        set: (ref: any, data: any) => ref.set(data),
      }));
      mockTransactionTail = result.then(() => undefined, () => undefined);
      return result;
    },
  }),
  FieldValue: { serverTimestamp: () => "server-time" },
  Timestamp: { now: () => "server-time" },
}));
jest.mock("firebase-admin/messaging", () => ({ getMessaging: () => ({ sendEachForMulticast: mockSend }) }));

import { __testables, onPublishRecomputeAnalytics, publishCycleResults } from "../src/index";

const school = "schools/school_001";
const publish = (sectionId: string) => publishCycleResults.run({
  auth: { uid: "admin", token: { role: "ADMIN" } },
  data: { schoolId: "school_001", cycleId: "c1", sectionId },
} as any);
const cycleEvent = () => onPublishRecomputeAnalytics.run({
  params: { schoolId: "school_001", cycleId: "c1" },
  data: { before: { data: () => ({ published: false }) }, after: { data: () => ({ published: true }) } },
} as any);
const notify = (sectionId = "A") => __testables.notifySectionPublish("school_001", sectionId, "c1");

beforeEach(() => {
  mockRecords.clear();
  mockTransactionTail = Promise.resolve();
  mockSend.mockReset().mockResolvedValue({ failureCount: 0 });
  mockRecords.set(`${school}/subjects/math`, { mandatory: true });
  for (const sectionId of ["A", "B"]) {
    mockRecords.set(`${school}/students/student_${sectionId}`, { sectionId, active: true });
    mockRecords.set(`${school}/assessments/math_${sectionId}`, {
      cycleId: "c1", sectionId, subjectId: "math", maxMarks: 100, weight: 1,
    });
    mockRecords.set(`${school}/users/user_${sectionId}`, { role: "STUDENT", sectionId, fcmTokens: [`token_${sectionId}`] });
  }
  mockRecords.set(`${school}/scores/score_A`, { assessmentId: "math_A", studentId: "student_A", obtainedMarks: 80, status: "PRESENT" });
});

test("publishing A, its cycle event, and retries notify A once and never notify unfinished B", async () => {
  await publish("A");
  await cycleEvent();
  await cycleEvent();
  await publish("A");
  expect(mockSend).toHaveBeenCalledTimes(1);
  expect(mockSend.mock.calls[0][0]).toMatchObject({ tokens: ["token_A"], data: { sectionId: "A", cycleId: "c1" } });
  expect(mockRecords.get(`${school}/publicationNotifications/c1_A`)?.status).toBe("SENT");
  expect(mockRecords.has(`${school}/publicationNotifications/c1_B`)).toBe(false);
});

test("explicitly publishing an incomplete section sends no availability notification", async () => {
  await publish("B");
  expect(mockSend).not.toHaveBeenCalled();
  expect(mockRecords.has(`${school}/publicationNotifications/c1_B`)).toBe(false);
});

test("the cycle event does not notify another complete section until that section is explicitly published", async () => {
  mockRecords.set(`${school}/scores/score_B`, { assessmentId: "math_B", studentId: "student_B", obtainedMarks: 10, status: "PRESENT" });
  await publish("A");
  await cycleEvent();
  expect(mockSend).toHaveBeenCalledTimes(1);
  await publish("B");
  expect(mockSend).toHaveBeenCalledTimes(2);
  expect(mockSend.mock.calls[1][0].tokens).toEqual(["token_B"]);
});

test.each([false, true])("duplicate stored scores stop publication before result persistence (reversed=%s)", async (reversed) => {
  const correction = { assessmentId: "math_A", studentId: "student_A", obtainedMarks: 10, status: "PRESENT" };
  const old = { ...correction, obtainedMarks: 90 };
  const entries: Array<[string, Record<string, any>]> = [
    [`${school}/scores/a_correction`, correction], [`${school}/scores/z_old`, old],
  ];
  mockRecords.delete(`${school}/scores/score_A`);
  if (reversed) entries.reverse();
  entries.forEach(([path, data]) => mockRecords.set(path, data));
  await expect(publish("A")).rejects.toMatchObject({ code: "failed-precondition" });
  expect(mockRecords.has(`${school}/resultSnapshots/c1_A_student_A`)).toBe(false);
  expect(mockRecords.has(`${school}/assessmentCycles/c1`)).toBe(false);
  expect(mockSend).not.toHaveBeenCalled();
});

test("concurrent calls claim one notification; tokens are distinct and split at 500", async () => {
  const tokens = Array.from({ length: 501 }, (_, i) => `token_${i}`);
  mockRecords.set(`${school}/users/user_A`, { role: "STUDENT", sectionId: "A", fcmTokens: [...tokens, tokens[0], "", null] });
  mockRecords.set(`${school}/users/second_A`, { role: "STUDENT", sectionId: "A", fcmTokens: [tokens[0]] });
  mockRecords.set(`${school}/sectionAnalytics/c1_A`, { passCount: 1, failCount: 0, incompleteCount: 0 });
  await Promise.all([notify(), notify()]);
  expect(mockSend.mock.calls.map(([message]) => message.tokens.length)).toEqual([500, 1]);
  expect(mockSend.mock.calls.flatMap(([message]) => message.tokens)).toEqual(tokens);
});

test.each([undefined, { passCount: 0, failCount: 0, incompleteCount: 0 }])("missing or empty section summary does not claim a notification (%j)", async (summary) => {
  if (summary) mockRecords.set(`${school}/sectionAnalytics/c1_A`, summary);
  await notify();
  expect(mockSend).not.toHaveBeenCalled();
  expect(mockRecords.has(`${school}/publicationNotifications/c1_A`)).toBe(false);
});

test("failed delivery is recorded and a retry cannot resend an already attempted batch", async () => {
  mockSend.mockRejectedValueOnce(new Error("FCM unavailable"));
  await expect(publish("A")).rejects.toThrow("FCM unavailable");
  await publish("A");
  expect(mockSend).toHaveBeenCalledTimes(1);
  expect(mockRecords.get(`${school}/publicationNotifications/c1_A`)?.status).toBe("FAILED");
});

test("partial FCM failures remain visible instead of being marked sent", async () => {
  mockSend.mockResolvedValueOnce({ failureCount: 1 });
  await publish("A");
  expect(mockRecords.get(`${school}/publicationNotifications/c1_A`)).toMatchObject({ status: "FAILED", failureCount: 1 });
});
