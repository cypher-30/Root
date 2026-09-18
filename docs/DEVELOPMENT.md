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
| `data/` | Room entities/DAOs/migrations, `RootRepository` (the one door into persistence), `Scheduler` (due-date rules), `SessionQueue` (in-memory session rules), `ContentAccess` (pack unlock rules), `SeedData` (bundled starter content), preference wrappers |
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
   `SeedData`), resolves the active language, and either restores a saved session
   from `SavedStateHandle` (if under 24h old and still fully unlocked) or starts a
   fresh one via `startSessionInternal`.
3. A session is a `SessionQueue`: at most 8 distinct due phrases, with at most one
   same-session retry for a `MISSED` outcome. `RootViewModel.rate()` persists the
   attempt through `RootRepository.recordAttempt`, which uses `Scheduler.nextDueAt`
   to compute the next due date, then advances the queue and re-saves session state.
4. `PracticeScreen`/`PracticeCard` render the current phrase, handle reveal, and
   handle both button and swipe-gesture rating, calling back into `RootViewModel`.
5. When the queue is exhausted, `SessionCompleteScreen` renders either "session
   done" or "nothing due" copy depending on whether anything was actually reviewed.

## Persistence and content rules

- Every entity uses a UUID primary key (except stable seeded IDs like
  `phrase-dholuo-hello`) with an `updatedAt` column, so future content updates can
  re-import without breaking existing learner history.
- `SeedData.seedIfEmpty` only **inserts missing** rows — it never replaces or deletes
  existing packs/phrases/attempts. This is why reseeding on every app start is safe.
- `ContentAccess.canAccess` is the single access rule used by pack listings, the due
  queue, the widget, and session restoration. If you add a new content-gating
  concept, wire it through this function rather than duplicating checks.
- `AttemptDao`'s due/latest-attempt queries break millisecond ties using SQLite
  `rowid` (insertion order), since two attempts can share a timestamp in tests or on
  fast devices.

## Extending Root

- **New screen/route**: add a composable under `ui/`, wire a `composable("route")`
  block in `MainActivity.RootNavigation`, and add any new state/actions to
  `RootViewModel` rather than giving the screen direct repository access.
- **New content pack/language**: add entities in `SeedData` using `insertMissing`
  (never `upsertAll`, which would overwrite existing learner data) and document
  provenance in `app/src/main/assets/content_sources.txt`.
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

Unit tests (`app/src/test`) cover `Scheduler`, `SessionQueue`, and `ContentAccess` in
isolation. Instrumented tests (`app/src/androidTest`) cover Room persistence/seeding,
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
