# Root

A quiet practice app for reconnecting with your heritage language. **A session, not
a destination:** open on a word, bring it to mind, rate your recall, and leave.

Root is a native Android / Jetpack Compose prototype, not a collection of disconnected
mockups. The same screens work in warm-paper light mode and warm-charcoal dark mode.
Practice, contributed phrases, recordings, scheduling, and challenges live on-device.
No learner account, app backend, automated pronunciation score, streak, or leaderboard.
Optional purchases use RevenueCat and therefore need network access; that is separate
from offline practice.

## The experience

- A root-line mark, editorial wordmark, adaptive app icon, and restrained drawn-path
  launch sequence lasting 3.2 seconds, with brief seed and finished-mark pauses.
- Home opens directly into a small recall deck. Reveal surfaces the word through
  fade, upward drift, and soft-to-sharp focus.
- **Missed / Close / Got it** buttons or left / up / right swipes. Missed cards return
  once within a session; repeated misses never trap you in an endless deck.
- Roots grow only for distinct phrases rated **Got it**. The completion state invites
  a pause instead of awarding a score.
- Secondary sheets lead to theme packs, language selection, content unlocks, an image
  sharing card, and a form for your own words.
- A weekly conversation nudge, reference-audio playback, local voice recording and
  comparison, and a home-screen widget.
- **The design study** in the overflow shows icon sizes, launch keyframes, and motion /
  haptic annotations. “Replay launch” makes the sequence inspectable.

See [the design system and motion specification](docs/DESIGN.md) and
[the development and onboarding guide](docs/DEVELOPMENT.md).

## Run

Requirements: Android Studio or Android SDK 36, JDK 17, and an Android device/emulator
running API 26+. The Gradle wrapper is included.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`.

No API key is needed to open the prototype, practice, contribute, record, share, or
use the widget. Select **More options → Paper & ink** to choose System, Light, or Dark.

### Optional Test Store purchases

The old SDK pin could not support RevenueCat Test Store. Root now uses **9.9.0**, the
[documented minimum Android version](https://www.revenuecat.com/docs/test-and-launch/sandbox/test-store).

1. In your RevenueCat project, create a Test Store, an offering with a product/package,
   and attach the product to entitlement **`premium`**.
2. Put its public SDK key in your **user-level**, untracked Gradle properties:

   ```properties
   ROOT_REVENUECAT_API_KEY=test_your_key_here
   ```

3. Build a debug APK. The paywall uses the configured product's localized price,
   purchase result, and entitlement. Restore is available.

Never put secret API keys in the source tree. Never distribute a release using a
Test Store key. Without a configured SDK, the paywall remains a labeled content
preview; it does not pretend a purchase succeeded. Practice never depends on billing
initialization or a successful network response.

## Content: an important boundary

Two languages are available now: **Dholuo** and **Shona**, each with a free Greetings
pack. Choose **More options → Language → Shona** (or use Change language in Packs).
Your active language and existing recall history are preserved when the app updates.

Shona includes **eight source-checked starter phrases**: singular/plural greetings,
welcome, morning/afternoon/evening greetings, and singular/plural thanks. Spelling and
usage were checked against [Omniglot's Shona phrases](https://www.omniglot.com/language/phrases/shona.php),
which credits Emma Thembani and Ernest Mdende. Source-checking is **not native-speaker
approval**; dialect, register, and respectful usage still need review. Provenance also
ships offline in `app\src\main\assets\content_sources.txt`.

The bundled Dholuo greetings remain **three unreviewed development samples**. Its other
catalog packs are shells and say “Coming soon.” This implementation does not turn
those samples into native-speaker-reviewed teaching content. Do not present them as
reviewed material in a public demo or sale.

No genuine reference recordings were supplied. Root does not fabricate a Dholuo or Shona
voice or substitute English text-to-speech. Missing audio is handled explicitly.
Use **Add a word of your own** to save a phrase and an optional reference recording;
get the speaker's permission first. Learner comparison recordings stay separate.
User-authored content is free, including a language you add yourself.

Before content launch: curate licensed phrases, obtain native-speaker review, record
or license pronunciation audio, and populate the paid/reward packs. Do not sell empty
catalog entries. The original Kencorpus curation project remains a separate content
deliverable, not something the visual prototype silently claims to have completed.

## Sharing is not a referral system

**Teach someone one word** exports a PNG with the target phrase, its meaning, and the
Root mark. Android's system share sheet receives a temporary, read-only content URI.

Opening that share sheet unlocks the Market reward locally, even if the sheet is
cancelled. The screen says so. Root cannot verify delivery, an install, or a friend's
activity; it does not claim otherwise. A generation/launch failure does not earn the
reward. Market content is still “Coming soon” until curated phrases are supplied.

## Local data and privacy

- Room holds language packs, phrases, attempts, and weekly challenges.
- Fixed next-review intervals: **Missed: four hours; Close: one day; Got it: four days**.
  Same-session retry is a separate, bounded queue rule.
- Seeding is idempotent and preserves existing phrase IDs and learner history.
- Active language, appearance, and sharing reward are device-local preferences.
- Microphone permission is requested only for recording. Audio lives in app-internal
  storage; sharing a phrase card does not share your recordings.
- Android backup is disabled. Uninstalling the app removes its local practice data.
- RevenueCat, when configured, has its own network and anonymous purchase identity;
  “no account” is not a claim that configured billing sends no data.

## Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest
# With a running emulator / attached device:
$env:ANDROID_SERIAL = "emulator-5554" # Choose your intended test device explicitly.
.\gradlew.bat :app:connectedDebugAndroidTest
```

Unit coverage includes scheduler rules. Device tests cover the durable practice
session engine (resume, paging, idempotent rating, one-retry rule, stop/close), the
Room schema migration, reveal/rating accessibility, both themes, the design study,
session closure, and connected navigation. Visual and real-purchase checks are described in
[DESIGN.md](docs/DESIGN.md#verification).

## Code map

```text
app\src\main\kotlin\com\root\app\
  MainActivity.kt       — connected navigation, theme and language sheets
  RootViewModel.kt      — thin UI adapter over PracticeRepository; only a session id is saved-state
  RootApplication.kt    — optional guarded billing initialization
  data\                 — Room, repository, seed content, scheduling, preferences
  practice\             — durable, Room-backed practice session engine (paging, rating, resume, stop/close)
  billing\              — shared premium entitlement state
  sharing\              — phrase-card image generation / sharing
  audio\                — local playback and recording
  widget\               — Glance due-phrase widget
  ui\                   — practice, completion, packs, paywall, invite, contribution
    theme\              — color, serif/grotesque type, tight shapes, paper grain
    root\               — shared ordered vector branches
    brand\              — icon-scale mark and wordmark lockup
    launch\             — ink seed → roots → wordmark
    motion\             — restrained springs, easing, haptic vocabulary
```

Source Serif 4 and Inter are bundled under the SIL Open Font License. Their licenses
ship in `app\src\main\assets\licenses`. App code: MIT — see [LICENSE](LICENSE).

For the full architecture walkthrough, data flow, and extension guide, see
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
