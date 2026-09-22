# Developing Root

This is the durable, shared onboarding guide for anyone picking up this codebase —
what to read first, how the pieces fit together, and how to extend or verify it.
It replaces the informal, local-only handoff notes an AI assistant may have left in
`HANDOFF.md`/`claude.md` on a particular machine: those files are git-ignored and
never assumed to exist. If something a teammate needs isn't here or in
[`README.md`](../README.md) / [`DESIGN.md`](DESIGN.md), that's a gap in this guide,
not a private note someone forgot to share.

## Reading order

1. [`README.md`](../README.md) — what the app is, how to run it, and its honest
   content/billing limitations.
2. [`DESIGN.md`](DESIGN.md) — the visual/interaction system and motion spec.
3. This file — the code map, data flow, and how to extend each area.

## Module map and responsibilities

All Kotlin lives under `app/src/main/kotlin/com/root/app/`.

| Package | Responsibility |
|---|---|
| (root) | `MainActivity` (navigation/theme host), `RootApplication` (billing bootstrap), `RootViewModel` (all UI-facing state) |
| `data/` | Room entities/DAOs/migrations, `RootRepository` (the one door into persistence), `Scheduler` (due-date rules), `ContentAccess` (pack unlock rules), `SeedData` (bundled starter content), preference wrappers |
| `practice/` | `PracticeRepository`: the durable, Room-backed practice session engine (queue paging, rating, resume, stop/close) |
| `content/` | Serializable catalog/pack contracts, validation, bounded HTTPS transport, immutable pack files, WorkManager installs and availability |
| `learning/` | Transactional lesson commands, revision-pinned runs, response evaluation and learning evidence separate from recall |
| `billing/` | RevenueCat configuration guard, shared entitlement cache (`EntitlementStore`), paywall state machine (`PaywallViewModel`) |
| `audio/` | `RootAudioSession`: recording/playback lifecycle, permission handling, file ownership |
| `sharing/` | PNG phrase-card rendering (`PhraseCardRenderer`) and FileProvider-backed sharing (`PhraseCardSharing`) |
| `widget/` | `RootWidget`: Glance home-screen widget showing the next due phrase |
| `ui/` | Screens (`PracticeScreen`, `PacksScreen`, `PaywallScreen`, `InviteScreen`, `ContributeScreen`, `SessionCompleteScreen`, `DesignStudyScreen`) |
| `ui/theme/` | Color scheme, typography, shapes, paper-grain surface |
| `ui/root/`, `ui/brand/`, `ui/icon/` | Shared root-branch geometry, wordmark, and line-icon set |
| `ui/launch/`, `ui/motion/` | Launch sequence and shared animation/haptic constants |
| `ui/audio/` | Compose wrapper around `RootAudioSession` (permission requests, recording UI) |

## How a practice session actually flows

1. `MainActivity` composes `RootTheme` and hosts a single `NavHost` inside
   `RootNavigation`; all screen state comes from one `RootViewModel` instance shared
   across the whole graph (via `viewModel()`).
2. `RootViewModel.load()` calls `RootRepository.initialize()` (idempotent seeding via
   `SeedData`), resolves the active language, and calls
   `RootRepository.beginOrResumePractice`, which durably resumes an open
   `PracticeSessionEntity` for the current scope (language + pack) if one exists, or
   starts a new one. `RootViewModel` only keeps a `sessionId`/`sessionPackId` pair in
   `SavedStateHandle` — the queue itself lives entirely in Room, so process death,
   navigation, or app restarts never lose progress, and there's no 24h expiry.
3. A session is a `PracticeQueueEntryEntity` row-per-phrase queue owned by
   `PracticeRepository`, paged in from due phrases in bounded chunks
   (`continueSession`/`fillPage`) rather than materialized all at once. Every rating
   is committed transactionally: `PracticeRepository.rate()` writes the `AttemptEntity`
   (via `Scheduler.nextDueAt` to compute the next due date), marks the queue entry
   `RATED`, and — for a `MISSED` outcome — appends at most one same-session retry
   entry for that phrase. Rating is idempotent: replaying a `rate()` call for an
   already-committed entry returns `AlreadyCommitted` instead of double-recording.
4. `PracticeScreen`/`PracticeCard` render the current phrase, handle reveal, and
   handle both button and swipe-gesture rating, calling back into `RootViewModel`.
   `PracticeScreen` also exposes a "Stop for now" action that calls
   `RootViewModel.stopSession()`, which durably ends the run (`PracticeEndReason.STOPPED`)
   without discarding history; closing the screen calls `closeSession()` similarly.
5. When the queue is exhausted (`hasMoreDue` is false and no `current` remains),
   `SessionCompleteScreen` renders either "session done" or "nothing due" copy
   depending on whether anything was actually reviewed. Switching language or pack
   ends any open run for the old scope before starting one for the new scope.

