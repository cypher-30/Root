# Content pipeline (`tools/content`)

This is the Python editorial/import/build/publish pipeline for Root's
language content: four real source adapters, a rights/review registry,
bounded safe ingestion, a deterministic pack builder, and a controlled
Supabase publisher. It owns `tools/content/**`, `content/schemas/**`,
`content/fixtures/**`, `content/sources/**`, `content/editorial/**`, and this
document. It does not touch Android/Gradle code.

## Install

```
pip install -e tools/content
```

Requires `jsonschema>=4.20` (installed automatically; used with a local
`referencing.Registry` so schema `$ref`s resolve from disk, never the
network). `ffprobe` (from ffmpeg) is required only for real media validation
(`content_pipeline.media.probe_audio`); if it is missing, that call raises
`FfprobeMissing` rather than silently approving unvalidated audio.

## CLI stages

Run from `tools/content/` or the repo root — all fixture/report paths
resolve relative to the repo root regardless of current working directory
(`content_pipeline.schemas.repo_root()`), e.g.
`python -m content_pipeline.cli <command>`.

| Command | Purpose |
|---|---|
| `discover <source> [--language L] [--release-dir DIR]` | Metadata-first discovery: confirms a source/config/release/language is reachable and what it contains, without importing rows. |
| `import <source> --language L [--limit N] [--out-dir DIR] [--release-dir DIR]` | Bounded candidate import (default `--limit 20`). Writes a JSON `ImportReport` (accepted/rejected/quarantined counts, an `inputFingerprint`, and an honest `blockedReason` if the source could not be reached/parsed). Never fabricates rows when blocked. |
| `validate-registry` | Validates `content/sources/registry.json` against `source-registry.schema.json`. |
| `validate-fixtures` | Validates every file in `content/fixtures/{valid,invalid}/` against its matching schema, asserting valid fixtures pass and invalid fixtures fail. |

`discover`/`import` never imply editorial approval — that is a separate gate
(see "Rights, review, and publication gates" below).

## Adapters and verified real-source status

All four adapters in `content_pipeline/adapters/` are fully implemented (not
stubs) and unit-tested with source-shaped fixtures. Live network smoke runs
were performed against the real public endpoints; exact results as of this
writing:

| Source | Discover | Import (bounded, real network) | Notes |
|---|---|---|---|
| **FLEURS** (`fleurs.py`) | ✅ real: HF `datasets-server.huggingface.co` `/splits`+`/info` confirm `sn_zw` (925 test rows), `luo_ke`, `sw_ke` configs, CC BY 4.0 | ⚠️ **honestly blocked**: `/rows` reliably returns real HTTP 500 for these audio-bearing configs (reproducible, not a bug in this adapter) | Reports `blocked_reason="network_unavailable: ... HTTP 500 ..."`, no fabricated rows. Config pinning (`sn_zw`/`luo_ke`/`sw_ke`) is real and confirmed. |
| **KenCorpus** (`kencorpus.py`) | ✅ real: Harvard Dataverse `doi:10.7910/DVN/6N5V1K` v9.1, CC BY 4.0, 4,837 files, per-file languages (`luo`, `sw`, three Luhya varieties) inferred from `directoryLabel`/filename, not the misleading dataset-level "Swahili" field | ✅ real: 10/10 candidates accepted for `luo` in a live run | Same-prefix text/audio candidates are quarantined as `unverified_pair` (never presented as a verified alignment) instead of guessed. |
| **Common Voice** (`common_voice.py`) | ✅ against an authorized local release directory only | ✅ against local test fixtures (`tests/fixtures/common_voice_release/`) | No live download is attempted: CV downloads require an authorized Mozilla Data Collective account. Provide a real authorized release via `--release-dir`/`release_dir=` to run for real; `validated.tsv` rows import (minus demographic columns), `reported.tsv` rows are quarantined, missing clip files are quarantined, not silently dropped. |
| **Tatoeba** (`tatoeba.py`) | ✅ real: `downloads.tatoeba.org/exports/per_language/sna/` listing confirms tiny `sna_sentences.tsv.bz2` (705 bytes) + 8 link files, CC BY 2.0 FR | ✅ real: 5/5 `sna` sentences accepted with real per-sentence translation lookups via `api.tatoeba.org` in a live run | Audio: no per-language export exists; only a 71,715,888-byte root CSV. A real HTTP HEAD confirms this size *before* deciding, and the adapter honestly reports `audioStatus="blocked"` / quarantines with `archive_budget_exceeded` rather than downloading a 71 MB archive just to smoke-test. |

