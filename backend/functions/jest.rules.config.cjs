module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  roots: ["<rootDir>/test"],
  testMatch: ["<rootDir>/test/*.rules.spec.ts"],
  setupFiles: ["<rootDir>/test/rules-emulator-guard.cjs"],
  reporters: ["default", "<rootDir>/test/rules-reporter.cjs"],
  testTimeout: 30000,
};