## Persistence and content rules

- Every entity uses a UUID primary key (except stable seeded IDs like
  `phrase-dholuo-hello`) with an `updatedAt` column, so future content updates can
  re-import without breaking existing learner history.
- `SeedData.seedIfEmpty` only **inserts missing** rows — it never replaces or deletes
  existing packs/phrases/attempts. This is why reseeding on every app start is safe.
- `ContentAccess.unlockedPackIds`/`canAccess` is the single access rule used by pack
  listings, the due queue, the widget, and `PracticeRepository`'s paging/revalidation.
  If you add a new content-gating concept, wire it through this helper rather than
  duplicating checks.
- `AttemptDao`'s due/latest-attempt queries break millisecond ties using SQLite
  `rowid` (insertion order), since two attempts can share a timestamp in tests or on
  fast devices.
- Schema changes bump `AppDatabase`'s version and add a `MIGRATION_x_y`; `exportSchema
  = true` writes JSON snapshots to `app/schemas/` (checked in) so migrations can be
  tested. See `Migration2To3Test` for the pattern: build a throwaway same-version
  `@Database` class to generate a guaranteed-correct "before" schema, populate it,
  then reopen the same file with the real `AppDatabase` and assert it opens cleanly
  and old data survived — Room throws on any schema mismatch, so a clean reopen is
  the main correctness signal.

The database is now version 4. The v3-to-v4 migration adds content versions,
installed pointers/jobs, managed-phrase mappings, media references, and lesson
runs/events/command receipts. Existing phrase IDs and attempts are not replaced.
Migration fixtures for older databases must use legacy-only DAOs, not new queries
that reference tables absent from their historical schema.

Curated updates never take over an unmanaged phrase/pack ID. Managed retirement
changes availability rather than deleting parent rows (which would cascade-delete
attempts). Due queries, access/revalidation, widget selection and sharing apply that
availability. Failed updates do not change the installed pointer. Filesystem rename
and Room commit are separate operations: immutable files are made ready first,
then the database pointer is committed, with interrupted requests exposed for retry.

Teaching's Back action pauses an unfinished run; explicit Restart creates new
evidence without deleting old runs. This differs intentionally from phrase
practice's existing Stop/Close behavior. Hints/transcript reveals are persisted
before display, and repeated/assisted responses do not become independent success.
Use the shared contracts in [TEACHING_CONTRACTS.md](TEACHING_CONTRACTS.md).

## Extending Root

- **New screen/route**: add a composable under `ui/`, wire a `composable("route")`
  block in `MainActivity.RootNavigation`, and add any new state/actions to
  `RootViewModel` rather than giving the screen direct repository access.
- **New curated teaching pack/language**: use the source registry, editorial
  review gates and deterministic pack builder in [CONTENT.md](CONTENT.md).
  `SeedData` remains the legacy starter initializer, not the downloadable-content
  publisher. Never bulk-upsert imported content over learner-owned records.
- **New DAO query**: add it to the relevant `Dao` interface in `Daos.kt`; keep
  read paths going through `RootRepository`, not directly from ViewModels/UI.
- **New motion/haptic**: add constants to `RootMotion`/`RootHaptics` rather than
  inlining new easing curves or vibration patterns per screen.

## Local setup

See [`README.md`](../README.md#run) for SDK/JDK requirements and build commands.
For RevenueCat Test Store, follow README's "Optional Test Store purchases" section —
the API key goes in your **user-level** `gradle.properties`, never in source control.

## Tests

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
$env:ANDROID_SERIAL = "your-device-or-emulator-id"   # always target explicitly
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:lintDebug
```

Unit tests (`app/src/test`) cover `Scheduler` and `ContentAccess` in isolation.
Instrumented tests (`app/src/androidTest`) cover Room persistence/seeding, the
`PracticeRepository` session engine (resume, paging, idempotent rating, one-retry
rule, durable stop/close, access revocation), the `MIGRATION_2_3` schema upgrade,
Compose UI accessibility and both themes, phrase-card image generation/sharing, and
end-to-end navigation. Always set `ANDROID_SERIAL` explicitly before running
instrumented tests — never assume a particular emulator/device is attached, since
device tests can modify app state or uninstall packages.

## Collaboration conventions

- Keep commits scoped to one functional area (data, billing, a screen, docs) with
  a subject line and a body explaining rationale — see recent commit history for
  the expected format.
- Branch per feature/fix; open a pull request rather than pushing directly to
  `main` once more than one person is working in the repository.
- Don't commit `local.properties`, signing keys, RevenueCat API keys, or personal
  session notes (`HANDOFF.md`, `claude.md`) — these are git-ignored on purpose.
- Native-speaker content review, licensed reference audio, and a verified
  real-money RevenueCat transaction are still outstanding; see README's content
  and billing sections before treating either as production-ready.
