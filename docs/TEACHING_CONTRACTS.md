# Teaching contracts: frozen Kotlin API summary

This document is the authoritative, frozen contract for the Android teaching/
content foundation implemented under `app/src/main/kotlin/com/root/app/content/`,
`app/src/main/kotlin/com/root/app/learning/`, and the new tables in
`app/src/main/kotlin/com/root/app/data/`. It exists so the Python editorial
pipeline (`tools/content/`, `content/schemas/`) can target the exact same shape
without inspecting Kotlin source. Field names below are the wire (JSON) names.

Scope owned here: content DTOs/validation, the learning package, the new Room
entities/DAOs (`ContentEntities.kt`/`ContentDao.kt`/`LearningEntities.kt`/
`LearningDao.kt`), and `AppDatabase` migration 3→4. **Not** owned here:
`RootRepository`/`RootViewModel`/`MainActivity`/UI/Gradle/the editorial
pipeline/JSON Schema files — those are integrated by other work in this
session and must treat this document, not this file's internals, as contract.

Note: `content/CatalogValidation.kt`, `content/ContentLibrary.kt`,
`content/ContentTransport*.kt`, and `content/PackFiles.kt` under this same
package were authored by a concurrently-running sibling agent building the
pack installer/network layer — not by this work. Several fields/enum values/
DAO methods below (`CatalogEntry.lessonCount`/`downloadBytes`,
`PackInstallJobStatus.CANCELLED`, `LessonRunStatus.UNAVAILABLE`, the
installer-support `ContentDao` methods) were added reactively to match that
real consumer once it appeared in the shared working tree, rather than being
part of the original frozen design.

## Versioning

- `schemaVersion` (currently `1`, see `SUPPORTED_SCHEMA_VERSIONS`): the wire
  format this JSON was written against.
- `minReaderVersion` (currently must be `<= CURRENT_READER_VERSION = 1`): the
  oldest reader able to safely load this manifest. A manifest whose
  `minReaderVersion` exceeds what a build understands is rejected outright,
  never best-effort parsed.
- `version` (pack) / `revision` (lesson): the content's own immutable
  editorial revision number, bumped on any teaching-content change.

## Wire contracts (`com.root.app.content`)

All types are `kotlinx.serialization` `@Serializable`; polymorphic types use a
`"kind"` discriminator field (`ContentJson` is configured with
`classDiscriminator = "kind"`).

```
ContentLanguage(id, code, name, variety?, script?)
PublicationStatus = "development" | "published"
Credits(text, license?, sourceUrl?)
ManifestAsset(id, key, sha256, bytes, mimeType, durationMs?, sampleRateHz?, credits?)
ManagedPhrase(id, prompt, meaning, audioAssetId?, register?, context?, credits?)
  // prompt  = TARGET-language text being taught (e.g. "Mhoro").
  // meaning = recall prompt in the learner's KNOWN language (e.g. "Hello (one person)").
  // Installer projection into PhraseEntity intentionally SWAPS field names:
  //   PhraseEntity(prompt = wire.meaning, answer = wire.prompt)
  // because PhraseEntity's own prompt/answer are named from the recall card's
  // point of view (known-language side shown first = "prompt"; target-language
  // side revealed on flip = "answer"). Do not map wire.prompt -> PhraseEntity.prompt
  // directly, or every recall card's known/target sides display inverted.

Choice(id, text, audioAssetId?)
ChoiceTask(id, prompt, choices[], acceptedChoiceIds[], feedbackCorrect?, feedbackIncorrect?)
TokenOccurrence(occurrenceId, text)
OrderedTokenTask(id, prompt, tokens[], acceptedSequences[][], feedbackCorrect?, feedbackIncorrect?)

EvaluableTask ("kind"):
  "choice_task"         -> Choice(task: ChoiceTask)
  "ordered_token_task"  -> OrderedTokens(task: OrderedTokenTask)

Activity ("kind"):
  "dialogue_turn"       -> DialogueTurn(id, speaker, text, translation?, audioAssetId?, linkedPhraseId?)
  "pattern_explanation" -> PatternExplanation(id, explanation, examples[])
  "choice"              -> ChoiceActivity(id, task: ChoiceTask, assistanceHint?)
  "ordered_tokens"      -> OrderedTokenActivity(id, task: OrderedTokenTask, assistanceHint?)
  "listening"           -> Listening(id, audioAssetId?, unavailableReason?, transcript?, translation?, comprehension: EvaluableTask)
  "reflection"          -> Reflection(id, prompt)
  "speaking_prompt"     -> SpeakingPrompt(id, prompt)   // optional, never blocks completion

LessonFormat = "guided_conversation" | "listening" | "pattern_workshop"
Lesson(id, revision, title, objective, format, activities[], requiredActivityIds[],
       linkedPhraseIds[]=[], prerequisiteLessonIds[]=[])

PackManifest(schemaVersion, minReaderVersion, id, version, language, title,
             objective, publication, phrases[], lessons[], assets[], credits[]=[])
```

