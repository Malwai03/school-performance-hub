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
npm install
npm run build
npm test
```

Score recomputation integration tests require the Firestore emulator and Firebase CLI.
From the repo root, run:
```bash
firebase emulators:exec --only firestore --project demo-school-score-results "npm --prefix backend/functions test -- --runTestsByPath test/score-recompute.spec.ts test/logic.spec.ts"
```
The tests invoke the exported score handler with real emulator snapshots; they do
not require a Functions emulator or send notifications. Without
`FIRESTORE_EMULATOR_HOST`, the integration suite is skipped. Current firebase-tools
needs Java 21; with Java 17 use `npx firebase-tools@13.35.1 emulators:exec ...`.

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
- Score creates, edits, deletes and reassignments refresh affected student results,
  and the section analytics of cycles that are already published (draft cycles get
  theirs at publish). Published results remain mutable, matching the existing
  score-edit behavior; recomputation does not republish or send notifications.
- Scores contain no historical cycle/section identifiers, so recomputation also
  refreshes existing snapshots for the affected students to recover old targets
  when an assessment has moved or disappeared. Replays read current scores rather
  than applying event marks or adding to counters. Computed snapshot documents are
  replaced so removed subject percentages are cleared.
- App ships with local demo seed data and backend-ready contract surfaces.
- CSV/XLSX import expects files in Firebase Storage under import paths.
