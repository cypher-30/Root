package com.root.app.teach

/** Where a resolved audio reference actually lives. UI never fetches remote audio
 *  during study — only [Bundled] and [DownloadedFile] are ever offered for lesson
 *  playback, matching the existing [com.root.app.audio.RootAudioSession] contract
 *  (assets by relative name, files by absolute app-internal path). */
sealed interface LessonAudioSource {
    /** App asset path, played through the existing bundled-asset branch of
     *  RootAudioSession.playReference/play. */
    data class Bundled(val assetPath: String) : LessonAudioSource
    /** Already-verified, installed pack media on this device (absolute,
     *  app-private path) — supplied once the real pack installer exists. */
    data class DownloadedFile(val absolutePath: String) : LessonAudioSource
    /** No genuine matching recording is resolvable right now. */
    object Unavailable : LessonAudioSource
}

/**
 * Typed resolver from a [com.root.app.content.Activity.Listening.audioAssetId] (a
 * nullable string — a lesson may be validly authored before its recording
 * exists) to a concrete, locally playable source. Backed by
 * `ContentLibrary.localAudio(packId, version, assetId)` in production (see
 * [ContentViewModel.audioResolver]) — audio is resolved local-only, never
 * fetched during study.
 */
fun interface LessonAudioResolver {
    fun resolve(audioAssetId: String?): LessonAudioSource
}
