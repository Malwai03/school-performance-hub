# Scoped backend audit fixes

2026-11-01. Audit source: `school-performance-hub-bc318/NIGHTSHIFT_REVIEW.md`
in the sibling NightShift worktree (source commit `ccc8b14`).

## Changes and regressions

- **Finding 1, duplicate scores:** `computeResult` rejects duplicate assessment
  entries with `failed-precondition` before selecting a score or persisting a new
  result. It does not infer which correction is authoritative. Tests cover both
  input orders in the helper and the callable/datastore path, including no result,
  publication write, or notification after rejection. Imports still accept
  noncanonical IDs; existing duplicate documents require explicit reconciliation.
- **Finding 4, notification portion:** only the selected section's callable sends
  publication notifications. The cycle event retains analytics recomputation but
  no longer notifies every section. Notifications require a nonempty complete
  section summary. A Firestore transaction claims one notification attempt per
  school/cycle/section before FCM is contacted. Tokens are deduplicated and split
  into batches of at most 500. Tests invoke the actual callable and cycle handler
  with an in-memory datastore, replay both paths, check unfinished and unselected
  complete sections, exercise concurrent claims and 501 distinct tokens, and check
  failed and partially failed sends.

## Left unchanged by the requested scope

- **Finding 2:** discovering the full mandatory curriculum and rejecting unresolved
  subject references requires subject loading and `computeAndPersistStudentResult`
  changes. Helpers cannot recover subjects that their caller never supplies.
- **Finding 3:** deletion/reassignment invalidation requires the protected
  `onScoreWriteRecompute` path.
- **Finding 4, publication state:** the callable still sets a cycle-wide published
  flag, including for an incomplete section, and the cycle event still computes
  analytics for other sections. The notification guard does not change result
  visibility or create section-level publication authorization/readiness. Those
  changes require the excluded callable/rules work. Readiness also depends on the
  currently supplied subject roster (finding 2).
- **Finding 5:** refreshing section aggregates after corrections requires the
  protected score-recompute path.
- **Finding 6:** academic chronology must be fixed in `getPreviousOverall` and its
  cycle data lookup. `deriveRisk` receives only the already-selected number.
- **Finding 7:** preserving stored zero weights requires `fetchAssessments` changes;
  the helper already preserves zero and cannot recover a zero changed to one by
  its loader.
- The audit's rounding finding was **LIKELY**, not CONFIRMED, and was not changed.

No app code, rules, callables, password generation, loaders, or protected recompute
functions were edited. No packages were installed, and no network was used.

## Verification and delivery limits

- Existing `npm test`: **15 passed, 2 skipped** (the security-rules emulator suite).
- Existing TypeScript compiler with `--noEmit`: **passed**.
- Dependencies were reused through a temporary local `node_modules` junction to
  those already present in a sibling worktree; no dependency files were changed.
- Tests use an in-memory Firestore/FCM adapter. Real Firestore transaction retries,
  deployed SDK/event delivery, production data, and actual FCM delivery were not
  verified. No deployment or real notification was attempted.
- FCM does not accept an idempotency key. The durable claim prevents duplicate
  submission on retries, but a process interruption after claiming may lose a
  notification; a partial failure may deliver to only some tokens. `SENDING` and
  `FAILED` records in `publicationNotifications` need operator review. They are not
  automatically retried. `SENT` records a successful FCM response, not confirmed
  receipt by a device. One publication attempt per cycle/section is supported;
  a future republish-version contract requires separate work.
- This is a scoped mitigation, not completion of all seven audit findings.
