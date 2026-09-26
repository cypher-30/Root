package com.root.app.content

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

class ContentTransportNetworkTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var transport: ContentTransport

    @Before fun setup() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start(InetAddress.getByName("localhost"), 0)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        transport = ContentTransport(server.url("/root/catalog.json").toString(), client)
    }

    @After fun close() = server.shutdown()

    @Test fun verifiedFilesAreReusedAndCorruptionNeverReplacesThem() {
        val data = "verified content"
        val hash = sha(data)
        val target = File(temporary.root, "pack")
        server.enqueue(MockResponse().setBody(data))
        transport.download("packs/1.json", data.length.toLong(), hash, target, 100)
        assertEquals(data, target.readText())
        transport.download("packs/1.json", data.length.toLong(), hash, target, 100)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("invalid contents"))
        assertThrows(ContentDownloadException::class.java) {
            transport.download("packs/2.json", data.length.toLong(), sha("different data!"), target, 100)
        }
        assertEquals(data, target.readText())
    }

    @Test fun chunkedOversizeAndRedirectsCannotInstall() {
        val target = File(temporary.root, "pack")
        server.enqueue(MockResponse().setChunkedBody("abcdef", 2))
        assertThrows(ContentDownloadException::class.java) {
            transport.download("pack", 5, sha("abcde"), target, 5)
        }
        assertFalse(target.exists())
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org/other"))
        assertThrows(ContentDownloadException::class.java) {
            transport.download("pack", 5, sha("abcde"), target, 5)
        }
        assertEquals(2, server.requestCount)
        assertFalse(target.exists())
    }

    @Test fun catalogConditionalRequestKeepsPriorBytes() {
        server.enqueue(MockResponse().setBody("{}").addHeader("ETag", "\"revision1\""))
        val initial = transport.catalog()
        assertEquals("{}", initial.bytes!!.toString(Charsets.UTF_8))
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(304))
        val cached = transport.catalog(initial.etag)
        assertNull(cached.bytes)
        assertEquals("\"revision1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
