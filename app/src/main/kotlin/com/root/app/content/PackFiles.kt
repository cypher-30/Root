package com.root.app.content

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Owns only content files. Learner recordings and legacy assets never live under this root. */
internal class PackFiles(private val root: File) {
    fun stage(packId: String, operationId: String): File =
        child("staging", "${checkedId(packId)}-${checkedId(operationId)}")

    fun version(packId: String, version: Int): File {
        require(version > 0)
        return child("versions", "${checkedId(packId)}/$version")
    }

    fun media(directory: File, assetId: String): File {
        requireOwned(directory)
        return File(directory, "audio-${checkedId(assetId)}")
    }

    fun ensureSpace(bytes: Long) {
        require(bytes in 1..ContentTransport.MAX_PACK_BYTES)
        ensureDirectory(root)
        if (root.usableSpace < bytes + 1024 * 1024) {
            throw ContentDownloadException(DownloadFailure.STORAGE, "Not enough storage to install this pack")
        }
    }

    fun ensureDirectory(directory: File) {
        requireOwned(directory)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw ContentDownloadException(DownloadFailure.STORAGE, "Cannot create pack storage")
        }
    }

    fun promote(packId: String, version: Int, operationId: String, manifestHash: String): File {
        val staged = stage(packId, operationId)
        val target = version(packId, version)
        if (target.exists()) {
            val manifest = File(target, "manifest.json")
            if (!manifest.isFile || ContentTransport.hash(manifest) != manifestHash) {
                throw ContentDownloadException(DownloadFailure.INTEGRITY, "An immutable pack version has different content")
            }
            return target
        }
        ensureDirectory(target.parentFile!!)
        try {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            // Never fall back to copying into a visible final directory.
            throw ContentDownloadException(DownloadFailure.STORAGE, "Cannot activate verified pack files", error)
        }
        return target
    }

    /**
     * Forcibly replaces an already-promoted (but corrupt) version directory
     * with [operationId]'s freshly-downloaded-and-verified staged files. Only
     * called after that staged copy has independently passed [ContentAudio]
     * verification, so this can never replace intact files with worse ones —
     * it only repairs a version directory that failed post-promotion
     * revalidation (see [ContentLibrary.installRemote]), most likely an orphan
     * left behind by a process death partway through a prior [promote] move.
     */
    fun replacePromoted(packId: String, version: Int, operationId: String): File {
        val staged = stage(packId, operationId)
        val target = version(packId, version)
        deleteVersion(packId, version)
        ensureDirectory(target.parentFile!!)
        try {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            throw ContentDownloadException(DownloadFailure.STORAGE, "Cannot repair pack storage", error)
        }
        return target
    }

    fun deleteStage(packId: String, operationId: String) = deleteOwnedTree(stage(packId, operationId))

    fun deleteVersion(packId: String, version: Int) = deleteOwnedTree(version(packId, version))

    private fun deleteOwnedTree(directory: File) {
        requireOwned(directory)
        require(directory.canonicalFile != root.canonicalFile)
        if (!directory.exists()) return
        // Validate the complete set before deleting; never follow an unexpected symlink.
        val files = directory.walkTopDown().onEnter {
            requireOwned(it)
            !Files.isSymbolicLink(it.toPath())
        }.toList()
        files.forEach {
            requireOwned(it)
            require(!Files.isSymbolicLink(it.toPath())) { "Unexpected content symlink" }
        }
        files.asReversed().forEach {
            if (!it.delete()) throw ContentDownloadException(DownloadFailure.STORAGE, "Cannot remove managed content")
        }
    }

    private fun child(kind: String, path: String): File =
        File(File(root, kind), path).also(::requireOwned)

    private fun requireOwned(file: File) {
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "Content path escaped app storage"
        }
    }

    companion object {
        private fun checkedId(value: String): String {
            require(value.length in 1..120 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) &&
                value != "." && value != "..") { "Invalid content storage ID" }
            return value
        }
    }
}