Every id (`PackManifest.id`, phrase/lesson/activity/choice/token-occurrence/
asset ids) must match `ID_PATTERN = ^[A-Za-z0-9][A-Za-z0-9._-]*$` (must start
with an alphanumeric char — never a bare `.`/`_`/`-`) and be 1..`MAX_ID_LENGTH`
(120) chars long — this exactly mirrors the parent's transport/filesystem ID
policy, so nothing accepted here can later be rejected by the installer.
Asset `key` must pass `isSafeAssetKey(key)`: right charset
(`ASSET_KEY_PATTERN = ^[A-Za-z0-9._/-]+$`), length 1..`MAX_ASSET_KEY_LENGTH`
(512), and no empty/`.`/`..` path segments (checked by splitting on `/`) — this
matches the parent's relative-object-key policy (no percent-encoding, query
string, colon, or backslash either, since none of those characters are in the
charset to begin with). `language.code` must match `LANGUAGE_CODE_PATTERN`
(loose BCP-47 shape). `asset.sha256` must match `SHA256_HEX_PATTERN` (64
lowercase hex chars). Note: the installer materializes downloaded media under
its own filename (`audio-<assetId>`, per the parent's storage layout), not
under the manifest's `key` — `key`/`sha256` here are provenance/integrity
metadata the installer verifies against, not a real filesystem path.
`CatalogValidation` reuses these exact same shared patterns/helper for catalog
pack IDs and `manifestKey`, so catalog- and manifest-level identifiers can
never diverge.

### Catalog (`CatalogModels.kt`)

```
CatalogEntry(id, version, language, title, publication, unitCount, lessonCount,
             phraseCount, audioCount, manifestKey, manifestSha256, manifestBytes,
             downloadBytes, retired=false)
Catalog(schemaVersion, catalogRevision, entries[])
```

`downloadBytes` is the total a client must fetch to fully install this pack
version (manifest + every referenced asset), distinct from `manifestBytes`
(the manifest document alone). `lessonCount` was added alongside `unitCount`
during integration with the pack installer (`ContentLibrary.kt`), which
surfaces both in its UI-facing summaries.

### Size limits (`ContentLimits`)

| Budget | Value |
|---|---|
| Catalog document | ≤ 2 MiB |
| Pack manifest document | ≤ 4 MiB |
| Assets per pack | ≤ 256 |
| Bytes per asset | ≤ 20 MiB |
| Total asset bytes per pack | ≤ 50 MiB |

## Validation (`ContentValidator`, `ContentValidation.kt`)

`ContentValidator.validate(manifest, manifestBytes?)` returns
`ValidationResult.Valid` or `Invalid(errors: List<ValidationError>)`
(`ValidationError(code, path, message)`) — it **collects every problem**
rather than stopping at the first. Checked, non-exhaustively:

- Unsupported `schemaVersion` / `minReaderVersion` above what this reader
  supports.
- Invalid id charset anywhere; invalid/unsafe asset keys (including `..`
  traversal); invalid `language.code`; invalid sha256 hex.
- Duplicate ids: pack id-space (phrases, assets), lesson-space (activities
  within a lesson), and global activity-id space (across all lessons in a
  pack), duplicate choice ids / token occurrence ids within one task.
- Unresolved references: `audioAssetId`/`linkedPhraseId` on any activity,
  phrase `audioAssetId`, lesson `linkedPhraseIds`, `requiredActivityIds`.
- Invalid answer keys: empty `acceptedChoiceIds`/`acceptedSequences`; an
  accepted choice id not among that task's choices; an accepted sequence that
  is not an exact permutation of that task's token occurrence ids.
- Cyclic/unresolved lesson `prerequisiteLessonIds` (DFS cycle detection).
- A `published` pack's `Listening` activity may not have a null
  `audioAssetId` (`PUBLISHED_LISTENING_MISSING_AUDIO`) — a `development`
  pack may, since a lesson can be authored before its recording exists; this
  is the one deliberate exception to "every reference must resolve," and it
  is never a silently-accepted placeholder — [LessonRunner] independently
  refuses (`AUDIO_UNAVAILABLE`) to record comprehension evidence for a
  `Listening` step whose audio is null or not actually installed.
- Whenever a `Listening` activity's `audioAssetId` is null (in any
  publication status), `unavailableReason` must be a non-blank string
  (`LISTENING_MISSING_UNAVAILABLE_REASON`) — a human-readable explanation the
  UI can show directly instead of a generic "no audio" message. Never
  required when `audioAssetId` is present.
