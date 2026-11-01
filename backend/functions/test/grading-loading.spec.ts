import { getFirestore } from "firebase-admin/firestore";
import { __testables } from "../src/index";

jest.mock("firebase-admin/firestore", () => {
  const actual = jest.requireActual("firebase-admin/firestore");
  const firestore = { collection: jest.fn(), getAll: jest.fn() };
  return { ...actual, getFirestore: () => firestore };
});

type Fields = Record<string, unknown>;
type Filter = { field: string; op: "==" | "in" | "<"; value: unknown };
type MockDocumentRef = {
  path: string;
  collection(name: string): MockCollectionRef;
  get(): Promise<ReturnType<typeof snapshot>>;
  set(fields: Fields, options?: { merge: boolean }): Promise<void>;
};
type MockCollectionRef = {
  doc(id: string): MockDocumentRef;
  where(field: string, op: Filter["op"], value: unknown): MockCollectionRef;
  orderBy(field: string, direction?: string): MockCollectionRef;
  limit(count: number): MockCollectionRef;
  get(): Promise<{ docs: ReturnType<typeof snapshot>[]; empty: boolean }>;
};

// Exercise the production loaders and persisted result without a live datastore.
// Queries honor their filters/order/limit, including the old cycle-ID query.
const documents = new Map<string, Fields>();
const schoolPath = "schools/school_001";
const resultPath = `${schoolPath}/resultSnapshots/cycle_300_s1_st1`;

function seed(collection: string, id: string, fields: Fields): void {
  documents.set(`${schoolPath}/${collection}/${id}`, fields);
}

function snapshot(path: string) {
  return {
    id: path.split("/").pop()!,
    exists: documents.has(path),
    get: (field: string) => documents.get(path)?.[field],
  };
}

function documentRef(path: string): MockDocumentRef {
  return {
    path,
    collection: (name: string) => collectionRef(`${path}/${name}`),
    get: async () => snapshot(path),
    set: async (fields: Fields, options?: { merge: boolean }) => {
      documents.set(path, { ...(options?.merge ? documents.get(path) : {}), ...fields });
    },
  };
}

function collectionRef(
  path: string,
  filters: Filter[] = [],
  ordering?: { field: string; direction: string },
  count?: number,
): MockCollectionRef {
  return {
    doc: (id: string) => {
      if (!id) throw new Error("Document ID must not be empty");
      return documentRef(`${path}/${id}`);
    },
    where: (field: string, op: Filter["op"], value: unknown) =>
      collectionRef(path, [...filters, { field, op, value }], ordering, count),
    orderBy: (field: string, direction = "asc") => collectionRef(path, filters, { field, direction }, count),
    limit: (limit: number) => collectionRef(path, filters, ordering, limit),
    get: async () => {
      let docs = [...documents.keys()]
        .filter((key) => key.slice(0, key.lastIndexOf("/")) === path)
        .map(snapshot)
        .filter((doc) => filters.every(({ field, op, value }) => {
          const actual = doc.get(field);
          if (op === "==") return actual === value;
          if (op === "in") return Array.isArray(value) && value.includes(actual);
          if (op === "<") return typeof actual === "string" && typeof value === "string" && actual < value;
          throw new Error(`Unsupported query operator: ${op}`);
        }));
      if (ordering) {
        const { field, direction } = ordering;
        docs = docs.filter((doc) => doc.get(field) !== undefined).sort((a, b) =>
          String(a.get(field)).localeCompare(String(b.get(field))) * (direction === "desc" ? -1 : 1),
        );
      }
      if (count !== undefined) docs = docs.slice(0, count);
      return { docs, empty: docs.length === 0 };
    },
  };
}

function seedAssessment(id: string, subjectId: string, marks: number, extra: Fields = {}): void {
  seed("assessments", id, { cycleId: "cycle_300", sectionId: "s1", subjectId, maxMarks: 100, ...extra });
  seed("scores", `score_${id}`, { assessmentId: id, studentId: "st1", obtainedMarks: marks, status: "PRESENT" });
}

function seedHistory(cycleId: string, startDateIso: string, overallPercentage: unknown, extra: Fields = {}): void {
  seed("assessmentCycles", cycleId, { startDateIso, published: true });
  seed("resultSnapshots", `${cycleId}_s1_st1`, {
    cycleId, sectionId: "s1", studentId: "st1", overallPercentage, passStatus: "PASS", ...extra,
  });
}

function recompute() {
  return __testables.computeAndPersistStudentResult("school_001", "cycle_300", "s1", "st1");
}

