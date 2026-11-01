import path from "node:path";

const RulesReporter = require("./rules-reporter.cjs");

describe("rules CI gate", () => {
  const suite = (name: string, passed = 1, skipped = 0, todo = 0) => ({
    testFilePath: path.join(__dirname, name),
    numPassingTests: passed,
    numFailingTests: 0,
    numPendingTests: skipped,
    numTodoTests: todo,
  });
  const firestore = suite("security.rules.spec.ts");
  const storage = suite("storage.rules.spec.ts");
  const score = suite("score.rules.spec.ts");
  const appsync = suite("appsync.rules.spec.ts");
  const teacherScope = suite("teacherscope.rules.spec.ts");

  beforeEach(() => { jest.spyOn(console, "log").mockImplementation(() => {}); });
  afterEach(() => { jest.restoreAllMocks(); });

  test("accepts executed tests from every rules suite", () => {
    const reporter = new RulesReporter();
    reporter.onRunComplete(new Set(), { testResults: [firestore, storage, score, appsync, teacherScope] });
    expect(reporter.getLastError()).toBeUndefined();
  });

  test.each([
    ["missing suite", [firestore, storage, score, teacherScope]],
    ["missing teacher scope suite", [firestore, storage, score, appsync]],
    ["empty suite", [firestore, suite("storage.rules.spec.ts", 0), score, appsync, teacherScope]],
    ["skipped test", [firestore, suite("storage.rules.spec.ts", 1, 1), score, appsync, teacherScope]],
    ["todo test", [firestore, storage, suite("score.rules.spec.ts", 1, 0, 1), appsync, teacherScope]],
    ["skipped app sync test", [firestore, storage, score, suite("appsync.rules.spec.ts", 1, 1), teacherScope]],
    ["skipped teacher scope test", [firestore, storage, score, appsync, suite("teacherscope.rules.spec.ts", 1, 1)]],
  ])("rejects a green Jest result with a %s", (_label, testResults) => {
    const reporter = new RulesReporter();
    reporter.onRunComplete(new Set(), { testResults });
    expect(reporter.getLastError()).toBeInstanceOf(Error);
  });

  test("rejects skips in any additional rules suite", () => {
    const reporter = new RulesReporter();
    reporter.onRunComplete(new Set(), {
      testResults: [firestore, storage, score, appsync, teacherScope], numPendingTests: 1, numPendingTestSuites: 1,
    });
    expect(reporter.getLastError()).toBeInstanceOf(Error);
  });
});

describe("rules emulator guard", () => {
  const names = ["FIRESTORE_EMULATOR_HOST", "FIREBASE_STORAGE_EMULATOR_HOST"] as const;
  const original = names.map((name) => process.env[name]);

  afterEach(() => {
    names.forEach((name, index) => {
      if (original[index] === undefined) delete process.env[name];
      else process.env[name] = original[index];
    });
  });

  test.each([
    ["", ""],
    ["127.0.0.1:8080", ""],
    ["", "127.0.0.1:9199"],
    ["remote.example:8080", "127.0.0.1:9199"],
    ["127.0.0.1:8080", "remote.example:9199"],
  ])("rejects missing or remote hosts (%s, %s) with a recovery command", (firestore, storage) => {
    process.env[names[0]] = firestore;
    process.env[names[1]] = storage;
    expect(() => jest.isolateModules(() => require("./rules-emulator-guard.cjs")))
      .toThrow(/npm run test:rules/);
  });

  test("accepts a pair of loopback emulators", () => {
    process.env[names[0]] = "127.0.0.1:8080";
    process.env[names[1]] = "localhost:9199";
    expect(() => jest.isolateModules(() => require("./rules-emulator-guard.cjs"))).not.toThrow();
  });
});
