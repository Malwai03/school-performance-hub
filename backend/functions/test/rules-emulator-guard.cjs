// Never fall back to a real Firebase service, even when running Jest directly.
for (const name of ["FIRESTORE_EMULATOR_HOST", "FIREBASE_STORAGE_EMULATOR_HOST"]) {
  const value = process.env[name] || "";
  if (!/^(localhost|127\.0\.0\.1|\[::1\]):[1-9]\d{0,4}$/.test(value)) {
    throw new Error(
      `${name} must point to a loopback emulator. Run npm run test:rules to start both emulators (Java 17+ required).`,
    );
  }
}
