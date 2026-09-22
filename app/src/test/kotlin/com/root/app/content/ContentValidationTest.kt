package com.root.app.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for [ContentValidator] — no Android/Room dependency. Mirrors
 *  the valid/invalid fixture pairs the Python pipeline is expected to share. */
class ContentValidationTest {

    private fun choiceTask(
        id: String = "task-greet-reply",
        choices: List<Choice> = listOf(Choice("c1", "Ndiripo"), Choice("c2", "Kwaziwai")),
        accepted: List<String> = listOf("c1"),
    ) = ChoiceTask(id, "How do you reply?", choices, accepted)

    private fun orderedTask(
        id: String = "task-order",
        tokens: List<TokenOccurrence> = listOf(
            TokenOccurrence("t1", "Mangwanani"), TokenOccurrence("t2", "akanaka"),
        ),
        accepted: List<List<String>> = listOf(listOf("t1", "t2")),
    ) = OrderedTokenTask(id, "Put the greeting in order", tokens, accepted)

    private fun validManifest(): PackManifest = PackManifest(
        schemaVersion = 1,
        minReaderVersion = 1,
        id = "pack-shona-greetings",
        version = 1,
        language = ContentLanguage(id = "lang-sn", code = "sn", name = "Shona"),
        title = "Greetings",
        objective = "Greet someone and continue a short exchange",
        publication = PublicationStatus.DEVELOPMENT,
        phrases = listOf(
            ManagedPhrase(id = "phrase-1", prompt = "Hello", meaning = "Mhoro", audioAssetId = "asset-1"),
        ),
        assets = listOf(
            ManifestAsset(id = "asset-1", key = "audio/hello.ogg", sha256 = "a".repeat(64), bytes = 1024, mimeType = "audio/ogg"),
        ),
        lessons = listOf(
            Lesson(
                id = "lesson-1",
                revision = 1,
                title = "Greeting exchange",
                objective = "Greet and reply",
                format = LessonFormat.GUIDED_CONVERSATION,
                activities = listOf(
                    Activity.DialogueTurn(id = "act-1", speaker = "A", text = "Mhoro", audioAssetId = "asset-1", linkedPhraseId = "phrase-1"),
                    Activity.ChoiceActivity(id = "act-2", task = choiceTask()),
                ),
                requiredActivityIds = listOf("act-1", "act-2"),
                linkedPhraseIds = listOf("phrase-1"),
            ),
        ),
    )

    @Test fun validManifestPasses() {
        val result = ContentValidator.validate(validManifest())
        assertEquals(ValidationResult.Valid, result)
    }