Re-run any of these yourself, e.g.:

```
python -m content_pipeline.cli discover tatoeba --language sna
python -m content_pipeline.cli import kencorpus --language luo --limit 10
```

## Rights, review, and publication gates (`gates.py`, `registry.py`)

`content/sources/registry.json` is the single machine-readable place
recording, per `(sourceId, releaseId, componentKind)`: license, rights
status (`cleared`/`unknown`/`incompatible`), review status
(`unreviewed`/`source_checked`/`native_reviewed`/`stale`), and required
credit. Missing registry entries return an explicit "unknown/unreviewed"
placeholder — never a silent "cleared". Real research findings currently on
file: FLEURS and KenCorpus text are CC-BY-4.0 `source_checked`; KenCorpus
audio is `unreviewed`; Common Voice is `unknown` pending an authorized
release; Tatoeba text/translation is CC-BY-2.0-FR `source_checked`
("cleared" would need an explicit editorial decision), audio is `unknown`.

`gates.py` turns those records into decisions:
- `check_component_publishable` — full gate: requires `rights_status ==
  "cleared"` **and** `review_status == "native_reviewed"`.
- `check_source_checked_only` — the weaker development-only gate: still
  rejects `unknown`/`incompatible` rights outright; only `cleared` rights
  with any review status pass.
- `check_publication_status_allowed` — only `manifest.publication ==
  "published"` may reach the public catalog; `development` never does.
  (Kotlin's `PublicationStatus` has no manifest-level `"retired"` value —
  retirement is tracked separately as `CatalogEntry.retired: Boolean`.)
- `check_review_revision_matches` — rejects a stale approval whose recorded
  `reviewRevision` no longer matches the content's current `revision` (i.e.
  editing approved content invalidates that approval).
