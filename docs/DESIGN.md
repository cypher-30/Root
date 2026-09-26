# Root — design and interaction system

## The argument

Root is a session, not a place. The first due phrase is already on the home screen.
An editorial hierarchy replaces the dashboard: the practiced language has the largest
type, controls recede, and secondary destinations stay in an overflow sheet.

This is a working Android Compose prototype. Light and dark use the same components
and connected navigation. There is no separate Figma deliverable.

## Foundations

### Color

| Role | Warm paper | Warm charcoal |
|---|---|---|
| Background | `#F4F0E8` | `#201E1A` |
| Main ink | `#27251F` | `#EAE0D0` |
| Secondary ink | `#655E53` | `#B9AE9C` |
| Card ground | `#F8F4ED` | `#26221D` |
| Hairline | `#D6CFC2` | `#4C453A` |
| Earth pigment | `#995137` | `#D99D7D` |

The dark palette is independently tuned: warmer, less white, and separated by lifted
surfaces and fine rules instead of bright elevation shadows. Primary means **ink**,
not a colorful call to action. Earth pigment is reserved for meaning, especially
growing roots; it is not the default button fill.

`Color.kt` supplies the complete Material scheme, including container and error roles.
`Shape.kt` restricts corners to **4 / 6 / 8 dp**. Controls retain accessible touch
targets rather than shrinking with their visible linework.

Paper grain is a deterministic, cached set of extremely low-opacity specks. Unlike
the original optional AGSL proposal, this implementation uses one Canvas path on all
supported APIs: no animated noise, no per-frame random generation, no GPU-version
branch. It is deliberately subordinate to text.

### Typography, with sources

**Source Serif 4** is the target-word and editorial-heading face; **Inter** is the UI
face. Both are bundled, not fetched at runtime.