- Every size budget in `ContentLimits`, plus an optional raw
  `manifestBytes`/`validateCatalogBytes(bytes)` check for the serialized
  document itself.

JSON Schema (owned by the editorial pipeline) is expected to validate shape;
this validator is the semantic layer neither JSON Schema nor Kotlin's type
system can express on its own. Python fixtures should exercise the same
valid/invalid cases as `ContentValidationTest.kt`.

## Room persistence (`com.root.app.data`)

Per the approved plan, one immutable JSON blob per content revision plus
indexed metadata — not a table per DTO.

- **`PackVersionEntity`** (`pack_versions`, unique on `(pack_id, version)`):
  one immutable revision's full `manifestJson` plus indexed
  `schemaVersion`/`minReaderVersion`/`title`/`publication`/counts/
  `manifestSha256`. Never mutated in place.
- **`InstalledPackEntity`** (`installed_packs`, PK `pack_id`): the current
  installed/ready pointer (`currentVersion`, `status ∈ {READY, RETIRED}`),
  kept independent of `PackInstallJobEntity` so a failed update never
  disturbs a working older install.
- **`PackInstallJobEntity`** (`pack_install_jobs`, unique on `request_id`):
  one install/update attempt's lifecycle, `status ∈ {PENDING, DOWNLOADING,
  VALIDATING, INSTALLING, SUCCEEDED, FAILED, CANCELLED}` (`CANCELLED` added
  during integration with the pack installer). The installer itself
  (network/filesystem) is **not** implemented here — this table is the
  contract it is expected to drive. A duplicate `request_id` insert fails
  rather than silently succeeding, so retried install requests never
  double-create jobs.
- **`ManagedPhraseEntity`** (`managed_phrases`, PK/unique `phrase_id`,
  `pack_id`, `pack_version` (the pack's content revision this phrase is
  currently managed at), FK to `phrases.id` **`ON DELETE RESTRICT`** — not
  `CASCADE`; a `PhraseEntity` can never be silently deleted while a managed
  row still references it): maps one managed (pack-owned) recall phrase to
  its `PhraseEntity`. `retired` (equivalently `active = !retired`) flips
  availability without touching identity/history — retiring/uninstalling a
  pack never deletes this row. `ManagedContentAccess.isEligible(managed)`
  (`managed == null || !managed.retired`) is the shared predicate: a phrase
  with **no** row here is legacy/personal and is always eligible; retired
  managed phrases are excluded while legacy phrases are never filtered. Only
  one row per `phrase_id` is kept (current state); an older pack revision's
  own set of managed phrases is still reconstructable from that revision's
  immutable `PackVersionEntity.manifestJson` — no separate history table.
- **`ContentAssetEntity`** (`content_assets`, unique on
  `(pack_id, pack_version, asset_id)`): one packaged media object actually
  referenced by an installed version, plus installer-filled `localUri`.
- **`LessonRunEntity`** (`lesson_runs`, indexed on
  `(pack_id, lesson_id, status)`): one durable lesson run, pinning
  `packId`/`packVersion`/`lessonRevision`, `status ∈ {ACTIVE, PAUSED,
  COMPLETED, UNAVAILABLE}` (`UNAVAILABLE` added so an installer retiring/
  uninstalling a pack can durably end any still-open run for it while
  preserving its recorded evidence), `currentStepIndex`, comma-joined
  `revealedActivityIds`, and `supersededByRunId` (set on an old run by
  Restart, never on itself by completion). `LearningDao.getOpenRun(packId,
  lessonId)` — the single run whose `status IN (ACTIVE, PAUSED)` for that
  exact `(packId, lessonId)` pair — enforces "at most one unfinished run per
  lesson." `packId` scoping is required (not just `lessonId`) because a
  lesson id is only unique *within* its own manifest — two different packs
  could otherwise collide on the same lesson id and resume the wrong pack's
  run.
- **`LearningEventEntity`** (`learning_events`, FK to `lesson_runs.id`
  `ON DELETE CASCADE`): one piece of durable evidence — `kind ∈ {EXPOSURE,
  ASSISTED, CHECKED, SELF_REPORT}`, optional `correct`, opaque
  `responseJson`. **Never** an `AttemptEntity`; recall scheduling is
  untouched by any lesson activity.
- **`LessonRunCommandEntity`** (`lesson_run_commands`, PK `command_id`): the
  idempotency ledger — `payloadHash` plus the stored `resultJson`.

### `ContentDao` methods added for the pack installer

The installer (`content/ContentLibrary.kt`, not owned here) is the one real
consumer of these; they were added reactively during integration rather than
in the original design, and are documented here so the contract stays
accurate:

- `observeAllInstalled()` / `allInstalled()`: all `InstalledPackEntity` rows
  (flow / one-shot), ordered by `pack_id`.
- `observeInstallJobs()` / `unfinishedInstallJobs()`: all jobs (flow, newest
  first) / only jobs still in `{PENDING, DOWNLOADING, VALIDATING,
  INSTALLING}` (one-shot, e.g. for resuming after process death).
- `updateManagedPhraseVersion(phraseId, packVersion, retired, updatedAt)`:
  repoints a managed phrase at a new installed pack version (distinct from
  `setManagedPhraseRetired`, which only flips `retired`).
- `endPackLessons(packId, now)`: marks every `ACTIVE`/`PAUSED` run for a pack
  `UNAVAILABLE`, for use when that pack is retired/uninstalled. Never touches
  `learning_events` — recorded evidence for that run is untouched.
- `skipUnavailablePackEntries(packId)`: marks any still-`PENDING` practice
  queue entry for one of this pack's managed phrases `SKIPPED`, mirroring the
  lazy per-entry revalidation `PracticeRepository` already performs, applied
  eagerly at retirement time.
- `openPracticeReferences(packId): Int`: count of queue entries snapshotting
  this pack whose owning session is not yet `ENDED` — while positive, this
  pack's already-downloaded media must not be deleted, since a legacy queue
  snapshot stores an audio *path*, not a revision id, and cannot be
  re-resolved later.
- `openLessonReferences(packId, version): Int`: count of still-`ACTIVE`/
  `PAUSED` runs pinned to this exact `(packId, version)` — while positive,
  that specific version's media must be retained even if a newer version is
  installed.

Neither of the last two performs any deletion themselves — they are read-only
guards the installer is expected to consult before removing files.

Migration `MIGRATION_3_4` is purely additive: every v3 table/row is
untouched (see `Migration3To4Test`); `AppDatabase` registers `contentDao()`
and `learningDao()` alongside the existing DAOs.

## The durable lesson runner (`com.root.app.learning.LessonRunner`)

One runner backs all three teaching formats (guided conversation, listening/
story, pattern workshop) — every format is authored as a real sequence of the
shared `Activity` primitives above, never a single opaque enum placeholder.

```
LessonRunner(db: AppDatabase, contentSource: LessonContentSource = RoomLessonContentSource(db.contentDao()),
             assetAvailable: suspend (packId, packVersion, assetId) -> Boolean = { _, _, _ -> true })

