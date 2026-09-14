# Lugha

Kenyan indigenous-language practice app — a Shipaton 2026 (Next Gen / student category)
entry. Short daily practice prompts for a mother-tongue language (starting with Dholuo),
local-first, no accounts, no backend, no model — the scheduler is a hand-written interval
rule, on purpose (see `Scheduler.kt`).

Built from `SU-PLUS-Project-Ideas-Report.pdf`'s archive entry `80387145` ("A Kenyan
indigenous language-based communication mobile application", 2020), and deliberately
sharing its stack (Kotlin, Jetpack Compose, Room) with the Kumbuka semester project, so
this doubles as a rehearsal for that build.

Full plan: `~/.claude/plans/abundant-brewing-mccarthy.md` on the machine this was
scaffolded on — copy its content into this repo's `docs/` if you want it versioned here
too.

## Status

This is a **Block 0/1 skeleton**, not a finished app:
- ✅ Project scaffolded — Gradle/Kotlin/Compose/Room wired, builds should sync in Android Studio.
- ✅ Core loop stubbed — Home → Practice (prompt/reveal/rate) → hand-written scheduler → Paywall.
- ✅ 3 seed phrases (`SeedData.kt`) so the loop is demoable before real content lands — **not native-speaker-reviewed, do not use in the demo video.**
- ⬜ RevenueCat wired but **needs your own API key** — see below. Nothing purchase-related works until this is done.
- ⬜ Real content (Block 2) — Kencorpus curation, ~150 phrases across 6-8 packs, native-speaker review.
- ⬜ Real paywall product/entitlement config in the RevenueCat dashboard.
- ⬜ Icon, screenshot, demo video (Blocks 3-4).

## Setup — manual steps that need your own accounts

I can't do these for you (they need your login/browser session), but they're quick:

1. **RevenueCat account + Test Store** (~10 min)
   - Sign up at https://app.revenuecat.com, create a project called "Lugha".
   - Add a **Test Store** app inside it — this is what makes Next Gen not need a Play
     Console account. Copy its API key (starts with `test_`).
   - Create one **Entitlement** named `premium`, and one **Package**/product attached
     to it (any name — e.g. "Unlock all packs").
   - Paste the API key into `app/src/main/kotlin/com/lugha/app/LughaApplication.kt`,
     replacing `test_REPLACE_WITH_YOUR_TEST_STORE_KEY`.

2. **Open in Android Studio**
   - Open this folder as a project. Let Gradle sync — first sync downloads Gradle 8.9
     itself (the wrapper jar is already committed) plus dependencies, so it needs network
     and a few minutes.
   - If a dependency version in `app/build.gradle.kts` has gone stale by the time you do
     this (the RevenueCat SDK version especially — check
     https://github.com/RevenueCat/purchases-android/releases), bump it; Android Studio's
     sync error will tell you exactly which one.

3. **Run it.** Home → Practice should work immediately (seed data). Home → Unlock all
   packs is the Block-0 gate from the plan — confirm a Test Store purchase completes and
   the screen flips to "Unlocked" before building anything else.

4. **GitHub repo** — this folder is already `git init`'d with the LICENSE committed on
   what should be your first commit (a Next Gen submission requirement). Create an empty
   public repo on GitHub, then:
   ```
   git remote add origin <your-repo-url>
   git add -A && git commit -m "Initial scaffold: Compose/Room/RevenueCat skeleton"
   git push -u origin main
   ```

## Project layout

```
app/src/main/kotlin/com/lugha/app/
  MainActivity.kt          — NavHost: home / practice / paywall
  LughaApplication.kt       — RevenueCat SDK init (needs your API key)
  data/
    Entities.kt             — Language/Pack/Phrase/Attempt, UUID+updatedAt convention
    Daos.kt                 — Room queries, including the "due today" query
    AppDatabase.kt
    Scheduler.kt            — the hand-written interval rule (no model)
    SeedData.kt             — placeholder phrases, delete once Block 2 content lands
  ui/
    PracticeScreen.kt        — prompt → reveal → Blank/Shaky/OK/Solid rating
    PaywallScreen.kt          — RevenueCat offering fetch + purchase flow
    theme/Theme.kt
```

## License

MIT — see `LICENSE`. Required for Shipaton Next Gen submissions (public repo + open
source license file).
