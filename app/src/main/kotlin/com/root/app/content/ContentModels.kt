package com.root.app.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned wire contracts for downloadable teaching content, shared (by shape,
 * not by code) with the Python editorial/pipeline tooling under `tools/content/`
 * and the JSON Schemas under `content/schemas/`. See docs/TEACHING_CONTRACTS.md
 * for the frozen API summary.
 *
 * These types describe *what a pack contains*, not how it is installed or
 * displayed. [ContentValidator] enforces every invariant JSON Schema cannot
 * express on its own (duplicate IDs, dangling references, unsafe paths, size
 * budgets). Room persistence lives in `com.root.app.data`; the durable lesson
 * runner lives in `com.root.app.learning`.
 *
 * Distinguish three numbers everywhere in this file:
 * - [PackManifest.schemaVersion]: the wire format version this JSON was written
 *   against.
 * - [PackManifest.minReaderVersion]: the oldest reader (this app's
 *   [CURRENT_READER_VERSION]) able to safely load it. A manifest whose
 *   min-reader-version exceeds what this app understands must be rejected, not
 *   best-effort parsed.
 * - [PackManifest.version]: the pack's own immutable content revision — bumped
 *   editorially every time the pack's teaching content changes.
 */

/** The version of this wire contract this build of Root can read. Any manifest
 *  declaring a higher [PackManifest.minReaderVersion] must be rejected outright. */
const val CURRENT_READER_VERSION: Int = 1

/** The schema versions this build knows how to fully validate/evaluate. */
val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1)

/** Constrained identifier charset shared by every stable ID in this contract:
 *  never arbitrary text, never a filesystem path. Must start with an
 *  alphanumeric character (never a bare `.`/`_`/`-`) and is bounded to
 *  [MAX_ID_LENGTH] chars — this exactly mirrors the transport/filesystem
 *  policy [CatalogValidation] already enforces for catalog pack IDs, so a
 *  manifest's own `id`/`phrases[].id`/etc. can never smuggle in something
 *  the installer's transport layer would reject. */
val ID_PATTERN: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

/** Maximum length (inclusive) of any [ID_PATTERN]-constrained identifier. */
const val MAX_ID_LENGTH: Int = 120

/** A relative asset object key's allowed charset: forward-slash separated path
 *  segments, each drawn from [ID_PATTERN]'s per-character set. This alone
 *  does not forbid `.`/`..` segments or enforce a length bound — see
 *  [isSafeAssetKey], the actual safety check used by [ContentValidator]. */
val ASSET_KEY_PATTERN: Regex = Regex("^[A-Za-z0-9._/-]+$")

/** Maximum length (inclusive) of a relative asset key, matching the
 *  transport/filesystem policy's 512-char bound. */
const val MAX_ASSET_KEY_LENGTH: Int = 512

/**
 * The full relative-asset-key safety check: right charset, bounded length, no
 * leading/trailing/doubled slash (no empty segments), and no `.`/`..`
 * segments — i.e. never a path that could escape the pack's own asset
 * directory. Deliberately never accepts percent-encoding, a query string, a
 * colon, or a backslash, since none of those characters are in
 * [ASSET_KEY_PATTERN]'s charset to begin with.
 */
fun isSafeAssetKey(key: String): Boolean {
    if (key.isEmpty() || key.length > MAX_ASSET_KEY_LENGTH) return false
    if (!ASSET_KEY_PATTERN.matches(key)) return false
    return key.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/** 64 lowercase hex characters — the exact SHA-256 digest shape required by
 *  every asset/manifest checksum. */
val SHA256_HEX_PATTERN: Regex = Regex("^[a-f0-9]{64}$")

/** A loose but real BCP-47-shaped code: primary subtag plus optional
 *  hyphen-separated extension subtags. Rejects free text like "Shona dialect". */
val LANGUAGE_CODE_PATTERN: Regex = Regex("^[a-zA-Z]{2,3}(-[A-Za-z0-9]{2,8})*$")

val ContentJson: Json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = false
    encodeDefaults = true
    prettyPrint = false
}

/** A stable content-language identity: never inferred from a display string.
 *  [id] is Root's own stable key (survives a code correction); [code] is the
 *  canonical BCP-47-shaped code; [name] is display-only. */
@Serializable
data class ContentLanguage(
    val id: String,
    val code: String,
    val name: String,
    val variety: String? = null,
    val script: String? = null,
)

@Serializable
enum class PublicationStatus {
    @SerialName("development") DEVELOPMENT,
    @SerialName("published") PUBLISHED,
}

/** Public, export-safe attribution. Never contains private consent records or
 *  contributor PII — those stay in `content/editorial/` outside app artifacts. */