suspend fun execute(command: LearningCommand): CommandResult
suspend fun currentState(runId: String): LessonRunState?   // read-only re-query, issues no command/ledger entry
suspend fun openRunState(packId: String, lessonId: String): LessonRunState?   // read-only; null if no ACTIVE/PAUSED run exists; never creates one
```

`LearningCommand` (each carries a caller-issued stable `commandId`):
`BeginOrResume(commandId, packId, packVersion, lessonId)`,
`SubmitResponse(commandId, runId, activityId, response: ActivityResponse)`,
`RevealSupport(commandId, runId, activityId)`,
`Advance(commandId, runId, expectedActivityId)`, `Pause(commandId, runId)`,
`Restart(commandId, packId, packVersion, lessonId)`.

`Advance.expectedActivityId` must match the run's actual current activity or
the command is rejected with `STEP_MISMATCH` (not silently applied) — this
closes a real gap where a delayed retry using a *distinct* `commandId` (so it
can't be caught by the idempotency ledger) could otherwise double-advance
past a step the caller never actually saw.

`ActivityResponse`: `Choice(choiceId)`, `OrderedTokens(occurrenceIds[])`,
`SelfReport(practiced)`, `Acknowledged` (display-only steps).

`CommandResult`: `Applied(state)`, `AlreadyApplied(state)` (a replayed
`commandId` with an identical payload — the command was **not** re-executed),
`Rejected(reason, message)` with
`reason ∈ {COMMAND_CONFLICT, RUN_NOT_FOUND, RUN_ENDED, LESSON_NOT_FOUND,
ACTIVITY_MISMATCH, STEP_MISMATCH, INVALID_RESPONSE, AUDIO_UNAVAILABLE}`.
`COMMAND_CONFLICT` is returned when the same `commandId` is reused with a
**different** payload hash — never silently re-applied.

`LessonRunState(runId, lessonId, packId, packVersion, lessonRevision, status,
currentActivityId?, currentActivityIndex, totalActivities,
requiredCompletedCount, requiredTotalCount, assistanceUsedActivityIds[],
currentActivityFeedback?, completed)` is the UI-facing snapshot — never a
raw Room entity. `currentActivityFeedback: ActivityFeedback?` (`response:
ActivityResponse, correct: Boolean?, kind: String`) is the last recorded
event for the *current* activity, if any — it lets the UI redraw exact
feedback state on resume (e.g. after process death mid-lesson) without
re-deriving it from raw events; `kind` mirrors the recorded
`LearningEventKind` name (`"EXPOSURE"`/`"ASSISTED"`/`"CHECKED"`/
`"SELF_REPORT"`). Null means the current activity has no recorded response
yet.

Behavior guaranteed by the implementation (see `LessonRunnerTest.kt`):

- **Idempotent commands**: every command commits (ledger entry + evidence +
  cursor/status) inside one Room transaction; a duplicate `commandId`
  short-circuits before any side effect re-runs.
- **Revision pin**: `BeginOrResume`/`Restart` snapshot `packVersion` and the
  lesson's `revision` into the run; the runner always resolves lesson content
  through the run's own pinned `(packId, packVersion)`, never "latest,"
  even if a newer pack version is installed mid-run.
- **Assistance persists before reveal**: `RevealSupport` durably records the
  activity id in `revealedActivityIds` before any answer support is shown by
  the caller; a later response to that activity is always recorded `ASSISTED`.
- **Retry is not independent evidence**: a second (or later) response to an
  activity that already has a recorded event is always `ASSISTED`, regardless
  of correctness; only a genuinely first, unassisted response can be `CHECKED`.
- **Pause/leave and Restart**: `Pause` durably parks an `ACTIVE` run; leaving
  a lesson mid-run is expected to call it. `Restart` always creates a **new**
  run — it never reopens/reuses a prior run — and marks any previously open
  run's `supersededByRunId`, preserving (not erasing) its events.
- **Completion, not a score**: `Advance` at the final step completes the run
  only once every `requiredActivityIds` has a recorded event (an
  acknowledgement/response — not necessarily a *correct* one); optional
  steps (`Reflection`, `SpeakingPrompt`) never gate completion.
- **No automatic recall evidence**: nothing in this package ever inserts an
  `AttemptEntity` or changes the recall scheduler. Linking a completed lesson
  to "Review these phrases" is explicitly out of this package's scope.
- **Listening requires real audio**: a `Listening` activity's comprehension
  response is rejected with `AUDIO_UNAVAILABLE` (not silently accepted) when
  `audioAssetId` is null or `assetAvailable(packId, packVersion, audioAssetId)`
  returns false. `audioAssetId` is nullable on the wire specifically so a
  lesson can be authored/validated before its recording exists — the runner,
  not the validator, is what actually gates evidence on real audio being
  present; a `development`-status pack is allowed to ship without it, a
  `published` one is not (see `PUBLISHED_LISTENING_MISSING_AUDIO`).
- **A required-but-unavailable `Listening` activity can still complete the
  lesson**: while `audioAssetId` is null (or the asset isn't actually
  installed), submitting `ActivityResponse.Acknowledged` for that activity is
  accepted as `EXPOSURE` evidence (identical treatment to `Reflection`/
  `DialogueTurn`'s acknowledgement) — this is what actually records an event
  for the activity. Any real `Choice`/`OrderedTokens` comprehension-answer
  attempt against unavailable audio is still rejected with
  `AUDIO_UNAVAILABLE` as above; only a plain acknowledgement of the honest
  "not available" state counts. Without this, a lesson whose sole/required
  activity is a permanently-unrecorded `Listening` step (e.g. the Shona pilot's
  `shona-pilot-lesson-listening`) could never be marked complete — this was a
  real bug, fixed and covered by
  `LessonRunnerTest.requiredListeningWithNullAudioAcceptsAcknowledgedAsExposureAndCanComplete`.

### Evaluators (`Evaluators.kt`, pure, no Room/Android)

```
ChoiceEvaluator.evaluate(task: ChoiceTask, chosenChoiceId: String): Boolean
OrderedTokenEvaluator.evaluate(task: OrderedTokenTask, submittedOccurrenceIds: List<String>): Boolean
```

Both are exact-match only against editorially specified accepted answers —
no punctuation/diacritic stripping, no free-text scoring. Token identity is
the occurrence id, not the token text, so a sentence that repeats a word is
fully supported.

## What is explicitly not implemented here

- No filesystem/network installer, no Supabase client, no download worker.
- No wiring into `RootRepository`/`RootViewModel`/UI/navigation, and no
  "Review these phrases" action.
- No JSON Schema files or Python pipeline code (coordinate field-for-field
  against this document).
- No real reviewed Shona/Dholuo/Swahili teaching content — all fixtures in
  this package's tests are structural/development-only, never claimed as
  reviewed or licensed.

## Tests

- Pure JVM (`app/src/test/kotlin/com/root/app/content/ContentValidationTest.kt`
  — 20 cases, `.../learning/EvaluatorsTest.kt` — 5 cases,
  `.../learning/LearningWireTest.kt` — 3 cases): DTO validation fixtures and
  evaluator/idempotency-hash contract checks, no Room. Verified green via
  `.\gradlew.bat :app:testDebugUnitTest` alongside the full
  `:app:compileDebugKotlin` for the whole app module.
- Instrumented (`app/src/androidTest/kotlin/com/root/app/data/Migration3To4Test.kt`,
  `.../data/ContentPersistenceTest.kt`, `.../learning/LessonRunnerTest.kt`): the
  real v3→v4 migration against a Room-built v3 baseline, install-job/managed-
  phrase persistence, and the full runner contract above against a real
  in-memory Room database. **Not run** in this environment — no
  emulator/device or `ANDROID_HOME`/`adb` was available; written and reviewed
  but unverified. Run with
  `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.root.app.data.Migration3To4Test" --tests "com.root.app.data.ContentPersistenceTest" --tests "com.root.app.learning.LessonRunnerTest"`
  once a device is attached.
