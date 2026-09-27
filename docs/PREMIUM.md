# Root Premium: what it is and what it costs

This is the product decision for Root's paid tier. The paywall copy comes from
`app/src/main/kotlin/com/root/app/billing/PremiumOffer.kt`. Keep the two in step.

## The rule

Premium pays for what costs real money to make: native-speaker review and
recording. It never gates practice, how often you practise, or the starter sets.
Premium is sold only while at least one finished premium pack is actually in
the app (`ContentAccess.hasPremiumContent`), and a single language is sold
only once that language has one (`ContentAccess.premiumContentLanguageKeys`).

## Always free

- Every bundled starter set, in every language. Nothing in `SeedCatalog` is
  locked. Older installs that seeded some sets as locked are opened by `SeedData`.
- Daily practice and review, with no limits.
- The starter Learn units and Explore (Situations, Stories & culture, My notebook).
- Your own words and recordings, a language you add yourself, sharing a word
  card, and the widget.
- Working offline, with no account.

## What Premium includes

1. **Native voices.** Every premium phrase is reviewed by a named native speaker
   and has a genuine recording, made with the speaker's consent and credit.
2. **Deeper sets**, planned for every language in this order: talking with
   elders (respect forms), family and home, food and cooking, ceremonies,
   health, travel, and work.
3. **Proverbs and longer stories.** Sayings, with what they mean and when to
   use them, and longer spoken scenes than the free Explore stories.
4. **Full Learn courses** that continue past the starter units.
5. **Pay once, for one language or all.** Premium packs added later for what
   you own are included.

Heritage learners usually want to talk with family, not order in a hotel.
That's why "talking with elders" comes first.

## Price

**US$4.99 for one language, or US$9.99 for all of them**, both one-time
unlocks with local prices set lower in the markets where the languages are
spoken. Neither is a subscription.

| Market | One language | All languages | Why |
| --- | --- | --- | --- |
| US, UK, EU, Canada, Australia (diaspora) | US$4.99 or local equivalent | US$9.99 or local equivalent | Less than a month of the big subscription apps, and fair for a lifetime of deeper content |
| Kenya | about US$1.99 in KES | about US$3.99 in KES | Dholuo and Swahili home market; keep it within reach |
| Zimbabwe | US$1.99 | US$3.99 | Shona home market (Play bills in USD) |
| Ethiopia | about US$1.99 in ETB, if Play sells paid content there | about US$3.99 in ETB | Amharic home market; if not, the free app still works fully |

The paywall offers the plan for the language you are learning first, then the
all-languages bundle. Opened from the language picker, it offers that
language's plan. There is no upgrade credit: someone who owns one language and
later wants them all buys the bundle.

Store setup (all one-time, non-consumable products):

| Plan | Play product ID | RevenueCat entitlement |
| --- | --- | --- |
| All languages | `root_premium_all` | `premium` |
| One language | `root_premium_<key>`, e.g. `root_premium_dholuo` | `premium_<key>`, e.g. `premium_dholuo` |

`<key>` is the language name in lower case without accents, with anything
else turned into `_` (`PremiumAccess.languageKey`). Keys are by name, not row
ID, so a learner-created "Shona" and the seeded Shona share one purchase. The
`premium` entitlement is the one earlier builds sold, so any existing purchase
keeps working as the bundle. Products the app doesn't recognise, and
subscriptions, are never shown.

Set prices in the Play Console, where each product's per-country prices live.
The amount charged always comes from the store. Until the store returns a plan
(no store connection yet, or no premium pack for sale), the paywall shows
`PremiumOffer.plannedLanguagePrice` and `plannedAllLanguagesPrice` under
**Planned price**, so people know what to expect. Change those constants, this
table, and the Play Console products together.

**Why one-time and not a subscription.** Root is calm by design and has no
streaks or pressure. A subscription only makes sense with a steady release
schedule, which Root doesn't have yet. The billing code sells only
non-consumable products and hides subscription packages. Reconsider a
subscription once premium packs ship on a regular schedule.

**Why per language.** Heritage learners usually want one language, the one
their family speaks. A Dholuo learner shouldn't have to pay for Amharic, and
each language's premium packs are paid for by that language's own learners.
The bundle is for families with more than one heritage language, and for
supporters.

**Does it pay for itself?** An illustrative example; replace the numbers with
real quotes:

- Google Play's service fee is 15% on the first US$1M a year, so one US$4.99
  sale nets about US$4.24, and one US$9.99 sale about US$8.49.
- Suppose reviewing, recording, and editing a 40-phrase pack with a native
  speaker costs US$150–300.
- Then each pack needs about 35–70 single-language diaspora sales to cover its
  cost. Home-market sales mostly widen access rather than fund packs.

## Other shapes considered

| Shape | Good | Bad | Verdict |
| --- | --- | --- | --- |
| One-time, all languages only (the first plan) | Simplest to explain | Each new pack costs money but earns nothing from earlier buyers; a Dholuo learner pays for Amharic too | Replaced; kept as the bundle |
| **Per language, US$4.99 each, plus an all-languages bundle at US$9.99 (current)** | Fits heritage learners, who usually want one language; funds each language from its own learners | One store product and entitlement per language to set up | Built |
| Annual "supporter" subscription, about US$19.99 a year | Recurring money pays recurring recording work | Needs a steady release schedule Root doesn't have; subscription pressure clashes with Root's calm tone | Revisit when packs ship regularly |
| Everything free, with a "fund a speaker" tip | Most generous and honest while no premium content exists | Unpredictable income | Could run alongside, not instead |

Whatever the shape, the rules above don't change: starter sets and practice stay
free, and nothing is sold before it's in the app.

## Share a word

Dholuo Market used to be locked as a reward for opening the share sheet. It is
a free starter set now, so sharing unlocks nothing and the share screen says so.
The app still keeps the local "has shared" record (`ReferralPrefs`), so a future
thank-you can include past sharers. The app does not advertise that until it
exists.

## Before the first sale

A premium pack must pass the publication gate:

- The text is rights-cleared.
- A named native speaker has reviewed it and consented to credit.
- Every phrase has a genuine recording with consent and credit.
- It has a content manifest with provenance, installed with `isFree = false`.

Engineering gaps:

- `ContentLibrary` installs every downloaded or bundled manifest as free today,
  and protected paid delivery isn't built. The first premium pack must either be
  bundled in the APK as a paid pack, or wait for protected delivery.
- In the Play Console, create the one-time products from the store setup table
  above: `root_premium_all`, plus `root_premium_<key>` for each language that
  has a premium pack. In RevenueCat, attach `root_premium_all` to the
  entitlement `premium` and each language product to `premium_<key>`, and
  publish them all in the current offering. Then run a Test Store purchase,
  cancel, and restore for one language and for the bundle before any
  real-money test.
