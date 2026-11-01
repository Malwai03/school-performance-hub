// One-off backfill of users/{teacherUid}.assignmentSections from teacherAssignments, for assignments
// written before onTeacherAssignmentWrite was deployed. Safe to run again at any time.
//
//   cd backend/functions && npm run build
//   GOOGLE_CLOUD_PROJECT=<project-id> node scripts/rebuild-teacher-scopes.js [schoolId ...]
//
// Uses Application Default Credentials (gcloud auth application-default login, or
// GOOGLE_APPLICATION_CREDENTIALS). With FIRESTORE_EMULATOR_HOST set it writes to the emulator instead.
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { rebuildTeacherScopes } = require("../lib/teacherScope");

const projectId = process.env.GOOGLE_CLOUD_PROJECT || process.env.GCLOUD_PROJECT;
if (!projectId) {
  console.error("Set GOOGLE_CLOUD_PROJECT to the Firebase project id.");
  process.exit(1);
}
initializeApp({ projectId });
const schoolIds = process.argv.slice(2);

rebuildTeacherScopes(getFirestore(), schoolIds.length ? schoolIds : undefined)
  .then((summary) => console.log(`Rebuilt teacher scopes: ${JSON.stringify(summary)}`))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
