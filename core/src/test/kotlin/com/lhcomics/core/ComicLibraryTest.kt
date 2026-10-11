package com.lhcomics.core

import com.lhcomics.core.library.ComicLibrary
import com.lhcomics.core.library.ItemKind
import com.lhcomics.core.library.Location
import com.lhcomics.core.library.NaturalOrder
import com.lhcomics.core.source.LocalDirSource
import com.lhcomics.core.source.SourceConfig
import com.lhcomics.core.source.SourceType
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicLibraryTest {
    private val root: File = Files.createTempDirectory("lhc-src").toFile()
    private val cache: File = Files.createTempDirectory("lhc-cache").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private fun library() = ComicLibrary(
        LocalDirSource(SourceConfig("t", "test", SourceType.LOCAL, host = root.path)),
        cache,
    )

    private fun zip(entries: List<Pair<String, ByteArray>>, encoding: String = "UTF-8", utf8Flag: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { out ->
            out.setEncoding(encoding)
            out.setUseLanguageEncodingFlag(utf8Flag)
            out.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
            for ((name, data) in entries) {
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(data)
                out.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun img(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), n.toByte())

    @Test
    fun `nested archives three levels deep`() {
        val innermost = zip(listOf("p2.jpg" to img(2), "p10.jpg" to img(10), "p1.jpg" to img(1)))
        val middle = zip(listOf("ch1/inner.cbz" to innermost, "cover.png" to img(0)))
        File(root, "series").mkdirs()
        File(root, "series/all.zip").writeBytes(zip(listOf("vol1.zip" to middle)))

        library().use { lib ->
            val top = lib.list(Location.parse("/series"))
            assertEquals(listOf("all.zip"), top.map { it.name })
            assertEquals(ItemKind.ARCHIVE, top[0].kind)

            val inAll = lib.list(Location.parse(top[0].location))
            assertEquals("vol1.zip", inAll.single().name)

            val vol1 = Location.parse(inAll.single().location)
            assertEquals("/series/all.zip!/vol1.zip!/", vol1.encode())
            assertEquals(listOf("ch1", "cover.png"), lib.list(vol1).map { it.name })

            // vol1 안의 압축은 하위 폴더(ch1)에 있어도 감지해야 한다.
            assertEquals(true, lib.containsArchives(vol1))

            val inner = Location.parse(lib.list(vol1.childDir("ch1")).single().location)
            assertEquals(false, lib.containsArchives(inner))
            val pages = lib.pages(inner)
            assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), pages.map { it.name })
            assertContentEquals(img(10), lib.read(Location.parse(pages[2].location)))
        }
    }

    @Test
    fun `cp949 file names without utf8 flag`() {
        File(root, "kr.zip").writeBytes(zip(listOf("원피스 01권/001.jpg" to img(1)), encoding = "MS949", utf8Flag = false))
        library().use { lib ->
            val items = lib.list(Location.parse("/kr.zip!/"))
            assertEquals("원피스 01권", items.single().name)
            assertEquals(1, lib.pages(Location.parse("/kr.zip!/")).size)
        }
    }

    @Test
    fun `seven zip archive`() {
        val file = File(root, "book.cb7")
        SevenZOutputFile(file).use { out ->
            for (i in listOf(3, 1, 2)) {
                val entry = out.createArchiveEntry(File("x"), "$i.jpg")
                out.putArchiveEntry(entry)
                out.write(img(i))
                out.closeArchiveEntry()
            }
        }
        library().use { lib ->
            val pages = lib.pages(Location.parse("/book.cb7!/"))
            assertEquals(listOf("1.jpg", "2.jpg", "3.jpg"), pages.map { it.name })
            assertContentEquals(img(3), lib.read(Location.parse(pages[2].location)))
        }
    }

    @Test
    fun `folder of images and adjacent books`() {
        listOf("a 1", "a 2", "a 10").forEach { File(root, "s/$it").mkdirs(); File(root, "s/$it/1.jpg").writeBytes(img(1)) }
        File(root, "s/a 2/.DS_Store").writeBytes(img(0))
        library().use { lib ->
            assertEquals(1, lib.pages(Location.parse("/s/a 2")).size)
            assertEquals("a 10", lib.adjacent(Location.parse("/s/a 2"), 1)?.name)
            assertNull(lib.adjacent(Location.parse("/s/a 10"), 1))
        }
    }

    @Test
    fun `location parent and parse`() {
        val loc = Location.parse("/x/all.zip!/v1.zip!/ch")
        assertEquals("/x/all.zip!/v1.zip!/", loc.parent()!!.encode())
        assertEquals("/x/all.zip!/", loc.parent()!!.parent()!!.encode())
        assertEquals("/x", loc.parent()!!.parent()!!.parent()!!.encode())
        assertEquals(null, Location.ROOT.parent())
    }

    @Test
    fun `natural order`() {
        val sorted = listOf("10화", "2화", "1화", "B", "a").sortedWith(NaturalOrder)
        assertEquals(listOf("1화", "2화", "10화", "a", "B"), sorted)
    }

    @Test
    fun `local source clamps path traversal to root`() {
        File(root, "inside.zip").writeBytes(zip(listOf("1.jpg" to img(1))))
        library().use { lib ->
            assertEquals(listOf("inside.zip"), lib.list(Location.parse("/../../")).map { it.name })
        }
    }
}
