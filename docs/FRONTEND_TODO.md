# Frontend TODO — everything backend has ready but no UI uses yet

**Read this if you're picking up frontend work on Root.** The backend (data layer,
ViewModels, business logic) for every item below is implemented, unit/instrumented
tested, and verified on both an emulator and a real Samsung tablet. Nothing here
needs new backend work first — it needs screens/composables wired to APIs that
already exist and already work. Each section names the exact function/class to
call and the test file that proves it behaves correctly.

---

## 1. Onboarding screen (new — currently doesn't exist at all)
- **Call**: `RootViewModel.showOnboarding: Boolean` (true when it should be shown —
  already computed at ViewModel init from `RootRepository.shouldOfferOnboarding()`).
- **On finish or on "Skip"**: call `RootViewModel.respondToOnboarding()` — skipping
  and completing are recorded identically on purpose (a skip is a respected choice,
  never re-nagged), so you don't need two different persistence calls.
- **Logic already covers**: re-offering onboarding again only if
  `RootPreferences.ONBOARDING_CURRENT_VERSION` is bumped in a future release (e.g.
  onboarding content changes materially) — you don't need to build that, just bump
  the constant if that day comes.
- **Reference**: `app/src/main/kotlin/com/root/app/overview/OnboardingGate.kt`,
  tests in `OnboardingGateTest.kt`.
- **What you design**: the actual screen(s)/copy/illustrations. Nothing about
  content or number of steps is decided — that's product/design's call.

## 2. Recommendations / "what to do next" list (new — currently doesn't exist)
- **Call**: `RootViewModel.overviewSnapshot()` (suspend) returns an
  `OverviewRecommendations.OverviewSnapshot`. Pass it to
  `OverviewRecommendations.recommend(snapshot)` to get a fixed-order
  `List<Recommendation>`.
- Each `Recommendation` is either `Available` (render it, make it tappable) or
  `Unavailable(reason: String)` — **the reason must be shown or the recommendation
  must be visibly disabled with that reason as a tooltip/caption. Never silently
  hide an unavailable recommendation** — that's a deliberate honesty requirement
  from the backend design, not a suggestion.
- Six kinds, always in the same order: `PRACTICE_DUE`, `CONTINUE_PRACTICING`,
  `WEEKLY_CHALLENGE`, `RESUME_DRAFT`, `ADD_A_WORD`, `EXPLORE_PACKS`.
- **Known limitation to design around**: `dueCount` in the snapshot is currently
  only 0-or-1 (a proxy for "is anything due", not an exact count) — there's no
  exact due-count query yet. If you need a real number for copy like "12 words
  due", flag it back to backend — that's a small, well-scoped addition.
- **Reference**: `app/src/main/kotlin/com/root/app/overview/OverviewRecommendations.kt`,
  tests in `OverviewRecommendationsTest.kt`.

## 3. Reels — real-media waveform + short-form clip playback (new — biggest gap)
This is the largest not-yet-built piece. Backend has three fully tested, working
pieces; there is currently **no screen at all** using them:
- `WaveformDecoder.decode(...)` — turns any audio file into ≤512 bounded, normalized
  amplitude bins, off the main thread. Use this to draw an actual waveform view.
- `WaveformCache` — disk-caches decoded waveforms keyed by content hash + a caller
  revision key, so you don't redecode on every screen visit.
- `ReelsPlayer` — a pure Kotlin (no Android dependency) state machine that takes a
  manifest-ordered `List<ReelClip>` and plays them back single-pass (never loops,
  never autoplays until you call `start()`, skips missing clips without crashing,
  supports 0.75x/1x/1.25x speed). You provide a small adapter implementing
  `ReelPlaybackPort` that wraps a real `MediaPlayer` — `ReelsPlayer` itself is
  already fully tested (7 JVM tests) so you only need to test your thin adapter.
- **Also ready and reusable in ANY audio screen right now**:
  `AccessibleAudioTransport` composable in
  `app/src/main/kotlin/com/root/app/ui/audio/AudioPracticeControls.kt` — a seek
  slider + cycling speed button with proper screen-reader labels. It already shows
  automatically whenever something is playing in the practice screen; you can reuse
  it directly in a Reels screen instead of building a new transport control.
