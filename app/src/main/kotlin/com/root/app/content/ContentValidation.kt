package com.root.app.content

/** One semantic validation failure. [path] is a dotted/bracketed pointer into
 *  the manifest (e.g. `lessons[2].activities[0]`) to make failures actionable. */
data class ValidationError(val code: String, val path: String, val message: String)

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val errors: List<ValidationError>) : ValidationResult
}

/**
 * Semantic validation that JSON Schema alone cannot express: duplicate IDs,
 * dangling references, unsafe asset paths, invalid answer keys, cyclic lesson
 * prerequisites, and size budgets. Kotlin and the Python pipeline run the same
 * fixtures against equivalent rules — see docs/TEACHING_CONTRACTS.md.
 *
 * Never silently accepts an invalid manifest: every problem is collected and
 * returned rather than the validator stopping at the first one, so a single
 * fixture run reports the full defect list.
 */
object ContentValidator {

    fun validate(manifest: PackManifest, manifestBytes: Long? = null): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        if (manifest.schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            errors += ValidationError(
                "UNSUPPORTED_SCHEMA_VERSION", "schemaVersion",
                "schemaVersion ${manifest.schemaVersion} is not one of $SUPPORTED_SCHEMA_VERSIONS",
            )
        }
        if (manifest.minReaderVersion > CURRENT_READER_VERSION) {
            errors += ValidationError(
                "UNSUPPORTED_MIN_READER_VERSION", "minReaderVersion",
                "minReaderVersion ${manifest.minReaderVersion} exceeds this reader's $CURRENT_READER_VERSION",
            )
        }
        requireId(manifest.id, "id", errors)
        if (manifest.version <= 0) {
            errors += ValidationError("INVALID_VERSION", "version", "version must be positive")
        }

        requireId(manifest.language.id, "language.id", errors)
        if (!LANGUAGE_CODE_PATTERN.matches(manifest.language.code)) {
            errors += ValidationError(
                "INVALID_LANGUAGE_CODE", "language.code",
                "'${manifest.language.code}' is not a valid BCP-47-shaped code",
            )
        }

        if (manifestBytes != null && manifestBytes > ContentLimits.MANIFEST_MAX_BYTES) {
            errors += ValidationError(
                "MANIFEST_TOO_LARGE", "$", "manifest is $manifestBytes bytes, limit is ${ContentLimits.MANIFEST_MAX_BYTES}",
            )
        }

        val assetIds = mutableSetOf<String>()
        var totalAssetBytes = 0L
        if (manifest.assets.size > ContentLimits.MAX_ASSETS_PER_PACK) {
            errors += ValidationError(
                "TOO_MANY_ASSETS", "assets",
                "${manifest.assets.size} assets exceeds limit ${ContentLimits.MAX_ASSETS_PER_PACK}",
            )
        }
        manifest.assets.forEachIndexed { i, asset ->
            val path = "assets[$i]"
            requireId(asset.id, "$path.id", errors)
            if (!assetIds.add(asset.id)) {
                errors += ValidationError("DUPLICATE_ASSET_ID", "$path.id", "duplicate asset id '${asset.id}'")
            }
            if (!isSafeAssetKey(asset.key)) {
                errors += ValidationError("UNSAFE_ASSET_KEY", "$path.key", "'${asset.key}' is not a safe relative asset key")
            }
            if (asset.bytes <= 0) {
                errors += ValidationError("INVALID_ASSET_SIZE", "$path.bytes", "asset bytes must be positive")
            } else if (asset.bytes > ContentLimits.MAX_ASSET_BYTES) {
                errors += ValidationError(
                    "ASSET_TOO_LARGE", "$path.bytes",
                    "asset '${asset.id}' is ${asset.bytes} bytes, limit is ${ContentLimits.MAX_ASSET_BYTES}",
                )
            }
            totalAssetBytes += asset.bytes.coerceAtLeast(0)
            if (!SHA256_HEX_PATTERN.matches(asset.sha256)) {
                errors += ValidationError("INVALID_SHA256", "$path.sha256", "sha256 must be 64 lowercase hex chars")
            }
        }
        if (totalAssetBytes > ContentLimits.MAX_TOTAL_ASSET_BYTES) {
            errors += ValidationError(
                "TOTAL_ASSET_BYTES_EXCEEDED", "assets",
                "total asset bytes $totalAssetBytes exceeds limit ${ContentLimits.MAX_TOTAL_ASSET_BYTES}",
            )
        }

