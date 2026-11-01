import { assignedSection } from "../src/teacherScope";

describe("assignedSection (what a teacherAssignments doc grants in the read rules)", () => {
  const data = { sectionId: "g1_a", subjectId: "math", teacherUid: "t1" };

  test("grants its section to the teacher named by both its id and its fields", () => {
    expect(assignedSection("g1_a_math_t1", data, "t1")).toBe("g1_a");
  });

  test.each([
    ["a deleted assignment", "g1_a_math_t1", undefined, "t1"],
    ["another teacher", "g1_a_math_t1", data, "t2"],
    ["an id for another pair", "g1_b_math_t1", data, "t1"],
    ["an id for another teacher", "g1_a_math_t2", { ...data, teacherUid: "t1" }, "t1"],
    ["a blank section", "_math_t1", { ...data, sectionId: "" }, "t1"],
    ["a missing subject", "g1_a__t1", { sectionId: "g1_a", teacherUid: "t1" }, "t1"],
    ["a non-string teacherUid", "g1_a_math_1", { ...data, teacherUid: 1 }, "1"],
  ])("grants nothing for %s", (_label, id, doc, uid) => {
    expect(assignedSection(id as string, doc as Record<string, unknown> | undefined, uid as string)).toBeNull();
  });
});