- **What's genuinely undecided (needs your/product's input, not backend's)**:
  the actual Reels screen layout, how a manifest of reel clips is defined/fetched
  (natural source: each `PackManifest.phrases[].audioAssetId`/`.credits` is already
  ordered and has credit info — this is the ready-made data source), and a credits
  UI for showing who recorded each clip.
- **Reference**: `app/src/main/kotlin/com/root/app/audio/WaveformDecoder.kt`,
  `WaveformCache.kt`, `app/src/main/kotlin/com/root/app/reels/ReelsPlayer.kt` +
  `ReelsPlayerTest.kt`, `app/src/androidTest/.../WaveformDecoderTest.kt`.

## 4. "Mark Practiced" — backend ready, partially wired, one piece unused
- `AudioPracticeControls` already has a "Mark practiced" button wired end-to-end
  (`PracticeScreen` → `MainActivity` → `RootViewModel.markPracticed(phraseId)`).
  This part works today — nothing to do.
- **Unused**: `RootRepository.lastPracticedMarkAt(phraseId)` exists (returns when a
  phrase was last marked practiced) but no screen shows it. If product wants a
  "last practiced 3 days ago" caption anywhere, this is ready to call.

## 5. Contribution drafts (autosave + resume) — backend ready, zero UI callers
This is a real, confirmed gap found during this session's audit: the backend
(`RootRepository.createDraft()`/`saveDraftText()`/`openDrafts()`/`discardDraft()`)
is fully implemented and tested, but **`ContributeScreen` never calls any of it** —
it still uses plain `rememberSaveable` state that's lost if the app is killed.
- To fix: `ContributeScreen` needs to (a) create a draft on first keystroke (or on
  entering the screen) via `createDraft(languageId)`, (b) autosave field changes via
  `saveDraftText(draftId, prompt, answer, speakerLabel)` (e.g. debounced), and (c)
  accept an optional `draftId` parameter so a "Resume draft" recommendation (see
  §2) can actually open an in-progress draft instead of a blank form.
- **Design decisions needed from you/product**: how often to autosave, whether
  there's a visible "draft saved" indicator, what happens to `Discard` in the UI.
- **Reference**: `app/src/main/kotlin/com/root/app/data/RootRepository.kt` (search
  `Draft`), `app/src/main/kotlin/com/root/app/ui/ContributeScreen.kt`.

## 6. Paywall / billing — screen exists, works, but needs a real sandbox test
- `PaywallScreen`/`PaywallViewModel` already work end-to-end against RevenueCat,
  including a real state machine for Loading/Ready/Purchasing/Restoring/Error/
  Unlocked/NotConfigured, and a just-fixed bug where a stale/delayed network
  response could regress premium state (now ordering-safe).
- **A `test_...` RevenueCat API key is already configured** in this machine's
  Gradle properties (not committed to source), so a real Test Store
  purchase/cancel/restore run is technically possible right now — deliberately
  **deferred until the frontend is otherwise done**, per your instruction, so you
  can see purchase UI end-to-end in one pass rather than testing it against a
  half-built screen.
- **Nothing to build here** unless product wants a different paywall design —
  the current one is functional, just not yet exercised against a real purchase.

## 7. Widget — works, one bug just fixed
- The home-screen widget (`RootWidget`) already shows the next due phrase and
  deep-links into practice on tap. A real bug (tapping the widget when the app
  wasn't running did nothing) was found and fixed this session. No frontend
  action needed unless you want to redesign the widget's look.

---

## Summary table

| Feature | Backend | Frontend/UI |
|---|---|---|
| Onboarding | ✅ done, tested | ❌ no screen |
| Recommendations list | ✅ done, tested | ❌ not rendered anywhere |
| Reels (waveform + playback) | ✅ done, tested | ❌ no screen |
| Mark Practiced | ✅ done, tested, UI wired | ⚠️ `lastPracticedMarkAt` unused |
| Draft autosave/resume | ✅ done, tested | ❌ zero UI callers (real gap) |
| Paywall/billing | ✅ done, tested | ✅ screen exists — needs a real sandbox test pass once frontend is done |
| Widget | ✅ done, tested + 1 bug fixed | ✅ nothing needed |

Once your teammate builds screens for items 1–5 above, come back and we'll run the
real RevenueCat sandbox purchase/restore test (item 6) and, separately, a real
content-publication pass (needs rights-cleared audio + your explicit go-ahead) —
those are the only two things genuinely waiting on non-engineering steps.