        val phraseIds = mutableSetOf<String>()
        manifest.phrases.forEachIndexed { i, phrase ->
            val path = "phrases[$i]"
            requireId(phrase.id, "$path.id", errors)
            if (!phraseIds.add(phrase.id)) {
                errors += ValidationError("DUPLICATE_PHRASE_ID", "$path.id", "duplicate phrase id '${phrase.id}'")
            }
            if (phrase.prompt.isBlank()) errors += ValidationError("BLANK_PHRASE_PROMPT", "$path.prompt", "prompt is blank")
            if (phrase.meaning.isBlank()) errors += ValidationError("BLANK_PHRASE_MEANING", "$path.meaning", "meaning is blank")
            phrase.audioAssetId?.let { ref ->
                if (ref !in assetIds) {
                    errors += ValidationError("UNRESOLVED_ASSET_REF", "$path.audioAssetId", "phrase references unknown asset '$ref'")
                }
            }
        }

        val lessonIds = mutableSetOf<String>()
        val globalActivityIds = mutableSetOf<String>()
        manifest.lessons.forEachIndexed { li, lesson ->
            val lpath = "lessons[$li]"
            requireId(lesson.id, "$lpath.id", errors)
            if (!lessonIds.add(lesson.id)) {
                errors += ValidationError("DUPLICATE_LESSON_ID", "$lpath.id", "duplicate lesson id '${lesson.id}'")
            }
            if (lesson.revision <= 0) {
                errors += ValidationError("INVALID_LESSON_REVISION", "$lpath.revision", "revision must be positive")
            }
            if (lesson.activities.isEmpty()) {
                errors += ValidationError("EMPTY_LESSON", "$lpath.activities", "lesson has no activities")
            }
            val activityIds = mutableSetOf<String>()
            lesson.activities.forEachIndexed { ai, activity ->
                val apath = "$lpath.activities[$ai]"
                requireId(activity.id, "$apath.id", errors)
                if (!activityIds.add(activity.id)) {
                    errors += ValidationError("DUPLICATE_ACTIVITY_ID", "$apath.id", "duplicate activity id '${activity.id}' in lesson '${lesson.id}'")
                }
                if (!globalActivityIds.add(activity.id)) {
                    errors += ValidationError("DUPLICATE_ACTIVITY_ID_GLOBAL", "$apath.id", "activity id '${activity.id}' reused across lessons")
                }
                validateActivity(activity, apath, assetIds, phraseIds, errors)
                if (manifest.publication == PublicationStatus.PUBLISHED && activity is Activity.Listening && activity.audioAssetId == null) {
                    errors += ValidationError(
                        "PUBLISHED_LISTENING_MISSING_AUDIO", "$apath.audioAssetId",
                        "listening activity '${activity.id}' has no audioAssetId but the pack is published; audio must be recorded before publish",
                    )
                }
            }
            lesson.requiredActivityIds.forEach { req ->
                if (req !in activityIds) {
                    errors += ValidationError("UNRESOLVED_REQUIRED_ACTIVITY", "$lpath.requiredActivityIds", "required activity '$req' not found in lesson '${lesson.id}'")
                }
            }
            if (lesson.requiredActivityIds.isEmpty()) {
                errors += ValidationError("NO_REQUIRED_ACTIVITIES", "$lpath.requiredActivityIds", "lesson '${lesson.id}' has no required activities")
            }
            lesson.linkedPhraseIds.forEach { ref ->
                if (ref !in phraseIds) {
                    errors += ValidationError("UNRESOLVED_PHRASE_REF", "$lpath.linkedPhraseIds", "lesson '${lesson.id}' references unknown phrase '$ref'")
                }
            }
        }

