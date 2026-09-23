# Language strategy: what's free, what's paid, and how we grow

Written after adding Swahili as a third free starter language and being asked
whether "one free indigenous language per continent, more unlocked later" is
the right long-term model. Short answer: no — use content quality as the
free/paid line, not geography. Details below.

## Current state (as of this doc)

Three free (non-premium) languages, each with a single "Greetings" starter
pack, source-checked but **not yet native-speaker reviewed**, no authentic
reference audio bundled:

- Dholuo — hand-entered development samples
- Shona — checked against Omniglot
- Swahili — checked against Omniglot

Everything paid today is **more packs within an existing language** or
**additional languages marked premium** (see `ContentAccess.kt`). There is no
usage/quantity restriction anywhere (see the earlier "few things a day"
investigation) — premium only ever unlocks more *content*, never more
*frequency*.

## Why "one indigenous language per continent, free" is the wrong model

1. **Continents aren't the unit that matters.** Africa alone has 2,000+
   living languages; picking a single "the African free language" is
   arbitrary no matter which one is chosen, and will read as tokenizing
   rather than representing.
2. **The real bottleneck is content quality, not a slot count.** Every pack
   we ship starts as "checked against a public phrase list, native review
   pending." Adding a language is cheap; getting it right (consenting native
   speakers, real audio, register/dialect accuracy) is the actual cost. A
   quota by continent optimizes the wrong variable.
3. **It doesn't scale down gracefully.** The day we want a 4th, 5th, 10th
   free language, "one per continent" runs out of continents long before it
   runs out of demand.

## Recommended model instead

- **Free tier = "every language that currently has a decent, checked starter
  pack."** Not a fixed count, not a geography quota — grows as real content
  quality allows it. This is already exactly what Dholuo/Shona/Swahili are
  doing; keep doing it.
- **Paid tier = depth, not breadth of access.** More packs *within* a
  language a learner is already committed to (Market, Numbers, Food,
  Directions, etc.), plus premium-marked languages that need heavier
  investment (larger curated catalogs, licensed/commissioned audio). Users
  never pay to "unlock the ability to learn" — only to go deeper once
  they're already engaged.
- **Community contribution is the actual growth lever for new low-resource
  languages**, not a top-down continent checklist. The app already has a
  personal-phrase contribution flow (`RootRepository.contribute`); the
  intended pipeline is: contributed phrases → curated review → promoted into
  a real starter pack, same path Dholuo/Shona/Swahili followed. This scales
  to however many languages the community actually shows up for, weighted by
  real demand instead of a map.
- **Publication stays gated** on the existing `publication-gate` criteria —
  rights clearance, native review, authentic audio — regardless of which
  language. No language skips that gate just to hit a geographic quota.

## What this means practically

- Don't hard-code a "languages per continent" target anywhere in code or
  content tooling.
- Keep `SeedData`'s pattern (small, honestly-labeled, source-checked starter
  pack, `isFree = true`, no synthetic audio) as the template for the *next*
  free language too — it's already the right shape.
- When prioritizing which language to invest real native-review/audio budget
  in next, prioritize by evidence of learner demand/contribution activity,
  not by "which continent doesn't have one yet."
