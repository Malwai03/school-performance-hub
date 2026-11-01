# School App (Android + Firebase)

Role-based student performance app for grades 1-10 with offline-first Android client and Firebase backend.

## Stack
- Android: Kotlin, Jetpack Compose, Room, WorkManager, Firebase SDK
- Backend: Firebase Auth, Firestore, Cloud Functions (TypeScript), FCM, Storage
- Security: Firestore and Storage rules with role constraints

## Modules
- `app/`: Android application
- `backend/functions/`: Cloud Functions source
- `backend/firestore/firestore.rules`: Firestore authorization rules
- `backend/storage/storage.rules`: Storage authorization rules

## Quick Start

### Android
1. Open project in Android Studio (Hedgehog+).
2. Build and run app for local demo mode (offline/local-first works without Firebase config).
3. For cloud sync/auth, add `app/google-services.json` and Firebase project settings.
4. Demo login codes:
   - `admin001`
   - `teacher001`
   - `student001`
   - password for demo users: `pass123`

### Firebase Functions
```bash
cd backend/functions
npm ci
npm run build
npm test
```

`npm test` (or `npm run test:unit`) runs lightweight grading and rules-harness unit tests.
Security rules have a separate required check:

```bash
cd backend/functions
npm ci
npm run test:rules
```

Use Node 20 and Java 17+ on `PATH`. The rules command downloads the pinned Firebase
CLI (13.35.1) and emulator binaries on first use, so that first run needs network
access. It starts only Firestore (127.0.0.1:8080) and Storage (127.0.0.1:9199), runs
both suites, then shuts them down. Keep those ports free. Missing Java, downloads
or occupied ports cause the command to fail; they never skip the tests.
No Firebase login, service account, production credentials or deployed project is
needed: `firebase.rules-test.json` and the `demo-school-rules` project keep this
run separate from `.firebaserc` and live Firebase resources.

`npm run test:rules:run` is the inner Jest command for an already running pair of
loopback emulators using that same demo project. It fails with setup instructions
when either emulator host variable is missing. Fixtures bypass rules only while
seeding, then authenticated/anonymous client operations exercise the actual rules.
Firestore data is cleared before every test; Storage tests also clear all objects.
The rules reporter prints executed/skipped counts for each suite and fails if
either suite is absent, empty, skipped or contains todo tests. Detailed counts are
written to `backend/functions/rules-test-results.json` and uploaded by the
`Backend tests` GitHub Actions workflow, which runs build, unit and rules checks.

The rules checks cover school user records, teacher assignments and score bounds,
student ownership, published/unpublished result access, backend-only result
writes, and Storage tenant/role boundaries. Students can read their own results
only after the linked assessment cycle is published; staff can inspect drafts.
Storage import access requires the ADMIN role, an ADMIN record in that school's
`users` collection (the same membership the Firestore rules use), and, when the token
has a `schoolId` claim, a matching one. These tests validate local emulator behavior;
they do not deploy rules or validate production IAM or callable functions.

Score writes require an existing student in the assessment's section, numeric
marks between zero and its maximum, and `scoreId` equal to the document ID.
Both teachers and admins follow these checks; only teachers need the matching
section/subject assignment. Updates preserve `studentId`, `assessmentId`, and
`scoreId`. Score IDs remain opaque, matching the Android sync contract; no new
ID format is imposed. Admin deletion is still permitted. Existing invalid score
records need separate administrator review before these rules are deployed.

Deploy (from repo root):
```bash
firebase deploy --only firestore:rules,storage:rules,functions
```

## Key Features Implemented
- Role-based app routing (`ADMIN`, `TEACHER`, `STUDENT`)
- Room-backed offline persistence and periodic score sync
- Admin modular workflow:
  - `Dashboard` (KPI + action queue)
  - `Roster` (Grade -> Section hierarchy, search, add/batch/import, student profile)
  - `Gradebook` (context-first marks table, bulk status/marks, readiness check, publish)
  - `Setup` (section/subject/cycle-semester wizard + import center)
- Student profile tabs for admin: `Overview`, `Subjects`, `Marks Entry`, `Trends`
- Teacher gradebook aligned with shared context and marks table components
- Student dashboard with pass/fail, risk, and trend
- Shared UX components:
  - `ContextSelectorBar`
  - `SectionHierarchyPanel`
  - `MarksEntryTable`
  - `WizardStepCard`
  - `EmptyStateCard`
- Callable backend functions:
  - `importCsv`
  - `importExcel`
  - `publishCycleResults`
- Triggers:
  - score-write recompute
  - publish recompute + notification
- Core grading engine with unit tests
- Publish readiness validation before final publish
- Bulk score upsert contract for high-speed marks entry

## Notes
- Result snapshots are backend computed and write-protected from clients.
- App ships with local demo seed data and backend-ready contract surfaces.
- CSV/XLSX import expects files in Firebase Storage under import paths.
