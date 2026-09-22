# Shona Pilot — DEVELOPMENT ONLY, NOT FOR PUBLIC RELEASE

This directory holds a **debug-only** prototype editorial unit used to exercise
the teaching runner's activity kinds (dialogue, contextual response/transfer,
listening, and a pattern workshop) end-to-end while a real, rights-cleared,
native-speaker-reviewed Shona unit is still pending.

## What this is

- `phrases.json` — 8 short Shona greeting/courtesy phrases (internal editorial
  authoring record: id/revision/targetText/knownLanguageCode/meaning/
  provenance), identical in substance to the phrases already shipped in the
  Android app's `SeedData.kt` / `assets/content_sources.txt` starter pack,
  each carrying an honest `provenance` record. The wire manifest's slim
  `ManagedPhrase` shape (`prompt`/`meaning`/`credits`) is derived from this
  record by `build_shona_pilot.py`, never hand-duplicated.
- `lessons.json` — 3 lessons built only from those 8 phrases (matching the
  frozen wire contract in `docs/TEACHING_CONTRACTS.md`):
  1. **Greetings and Courtesies** (`format: guided_conversation`) — an
     opening `reflection` activity (`scene-setup`, English only, never gates
     completion) states the scene honestly: Tendai visits neighbor Rudo
     across a morning arrival, an afternoon return, and an evening farewell
     in one day. This explains *why* the six turns below span three times of
     day (Shona greetings are genuinely time-of-day specific, so a single
     continuous exchange cannot naturally use all of them) instead of
     silently presenting six disconnected phrase cards as one impossible
     scene. The six `dialogue_turn` activities that follow use each of the
     6 singular-register phrases exactly once (no repeats, no gaps), plus
     one `choice` contextual-reply transfer task (its answer options nested
     under `task`).
  2. **Listening Practice (Audio Unavailable)** (`format: listening`) — a
     single `listening` activity with `audioAssetId: null`, a required
     `unavailableReason`, and a required `comprehension` (`EvaluableTask`)
     even though it cannot be exercised without real audio. No audio,
     synthesized voice, or unrelated benchmark recording is attached; no
     placeholder asset id/hash/duration was invented to satisfy a
     required-string field. Listening is explicitly, honestly blocked, not
     silently skipped: `gates.check_audio_assets_present_for_publication`
     only permits a null `audioAssetId` while `publication !=
     "published"`. The activity IS listed in `requiredActivityIds` — Kotlin's
     `ContentValidator` rejects a lesson with an empty `requiredActivityIds`
     list (`NO_REQUIRED_ACTIVITIES`) — but stays permanently un-completable
     until real audio exists: the `LessonRunner` returns `AUDIO_UNAVAILABLE`
     for a null `audioAssetId` rather than a fake/blank player or ever
     accepting comprehension evidence, so the lesson is honestly blocked,
     never silently skippable or fakeable-complete.
  3. **Address Pattern Workshop** (`format: pattern_workshop`) — a
     `pattern_explanation` activity describing the sg/pl phrase-pair pattern
     narrowly (only for the two pairs already checked, not a general grammar
     claim), a guided `ordered_tokens` activity, and a changed-context
     (plural) `ordered_tokens` transfer activity (answer tokens/sequences
     nested under `task`).
- `manifest.json` is **not** stored in this directory. The built,
  manifest-only pack (same `PackManifest` shape/limits used for real packs,
  produced via `tools/content/scripts/build_shona_pilot.py` and
  `content_pipeline.pack_builder.build_manifest`; schema-validated,
  canonical/deterministic bytes, `assets: []` because no audio exists) is
  written to `content/editorial/shona-pilot.json` instead — a single
  top-level file, kept separate from this directory's private editorial
  authoring registry (`phrases.json`/`lessons.json`) so the parent's
  debug-only Gradle Copy task has one unambiguous file to copy.

## What this is **not**

- **Not** reviewed by a native Shona speaker. `reviewStatus` on every phrase
  is `source_checked`, never `native_reviewed`.
- **Not** rights-cleared. `rightsStatus` is honestly recorded as `unknown`
  (checked against Omniglot for accuracy; no redistribution license was
  obtained from Omniglot or the credited phrase contributors).
- **Not** eligible for the public catalog. `publication` is
  `"development"`. Running this manifest's provenance through
  `gates.check_component_publishable` or `gates.check_source_checked_only`
  in `tools/content/content_pipeline/gates.py` returns `allowed=False` for
  exactly these reasons — see `tools/content/tests/test_shona_pilot.py`,
  which asserts this rather than assuming it.
- **Not** carrying any audio, TTS, or "temporary" recording. No file was
  synthesized or reused from an unrelated speaker/corpus to fake playback.
  Real audio requires either a consenting native-speaker recording or an
  explicitly authorized source release — neither exists yet. This is the
  honest external blocker for turning lesson 2 into a real listening lesson.
- **Not** grammar or translation invented beyond the already source-checked
  phrase set. The pattern workshop only points out a pattern across two
  already-checked pairs; it does not claim a general rule.

## Installing for debug only

The parent/Android integration should install
`content/editorial/shona-pilot.json` (manifest-only; the private editorial
authoring registry in this directory is not shipped) **only** into a
debug-build asset path (e.g. a debug-only Gradle `Copy` task placing it at
`content/shona-pilot.json` in the app's debug assets, gated by a
`BuildConfig.DEBUG`-style flag), never bundled into release. This pipeline
enforces `publication != "published"` is never publish-eligible
(`gates.check_publication_status_allowed`), and separately
`gates.check_audio_assets_present_for_publication` would refuse this exact
manifest if anyone ever tried to flip its `publication` to
`"published"` while its listening step's `audioAssetId` is still null. It is
still the Android app's responsibility to keep development-only fixtures out
of release asset packaging.

**Pilot manifest path (for parent to install in debug assets):**
`content/editorial/shona-pilot.json`

## Regenerating

```
python tools/content/scripts/build_shona_pilot.py
```

Regenerate after any phrase/lesson wording change; the manifest content hash
changes whenever the normalized inputs change, so stale copies are detectable.
Rebuilding also writes `phrases.json`/`lessons.json` (private editorial
registry) in this directory.
