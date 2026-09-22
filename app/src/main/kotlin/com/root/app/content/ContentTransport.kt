package com.root.app.content

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class DownloadFailure {
    NOT_CONFIGURED, NETWORK, INVALID_URL, TOO_LARGE, INTEGRITY, STORAGE, INVALID_AUDIO,
}

class ContentDownloadException(
    val failure: DownloadFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Public read-only transport. Redirects are deliberately not followed across trust boundaries. */
class ContentTransport internal constructor(catalogUrl: String, client: OkHttpClient = OkHttpClient()) {
    private val catalog = checkedCatalogUrl(catalogUrl)
    private val base = catalog.resolve(".")!!
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    data class CatalogBytes(val bytes: ByteArray?, val etag: String?)

    fun catalog(etag: String? = null): CatalogBytes {
        val request = Request.Builder().url(catalog).apply {
            if (etag != null) header("If-None-Match", etag)
        }.build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 304 && etag != null) return@use CatalogBytes(null, etag)
            if (!response.isSuccessful) {
                throw ContentDownloadException(DownloadFailure.NETWORK, "Catalog HTTP ${response.code}")
            }
            val body = response.body
                ?: throw ContentDownloadException(DownloadFailure.NETWORK, "Catalog body is missing")
            if (body.contentLength() > MAX_CATALOG_BYTES) {
                throw ContentDownloadException(DownloadFailure.TOO_LARGE, "Catalog exceeds the size limit")
            }
            val bytes = body.byteStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                copyBounded(stream, output, MAX_CATALOG_BYTES)
                output.toByteArray()
            }
            CatalogBytes(bytes, response.header("ETag"))
        }
    }

    fun download(key: String, bytes: Long, sha256: String, destination: File, limit: Long) {
        require(bytes in 1..limit) { "Invalid declared object size" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid SHA-256" }
        val url = objectUrl(base, key)
        if (destination.isFile && destination.length() == bytes && hash(destination) == sha256) return
        val parent = destination.parentFile
            ?: throw ContentDownloadException(DownloadFailure.STORAGE, "Missing destination directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw ContentDownloadException(DownloadFailure.STORAGE, "Cannot create download directory")
        }
        val temporary = File(parent, "${destination.name}.partial")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ContentDownloadException(DownloadFailure.NETWORK, "Content HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw ContentDownloadException(DownloadFailure.NETWORK, "Content body is missing")
                if (body.contentLength() != -1L && body.contentLength() != bytes) {
                    throw ContentDownloadException(DownloadFailure.INTEGRITY, "Content length changed")
                }
                body.byteStream().use { input ->
                    temporary.outputStream().use { output -> copyBounded(input, output, bytes) }
                }
            }
            if (temporary.length() != bytes || hash(temporary) != sha256) {
                throw ContentDownloadException(DownloadFailure.INTEGRITY, "Content checksum does not match")
            }
            java.nio.file.Files.move(
                temporary.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                android.util.Log.w("RootContent", "Could not remove an incomplete download")
            }
        }
    }

    companion object {
        const val MAX_CATALOG_BYTES = 2L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        const val MAX_ASSET_BYTES = 20L * 1024 * 1024
        const val MAX_PACK_BYTES = 50L * 1024 * 1024

        internal fun checkedCatalogUrl(value: String): HttpUrl {
            if (value.isBlank()) throw ContentDownloadException(
                DownloadFailure.NOT_CONFIGURED, "Content downloads are not configured",
            )
            val url = try {
                value.toHttpUrl()
            } catch (error: IllegalArgumentException) {
                throw ContentDownloadException(DownloadFailure.INVALID_URL, "Invalid catalog URL", error)
            }
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.fragment != null || url.query != null || url.encodedPath.endsWith("/")
            ) throw ContentDownloadException(DownloadFailure.INVALID_URL, "Catalog must be a public HTTPS file URL")
            return url
        }

        internal fun objectUrl(base: HttpUrl, key: String): HttpUrl {
            if (key.isEmpty() || key.length > 512 ||
                !key.matches(Regex("[A-Za-z0-9._/-]+")) ||
                key.split('/').any { it.isEmpty() || it == "." || it == ".." }
            ) throw ContentDownloadException(DownloadFailure.INVALID_URL, "Invalid content object key")
            val result = base.resolve(key)
                ?: throw ContentDownloadException(DownloadFailure.INVALID_URL, "Invalid content object URL")
            if (result.scheme != base.scheme || result.host != base.host || result.port != base.port ||
                !result.encodedPath.startsWith(base.encodedPath)
            ) throw ContentDownloadException(DownloadFailure.INVALID_URL, "Content object escapes its origin")
            return result
        }

        internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
            var count = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                count += read
                if (count > limit) throw ContentDownloadException(
                    DownloadFailure.TOO_LARGE, "Download exceeds its declared size",
                )
                output.write(buffer, 0, read)
            }
            return count
        }

        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
