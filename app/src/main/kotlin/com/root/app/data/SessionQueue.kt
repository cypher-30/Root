package com.root.app.data

/**
 * A finite session. Duplicate input IDs keep their first occurrence; a missed
 * phrase can return once, and only a distinct GOT_IT contributes to growth.
 * Restore by constructing from initialPhrases and replaying ratingHistory.
 */
class SessionQueue(
    initial: List<PhraseEntity>,
    private val retryLimit: Int = 1,
    initialLimit: Int = 8,
) {
    init {
        require(retryLimit in 0..1) { "A session allows zero or one retry per phrase." }
        require(initialLimit >= 0) { "The initial phrase limit cannot be negative." }
    }

    private val initialSnapshot = initial.distinctBy { it.id }.take(initialLimit)
    private val queue = initialSnapshot.toMutableList()
    private val retriedIds = mutableSetOf<String>()
    private val correctIds = mutableSetOf<String>()
    private val reviewedIds = mutableSetOf<String>()
    private val history = mutableListOf<ConfidenceLevel>()

    val current: PhraseEntity? get() = queue.getOrNull(ratedCount)
    val isComplete: Boolean get() = current == null
    val correctCount: Int get() = correctIds.size
    val ratedCount: Int get() = history.size
    val reviewedCount: Int get() = reviewedIds.size
    val initialPhrases: List<PhraseEntity> get() = initialSnapshot.toList()
    val remainingPhrases: List<PhraseEntity> get() = queue.drop(ratedCount)
    val ratingHistory: List<ConfidenceLevel> get() = history.toList()

    fun rate(confidence: ConfidenceLevel) {
        val phrase = checkNotNull(current) { "Cannot rate a completed session." }
        when (confidence) {
            ConfidenceLevel.MISSED ->
                if (retryLimit == 1 && retriedIds.add(phrase.id)) queue.add(phrase)
            ConfidenceLevel.CLOSE -> Unit
            ConfidenceLevel.GOT_IT -> correctIds.add(phrase.id)
        }
        reviewedIds.add(phrase.id)
        history.add(confidence)
    }
}