beforeEach(() => {
  documents.clear();
  const db = getFirestore();
  jest.mocked(db.collection).mockImplementation((path) => collectionRef(path) as unknown as ReturnType<typeof db.collection>);
  jest.mocked(db.getAll).mockImplementation(async (...refs) => refs.map((ref) => {
    if (!("path" in ref)) throw new Error("Mock getAll expects document references");
    return snapshot(ref.path);
  }) as unknown as Awaited<ReturnType<typeof db.getAll>>);
  seed("sections", "s1", { grade: 6 });
  seed("subjects", "math", { grade: 6, mandatory: true, passThresholdPct: 33 });
  seed("assessmentCycles", "cycle_300", { startDateIso: "2026-11-01", published: true });
});

describe("required subject loading (finding 2)", () => {
  test("persists INCOMPLETE when a grade's mandatory subject has no assessment", async () => {
    seed("subjects", "science", { grade: 6, mandatory: true });
    seedAssessment("math_exam", "math", 80, { weight: 1 });

    const result = await recompute();

    expect(result.passStatus).toBe("INCOMPLETE");
    expect(result.subjectPercentages).toEqual({ math: 80 });
    expect(documents.get(resultPath)?.passStatus).toBe("INCOMPLETE");
  });

  test("also includes subjects whose mandatory flag defaults to true", async () => {
    seed("subjects", "science", { grade: 6 });
    seedAssessment("math_exam", "math", 80);
    expect((await recompute()).passStatus).toBe("INCOMPLETE");
  });

  test("does not require optional subjects or subjects from another grade or school", async () => {
    seed("subjects", "art", { grade: 6, mandatory: false });
    seed("subjects", "science", { grade: 7, mandatory: true });
    documents.set("schools/another_school/subjects/science", { grade: 6, mandatory: true });
    seedAssessment("math_exam", "math", 80);

    const result = await recompute();
    expect(result.passStatus).toBe("PASS");
    expect(result.overallPercentage).toBe(80);
  });

  test("rejects a referenced but missing subject before persisting a result", async () => {
    seedAssessment("math_exam", "math", 80);
    seedAssessment("science_exam", "missing_science", 100);

    await expect(recompute()).rejects.toMatchObject({ code: "failed-precondition" });
    expect(documents.has(resultPath)).toBe(false);
  });

  test("rejects an empty subject reference instead of silently discarding it", async () => {
    seedAssessment("math_exam", "math", 80);
    seedAssessment("unknown_exam", "", 100);
    await expect(recompute()).rejects.toMatchObject({ code: "failed-precondition" });
    expect(documents.has(resultPath)).toBe(false);
  });

  test("persists INCOMPLETE when the grade has no assessments at all", async () => {
    const result = await recompute();
    expect(result.passStatus).toBe("INCOMPLETE");
    expect(result.overallPercentage).toBeNull();
    expect(documents.get(resultPath)?.passStatus).toBe("INCOMPLETE");
  });

  test.each([undefined, "6", 0])("rejects an unresolved section grade (%s)", async (grade) => {
    seed("sections", "s1", { grade });
    seedAssessment("math_exam", "math", 80);
    await expect(recompute()).rejects.toMatchObject({ code: "failed-precondition" });
    expect(documents.has(resultPath)).toBe(false);
  });

  test("rejects a missing section", async () => {
    documents.delete(`${schoolPath}/sections/s1`);
    seedAssessment("math_exam", "math", 80);
    await expect(recompute()).rejects.toMatchObject({ code: "failed-precondition" });
    expect(documents.has(resultPath)).toBe(false);
  });
});

describe("assessment weight loading (finding 7)", () => {
  test.each([
    { weight: 0, expectedWeight: 0, overall: 20, passStatus: "FAIL", riskLevel: "HIGH" },
    { weight: undefined, expectedWeight: 1, overall: 60, passStatus: "PASS", riskLevel: "LOW" },
    { weight: null, expectedWeight: 1, overall: 60, passStatus: "PASS", riskLevel: "LOW" },
    { weight: 3, expectedWeight: 3, overall: 80, passStatus: "PASS", riskLevel: "LOW" },
  ])("loads weight $weight and persists $overall%/$passStatus", async ({ weight, expectedWeight, overall, passStatus, riskLevel }) => {
    seedAssessment("exam", "math", 20, { weight: 1 });
    seedAssessment("practice", "math", 100, weight === undefined ? {} : { weight });

    const loaded = await __testables.fetchAssessments("school_001", "cycle_300", "s1");
    expect(loaded.find((assessment) => assessment.assessmentId === "practice")?.weight).toBe(expectedWeight);
    expect(await recompute()).toMatchObject({ overallPercentage: overall, passStatus, riskLevel });
    expect(documents.get(resultPath)).toMatchObject({ overallPercentage: overall, passStatus, riskLevel });
  });

  test.each([NaN, Infinity, -Infinity, "invalid"])("rejects a non-finite stored weight (%s)", async (weight) => {
    seedAssessment("exam", "math", 20, { weight: 1 });
    seedAssessment("practice", "math", 100, { weight });
    await expect(recompute()).rejects.toMatchObject({ code: "failed-precondition" });
    expect(documents.has(resultPath)).toBe(false);
  });

  test("the grading helper also rejects a non-finite weight", () => {
    expect(() => __testables.computeResult(
      [{ subjectId: "math" }],
      [{ assessmentId: "exam", cycleId: "cycle_300", sectionId: "s1", subjectId: "math", maxMarks: 100, weight: NaN }],
      [{ scoreId: "score", assessmentId: "exam", studentId: "st1", obtainedMarks: 80, status: "PRESENT" }],
      null,
    )).toThrow("Assessment weight must be finite");
  });
});

