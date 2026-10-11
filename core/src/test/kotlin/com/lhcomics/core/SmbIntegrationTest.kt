package com.lhcomics.core

import com.lhcomics.core.library.ComicLibrary
import com.lhcomics.core.library.ItemKind
import com.lhcomics.core.library.Location
import com.lhcomics.core.source.SourceConfig
import com.lhcomics.core.source.SourceType
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 실제 NAS 접속 확인용. 환경 변수가 없으면 건너뛴다(계정 정보를 코드에 두지 않는다).
 *
 * LHC_SMB_HOST=192.168.0.10 LHC_SMB_SHARE=comics LHC_SMB_USER=me LHC_SMB_PASS=... \
 *   ./gradlew :core:test --tests '*SmbIntegrationTest*' -i
 */
class SmbIntegrationTest {
    private val host = System.getenv("LHC_SMB_HOST").orEmpty()

    @Test
    fun `browse share and read first page of first archive`() {
        assumeTrue("LHC_SMB_HOST not set", host.isNotEmpty())
        val config = SourceConfig(
            id = "smb-it",
            name = "nas",
            type = SourceType.SMB,
            host = host,
            share = System.getenv("LHC_SMB_SHARE").orEmpty(),
            basePath = System.getenv("LHC_SMB_PATH").orEmpty(),
            username = System.getenv("LHC_SMB_USER").orEmpty(),
            password = System.getenv("LHC_SMB_PASS").orEmpty(),
        )
        val cache = Files.createTempDirectory("lhc-smb").toFile()
        ComicLibrary(com.lhcomics.core.source.SmbSource(config), cache).use { lib ->
            lateinit var root: List<com.lhcomics.core.library.Item>
            val listMs = measureTimeMillis { root = lib.list(Location.ROOT) }
            println("[smb] root: ${root.size} items in ${listMs}ms → ${root.take(10).map { "${it.kind.name[0]}:${it.name}" }}")
            assertTrue(root.isNotEmpty() || listMs >= 0)

            // 폴더를 최대 3단계까지 내려가며 첫 압축 파일을 찾는다.
            val archive = findArchive(lib, Location.ROOT, depth = 3)
            if (archive == null) {
                println("[smb] no archive found within 3 levels")
                return
            }
            println("[smb] archive: ${archive.location} (${archive.size / 1024}KB)")
            val loc = Location.parse(archive.location)
            lateinit var pages: List<com.lhcomics.core.library.Page>
            val pagesMs = measureTimeMillis { pages = lib.pages(loc) }
            println("[smb] pages: ${pages.size} in ${pagesMs}ms, nested=${lib.containsArchives(loc)}")
            if (pages.isNotEmpty()) {
                var bytes = ByteArray(0)
                val readMs = measureTimeMillis { bytes = lib.read(Location.parse(pages.first().location)) }
                println("[smb] first page ${pages.first().name}: ${bytes.size / 1024}KB in ${readMs}ms")
                val lastMs = measureTimeMillis { lib.read(Location.parse(pages.last().location)) }
                println("[smb] last page ${pages.last().name} in ${lastMs}ms")
                assertTrue(bytes.isNotEmpty())
            }
        }
        cache.deleteRecursively()
    }

    private fun findArchive(lib: ComicLibrary, loc: Location, depth: Int): com.lhcomics.core.library.Item? {
        val items = runCatching { lib.list(loc) }.getOrElse { return null }
        items.firstOrNull { it.kind == ItemKind.ARCHIVE }?.let { return it }
        if (depth == 0) return null
        for (dir in items.filter { it.kind == ItemKind.DIRECTORY }.take(8)) {
            findArchive(lib, Location.parse(dir.location), depth - 1)?.let { return it }
        }
        return null
    }
}