@Serializable
data class Credits(
    val text: String,
    val license: String? = null,
    val sourceUrl: String? = null,
)

/** One packaged media object. [key] is this pack's own relative object key
 *  (see [ASSET_KEY_PATTERN]) — never a filesystem path outside the pack.
 *  [sampleRateHz] is optional audio QA metadata; it is never validated or
 *  consumed by the runner, only carried through for pipeline/audit tooling. */
@Serializable
data class ManifestAsset(
    val id: String,
    val key: String,
    val sha256: String,
    val bytes: Long,
    val mimeType: String,
    val durationMs: Long? = null,
    val sampleRateHz: Int? = null,
    val credits: Credits? = null,
)

/** A managed recall phrase carried by this pack. Installing it creates (or
 *  reuses) a [com.root.app.data.PhraseEntity] whose recall identity is owned by
 *  this pack — see [com.root.app.data.ManagedPhraseEntity]. [credits] is
 *  per-phrase attribution for packs that mix sources/licenses within one pack;
 *  it is display-only, never validated beyond [Credits]'s own shape. Detailed
 *  source/release provenance (corpus id, release id, consent state) is
 *  intentionally NOT part of this wire type — that stays in the pipeline's own
 *  `content/editorial/` records, never shipped to the app.
 *
 *  **Field direction (do not invert):** [prompt] is the *target-language*
 *  text being taught (e.g. the Shona phrase); [meaning] is the recall prompt
 *  shown in the learner's *known* language (e.g. its English gloss). This is
 *  the opposite naming convention from [com.root.app.data.PhraseEntity], whose
 *  own `prompt`/`answer` are named from the recall-card's point of view (the
 *  known-language side is shown first, so it is called `prompt` there; the
 *  target-language side is revealed as the `answer`). The installer's
 *  projection is therefore `PhraseEntity(prompt = wire.meaning, answer =
 *  wire.prompt)` — swapping which wire field feeds which entity column.
 *  Getting this backwards silently inverts every recall card's display
 *  (showing the target-language text first and the known-language gloss as
 *  the "answer"). */
@Serializable
data class ManagedPhrase(
    val id: String,
    /** The target-language phrase being taught, e.g. "Mhoro". Maps to
     *  [com.root.app.data.PhraseEntity.answer] on install — never `.prompt`. */
    val prompt: String,
    /** The recall prompt in the learner's known language, e.g. "Hello (one
     *  person)". Maps to [com.root.app.data.PhraseEntity.prompt] on install —
     *  never `.answer`. */
    val meaning: String,
    val audioAssetId: String? = null,
    val register: String? = null,
    val context: String? = null,
    val credits: Credits? = null,
)


/** One selectable option in a [ChoiceTask]. */
@Serializable
data class Choice(
    val id: String,
    val text: String,
    val audioAssetId: String? = null,
)

/** A single-response selection task. [acceptedChoiceIds] is the editorially
 *  specified accepted set (supports intentional synonym acceptance without any
 *  free-text matching). */
@Serializable
data class ChoiceTask(
    val id: String,
    val prompt: String,
    val choices: List<Choice>,
    val acceptedChoiceIds: List<String>,
    val feedbackCorrect: String? = null,
    val feedbackIncorrect: String? = null,
)

/** One occurrence of a word/phrase token in an ordered-construction task.
 *  [occurrenceId] (not [text]) is the identity checked against
 *  [OrderedTokenTask.acceptedSequences], so a repeated word is fully supported. */
@Serializable
data class TokenOccurrence(
    val occurrenceId: String,
    val text: String,
)

/** An ordered-token construction task. Every accepted sequence in
 *  [acceptedSequences] must be a permutation of [tokens]' occurrence IDs. */
@Serializable
data class OrderedTokenTask(
    val id: String,
    val prompt: String,
    val tokens: List<TokenOccurrence>,
    val acceptedSequences: List<List<String>>,
    val feedbackCorrect: String? = null,
    val feedbackIncorrect: String? = null,
)

/** The two machine-checkable task shapes shared by every teaching format —
 *  never a free-text matcher (see plan §Teaching implementation). */
@Serializable
sealed interface EvaluableTask {
    val id: String

    @Serializable
    @SerialName("choice_task")
    data class Choice(val task: ChoiceTask) : EvaluableTask {
        override val id: String get() = task.id
    }

    @Serializable
    @SerialName("ordered_token_task")
    data class OrderedTokens(val task: OrderedTokenTask) : EvaluableTask {
        override val id: String get() = task.id
    }
}

/**
 * One runnable step inside a [Lesson]. Every teaching format (guided
 * conversation, listening/story, pattern workshop) is authored as a real
 * sequence of these primitives — never a single opaque "format" placeholder.
 */
