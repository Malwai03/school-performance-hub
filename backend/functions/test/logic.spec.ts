import { __testables } from "../src/index";

describe("grading logic", () => {
  test.each([false, true])("rejects duplicate assessment scores regardless of input order (reversed=%s)", (reversed) => {
    const duplicates = [
      { scoreId: "a_correction", assessmentId: "m1", studentId: "st1", obtainedMarks: 10, status: "PRESENT" as const },
      { scoreId: "z_old", assessmentId: "m1", studentId: "st1", obtainedMarks: 90, status: "PRESENT" as const },
    ];
    if (reversed) duplicates.reverse();

    expect(() => __testables.computeResult(
      [{ subjectId: "math", mandatory: true }],
      [{ assessmentId: "m1", cycleId: "c1", sectionId: "s1", subjectId: "math", maxMarks: 100 }],
      duplicates,
      null,
    )).toThrow("Duplicate scores for assessment m1");
  });

  test("marks incomplete when required score missing", () => {
    const result = __testables.computeResult(
      [{ subjectId: "math", mandatory: true, passThresholdPct: 33 }],
      [{ assessmentId: "a1", cycleId: "c1", sectionId: "s1", subjectId: "math", maxMarks: 100, weight: 1 }],
      [],
      null,
    );

    expect(result.passStatus).toBe("INCOMPLETE");
    expect(result.overallPercentage).toBeNull();
  });

  test("fails when overall below threshold", () => {
    const result = __testables.computeResult(
      [
        { subjectId: "math", mandatory: true, passThresholdPct: 33 },
        { subjectId: "science", mandatory: true, passThresholdPct: 33 },
      ],
      [
        { assessmentId: "m1", cycleId: "c1", sectionId: "s1", subjectId: "math", maxMarks: 100, weight: 1 },
        { assessmentId: "s1", cycleId: "c1", sectionId: "s1", subjectId: "science", maxMarks: 100, weight: 1 },
      ],
      [
        { scoreId: "x1", assessmentId: "m1", studentId: "st1", obtainedMarks: 40, status: "PRESENT" },
        { scoreId: "x2", assessmentId: "s1", studentId: "st1", obtainedMarks: 20, status: "PRESENT" },
      ],
      50,
    );

    expect(result.passStatus).toBe("FAIL");
    expect(result.riskLevel).toBe("HIGH");
  });

  test("passes when each mandatory subject and overall are above threshold", () => {
    const result = __testables.computeResult(
      [
        { subjectId: "math", mandatory: true, passThresholdPct: 33 },
        { subjectId: "science", mandatory: true, passThresholdPct: 33 },
      ],
      [
        { assessmentId: "m1", cycleId: "c1", sectionId: "s1", subjectId: "math", maxMarks: 100, weight: 1 },
        { assessmentId: "s1", cycleId: "c1", sectionId: "s1", subjectId: "science", maxMarks: 100, weight: 1 },
      ],
      [
        { scoreId: "x1", assessmentId: "m1", studentId: "st1", obtainedMarks: 55, status: "PRESENT" },
        { scoreId: "x2", assessmentId: "s1", studentId: "st1", obtainedMarks: 60, status: "PRESENT" },
      ],
      48,
    );

    expect(result.passStatus).toBe("PASS");
    expect(result.overallPercentage).toBe(57.5);
    expect(result.riskLevel).toBe("LOW");
  });
});
