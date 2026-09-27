package com.root.app.data

/**
 * Real recordings attached to starter phrases. Each clip is an unmodified
 * Lingua Libre recording from Wikimedia Commons (CC BY-SA 4.0), bundled under
 * `assets/audio/seed/` and credited here and in `assets/content_sources.txt`.
 *
 * A clip is only listed when its recorded word is exactly the phrase's answer.
 * Lingua Libre volunteers are not verified native speakers and the clips have
 * not had Root's own review, so the UI credits the speaker without claiming more.
 * Dholuo and Shona have no matching recordings yet.
 */
object SeedAudio {
    data class Clip(
        val phraseId: String,
        /** The exact answer the clip was matched to; audio is only attached while the phrase still says this. */
        val answer: String,
        val assetPath: String,
        val speaker: String,
        val license: String,
        val sourceUrl: String,
    )

    private const val LICENSE = "CC BY-SA 4.0"

    private fun clip(phraseId: String, answer: String, speaker: String, commonsFile: String) = Clip(
        phraseId = phraseId,
        answer = answer,
        assetPath = "audio/seed/$phraseId.wav",
        speaker = speaker,
        license = LICENSE,
        sourceUrl = "https://commons.wikimedia.org/wiki/File:$commonsFile",
    )

    val clips: List<Clip> = listOf(
        clip("phrase-swahili-greetings-03", "Karibu", "Rigolearning", "LL-Q7838_(swa)-Rigolearning_(WikiLucas00)-karibu.wav"),
        clip("phrase-amharic-directions-02", "Yet?", "Woubster", "LL-Q28244_(amh)-Woubster-የት.wav"),
        clip("phrase-amharic-numbers-01", "And (አንድ)", "Woubster", "LL-Q28244_(amh)-Woubster-አንድ.wav"),
        clip("phrase-amharic-numbers-02", "Hulet (ሁለት)", "Woubster", "LL-Q28244_(amh)-Woubster-ሁለት.wav"),
        clip("phrase-amharic-numbers-03", "Sost (ሶስት)", "Woubster", "LL-Q28244_(amh)-Woubster-ሶስት.wav"),
        clip("phrase-amharic-numbers-04", "Amist (አምስት)", "Woubster", "LL-Q28244_(amh)-Woubster-አምስት.wav"),
    )

    private val byPhrase = clips.associateBy { it.phraseId }
    private val byAsset = clips.associateBy { it.assetPath }

    fun forPhrase(phraseId: String): Clip? = byPhrase[phraseId]

    /** Credit line for a bundled clip, or null for learner recordings and downloaded content. */
    fun creditFor(assetPath: String?): String? = assetPath?.let(byAsset::get)?.let {
        "Recording by ${it.speaker} · Lingua Libre · ${it.license}"
    }
}