@Serializable
sealed interface Activity {
    val id: String

    /** Display-only contextual turn: a line of dialogue, narration, or setup. */
    @Serializable
    @SerialName("dialogue_turn")
    data class DialogueTurn(
        override val id: String,
        val speaker: String,
        val text: String,
        val translation: String? = null,
        val audioAssetId: String? = null,
        val linkedPhraseId: String? = null,
    ) : Activity

    /** A reviewed example plus a language-specific explanation — the pattern
     *  workshop's non-templated grammar note. */
    @Serializable
    @SerialName("pattern_explanation")
    data class PatternExplanation(
        override val id: String,
        val explanation: String,
        val examples: List<String>,
    ) : Activity

    /** Single-response selection: the guided reply, changed-context reply, or a
     *  comprehension check. */
    @Serializable
    @SerialName("choice")
    data class ChoiceActivity(
        override val id: String,
        val task: ChoiceTask,
        val assistanceHint: String? = null,
    ) : Activity

    /** Ordered-token construction: the guided/changed-context construction task. */
    @Serializable
    @SerialName("ordered_tokens")
    data class OrderedTokenActivity(
        override val id: String,
        val task: OrderedTokenTask,
        val assistanceHint: String? = null,
    ) : Activity

    /** Authentic audio/transcript encounter. [audioAssetId] is nullable: a
     *  lesson may be authored (and validated as [ValidationResult.Valid])
     *  before its recording exists — never a fabricated placeholder asset.
     *  While [audioAssetId] is null or the referenced clip is not actually
     *  installed on-device, [LessonRunner] refuses any [comprehension]-answer
     *  submission with `AUDIO_UNAVAILABLE` (it never silently substitutes
     *  transcript-only evidence for a real comprehension check). A plain
     *  acknowledgement of that honest unavailable state is still accepted as
     *  an `EXPOSURE` event (so the learner isn't stuck on this single step and
     *  can Advance past it to explore other activities/lessons), but that
     *  acknowledgement never counts toward this lesson's required-activity
     *  completion when this activity is required — the lesson as a whole
     *  stays incomplete until real playable audio exists and a real
     *  [comprehension] attempt is submitted and evaluated. [comprehension]
     *  is the machine-checked task that provides that real graded evidence. */
    @Serializable
    @SerialName("listening")
    data class Listening(
        override val id: String,
        val audioAssetId: String? = null,
        /** Human-readable reason shown to the learner when [audioAssetId] is null (e.g. no consenting recording yet). Required whenever audioAssetId is null. */
        val unavailableReason: String? = null,
        val transcript: String? = null,
        val translation: String? = null,
        val comprehension: EvaluableTask,
    ) : Activity

    /** Free reflection prompt: no accepted answer, always optional evidence. */
    @Serializable
    @SerialName("reflection")
    data class Reflection(
        override val id: String,
        val prompt: String,
    ) : Activity

    /** Optional self-assessed speaking. Never blocks lesson completion and never
     *  produces a Got it recall attempt — see [com.root.app.learning.LearningEventKind.SELF_REPORT]. */
    @Serializable
    @SerialName("speaking_prompt")
    data class SpeakingPrompt(
        override val id: String,
        val prompt: String,
    ) : Activity
}

@Serializable
enum class LessonFormat {
    @SerialName("guided_conversation") GUIDED_CONVERSATION,
    @SerialName("listening") LISTENING,
    @SerialName("pattern_workshop") PATTERN_WORKSHOP,
}

/** One teaching unit's lesson. [requiredActivityIds] defines completion —
 *  completion is "required steps done", never a score/percentage. Optional
 *  speaking/reflection activities are never required. */
@Serializable
data class Lesson(
    val id: String,
    val revision: Int,
    val title: String,
    val objective: String,
    val format: LessonFormat,
    val activities: List<Activity>,
    val requiredActivityIds: List<String>,
    val linkedPhraseIds: List<String> = emptyList(),
    val prerequisiteLessonIds: List<String> = emptyList(),
)

/**
 * The frozen wire root: one immutable pack version's full teaching content.
 * See docs/TEACHING_CONTRACTS.md for the authoritative field-by-field contract.
 */
@Serializable
data class PackManifest(
    val schemaVersion: Int,
    val minReaderVersion: Int,
    val id: String,
    val version: Int,
    val language: ContentLanguage,
    val title: String,
    val objective: String,
    val publication: PublicationStatus,
    val phrases: List<ManagedPhrase>,
    val lessons: List<Lesson>,
    val assets: List<ManifestAsset>,
    val credits: List<Credits> = emptyList(),
)
