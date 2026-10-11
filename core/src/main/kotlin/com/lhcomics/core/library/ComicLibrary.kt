package com.lhcomics.core.library

import com.lhcomics.core.archive.Archives
import com.lhcomics.core.source.FileSource
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class ItemKind { DIRECTORY, ARCHIVE, IMAGE }

@Serializable
data class Item(
    val name: String,
    /** 폴더/압축이면 열 위치, 이미지면 이미지 파일 위치. */
    val location: String,
    val kind: ItemKind,
    val size: Long = 0,
    val modified: Long = 0,
)

@Serializable
data class Page(val name: String, val location: String)

object Images {
    private val extensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "heif", "jxl")

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in extensions

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "heic", "heif" -> "image/heif"
        "jxl" -> "image/jxl"
        else -> "application/octet-stream"
    }
}

/**
 * 폴더, 압축 파일, 압축 안의 압축을 같은 방식으로 탐색하고 읽는 진입점.
 * 안드로이드 앱과 서버가 함께 쓴다.
 */
class ComicLibrary(
    val source: FileSource,
    cacheDir: File,
    charset: String = source.config.encoding,
) : AutoCloseable {
    private val archives = ArchiveCache(source, File(cacheDir, "nested"), charset)

    fun list(location: Location): List<Item> {
        val items = if (!location.inArchive) listSource(location) else listArchive(location)
        return items.sortedWith(compareBy<Item> { it.kind != ItemKind.DIRECTORY && it.kind != ItemKind.ARCHIVE }
            .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) })
    }

    private fun listSource(location: Location): List<Item> =
        source.list(location.dir).filterNot { isJunk(it.name) }.mapNotNull { e ->
            when {
                e.isDirectory -> Item(e.name, location.childDir(e.name).encode(), ItemKind.DIRECTORY, 0, e.modified)
                Archives.isArchive(e.name) -> Item(e.name, location.childArchive(e.name).encode(), ItemKind.ARCHIVE, e.size, e.modified)
                Images.isImage(e.name) -> Item(e.name, location.childDir(e.name).encode(), ItemKind.IMAGE, e.size, e.modified)
                else -> null
            }
        }

    private fun listArchive(location: Location): List<Item> = archives.use(location.archives) { reader ->
        val prefix = if (location.dir.isEmpty()) "" else location.dir + "/"
        val dirs = linkedSetOf<String>()
        val result = mutableListOf<Item>()
        for (e in reader.entries) {
            if (!e.path.startsWith(prefix) || e.path == location.dir || isJunkPath(e.path)) continue
            val rest = e.path.removePrefix(prefix)
            val first = rest.substringBefore('/')
            // 디렉터리 엔트리가 없는 압축 파일도 많아서 경로에서 폴더를 만든다.
            if (rest.contains('/') || e.isDirectory) {
                dirs += first
                continue
            }
            when {
                Archives.isArchive(first) -> result += Item(first, location.childArchive(first).encode(), ItemKind.ARCHIVE, e.size)
                Images.isImage(first) -> result += Item(first, location.childDir(first).encode(), ItemKind.IMAGE, e.size)
            }
        }
        dirs.mapTo(result) { Item(it, location.childDir(it).encode(), ItemKind.DIRECTORY) }
        result
    }

    /**
     * [location]을 한 권으로 보고 페이지 목록을 만든다. 압축 안에서는 하위 폴더까지
     * 포함하고(권/화 폴더 구조), 일반 폴더는 바로 아래 이미지만 쓴다.
     */
    fun pages(location: Location): List<Page> {
        if (!location.inArchive) {
            return listSource(location).filter { it.kind == ItemKind.IMAGE }
                .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                .map { Page(it.name, it.location) }
        }
        return archives.use(location.archives) { reader ->
            val prefix = if (location.dir.isEmpty()) "" else location.dir + "/"
            reader.entries
                .filter { !it.isDirectory && it.path.startsWith(prefix) && Images.isImage(it.path) && !isJunkPath(it.path) }
                .sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }
                .map { e ->
                    Page(e.path.substringAfterLast('/'), Location(location.archives, e.path).encode())
                }
        }
    }

    /** 이미지 파일 위치의 바이트를 읽는다. */
    fun read(fileLocation: Location): ByteArray {
        if (!fileLocation.inArchive) return source.readBytes(fileLocation.dir)
        return archives.use(fileLocation.archives) { reader ->
            val entry = reader.entries.firstOrNull { it.path == fileLocation.dir && !it.isDirectory }
                ?: throw NoSuchElementException(fileLocation.dir)
            reader.readBytes(entry)
        }
    }

    /** 표지로 쓸 첫 이미지. 이미지가 없으면 첫 하위 폴더/압축 파일까지 한 단계 내려가 본다. */
    fun cover(location: Location): Page? {
        pages(location).firstOrNull()?.let { return it }
        val child = list(location).firstOrNull { it.kind == ItemKind.ARCHIVE || it.kind == ItemKind.DIRECTORY } ?: return null
        return pages(Location.parse(child.location)).firstOrNull()
    }

    /**
     * 압축 파일 안 어디든(하위 폴더 포함) 다른 압축 파일이 있는지. 있으면 바로 읽지 않고
     * 폴더처럼 탐색하게 한다.
     */
    fun containsArchives(location: Location): Boolean {
        if (!location.inArchive) return list(location).any { it.kind == ItemKind.ARCHIVE }
        return archives.use(location.archives) { reader ->
            val prefix = if (location.dir.isEmpty()) "" else location.dir + "/"
            reader.entries.any { !it.isDirectory && it.path.startsWith(prefix) && Archives.isArchive(it.path) && !isJunkPath(it.path) }
        }
    }

    /** 같은 폴더에서 이전/다음 권(폴더 또는 압축). */
    fun adjacent(location: Location, delta: Int): Item? {
        val parent = location.parent() ?: return null
        val books = list(parent).filter { it.kind == ItemKind.DIRECTORY || it.kind == ItemKind.ARCHIVE }
        val index = books.indexOfFirst { Location.parse(it.location) == location }
        if (index < 0) return null
        return books.getOrNull(index + delta)
    }

    private fun isJunk(name: String) = name.startsWith(".") || name == "Thumbs.db" || name == "@eaDir" || name == "#recycle"

    private fun isJunkPath(path: String) = path.startsWith("__MACOSX/") || path.split('/').any { isJunk(it) }

    override fun close() {
        archives.close()
        source.close()
    }
}