describe("academic baseline loading (finding 6)", () => {
  describe.each(["cycle_100", "cycle_900"])("October recorded as %s", (octoberId) => {
    beforeEach(() => {
      // September was created after October in the first case. In the second,
      // October was backfilled after the current November cycle was created.
      seedHistory(octoberId, "2026-10-01", 80);
      seedHistory("cycle_200", "2026-09-01", 60);
      seedHistory("cycle_050", "2026-08-01", 100);
      seedHistory("cycle_150", "2026-12-01", 10); // Future academic cycle, smaller ID.
      seedHistory("cycle_250", "2026-11-01", 10); // Same start is not earlier.
      seedHistory("cycle_275", "2026-10-25", 50);
      seed("assessmentCycles", "cycle_275", { startDateIso: "2026-10-25", published: false });
      seedHistory("cycle_280", "2026-10-20", 50, { passStatus: "INCOMPLETE" });
      seedHistory("cycle_281", "2026-10-21", NaN);
      seedHistory("cycle_282", "2026-10-32", 50);
      seedHistory("cycle_283", "2026-10-23", 50, { passStatus: undefined });
      seedHistory("cycle_290", "2026-10-30", 50);
      documents.delete(`${schoolPath}/assessmentCycles/cycle_290`);
      seed("resultSnapshots", "cycle_300_s1_st1", {
        cycleId: "cycle_300", studentId: "st1", overallPercentage: 99, passStatus: "PASS",
      });
      seed("resultSnapshots", `${octoberId}_s1_other`, {
        cycleId: octoberId, studentId: "other", overallPercentage: 99, passStatus: "PASS",
      });
    });

    test("selects October's 80 as the nearest earlier published, complete baseline", async () => {
      expect(await __testables.getPreviousOverall("school_001", "st1", "cycle_300")).toBe(80);
    });

    test.each([
      { overall: 65, riskLevel: "HIGH" },
      { overall: 74.01, riskLevel: "LOW" }, // 5.99-point decline.
      { overall: 74, riskLevel: "MEDIUM" }, // 6-point decline.
      { overall: 68.01, riskLevel: "MEDIUM" }, // 11.99-point decline.
      { overall: 68, riskLevel: "HIGH" }, // 12-point decline.
    ])("recomputes $overall% as $riskLevel risk using academic chronology", async ({ overall, riskLevel }) => {
      seedAssessment("exam", "math", overall, { weight: 1 });
      expect(await recompute()).toMatchObject({ overallPercentage: overall, passStatus: "PASS", riskLevel });
      expect(documents.get(resultPath)).toMatchObject({ overallPercentage: overall, riskLevel });
    });
  });

  test("accepts a complete FAIL snapshot as a historical baseline", async () => {
    seedHistory("cycle_100", "2026-09-01", 80);
    seedHistory("cycle_900", "2026-10-01", 20, { passStatus: "FAIL" });
    expect(await __testables.getPreviousOverall("school_001", "st1", "cycle_300")).toBe(20);
  });

  test("uses no baseline when all history is unpublished or incomplete", async () => {
    seedHistory("cycle_100", "2026-10-01", 80, { passStatus: "INCOMPLETE" });
    seedHistory("cycle_200", "2026-09-01", 90);
    seed("assessmentCycles", "cycle_200", { startDateIso: "2026-09-01", published: false });
    seedAssessment("exam", "math", 65);

    expect(await __testables.getPreviousOverall("school_001", "st1", "cycle_300")).toBeNull();
    expect((await recompute()).riskLevel).toBe("LOW");
  });

  test.each([undefined, "", "not-a-date", "2026-02-30"])("does not infer chronology from IDs when the current date is invalid (%s)", async (startDateIso) => {
    seed("assessmentCycles", "cycle_300", { startDateIso, published: true });
    seedHistory("cycle_100", "2026-10-01", 80);
    expect(await __testables.getPreviousOverall("school_001", "st1", "cycle_300")).toBeNull();
  });
});