- `check_audio_assets_present_for_publication` — a manifest-wide semantic
  check (spans `publication`, every lesson's `listening`
  activities, and `assets[]`, so it can't be a single-node JSON Schema rule):
  a null `audioAssetId` (honestly "no authentic recording yet") is permitted
  only while `publication != "published"`; any non-null
  `audioAssetId` must name a real entry in `manifest.assets` — never an
  invented placeholder id. `pack_builder.build_manifest` runs this
  automatically and raises `PackBuildError` on failure, so a "published"
  manifest can never ship with missing/fabricated listening audio.

Source raw/license metadata (what an adapter's `discover`/`import` records)
is **never** treated as publication approval by itself.

## Reports, quarantine, idempotency

- `reports.py` — `ImportReport`: accepted/rejected/quarantined lists, a
  `blocked_reason` when the whole run could not proceed, and a
  `write()` that emits a canonical (sorted-key, no-whitespace) JSON file
  under a reports directory, keyed by adapter/release.
- `quarantine.py` — content-addressed `quarantine_key()` so the same
  source/release/item always quarantines to the same stable key across runs
  (idempotent, not a growing pile of duplicate entries).
- `idempotency.py` — `fingerprint_inputs()` over canonical JSON of the
  effective parameters, so re-running the same import is detectable/no-op
  via `IdempotencyLedger`.

## Safe bounded ingestion

- `net.py` wraps all HTTP access with `max_bytes` ceilings and turns 401/403
  into `AuthorizationRequired` (never silently retried around) and other
  failures into `NetworkUnavailable`.
- `safe_archive.py` provides `safe_extract_zip`/`safe_extract_tar` with path
  traversal, symlink, and byte/entry-count budget checks
  (`UnsafeArchiveMember`, `ArchiveBudgetExceeded`).
- Default smoke-import limit is 20 candidates (`DEFAULT_SMOKE_ITEM_LIMIT`);
  archive budget defaults to 25 MiB (`DEFAULT_ARCHIVE_BYTE_BUDGET`). A source
  that only offers a large bulk archive (e.g. Tatoeba's 71 MB audio CSV) is
  never downloaded just to "test that the adapter starts" — the adapter
  checks a real HTTP HEAD `Content-Length` and reports the budget-exceeded
  condition honestly instead.

## Deterministic pack builder (`pack_builder.py`)

`build_manifest(...)` builds one immutable `PackManifest` version:
canonical (sorted-key, no-whitespace, Unicode-preserving) JSON bytes, so
identical normalized inputs always produce an identical `manifest_sha256` —
no build timestamp enters the manifest. It enforces, and raises
`PackBuildError` for any violation of:

- `MAX_MANIFEST_BYTES` = 4 MiB
- `MAX_ASSETS_PER_PACK` = 256
- `MAX_ASSET_BYTES` = 20 MiB (per asset)
- `MAX_PACK_TOTAL_BYTES` = 50 MiB (assets total)
- safe relative asset keys (must start with `assets/`, no `..`/absolute/backslash)
- no duplicate relative keys
- schema validity against `manifest.schema.json`

### Filesystem/transport ID and key conventions (parent-specified policy)

These are enforced in `limits.py` and by every schema, and `pack_builder.py`
derives them automatically rather than trusting caller-supplied values:

- **IDs** (pack/phrase/lesson/activity/asset): `^[A-Za-z0-9][A-Za-z0-9._-]*$`,
  length 1–120 (`ID_PATTERN`, `ID_MAX_LENGTH`). Note: the frozen Kotlin
  `ID_PATTERN` is the looser `^[A-Za-z0-9._-]+$` (no required leading
  alphanumeric); this pipeline deliberately keeps the stricter
  leading-alnum-required pattern for anything used in a filesystem path
  (pack id, asset id) since any value accepted by our stricter schema is
  always also accepted by Kotlin's looser validator — this was flagged to
  teaching-foundation as a recommendation, not a blocker.
- **`version`**: a positive `Int` (`minimum: 1`).
- **Relative object keys** (asset `key`, `manifestKey`): ≤512 chars,
  `[A-Za-z0-9._/-]+`, no empty/`.`/`..` path segments, no percent-encoding,
  query string, colon, or backslash. This is enforced by requiring every
  `/`-separated segment to itself match the ID pattern.
- **`sha256`**: lowercase 64-hex (`^[0-9a-f]{64}$`).
- **`bytes`**: positive integer (`minimum: 1`).
- **Asset `key`** is *always* derived by the pack builder as
  `assets/audio-<assetId><ext>` (e.g. `assets/audio-asset.sn.greeting.001.m4a`)
  — never the original/source-derived filename, so no source filename or
  path ever leaks into the shipped pack. Callers only ever supply an
  `asset_id`; `AssetInput` has no `key` field to override this.
- **`manifestKey`** (catalog entries) is always
  `root_content/versions/<packId>/<version>/manifest.json`, produced by
  `limits.manifest_key_for(pack_id, version)` — this matches exactly where
  the parent's Kotlin `ContentTransport`/pack-installer stores the
  downloaded, immutable manifest on-device.
- Downloaded media on the Android side is likewise named
  `audio-<assetId>` (not the source filename) — this pipeline's asset `key`
  convention and the parent's on-device filename convention are the same
  string, by design.

`build_catalog(...)` builds the top-level catalog pointer the same way,
enforcing `MAX_CATALOG_BYTES` = 2 MiB and `catalog.schema.json` validity.

### CatalogEntry fields (`catalog_entry_for(result, ...)`)

Each pack's catalog entry is `id`, `version`, `language`, `title`,
`publication`, `phraseCount`, `unitCount`, `lessonCount`,
`audioCount`, `manifestKey`, `manifestSha256`, `manifestBytes`,
`downloadBytes`, `retired`. Use
`pack_builder.catalog_entry_for(build_result, retired=False, unit_count=None)`
to derive an entry from a `BuildResult` rather than hand-typing these fields
(hand-typed counts are how they drift/lie):

- **`publication`** is copied verbatim from the manifest's own top-level
  `publication` string (`"development"` | `"published"`, matching Kotlin
  `PublicationStatus` -- there is no nested revision; pack/lesson versioning
  already lives in `manifest.version`/`Lesson.revision`), so the catalog
  entry and the manifest it points to can never silently disagree about
  publication state.
- **`downloadBytes`** = `manifestBytes + sum(asset.bytes)` — the honest total
  a user is shown *before* requesting a pack download. `manifestBytes` alone
  is never surfaced as "the download size" anywhere. Capped at
  `MAX_DOWNLOAD_BYTES` = 50 MiB; `catalog_entry_for` raises `PackBuildError`
  if the combined manifest+assets total would exceed it (a case the
  asset-only `MAX_PACK_TOTAL_BYTES` check alone cannot catch, since the
  manifest itself is not counted there).
- **`language` / `title`**: copied verbatim from `manifest.language` /
  `manifest.title` — never re-typed or re-derived, so the catalog can't
  disagree with the manifest it links to.
- **`phraseCount` / `audioCount`**: `phraseCount` = `len(manifest.phrases)`;
  `audioCount` = `len(manifest.assets)` filtered to audio `mimeType`s. Both
  are exact counts over the built manifest, never estimates.
- **`lessonCount`** = the exact `len(manifest.lessons)` — the literal number
  of lesson entries, always accurate.
- **`unitCount`** defaults to the same value as `lessonCount` (this pipeline
  does not model a coarser "unit groups multiple lessons" concept yet);
  pass `unit_count=` explicitly if/when a real distinct grouping exists.
  `unitCount` and `lessonCount` are intentionally separate schema fields so
  they can diverge later without a breaking change.

### Wire shape

This mirrors the frozen Kotlin wire contract in `docs/TEACHING_CONTRACTS.md`
(owned by teaching-foundation) field-for-field; that document is the
authoritative source of truth, and `content/schemas/*.schema.json` is kept
in sync with it, not the other way around.

- `ContentLanguage`: `id`, `code`, `name`, `variety?`, `script?`.
- `Credits`: `text`, `license?`, `sourceUrl?` — the slim, shipped
  attribution shape. Used both top-level (`manifest.credits[]`) and
  per-phrase/per-asset (`credits?`). This pipeline's own richer internal
  rights/review tracking (`provenance.schema.json`,
  `content/sources/registry.json`) is reduced to this shape before a
  manifest is built; it never ships in the manifest itself.
- `ManagedPhrase`: `id`, `prompt`, `meaning`, `audioAssetId?`, `register?`,
  `context?`, `credits?`. **Field-direction convention (matches the
  parent's installer projection `PhraseEntity(answer=wire.prompt,
  prompt=wire.meaning)`):** `prompt` is the TARGET-LANGUAGE text being
  taught (the expected recall answer, e.g. Shona `"Mhoro"`); `meaning` is
  the LEARNER-KNOWN-LANGUAGE gloss/recall cue (e.g. English `"Hello"`).
  Reversing these inverts phrase display end-to-end on the client. Every
  adapter/build script in this pipeline maps target-language text into
  `prompt` and the known-language gloss into `meaning`, never the other way
  around (see `phrase.schema.json`'s per-field descriptions, and
  `Activity.dialogue_turn`'s `text`/`translation` and `Activity.listening`'s
  `transcript`/`translation`, which follow the identical convention).
- `ManifestAsset`: `id`, `key`, `sha256`, `bytes`, `mimeType`,
  `durationMs?`, `sampleRateHz?`, `credits?`.
- `Activity` (discriminated by `kind`, one of `dialogue_turn` /
  `pattern_explanation` / `choice` / `ordered_tokens` / `listening` /
  `reflection` / `speaking_prompt`):
  - `dialogue_turn`: `id`, `speaker`, `text`, `translation?`,
    `audioAssetId?`, `linkedPhraseId?`.
  - `pattern_explanation`: `id`, `explanation`, `examples[]` (phrase ids or
    short illustrative strings).
  - `choice`: `id`, `task: ChoiceTask`, `assistanceHint?`, where
    `ChoiceTask` is `id`, `prompt`, `choices[]` (`id`, `text`,
    `audioAssetId?`), `acceptedChoiceIds[]`, `feedbackCorrect?`,
    `feedbackIncorrect?`.
  - `ordered_tokens`: `id`, `task: OrderedTokenTask`, `assistanceHint?`,
    where `OrderedTokenTask` is `id`, `prompt`, `tokens[]` (`occurrenceId`,
    `text`), `acceptedSequences[][]`, `feedbackCorrect?`,
    `feedbackIncorrect?`.
  - `listening`: `id`, `audioAssetId?`, `unavailableReason` (required when
    `audioAssetId` is null), `transcript?`, `translation?`, and a required
    `comprehension: EvaluableTask` (`kind: "choice_task"|"ordered_token_task"`
    wrapping a `ChoiceTask`/`OrderedTokenTask`) — required even when audio is
    unavailable.
  - `reflection`: `id`, `prompt`.
  - `speaking_prompt`: `id`, `prompt`.
  - There is no `evidenceType`/`required` field authored on any activity:
    evidence type is computed at runtime by the Kotlin `LessonRunner`, and
    whether an activity gates lesson completion is decided solely by
    `Lesson.requiredActivityIds`.
- `Lesson`: `id`, `revision`, `title`, `objective`,
  `format` (`"guided_conversation"|"listening"|"pattern_workshop"`),
  `activities[]`, `requiredActivityIds[]` (required, non-empty — Kotlin's
  `ContentValidator` rejects an empty list via `NO_REQUIRED_ACTIVITIES`;
  every lesson has at least one gating activity, even a `listening`
  activity whose audio is still unavailable — see below),
  `linkedPhraseIds[]=[]`, `prerequisiteLessonIds[]=[]`. Array position in
  `activities[]` is the implicit order; there is no separate `order` field.
- `PackManifest`: `schemaVersion`, `minReaderVersion`, `id`, `version`,
  `language`, `title`, `objective`, `publication`
  (a plain string, `"development"|"published"` only — no nested revision
  and no manifest-level `"retired"`; matches Kotlin `PublicationStatus`
  exactly, an enum, not an object), `phrases[]`, `lessons[]`, `assets[]`,
  `credits[]=[]`.
- `Catalog`: `schemaVersion`, `catalogRevision`, `entries[]` (top-level array
  is named `entries`, not `packs`). `CatalogEntry`: `id`, `version`,
  `language`, `title`, `publication`, `unitCount`,
  `lessonCount`, `phraseCount`, `audioCount`, `manifestKey`,
  `manifestSha256`, `manifestBytes`, `downloadBytes`, `retired=false`.

## Media validation (`media.py`)

`probe_audio(path)` shells out to `ffprobe`. If `ffprobe` is not found on
`PATH`, it raises `FfprobeMissing` — this is reported as a missing
dependency, never silently treated as "audio OK". When ffprobe is present,
it reports real codec/duration/sample-rate and flags zero-duration/missing
sample-rate/no-audio-stream as issues (`ok=False`), not a silent pass.
`ffprobe`/`ffmpeg` are confirmed installed in this environment;
`tests/test_media.py::RealFfprobeSmokeTests` generates a real, tiny silent
AAC clip with `ffmpeg` and probes it with the real `ffprobe` binary
(unmocked) whenever both tools are present on `PATH`, skipping itself
honestly otherwise.

## Publisher (`publisher.py`) — controlled Supabase Storage, catalog-last

- Maintainer credentials come **only** from environment variables
  `ROOT_SUPABASE_URL` / `ROOT_SUPABASE_SERVICE_KEY` (`load_target_from_env`),
  never committed/logged/hardcoded. Missing credentials raise
  `PublisherCredentialsMissing` rather than silently no-op'ing.
- `publish_pack(...)` requires an explicit `approved=True` from the caller
  (`PublishNotApproved` otherwise) — raw rights/review clearance is not by
  itself authorization to upload. It uploads assets first (verifying each
  post-upload byte-for-byte via hash before publishing the manifest), then
  the manifest, verifying that too.
- `publish_catalog(...)` is always the last step of a release and likewise
  requires explicit `approved=True`.
- All network calls go through injectable `upload_fn`/`get_fn`, so
  `tests/test_publisher.py` fully mocks upload/verification behavior without
  any real project or credentials. No upload is attempted without an
  explicit, caller-selected project/bucket.
- Public reads (anonymous fixture downloads from a Supabase bucket) need no
  app-embedded keys; only the maintainer-side publish path needs the service
  key, and it is never bundled into the app.

### Current hosted-publish status: explicitly blocked, not provisioned

No Supabase project URL/credentials have been supplied (the human maintainer
is currently unavailable to create/select one), and this pipeline does not
provision a project on its own. `load_target_from_env()` will raise
`PublisherCredentialsMissing` until `ROOT_SUPABASE_URL` /
`ROOT_SUPABASE_SERVICE_KEY` are set by whoever owns that project. Everything
in `publisher.py` is implemented and exercised end-to-end only via
`tests/test_publisher.py`'s injected `upload_fn`/`get_fn` mocks (files-first →
manifest → catalog-last, hash verification, approval gating, credential
loading). A real hosted publish/download/rollback smoke test against an
actual Supabase project remains an open, explicit blocker for whoever
supplies project credentials — this is not silently skipped or faked.

## Shona pilot (development-only, never public)

`content/editorial/shona_pilot/` (private editorial authoring registry:
`phrases.json`/`lessons.json`) — see its own `README.md` for full detail.
Summary: 8 short greeting/courtesy phrases (matching the app's existing
`SeedData.kt` starter set), 3 lessons (a six-turn `guided_conversation` +
contextual `choice` transfer task, a `listening`-format lesson with a
single `listening` activity honestly carrying `audioAssetId: null` + a
required `unavailableReason` + a required (but currently unexercisable)
`comprehension` task (zero fake/synthesized audio, no invented placeholder
asset; the activity IS listed in `requiredActivityIds` — Kotlin's
`ContentValidator` rejects an empty `requiredActivityIds` list — but stays
permanently un-completable until real audio exists: `LessonRunner` returns
`AUDIO_UNAVAILABLE` for a null `audioAssetId` rather than ever accepting
comprehension evidence, so the lesson is honestly blocked, not silently
skippable or fakeable),
and a `pattern_workshop` with a guided and a changed-context/plural
`ordered_tokens` construction activity), built into a schema-valid manifest
via `tools/content/scripts/build_shona_pilot.py`. The six dialogue turns are
a single coherent scene (Tendai visits neighbor Rudo across a morning
arrival, an afternoon return, and an evening farewell — see the lesson's
opening `reflection` activity, `scene-setup`, for the English framing),
using each of the 6 singular-register phrases exactly once — not six
disconnected greeting cards, and no invented grammar/register claim beyond
what each phrase already carries. Every phrase's `provenance.rightsStatus`
is honestly `unknown` and `reviewStatus` is `source_checked` (never
`native_reviewed`); `gates.py` correctly refuses to treat this content as
publishable (`tests/test_shona_pilot.py` asserts this). Real audio remains
an external blocker: it needs either a consenting native-speaker recording
or an authorized source release, neither of which exists yet.

**Pilot manifest path for the parent/Android side to install into debug
assets only:** `content/editorial/shona-pilot.json` (manifest-only, single
top-level file — separate from the private editorial registry directory
above; the parent packages this exact file via a debug-only Gradle `Copy`
task into the app's `content/shona-pilot.json` debug asset). No publication
claim is made by producing this file.

## Running the tests

```
cd tools/content
python -m unittest discover -s tests -v
```

As of this writing: **130 tests, all passing** — covering canonical JSON,
all 13 JSON Schemas against 14 valid/invalid fixtures, the source
registry/gates (including the `listening` activity's null-audio
publication-status gate), reports/quarantine/idempotency, safe archive
extraction, all four adapters (mocked-network unit tests plus the live
smoke runs described above), media probing (mocked `ffprobe` plus a real
unmocked smoke test), the deterministic pack builder (every size/count
limit, the ID/asset-key/manifestKey conventions, the `catalog_entry_for`
downloadBytes/lessonCount derivation, and the null-audio publication gate,
each at and past its boundary), the mocked Supabase publisher, and the
Shona pilot content/gate assertions.

## Coordination with the Android contracts agent

The manifest/catalog/activity wire shape now mirrors, field-for-field, the
frozen contract teaching-foundation published at `docs/TEACHING_CONTRACTS.md`
(Kotlin `ContentModels.kt`/`CatalogModels.kt`/`ContentValidation.kt`), which
this pipeline adopted in place of its earlier independently-invented field
names (`targetText`→`prompt`, `relativeKey`→`key`, `mime`→`mimeType`,
`displayName`→`name`, `steps`→`activities`, `single_response_selection`→
`choice`, `ordered_token_construction`→`ordered_tokens`, `audio_encounter`→
`listening`, `packs`→`entries`, etc). `content/schemas/*.schema.json` is
kept in sync with `docs/TEACHING_CONTRACTS.md`; if that document changes,
update the schemas (and `content/fixtures/**`) to match and re-run
`validate-fixtures` — schemas are versioned via `schemaVersion` specifically
so this kind of resync is safe. One open, non-blocking flag raised back to
teaching-foundation: Kotlin's `ID_PATTERN` (`^[A-Za-z0-9._-]+$`) has no
required leading alphanumeric character, unlike this pipeline's stricter
`^[A-Za-z0-9][A-Za-z0-9._-]*$`; this pipeline keeps the stricter pattern for
anything used in a filesystem path (pack id, asset id) since it is a strict
subset of Kotlin's looser pattern.