        // Soft prerequisites must reference real sibling lessons and must not cycle.
        manifest.lessons.forEachIndexed { li, lesson ->
            lesson.prerequisiteLessonIds.forEach { prereq ->
                if (prereq !in lessonIds) {
                    errors += ValidationError(
                        "UNRESOLVED_PREREQUISITE", "lessons[$li].prerequisiteLessonIds",
                        "lesson '${lesson.id}' has unknown prerequisite '$prereq'",
                    )
                }
            }
        }
        findPrerequisiteCycle(manifest.lessons)?.let { cycle ->
            errors += ValidationError("CYCLIC_PREREQUISITES", "lessons", "cyclic lesson prerequisites: ${cycle.joinToString(" -> ")}")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
    private fun validateActivity(
        activity: Activity,
        path: String,
        assetIds: Set<String>,
        phraseIds: Set<String>,
        errors: MutableList<ValidationError>,
    ) {
        fun checkAsset(ref: String?, field: String) {
            if (ref != null && ref !in assetIds) {
                errors += ValidationError("UNRESOLVED_ASSET_REF", "$path.$field", "references unknown asset '$ref'")
            }
        }
        fun checkPhrase(ref: String?, field: String) {
            if (ref != null && ref !in phraseIds) {
                errors += ValidationError("UNRESOLVED_PHRASE_REF", "$path.$field", "references unknown phrase '$ref'")
            }
        }
        when (activity) {
            is Activity.DialogueTurn -> {
                checkAsset(activity.audioAssetId, "audioAssetId")
                checkPhrase(activity.linkedPhraseId, "linkedPhraseId")
                if (activity.text.isBlank()) errors += ValidationError("BLANK_TEXT", "$path.text", "dialogue turn text is blank")
            }
            is Activity.PatternExplanation -> {
                if (activity.examples.isEmpty()) {
                    errors += ValidationError("NO_EXAMPLES", "$path.examples", "pattern explanation has no examples")
                }
            }
            is Activity.ChoiceActivity -> validateChoiceTask(activity.task, "$path.task", errors)
            is Activity.OrderedTokenActivity -> validateOrderedTokenTask(activity.task, "$path.task", errors)
            is Activity.Listening -> {
                checkAsset(activity.audioAssetId, "audioAssetId")
                if (activity.audioAssetId == null && activity.unavailableReason.isNullOrBlank()) {
                    errors += ValidationError(
                        "LISTENING_MISSING_UNAVAILABLE_REASON", "$path.unavailableReason",
                        "listening activity '${activity.id}' has no audioAssetId and must supply a non-blank unavailableReason for an honest UI",
                    )
                }
                when (val c = activity.comprehension) {
                    is EvaluableTask.Choice -> validateChoiceTask(c.task, "$path.comprehension.task", errors)
                    is EvaluableTask.OrderedTokens -> validateOrderedTokenTask(c.task, "$path.comprehension.task", errors)
                }
            }
            is Activity.Reflection -> Unit
            is Activity.SpeakingPrompt -> Unit
        }
    }

    private fun validateChoiceTask(task: ChoiceTask, path: String, errors: MutableList<ValidationError>) {
        requireId(task.id, "$path.id", errors)
        val ids = mutableSetOf<String>()
        task.choices.forEachIndexed { i, choice ->
            requireId(choice.id, "$path.choices[$i].id", errors)
            if (!ids.add(choice.id)) {
                errors += ValidationError("DUPLICATE_CHOICE_ID", "$path.choices[$i].id", "duplicate choice id '${choice.id}'")
            }
        }
        if (task.acceptedChoiceIds.isEmpty()) {
            errors += ValidationError("EMPTY_ACCEPTED_ANSWER", "$path.acceptedChoiceIds", "no accepted choice ids")
        }
        task.acceptedChoiceIds.forEach { accepted ->
            if (accepted !in ids) {
                errors += ValidationError("INVALID_ANSWER_KEY", "$path.acceptedChoiceIds", "accepted choice '$accepted' is not one of this task's choices")
            }
        }
    }

    private fun validateOrderedTokenTask(task: OrderedTokenTask, path: String, errors: MutableList<ValidationError>) {
        requireId(task.id, "$path.id", errors)
        val occurrenceIds = mutableSetOf<String>()
        task.tokens.forEachIndexed { i, tok ->
            requireId(tok.occurrenceId, "$path.tokens[$i].occurrenceId", errors)
            if (!occurrenceIds.add(tok.occurrenceId)) {
                errors += ValidationError("DUPLICATE_TOKEN_OCCURRENCE_ID", "$path.tokens[$i].occurrenceId", "duplicate occurrence id '${tok.occurrenceId}'")
            }
        }
        if (task.acceptedSequences.isEmpty()) {
            errors += ValidationError("EMPTY_ACCEPTED_ANSWER", "$path.acceptedSequences", "no accepted token sequences")
        }
        task.acceptedSequences.forEachIndexed { si, sequence ->
            val spath = "$path.acceptedSequences[$si]"
            if (sequence.size != occurrenceIds.size || sequence.toSet() != occurrenceIds) {
                errors += ValidationError(
                    "INVALID_ANSWER_KEY", spath,
                    "accepted sequence is not a permutation of this task's token occurrence ids",
                )
            }
        }
    }

    /** DFS cycle detection over the prerequisite graph; returns the first cycle
     *  found as a lesson-id path, or null if the graph is acyclic. */
    private fun findPrerequisiteCycle(lessons: List<Lesson>): List<String>? {
        val byId = lessons.associateBy { it.id }
        val state = HashMap<String, Int>() // 0 unvisited, 1 in-progress, 2 done
        val stack = mutableListOf<String>()

        fun dfs(id: String): List<String>? {
            when (state[id]) {
                1 -> return stack.subList(stack.indexOf(id), stack.size) + id
                2 -> return null
                else -> Unit
            }
            state[id] = 1
            stack.add(id)
            val lesson = byId[id]
            if (lesson != null) {
                for (dep in lesson.prerequisiteLessonIds) {
                    if (dep !in byId) continue // reported separately as unresolved
                    val cycle = dfs(dep)
                    if (cycle != null) return cycle
                }
            }
            stack.removeAt(stack.size - 1)
            state[id] = 2
            return null
        }

        for (lesson in lessons) {
            if (state[lesson.id] == null) {
                val cycle = dfs(lesson.id)
                if (cycle != null) return cycle
            }
        }
        return null
    }

    private fun requireId(id: String, path: String, errors: MutableList<ValidationError>) {
        if (id.length !in 1..MAX_ID_LENGTH || !ID_PATTERN.matches(id)) {
            errors += ValidationError(
                "INVALID_ID", path,
                "'$id' does not match required id charset ${ID_PATTERN.pattern} (length 1..$MAX_ID_LENGTH)",
            )
        }
    }
}

/** Validates a serialized catalog document's raw size budget. Structural
 *  validation of [Catalog] itself is intentionally minimal — the catalog is a
 *  refreshable summary, not the durable content boundary; [PackManifest] is. */
fun validateCatalogBytes(bytes: Long): ValidationResult =
    if (bytes > ContentLimits.CATALOG_MAX_BYTES) {
        ValidationResult.Invalid(
            listOf(ValidationError("CATALOG_TOO_LARGE", "$", "catalog is $bytes bytes, limit is ${ContentLimits.CATALOG_MAX_BYTES}")),
        )
    } else {
        ValidationResult.Valid
    }
