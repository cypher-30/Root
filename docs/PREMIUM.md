# Root Premium: what it is and what it costs

This is the product decision for Root's paid tier. The paywall copy comes from
`app/src/main/kotlin/com/root/app/billing/PremiumOffer.kt`. Keep the two in step.

## The rule

Premium is extra phrase sets per language. Every starter set, and everything
you need to practise, stays free. Premium never gates practice, how often you
practise, or your own words. A premium set is only sold once it's actually in
the app (`ContentAccess.hasPremiumContent`), and a single language is sold only
once that language has one (`ContentAccess.premiumContentLanguageKeys`).

## Always free

- Every starter set in every language (five or six sets, 39–40 phrases each).
- Daily practice with no limits, the Learn units, and Explore's stories,
  culture notes and your notebook.
- Your own words and recordings, a language you add yourself, sharing a word
  card, and the widget.
- Working offline, with no account.

## What Premium includes

Two premium sets per language today, bundled in the app and shown in Explore
with a lock until you own that language. Every phrase comes from the same public
sources as the starter sets (see `app/src/main/assets/content_sources.txt`).

| Language | Premium sets | Source |
| --- | --- | --- |
| Dholuo | Body & health (10 phrases), Days of the week (10) | Wikivoyage Luo phrasebook |
| Shona | Heart words (7), Keep talking (6) | Omniglot |
| Swahili | Heart words (8), Keep talking (8) | Omniglot |
| Amharic | Heart words (9), Keep talking (7) | Omniglot |

Heart words are the things you say to family: I love you, long time no see,
get well soon, and birthday and holiday wishes. Keep talking is what keeps a
conversation going: do you understand, please say that again, speak slowly.

Once unlocked, a premium set works like any other: it appears in Practice,
Learn's topics and Explore, and its phrases can be saved to the notebook.
Premium sets added later for a language you own are included at no extra cost.

## Showcase code

For the Shipaton judges and demos, the code **SHIPATON2026** (case, spaces and
dashes ignored) opens every premium set on that device, the same way the
all-languages purchase would. Enter it under **Have a code?** on the Premium
screen; **Remove code** locks the sets again. It is stored locally
(`billing/RedeemCode.kt`, `EntitlementStore`), works without a store
connection, and is not a RevenueCat transaction.

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

Set prices in the Play Console (or the RevenueCat Test Store for the showcase).
The amount charged always comes from the store: Google Play charges each person
the price set for their Play account's country, in their currency, so the app
never asks where you are. Until the store returns a plan, the paywall shows
**Planned prices** for the phone's country (`PremiumOffer.plannedPrices`, using
the SIM or network country from `DeviceCountry`, which needs no permission or
location): the home-market price in Kenya, Zimbabwe and Ethiopia, the diaspora
price everywhere else. Change those values, this table, and the store products
together. The Test Store has one price per product, so the showcase sells at
the diaspora price everywhere.

**Why one-time and not a subscription.** Root is calm by design and has no
streaks or pressure. A subscription only makes sense with a steady release
schedule, which Root doesn't have yet. The billing code sells only
non-consumable products and hides subscription packages. Reconsider a
subscription once premium packs ship on a regular schedule.

**Why per language.** Heritage learners usually want one language, the one
their family speaks. A Dholuo learner shouldn't have to pay for Amharic, and
each language's premium sets are paid for by that language's own learners.
The bundle is for families with more than one heritage language, and for
supporters.

**Does it pay for itself?** An illustrative example; replace the numbers with
real quotes:

- Google Play's service fee is 15% on the first US$1M a year, so one US$4.99
  sale nets about US$4.24, and one US$9.99 sale about US$8.49.
- Suppose curating, recording, and editing a new set with a native speaker
  costs US$150–300.
- Then each set needs about 35–70 single-language diaspora sales to cover its
  cost. Home-market sales mostly widen access rather than fund new sets.

## Other shapes considered

| Shape | Good | Bad | Verdict |
| --- | --- | --- | --- |
| One-time, all languages only (the first plan) | Simplest to explain | Each new pack costs money but earns nothing from earlier buyers; a Dholuo learner pays for Amharic too | Replaced; kept as the bundle |
| **Per language, US$4.99 each, plus an all-languages bundle at US$9.99 (current)** | Fits heritage learners, who usually want one language; funds each language from its own learners | One store product and entitlement per language to set up | Built |
| Annual "supporter" subscription, about US$19.99 a year | Recurring money pays recurring recording work | Needs a steady release schedule Root doesn't have; subscription pressure clashes with Root's calm tone | Revisit when packs ship regularly |
| Everything free, with a "fund a speaker" tip | Most generous | Unpredictable income | Could run alongside, not instead |

Whatever the shape, the rules above don't change: starter sets and practice stay
free, and nothing is sold before it's in the app.

## Share a word

Dholuo Market used to be locked as a reward for opening the share sheet. It is
a free starter set now, so sharing unlocks nothing and the share screen says so.
The app still keeps the local "has shared" record (`ReferralPrefs`), so a future
thank-you can include past sharers. The app does not advertise that until it
exists.

## Selling it (RevenueCat Test Store for the showcase)

Root isn't on Google Play yet. For the Shipaton showcase, purchases run through
RevenueCat's **Test Store**, which the debug build's `test_` key already uses
(Android SDK 9.9.0+). In the RevenueCat dashboard, under the Test Store app:

1. Create five one-time (non-consumable) products: `root_premium_all` at
   US$9.99, and `root_premium_dholuo`, `root_premium_shona`,
   `root_premium_swahili`, `root_premium_amharic` at US$4.99 each. Test Store
   products can't be edited after creation, so check IDs and prices first.
2. Attach `root_premium_all` to the entitlement `premium`, and each language
   product to `premium_<key>` (create those four entitlements).
3. Add all five products as packages to the current offering (`default`). The
   app ignores the offering's subscription packages.
4. On the phone, open a locked set's **See Premium**, buy, and choose the
   successful outcome in RevenueCat's test sheet. Then check **Restore
   purchases**, and try a cancelled purchase.

Test Store purchases show as sandbox data in the dashboard and never charge
money. The SDK refuses a Test Store key in release builds, and
`verifyReleaseConfiguration` rejects one too.

**Status (29 Sep 2026):** all five products, entitlements and packages exist in
the `default` offering. It also still holds the older Monthly/Yearly/Lifetime
packages, which the app filters out. Checked on a phone: the Swahili paywall
lists only *Swahili only* (US$4.99) and *All languages* (US$9.99). A cancelled
purchase returns quietly, and a failed one shows an error. A successful purchase
unlocks Heart words and Keep talking through `premium_swahili`. Restore on
Dholuo says Swahili was restored and Dholuo isn't included. The Test Store
can't truly restore, so it returns the current customer.

## Before a public release

- A named native speaker reviews each premium set and consents to credit, and
  premium phrases get genuine recordings with consent and credit.
- Create the same products in the Play Console, connect Play to RevenueCat,
  and use a production key (`ROOT_REVENUECAT_RELEASE_API_KEY`).
- `ContentLibrary` installs every downloaded manifest as free today, and
  protected paid delivery isn't built. New premium sets must be bundled in the
  APK, as the current ones are, until it is.
- Remove or rotate the showcase code.
