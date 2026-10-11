package com.lhcomics.server

import com.lhcomics.core.source.SourceConfig
import com.lhcomics.core.source.SourceType
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private val comics: File = Files.createTempDirectory("lhc-comics").toFile()
    private val data: File = Files.createTempDirectory("lhc-data").toFile()
    private val config = ServerConfig(
        password = "secret",
        sources = listOf(SourceConfig("local0", "만화", SourceType.LOCAL, host = comics.path)),
        dataDir = data,
        webDir = File(data, "no-web"),
        port = 0,
    )

    @AfterTest
    fun cleanup() {
        comics.deleteRecursively()
        data.deleteRecursively()
    }

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { out ->
            for ((name, data) in entries) {
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(data)
                out.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun ApplicationTestBuilder.login(): String {
        val res = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"secret"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `requires login`() = testApplication {
        application { lhComics(config) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sources").status)
        val wrong = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"nope"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val token = login()
        val ok = client.get("/api/sources") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("만화"))
    }

    @Test
    fun `browse nested archive and read resized page`() = testApplication {
        val inner = zip("p2.png" to png(1600, 2400), "p1.png" to png(1600, 2400))
        File(comics, "all.zip").writeBytes(zip("vol1.cbz" to inner))
        application { lhComics(config) }
        val token = login()
        val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $token") }

        val root = Json.parseToJsonElement(client.get("/api/list?s=local0&loc=${enc("/")}", auth).bodyAsText()).jsonObject
        val archive = root["items"]!!.jsonArray.single().jsonObject
        assertEquals("ARCHIVE", archive["kind"]!!.jsonPrimitive.content)

        // all.zip 은 안에 압축이 있으므로 폴더로 열어야 한다.
        val allBook = Json.parseToJsonElement(
            client.get("/api/book?s=local0&loc=${enc(archive["location"]!!.jsonPrimitive.content)}", auth).bodyAsText(),
        ).jsonObject
        assertEquals("true", allBook["browse"]!!.jsonPrimitive.content)

        val book = Json.parseToJsonElement(client.get("/api/book?s=local0&loc=${enc("/all.zip!/vol1.cbz!/")}", auth).bodyAsText()).jsonObject
        val pages = book["pages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("p1.png", "p2.png"), pages.map { it["name"]!!.jsonPrimitive.content })

        val pageLoc = enc(pages[0]["location"]!!.jsonPrimitive.content)
        val original = client.get("/api/image?s=local0&loc=$pageLoc", auth)
        assertEquals("image/png", original.headers[HttpHeaders.ContentType])
        val resized = client.get("/api/image?s=local0&loc=$pageLoc&w=800", auth)
        assertEquals("image/jpeg", resized.headers[HttpHeaders.ContentType])
        assertEquals(800, ImageIO.read(resized.bodyAsBytes().inputStream()).width)

        val cover = client.get("/api/cover?s=local0&loc=${enc("/all.zip!/vol1.cbz!/")}", auth)
        assertEquals(360, ImageIO.read(cover.bodyAsBytes().inputStream()).width)
    }

    @Test
    fun `progress sync keeps newest`() = testApplication {
        application { lhComics(config) }
        val token = login()
        suspend fun put(page: Int, at: Long) = client.put("/api/progress") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sourceId":"local0","location":"/a.zip!/","title":"a","page":$page,"pageCount":10,"updatedAt":$at}""")
        }
        assertEquals(HttpStatusCode.NoContent, put(5, 2000).status)
        put(1, 1000) // 늦게 도착한 오래된 기록
        val recent = client.get("/api/progress/recent") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        assertEquals(5, Json.parseToJsonElement(recent).jsonArray.single().jsonObject["page"]!!.jsonPrimitive.content.toInt())
    }
}