    @Test fun rejectsUnsupportedSchemaVersion() {
        val manifest = validManifest().copy(schemaVersion = 99)
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "UNSUPPORTED_SCHEMA_VERSION" })
    }

    @Test fun rejectsMinReaderVersionAboveCurrent() {
        val manifest = validManifest().copy(minReaderVersion = CURRENT_READER_VERSION + 1)
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "UNSUPPORTED_MIN_READER_VERSION" })
    }

    @Test fun rejectsDuplicateActivityIdsAcrossLessons() {
        val base = validManifest()
        val duplicateLesson = base.lessons[0].copy(id = "lesson-2")
        val manifest = base.copy(lessons = base.lessons + duplicateLesson)
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "DUPLICATE_ACTIVITY_ID_GLOBAL" })
    }

    @Test fun rejectsUnresolvedAssetReference() {
        val base = validManifest()
        val manifest = base.copy(phrases = listOf(base.phrases[0].copy(audioAssetId = "missing-asset")))
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "UNRESOLVED_ASSET_REF" })
    }

    @Test fun rejectsUnresolvedLinkedPhrase() {
        val base = validManifest()
        val manifest = base.copy(lessons = listOf(base.lessons[0].copy(linkedPhraseIds = listOf("no-such-phrase"))))
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "UNRESOLVED_PHRASE_REF" })
    }

    @Test fun rejectsInvalidIdCharset() {
        val base = validManifest()
        val manifest = base.copy(id = "pack/with slash")
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "INVALID_ID" })
    }

    @Test fun rejectsUnsafeAssetKeyWithTraversal() {
        val base = validManifest()
        val manifest = base.copy(assets = listOf(base.assets[0].copy(key = "../../etc/passwd")))
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "UNSAFE_ASSET_KEY" })
    }

    @Test fun rejectsEmptyAcceptedChoiceIds() {
        val base = validManifest()
        val lesson = base.lessons[0]
        val badActivity = (lesson.activities[1] as Activity.ChoiceActivity).let {
            it.copy(task = it.task.copy(acceptedChoiceIds = emptyList()))
        }
        val manifest = base.copy(lessons = listOf(lesson.copy(activities = listOf(lesson.activities[0], badActivity))))
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "EMPTY_ACCEPTED_ANSWER" })
    }

    @Test fun rejectsAcceptedChoiceIdNotAmongChoices() {
        val base = validManifest()
        val lesson = base.lessons[0]
        val badActivity = (lesson.activities[1] as Activity.ChoiceActivity).let {
            it.copy(task = it.task.copy(acceptedChoiceIds = listOf("not-a-real-choice")))
        }
        val manifest = base.copy(lessons = listOf(lesson.copy(activities = listOf(lesson.activities[0], badActivity))))
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "INVALID_ANSWER_KEY" })
    }

    @Test fun rejectsOrderedTokenSequenceNotAPermutation() {
        val base = validManifest()
        val lesson = base.lessons[0]
        val orderedActivity = Activity.OrderedTokenActivity(id = "act-3", task = orderedTask(accepted = listOf(listOf("t1", "t1"))))
        val manifest = base.copy(
            lessons = listOf(
                lesson.copy(
                    activities = lesson.activities + orderedActivity,
                    requiredActivityIds = lesson.requiredActivityIds + "act-3",
                ),
            ),
        )
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "INVALID_ANSWER_KEY" })
    }

    @Test fun rejectsCyclicPrerequisites() {
        val base = validManifest()
        val lessonA = base.lessons[0].copy(id = "lesson-a", prerequisiteLessonIds = listOf("lesson-b"))
        val lessonB = base.lessons[0].copy(
            id = "lesson-b",
            prerequisiteLessonIds = listOf("lesson-a"),
            activities = base.lessons[0].activities.map {
                when (it) {
                    is Activity.DialogueTurn -> it.copy(id = "act-1b")
                    is Activity.ChoiceActivity -> it.copy(id = "act-2b")
                    else -> it
                }
            },
            requiredActivityIds = listOf("act-1b", "act-2b"),
        )
        val manifest = base.copy(lessons = listOf(lessonA, lessonB))
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "CYCLIC_PREREQUISITES" })
    }

    @Test fun rejectsMissingListeningAudioAssetReference() {
        val base = validManifest()
        val listening = Activity.Listening(
            id = "act-listen",
            audioAssetId = "missing-audio",
            comprehension = EvaluableTask.Choice(choiceTask(id = "task-listen")),
        )
        val manifest = base.copy(
            lessons = listOf(
                base.lessons[0].copy(
                    activities = base.lessons[0].activities + listening,
                    requiredActivityIds = base.lessons[0].requiredActivityIds + "act-listen",
                ),
            ),
        )
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "UNRESOLVED_ASSET_REF" })
    }

    @Test fun allowsNullListeningAudioOnDevelopmentPack() {
        val base = validManifest()
        val listening = Activity.Listening(
            id = "act-listen",
            audioAssetId = null,
            unavailableReason = "No consenting recording obtained yet",
            comprehension = EvaluableTask.Choice(choiceTask(id = "task-listen")),
        )
        val manifest = base.copy(
            publication = PublicationStatus.DEVELOPMENT,
            lessons = listOf(
                base.lessons[0].copy(
                    activities = base.lessons[0].activities + listening,
                    requiredActivityIds = base.lessons[0].requiredActivityIds + "act-listen",
                ),
            ),
        )
        assertEquals(ValidationResult.Valid, ContentValidator.validate(manifest))
    }

    @Test fun rejectsNullListeningAudioOnPublishedPack() {
        val base = validManifest()
        val listening = Activity.Listening(
            id = "act-listen",
            audioAssetId = null,
            unavailableReason = "No consenting recording obtained yet",
            comprehension = EvaluableTask.Choice(choiceTask(id = "task-listen")),
        )
        val manifest = base.copy(
            publication = PublicationStatus.PUBLISHED,
            lessons = listOf(
                base.lessons[0].copy(
                    activities = base.lessons[0].activities + listening,
                    requiredActivityIds = base.lessons[0].requiredActivityIds + "act-listen",
                ),
            ),
        )
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "PUBLISHED_LISTENING_MISSING_AUDIO" })
    }

    @Test fun rejectsNullListeningAudioWithoutUnavailableReason() {
        val base = validManifest()
        val listening = Activity.Listening(
            id = "act-listen",
            audioAssetId = null,
            unavailableReason = null,
            comprehension = EvaluableTask.Choice(choiceTask(id = "task-listen")),
        )
        val manifest = base.copy(
            publication = PublicationStatus.DEVELOPMENT,
            lessons = listOf(
                base.lessons[0].copy(
                    activities = base.lessons[0].activities + listening,
                    requiredActivityIds = base.lessons[0].requiredActivityIds + "act-listen",
                ),
            ),
        )
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "LISTENING_MISSING_UNAVAILABLE_REASON" })
    }

    @Test fun rejectsTooManyAssets() {
        val base = validManifest()
        val many = (1..300).map { i ->
            ManifestAsset(id = "asset-extra-$i", key = "audio/extra-$i.ogg", sha256 = "b".repeat(64), bytes = 10, mimeType = "audio/ogg")
        }
        val manifest = base.copy(assets = base.assets + many)
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "TOO_MANY_ASSETS" })
    }

    @Test fun rejectsAssetOverPerAssetLimit() {
        val base = validManifest()
        val manifest = base.copy(assets = listOf(base.assets[0].copy(bytes = ContentLimits.MAX_ASSET_BYTES + 1)))
        val result = ContentValidator.validate(manifest) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "ASSET_TOO_LARGE" })
    }

    @Test fun rejectsManifestOverByteBudget() {
        val result = ContentValidator.validate(validManifest(), manifestBytes = ContentLimits.MANIFEST_MAX_BYTES + 1) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "MANIFEST_TOO_LARGE" })
    }

    @Test fun catalogOverBudgetRejected() {
        val result = validateCatalogBytes(ContentLimits.CATALOG_MAX_BYTES + 1) as ValidationResult.Invalid
        assertTrue(result.errors.any { it.code == "CATALOG_TOO_LARGE" })
    }

    @Test fun roundTripsThroughContentJson() {
        val manifest = validManifest()
        val json = ContentJson.encodeToString(PackManifest.serializer(), manifest)
        val decoded = ContentJson.decodeFromString(PackManifest.serializer(), json)
        assertEquals(manifest, decoded)
        assertEquals(ValidationResult.Valid, ContentValidator.validate(decoded))
    }
}
