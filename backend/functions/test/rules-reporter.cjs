const path = require("node:path");

// A green CI run must contain assertions from every rule suite (security, storage, score, app sync), with no skips.
class RulesReporter {
  onRunComplete(_contexts, results) {
    this.error = undefined;
    if (results.numPendingTests || results.numTodoTests || results.numPendingTestSuites) {
      this.error = new Error("Rules gate does not allow skipped suites, skipped tests or todo tests.");
    }
    const expected = [
      "security.rules.spec.ts", "storage.rules.spec.ts", "score.rules.spec.ts", "appsync.rules.spec.ts",
    ];
    for (const file of expected) {
      const suite = results.testResults.find((result) => path.basename(result.testFilePath) === file);
      const executed = suite ? suite.numPassingTests + suite.numFailingTests : 0;
      const skipped = suite ? suite.numPendingTests + suite.numTodoTests : 0;
      console.log(`Rules gate: ${file}: executed=${executed}, skipped=${skipped}`);
      if (!suite || executed === 0 || skipped !== 0) {
        this.error = new Error(`Rules gate requires executed tests and zero skipped/todo tests in ${file}.`);
      }
    }
  }

  getLastError() {
    return this.error;
  }
}

module.exports = RulesReporter;