- [Source Serif's primary description](https://github.com/google/fonts/blob/main/ofl/sourceserif4/DESCRIPTION.en_us.html)
  describes a transitional face loosely influenced by Pierre Simon Fournier, designed
  for readable digital and printed text. This supports the publication-like identity.
- [Source Serif metadata](https://github.com/google/fonts/blob/main/ofl/sourceserif4/METADATA.pb)
  documents weight 200–900 and optical size 8–60. The hero uses explicit optical size
  48; the optical axis does **not** automatically follow Compose text size.
- [Inter's primary description](https://github.com/google/fonts/blob/main/ofl/inter/DESCRIPTION.en_us.html)
  explains its screen-oriented construction and tall x-height. Its neutral forms let
  the serif word carry the character without sacrificing small-control legibility.
- [Inter metadata](https://github.com/google/fonts/blob/main/ofl/inter/METADATA.pb)
  documents weight 100–900 and optical size 14–32. Chrome uses optical size 14.
- Both sources list extended Latin and Vietnamese support, useful for diacritics, and
  carry the SIL Open Font License. Licenses are bundled alongside the app's assets.

These are reasons for the pairing, not claims that fonts improve learning outcomes.
Extended Latin support is **not universal language support**. User-contributed text
uses Android's fallback for uncovered scripts. Additional curated scripts require
glyph, diacritic, RTL, line-breaking, and native-reader checks; Latin tracking must
not be blindly applied to all writing systems.

| Role | Size / line height |
|---|---|
| Revealed answer | 48 / 58 sp, restrained 0.6 sp tracking |
| Editorial title | 32 / 39 sp |
| Known-language prompt | 22 / 31 sp |
| Body | 16 / 25 or 14 / 22 sp |
| Metadata | 12 / 18 sp |
| Eyebrow | 11 / 16 sp, 1.5 sp tracking |

Answers wrap instead of being ellipsized. Screens scroll at narrow widths and enlarged
font settings. Before reveal, the answer stays hidden: visual hierarchy never leaks
the answer to defeat recall.

### Linework and logo

`RootGeometry` freezes five ordered branch paths in a 100-unit coordinate space.
There is one seed, a weighted trunk, and two main branches with smaller offshoots.
The paths are authored, not randomized: recognition matters more than generative variety.

The **same coordinates** are used for:

1. The Root mark and serif wordmark lockup.
2. Launch path extraction from zero to full length.
3. Accumulated in-session recall, shown through a growing root path.

The adaptive icon preserves safe-zone margins and includes a monochrome layer.
The design study shows the mark at 24, 48, and 96 dp, a full wordmark, and launch
keyframes. Compose brand previews also include 192 dp.

UI icons use the same square-capped, thin line vocabulary. There are no emoji,
filled multicolor icons, streak counters, achievement badges, or confetti.

## Connected flow

```text
Launch → home recall deck → reveal → rating → next card → quiet completion
             │                              │
             │                    Missed returns once to the tail
             │
             └─ overflow
                 ├─ packs → selected eligible pack / content paywall
                 ├─ unlock more words → paywall / restore
                 ├─ teach someone one word → PNG → system share sheet
                 ├─ add a word → optional reference recording → deck
                 ├─ language → selected language / add your own
                 ├─ System / Light / Dark
                 └─ design study → replay launch
```

The weekly challenge lives beneath the deck and in completion, not in a competing
dashboard. It asks for one real-world conversation use, then draws a quiet check.
The widget opens practice directly.

Home and practice are one continuous surface, not two copies of the same queue.
An unavailable/empty pack says so. Nothing due is different from a failed data load
and different from finishing a session. The completion state offers closure and a
manual check for due words, not an obligation to keep practicing.

## Motion and haptic annotations

| Moment | Visual behavior | Haptic |
|---|---|---|
| Launch | Seed held 250 ms → ordered path growth over 2,600 ms with a gentle start and decelerating finish → completed mark held 350 ms; 3,200 ms total; tap to skip | None |
| Reveal | Alpha 0→1, 12 dp upward drift, blur 12→0 dp; 520 ms | Light clock tick |
| Drag | Translation follows the finger; slight lift/scale and directional tilt | None |
| Missed | Left exit; visible return toward the deck; one retry at the tail | One soft pulse |
| Close | Upward exit | Two light pulses |
| Got it | Rightward exit | One firmer pulse |
| Recall roots | Extend only on a new distinct Got it | No additional vibration |
| Challenge done | Check stroke drawn over 200 ms | No badge/fanfare |
| Volume slider | Ink-ruler track; thumb swells 1.2× with a faint halo while held (settle spring); 5 % steps | Light clock tick at each quarter mark |

Springs use damping ratio 1 and stiffness 190: there is no bounce or overshoot.
Release needs directional intent, with displacement or velocity thresholds. Downward
drag is not an outcome. Cancellation settles the card back. Buttons and accessibility
custom actions invoke the same rating transaction.

Ratings are unavailable before reveal and while a rating is exiting/saving. A save
failure restores the card instead of silently consuming it. The deck pages through
due phrases in bounded database reads rather than an unbounded in-memory list, with
at most one same-session retry per missed phrase, and the run persists durably
across restarts until you rate through everything due or explicitly stop/close it.

The root is not a disguised accuracy percentage. Its eight-step growth reflects
distinct successful recalls, not the ratio of right answers to attempts. “Close”
does not grow a branch. Finishing does not fill in roots that were never earned.
Lifetime capability copy is grounded in latest self-rated recall, not a proficiency
certification.

Blur runs only on API 31+, with fade/drift below that. System-disabled animations
remove the launch wait and rating travel; all controls and outcomes remain usable.
Haptics respect the system setting and tolerate hardware without a vibrator.

## Sound

Root has its own small sonic identity: soft, felted wooden notes with a trace of
paper grain, built from D and A with F♯ (A3 to A5; thirds and fifths only). Cues are
short, fade in and out naturally, sit well below full scale (-12 dBFS peak for the
startup sound, -11 to -24 dBFS for interaction cues), and carry no sub-bass, so phone speakers
play them faithfully. Every sound is original. They are synthesized by
`tools\audio\generate_root_sounds.py` using only the Python standard library and a
fixed seed; no third-party samples or recordings are used.

**Sound is on by default** (effects and startup sound on, in-app volume 100 %; the
cues themselves are mastered quiet, so 100 % is still gentle). Sound is two levels
deep so Profile stays tidy:

- **Profile** is arranged as YOUR PROGRESS, SETTINGS (Language, Sound, Paper & ink),
  MORE WORDS & SHARING, and ABOUT ROOT; entries carry a chevron. The Sound entry
  reads "Sound · On · 100%" (or "Sound · Off").
- **Sound** (route `"sound"`, `SoundSettingsScreen`): a *Sound effects* switch, an
  ink-ruler volume slider (hairline track with quarter marks, an ink stroke for the
  level, a seed-like thumb, a serif percentage, Quiet/Full ends; moving it is silent),
  a separate *Startup sound* switch (unavailable while effects are
  off), and a **Sound Lab** row.
- **Sound Lab** (route `"soundLab"`, `SoundLabScreen`), listening only: *Watch the
  opening with sound* replays the launch drawing in sync with the startup sound,
  then every cue in use, a cancellable practice demo, the cues not used yet, and
  the earlier motifs A and B.

Previews play even when effects are off, because you asked for them, but they
still respect the quiet policy below; the Lab says why a sound stayed quiet.

**The startup sound, "Root growth" (2.95 s), is scored to the launch animation**
rather than being a separate jingle. Times are from the start of growth (after the
250 ms seed hold), matching `RootMotion.launchMillis` = 2600 ms, the 350 ms hold and
`launchEase`:

| Time | Picture | Sound |
|---|---|---|
| 0 ms | Trunk starts drawing | Soft ink-on-paper texture begins; its loudness and brightness follow the drawing speed; a wooden tap on A4 |
| 490, 720, 971, 1342 ms | Each new branch starts | A tap stepping down F♯4, D4, A3, D4, like roots going deeper |
| ~1740 ms | Drawing lands (progress 0.92), wordmark nearly in | A warm open fifth (D4 + A4, a soft D5) settles |
| 1740–2950 ms | Easing out, then the hold | The settle rings out and ends as the screen changes |

Accents after the first are led by 30 ms to cover output latency (an estimate, not
yet measured). The sound plays only when the system animation speed is 1x: at other
speeds the drawing and the audio would drift apart, so it stays silent, as it does
with animations off. If the sound isn't loaded in time it is skipped, never played
late. If the launch animation's timing changes, update `LAUNCH_*` in the generator,
regenerate, and update `RootSoundCatalogueTest`, which fails when they drift.

| Moment | Cue | Rule |
|---|---|---|
| Launch | Root growth (2.95 s), `RootSoundCue.STARTUP_MOTIF` | Only with Startup sound on, on a fresh cold launch as the seed starts to grow, at 1x animation speed. Never on rotation/restore, widget entry, or with reduced motion. Fades if you skip. |
| Reveal | Reveal (130 ms), a single soft tap | Tapping Reveal only; never repeats |
| Got it | Got it (400 ms), a rising third | After a *new* Got it rating has been saved. Never for Missed or Close. |
| Correct lesson answer | Correct answer (480 ms) | After the answer is evaluated as correct for the current activity |
| Lesson complete | Lesson finished (1 s), a settled chord | Once per lesson run, the first time it is completed |

Deliberately silent: mistakes, Missed/Close, errors, navigation, downloads,
purchases, recording, sharing, challenges and practice-session completion (for now). A wrong
answer never makes a sound. Motifs A (1.6 s) and B (1.8 s), earlier startup ideas
whose notes all landed in the first half-second and so fell out of step with the
drawing, stay in the Sound Lab only for comparison. The *Neutral settle*, *Saved* and
*Touch* cues are liked and kept, but exist only in the Sound Lab until a placement
is chosen.

Suggested placements for the unused cues (not wired yet; pick at most one each and
add them through `SoundMoments` so they fire only on committed results):

| Cue | Best fit | Alternatives | Avoid |
|---|---|---|---|
| Saved (300 ms) | A contributed word is saved to Your words (after the Room write succeeds, not on draft autosave) | "Mark practiced" saved; weekly challenge "I did this" recorded | Draft autosave, every keystroke, failed saves |
| Neutral settle (260 ms) | A practice session closes ("A little closer."), whatever the score, a calm full stop rather than a reward | Stopping a session early; finishing a Reels pass | Missed/Close ratings or wrong answers (it would read as a "wrong" sound) |
| Touch (90 ms, the quietest cue) | Selecting a lesson answer option before Check: acknowledges the choice without judging it | The moment a dragged card crosses the rating threshold, paired with its haptic; a recall root growing | Tabs, scrolling, every button; anything right before reference audio |

Quiet policy, checked before every automatic sound:
- Stay quiet in Do Not Disturb, silent or vibrate ringer mode, during calls or
  communication audio, when system sound volume is zero, when the device's quiet
  state can't be read, while other media is
  playing, and while Root itself is recording or playing a voice (reference
  playback, recordings and Reels always win; they stop any cue in progress).
- Play only in the foreground, one sound at a time, at least 250 ms apart. Events
  older than one second, or delivered while no screen is listening, are dropped
  rather than replayed later. Nothing queues.
- Request transient audio focus; if focus is denied or lost, the cue stops.
- Sounds never replace meaning: every sounded moment already has visible feedback
  and, where one exists, the same haptic. Haptics are controlled independently.

## Audio and contribution

Reference playback supports a supplied bundled asset or a contributed app-internal
recording. The learner's comparison recording is separate from the reference. No
scoring service, transcription, voice upload, or model participates.

Recording requests microphone permission on demand, handles refusal, stops when the
screen/app leaves the foreground, and limits duration. Contribution includes a consent
reminder. Prompt, answer, language, and optional voice are saved locally; adding your
own language does not require a content purchase.

**Content dependency:** the repository supplies three unreviewed Dholuo samples and
eight source-checked Shona greetings, but no authentic reference recordings.
Both starter packs are free; the existing language picker exposes them without a
separate navigation flow. Shona provenance ships in `assets/content_sources.txt`
and is linked in the README. Native-speaker approval remains pending for both.
Missing audio is explicit; there is no fabricated reference voice.

## Content access and sharing

One access decision must apply to pack rows, review queues, widget queries, and restored
sessions. Premium purchases and restores update shared entitlement state. Offline
practice can use previously cached entitlement state, but an unconfigured preview
cannot grant a real purchase. Personal phrases remain free.

The paywall sells content, not more attempts or removal of learning pressure. Actual
product names/prices come from RevenueCat. Without configuration, explanatory preview
copy replaces a fake checkout. Additional curated languages and empty packs must be
supplied before those items are sold.

The share card is a real PNG: large serif phrase, quieter meaning, language, Root mark.
Only the generated cache directory is exposed through `FileProvider`; the intent grants
temporary read access. A local reward follows successful share-sheet launch, not verified
delivery. Cancellation also keeps that reward, and the user is told this in advance.

## Persistence and lifecycle

Room remains authoritative for phrases and attempts. Seed initialization is idempotent,
preserving existing IDs/history instead of replacing parents and cascading deletion.
Latest-attempt selection is deterministic, and eligible queues respect language and
pack access. Schema evolution preserves learner content.

The UI uses a thin ViewModel over `PracticeRepository`, which persists the run (queue,
ratings, and cursor) durably in Room; only a session id is kept in saved state, so
recreation, process death, or reopening the app resumes the same run instead of
replaying an in-memory history. The active language and appearance persist locally.
Audio lifecycle is separate from visual recomposition.

Teaching uses separate revision-pinned lesson runs. Leaving a lesson pauses it;
Restart keeps earlier evidence and begins a new run. Guided conversation,
listening/comprehension and pattern construction share the same runner. Completing
required steps is not a pronunciation score, a recall rating, or a proficiency
certificate. Hints, revealed transcripts and retries remain assisted evidence.
Missing authentic audio blocks a listening task rather than silently turning it
into reading.

The minimal teaching/catalog views are functional integration, not a replacement
for the frontend redesign. They must distinguish development review status,
remote availability, installation/update state and learning progress. Keep a
working installed pack usable when an update fails. All required source credits
are available offline; exported managed phrases retain credits in the image.
See [the shared teaching contracts](TEACHING_CONTRACTS.md) and
[content operations](CONTENT.md).

## Verification

Use the existing Gradle/JUnit/Compose tooling:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
$env:ANDROID_SERIAL = "emulator-5554"
.\gradlew.bat :app:connectedDebugAndroidTest
```

Inspect on a running device:

- Cold launch, tap-to-skip, replay, and mark legibility at icon scale.
- Light/dark deck; reveal; all three button and swipe outcomes; visible missed return.
- No double rating, no endless retry, saved-state recreation, and offline reopening.
- Small width and large font; no hidden outcome controls; TalkBack labels/actions.
- Packs, language change, own phrase save, microphone denial, record/replay/delete.
- Share PNG readability and URI permissions; reward granted without claiming delivery.
- Weekly done persistence; session closure; widget appearance, refresh, and tap target.
- Configured Test Store purchase, failure, cancellation, restore, and cached offline
  access. This final integration requires the project's real dashboard configuration.

The original Compose BOM was bumped within the Kotlin-2.0-compatible line to
`2025.01.01`; Glance is `1.1.1`. Repository metadata was checked rather than blindly
upgrading the whole toolchain. RevenueCat changed from `8.16.0` to `9.9.0` because
[Test Store explicitly requires it](https://www.revenuecat.com/docs/test-and-launch/sandbox/test-store).
No credentials, content-review claims, or unperformed device checks should be recorded
as passing.

### Implementation verification, 15 September 2026

At the base-prototype checkpoint, the debug APK built and all **23 local JUnit cases**
and **10 device cases** passed on the
explicitly selected `Kumbuka_Test` emulator (API 36). Android lint reports **zero errors**
with non-blocking warnings remaining. Device cases cover data-preserving seeding,
timestamp ties, eligible queues, reveal/rating controls, connected secondary routes,
challenge completion, share-image generation, long diacritics, and provider readability.

Subsequent updates slowed launch to 3.2 seconds and added the free Shona starter.
The updated APK builds; **seven targeted persistence/navigation device cases** passed,
including Shona selection, free language-scoped recall, idempotent additions, and
preservation of existing personal languages and history. There are now 13 device
cases in total; the full combined suite and lint had not been rerun after those
follow-ups at the time. See [docs/DEVELOPMENT.md](DEVELOPMENT.md#tests) for the
current test commands.

### Baseline verification, 18 September 2026

Before publishing this repository, `:app:assembleDebug`, `:app:testDebugUnitTest`,
and `:app:lintDebug` were re-run against the full working tree (including all the
untracked files listed above) with no build repairs. All three succeeded: the debug
build and every unit test passed, and lint reported no fatal errors. This is a
confirmation that the current toolchain (AGP 9.4.0, Kotlin/Compose 2.2.10, Gradle
9.7.1) builds cleanly, not a rerun of the device-test suite above, which still
requires an explicitly selected emulator or device.

`:app:connectedDebugAndroidTest` was subsequently re-run on an explicitly booted
`Kumbuka_Test` emulator (`emulator-5554`). All 13 device cases passed; a single
`RootNavigationTest` scroll action failed on the first pass and then passed on an
immediate re-run of the same test with no code changes in between, which points to
emulator UI-timing flakiness rather than a regression from the documentation-only
edits made in this pass. `:app:assembleDebug` and `:app:testDebugUnitTest` were also
re-run after the full documentation pass (including the `gradle-wrapper.properties`
corruption fix) and both stayed green, confirming the comment-only edits did not
change behavior.

Manual inspection covered paper and charcoal rendering, 320 dp width at 1.3 font
scale, swipe-driven recall/roots, recreation, a saved local microphone clip, playback,
the native image share sheet, and reward persistence after cancelling it. The widget
provider is registered, uses eligible due content, and refreshes with app appearance.
Local screenshots are evidence, not reviewed language-content marketing assets.

Not represented as completed: native-speaker approval, licensed reference recordings
for the bundled samples, populated paid/reward packs, a real configured Test Store
transaction, physical-device haptic feel, and API-26 runtime/device inspection. The
API-26 code/resource paths are guarded and lint-checked; that is not a substitute for
a low-API device pass. Glance uses the system serif because RemoteViews cannot share
Compose's bundled-font rendering directly.
