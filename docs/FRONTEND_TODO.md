# Frontend status and remaining launch gates

**Read this if you're picking up frontend work on Root.** Every screen listed
below exists and is wired in `MainActivity`. This file records what each one
guarantees, where its tests live, and what still blocks a public paid launch.
The launch gates at the end are not engineering tasks; do not mark them done
from code alone.

---

## Screens and their guarantees

### Onboarding (`ui/OnboardingScreen.kt`)
- Shown when `RootViewModel.showOnboarding` is true. Skip and finish both call
  `respondToOnboarding()` and are recorded identically (`overview/OnboardingGate.kt`).
- System Back moves to the previous step. While onboarding is showing, the app
  underneath is hidden from screen readers.
- Privacy copy: practice, your own words, and your recordings are kept on this
  device. Root goes online only to download packs or complete a purchase.
- Tests: `RootExperienceTest` (steps and skip), `OnboardingGateTest`.

### Recommendations (`ui/RecommendationsScreen.kt`)
- Six kinds, always in the same order. Each item is either available or shown
  disabled with its reason. Items are never hidden silently.
- Refreshes when the app resumes and whenever a contribution draft is saved,
  committed, or discarded.
- `dueCount` is still only 0 or 1 (a flag meaning "something is due"). Add a real
  count query before writing copy like "12 words due".

### Reels (`ui/ReelsScreen.kt`, `reels/ReelsPlayer.kt`, `reels/AudioSessionReelPort.kt`)
- Nothing plays until the learner taps **Play reel**. Clips play once, in manifest
  order, and never loop. **Stop** is always available. **Play from the start**
  replays after the reel finishes or is stopped.
- The selected speed (0.75×, 1×, 1.25×) carries across clips. Backgrounding or
  leaving the screen stops playback, and it never resumes on its own.
- A missing or unplayable clip is skipped and counted on screen; playback never
  gets stuck. Credits are listed for every clip.
- Waveforms decode off the main thread and are cached per installed pack version.
- Tests: `ReelsPlayerTest` (13 JVM tests: order, no autoplay, missing/failed
  clips, stop/stale callbacks, speed, replay, interruption).

### Add a word / contribution drafts (`ui/ContributeScreen.kt`, `ui/ContributeViewModel.kt`)
- Draft data is kept in Room (schema v8, `contribution_drafts.language_name_draft`).
  It includes typed text, a new language's name, and the latest **finished**
  recording. It survives leaving the screen, configuration changes, and process
  death. A recording that is still in progress is never saved.
- Saving is ordered and debounced (500 ms). The form shows *Saving draft…*,
  *Draft saved on this device.*, or an error. It never claims a save that hasn't
  happened. Back waits for the last save to finish; if that save fails, the
  learner is asked before anything is lost.
- **Discard draft** asks for confirmation first. It then deletes the draft's text
  and recording. If a file can't be deleted, it is recorded so cleanup can retry.
- Permission to keep a recording must be confirmed again on every save.
- If a commit fails, the recording goes back to where the draft expects it, and
  the learner can try again.
- At startup, unreferenced draft recordings older than 6 hours are removed.
- Tests: `Migration7To8Test`, `RootRepositoryArchiveTest` (resume after
  recreation, new-language name, cleared fields, audio reference and delete on
  discard, orphan cleanup).

### Practice, Mark practiced (`ui/PracticeScreen.kt`, `ui/audio/AudioPracticeControls.kt`)
- **Mark practiced** saves the mark first and only then updates the screen. If the
  save fails, the learner sees an error and can retry. The "last practiced" time
  comes from the database.

### Teaching (`ui/teach/*`, `teach/ContentViewModel.kt`)
- A unit's detail screen shows loading, missing, or failed (with **Try again**).
  It no longer shows an endless "Opening lesson…" message or silently jumps back.
- Rows of buttons and word tokens wrap onto new lines on narrow screens and at
  large font sizes.

### Your words archive (`archive/ArchiveScreen.kt`)
- Kept clear of system bars and the keyboard. Each Edit and Delete button's
  accessible label names its phrase. Deleting asks for confirmation.

### Paywall (`ui/PaywallScreen.kt`, `billing/PaywallViewModel.kt`)
- Sells a **one-time unlock only**. Subscription packages from the store are
  filtered out.
- If no reviewed premium phrases are installed (`ContentAccess.hasPremiumContent`),
  the paywall shows *Premium packs aren't ready yet.* and offers nothing for sale.
  Restore purchases still works.
- The screen explains that restoring purchases brings back premium access only.
  It cannot bring back your own words, recordings, or practice history.
- Privacy, Terms, and Support links appear when `ROOT_PRIVACY_POLICY_URL`,
  `ROOT_TERMS_URL`, and `ROOT_SUPPORT_URL` are set (HTTPS only).
- Tests: `PaywallViewModelTest` (includes no-content and subscription filtering).

### Widget (`widget/RootWidget.kt`)
- Shows the next due phrase and opens practice when tapped.

---

## Release build behavior

- Release builds do **not** seed the unreviewed Dholuo, Shona, Swahili, and
  Amharic starter samples (`BuildConfig.SHIP_SAMPLE_CONTENT`). If those rows
  already exist on a device, they are not deleted. Debug and validation builds
  still seed them.
- Release reads `ROOT_REVENUECAT_RELEASE_API_KEY`, never the debug
  `ROOT_REVENUECAT_API_KEY`. `verifyReleaseConfiguration` runs before every
  release build. It rejects Test Store (`test_`) and placeholder keys. With
  `-ProotPublicRelease=true`, it also fails when the key or any policy URL is
  missing, or when sample content is enabled.

---

## Launch gates (blocked on owner decisions and authorization)

| Gate | What's needed |
|---|---|
| Content approval | Exact free/premium launch inventory, native-speaker sign-off, rights-cleared text and genuine reference audio, credits. Without this, release has no starter content and premium stays unsellable. |
| Store purchases | RevenueCat/Play configuration for a one-time non-consumable mapped to entitlement `premium`. First a Test Store purchase/cancel/restore pass, then a Play internal-track license-tester pass. No real-money purchase without separate approval. |
| Free content hosting | Authorized catalog hosting (`ROOT_CONTENT_CATALOG_URL`) and verified download/update/failure/rollback, or downloads presented as unavailable. |
| Distribution | App identity, signing/Play App Signing, privacy/terms/support URLs, Data Safety, content rating, listing, rollout plan. Version is still `0.1.0` (code 1). |
