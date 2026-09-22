package com.root.app.content

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun promotionIsImmutableAndKeepsOldVersion() {
        val files = PackFiles(temporary.newFolder())
        val stage = files.stage("shona", "job")
        files.ensureDirectory(stage)
        val manifest = File(stage, "manifest.json").apply { writeText("{\"version\":1}") }
        val hash = ContentTransport.hash(manifest)
        val installed = files.promote("shona", 1, "job", hash)
        assertFalse(stage.exists())
        assertTrue(File(installed, "manifest.json").isFile)
        assertEquals(installed, files.promote("shona", 1, "retry", hash))
        assertThrows(ContentDownloadException::class.java) { files.promote("shona", 1, "job", "0".repeat(64)) }
        assertEquals(hash, ContentTransport.hash(File(installed, "manifest.json")))
    }

    @Test fun pathsAndCleanupAreScopedToManagedJobs() {
        val root = temporary.newFolder()
        val files = PackFiles(root)
        assertThrows(IllegalArgumentException::class.java) { files.stage("../outside", "id") }
        assertThrows(IllegalArgumentException::class.java) { files.version("valid", 0) }
        val a = files.stage("one", "job").also(files::ensureDirectory)
        val b = files.stage("two", "job").also(files::ensureDirectory)
        File(a, "part").writeText("a")
        File(b, "part").writeText("b")
        files.deleteStage("one", "job")
        assertFalse(a.exists())
        assertTrue(File(b, "part").exists())
        assertTrue(root.exists())
    }
}
