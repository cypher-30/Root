package com.root.app.content

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class ContentTransportTest {
    @Test fun catalogRequiresPublicHttpsFile() {
        listOf("", "http://example.org/catalog.json", "https://u:p@example.org/catalog.json",
            "https://example.org/catalog.json?token=secret", "https://example.org/").forEach {
            assertThrows(ContentDownloadException::class.java) { ContentTransport.checkedCatalogUrl(it) }
        }
        assertEquals("example.org", ContentTransport.checkedCatalogUrl("https://example.org/catalog.json").host)
    }

    @Test fun objectKeysCannotEscapeBucketOrInjectAnotherOrigin() {
        val base = "https://example.org/storage/v1/object/public/root/".toHttpUrl()
        listOf("../secret", "/secret", "a/../b", "a//b", "a\\b", "a%2fb", "https://evil.org/x",
            "a?token=x", "a#fragment", ".").forEach {
            assertThrows(ContentDownloadException::class.java) { ContentTransport.objectUrl(base, it) }
        }
        assertEquals("${base}packs/shona/1/audio.m4a",
            ContentTransport.objectUrl(base, "packs/shona/1/audio.m4a").toString())
    }

    @Test fun streamCeilingAllowsExactLimitAndRejectsNextByte() {
        val output = ByteArrayOutputStream()
        assertEquals(5L, ContentTransport.copyBounded(ByteArrayInputStream(ByteArray(5)), output, 5))
        assertEquals(5, output.size())
        val error = assertThrows(ContentDownloadException::class.java) {
            ContentTransport.copyBounded(ByteArrayInputStream(ByteArray(6)), ByteArrayOutputStream(), 5)
        }
        assertEquals(DownloadFailure.TOO_LARGE, error.failure)
    }

    @Test fun actualProtocolByteCeilingsAreEnforcedWithoutUnboundedBuffers() {
        listOf(ContentTransport.MAX_CATALOG_BYTES, ContentTransport.MAX_MANIFEST_BYTES,
            ContentTransport.MAX_ASSET_BYTES, ContentTransport.MAX_PACK_BYTES).forEach { limit ->
            assertEquals(limit, ContentTransport.copyBounded(ZeroStream(limit), OutputStream.nullOutputStream(), limit))
            assertThrows(ContentDownloadException::class.java) {
                ContentTransport.copyBounded(ZeroStream(limit + 1), OutputStream.nullOutputStream(), limit)
            }
        }
    }

    private class ZeroStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
            remaining -= count
            return count
        }
    }
}
