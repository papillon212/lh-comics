package com.lhcomics.core.source

import com.lhcomics.core.io.CachedRandomAccess
import com.lhcomics.core.io.RandomAccessData
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV(시놀로지, Nextcloud 등). 압축 파일은 HTTP Range 요청으로 필요한 부분만 받는다. */
class WebDavSource(override val config: SourceConfig) : FileSource {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = run {
        val scheme = if (config.secure) "https" else "http"
        val port = if (config.port > 0) ":${config.port}" else ""
        "$scheme://${config.host}$port/".toHttpUrl()
    }

    private fun url(path: String): HttpUrl {
        val b = base.newBuilder()
        Paths.normalize("${config.basePath}/$path").split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).apply {
        if (config.username.isNotEmpty()) header("Authorization", Credentials.basic(config.username, config.password))
    }

    override fun list(path: String): List<FileEntry> {
        val dirUrl = url(path).newBuilder().addPathSegment("").build()
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val req = request(dirUrl)
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        val xml = http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("WebDAV ${res.code}: ${dirUrl.encodedPath}")
            res.body!!.byteStream().readBytes()
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.inputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        // 서버마다 href 인코딩 방식이 달라서 디코딩한 경로끼리 비교한다.
        val selfPath = decode(dirUrl.encodedPath).trimEnd('/')
        val result = mutableListOf<FileEntry>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.text("href") ?: continue
            val hrefPath = decode(href.toHttpUrlOrPath()).trimEnd('/')
            if (hrefPath == selfPath) continue
            val name = hrefPath.substringAfterLast('/')
            if (name.startsWith(".")) continue
            val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            result += FileEntry(
                name = name,
                path = Paths.join(path, name),
                isDirectory = isDir,
                size = r.text("getcontentlength")?.toLongOrNull() ?: 0L,
                modified = r.text("getlastmodified")?.let(::parseHttpDate) ?: 0L,
            )
        }
        return result
    }

    /** URLDecoder는 '+'를 공백으로 바꾸므로 경로용으로 보정한다. */
    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun String.toHttpUrlOrPath(): String =
        if (startsWith("http://") || startsWith("https://")) toHttpUrl().encodedPath else this

    private fun Element.text(local: String): String? =
        getElementsByTagNameNS("DAV:", local).item(0)?.textContent?.trim()

    private fun parseHttpDate(s: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time
    }.getOrNull()

    override fun readBytes(path: String): ByteArray {
        val req = request(url(path)).get().build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("WebDAV ${res.code}: $path")
            res.body!!.bytes()
        }
    }

    override fun openRandom(path: String): RandomAccessData {
        val target = url(path)
        val length = http.newCall(request(target).head().build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("WebDAV ${res.code}: $path")
            res.header("Content-Length")?.toLongOrNull() ?: throw IOException("missing Content-Length: $path")
        }
        val raw = object : RandomAccessData {
            override val size = length
            override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (position >= size) return -1
                val end = minOf(position + len, size) - 1
                val req = request(target).get().header("Range", "bytes=$position-$end").build()
                return http.newCall(req).execute().use { res ->
                    if (res.code != 206) throw IOException("server does not support Range (${res.code})")
                    val bytes = res.body!!.bytes()
                    System.arraycopy(bytes, 0, buf, off, bytes.size)
                    bytes.size
                }
            }
            override fun close() {}
        }
        return CachedRandomAccess(raw, blockSize = 512 * 1024)
    }
}
