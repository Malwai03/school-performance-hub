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
