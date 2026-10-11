package com.lhcomics.core.library

import com.lhcomics.core.archive.ArchiveReader
import com.lhcomics.core.archive.Archives
import com.lhcomics.core.io.FileRandomAccess
import com.lhcomics.core.source.FileSource
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * 열린 압축 리더를 재사용하는 LRU 캐시. 중첩 압축은 바깥 압축에서 안쪽 파일을
 * 캐시 폴더로 풀어낸 뒤 로컬 파일로 연다. 풀린 파일은 [maxNestedBytes]를 넘으면 오래된 순으로 지운다.
 */
class ArchiveCache(
    private val source: FileSource,
    private val nestedDir: File,
    private val charset: String = "",
    private val maxOpen: Int = 6,
    private val maxNestedBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    private class Slot(val reader: ArchiveReader) {
        var refs = 0
        var evicted = false
    }

    private val slots = object : LinkedHashMap<List<String>, Slot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, Slot>): Boolean {
            if (size <= maxOpen) return false
            val slot = eldest.value
            slot.evicted = true
            if (slot.refs == 0) runCatching { slot.reader.close() }
            return true
        }
    }

    /** 리더를 빌려 쓰는 동안에는 LRU에서 밀려나도 닫히지 않는다. */
    fun <T> use(chain: List<String>, block: (ArchiveReader) -> T): T {
        val slot = acquire(chain)
        try {
            return block(slot.reader)
        } finally {
            release(slot)
        }
    }

    private fun acquire(chain: List<String>): Slot {
        synchronized(slots) {
            slots[chain]?.let { it.refs++; return it }
        }
        // 압축 열기는 네트워크를 타므로 락 밖에서 한다.
        val reader = openReader(chain)
        synchronized(slots) {
            slots[chain]?.let {
                runCatching { reader.close() }
                it.refs++
                return it
            }
            val slot = Slot(reader).also { it.refs = 1 }
            slots[chain] = slot
            return slot
        }
    }

    private fun release(slot: Slot) = synchronized(slots) {
        slot.refs--
        if (slot.evicted && slot.refs == 0) runCatching { slot.reader.close() }
    }

    private fun openReader(chain: List<String>): ArchiveReader {
        require(chain.isNotEmpty())
        if (chain.size == 1) {
            val data = source.openRandom(chain[0])
            return try {
                Archives.open(chain[0], data, charset)
            } catch (e: Exception) {
                data.close()
                throw e
            }
        }
        val innerPath = chain.last()
        val file = use(chain.dropLast(1)) { outer ->
            val entry = outer.entries.firstOrNull { it.path == innerPath && !it.isDirectory }
                ?: throw FileNotFoundException(innerPath)
            extractNested(chain, entry.size) { out -> outer.copyTo(entry, out) }
        }
        val data = FileRandomAccess(file)
        return try {
            Archives.open(innerPath, data, charset)
        } catch (e: Exception) {
            data.close()
            throw e
        }
    }

    private fun extractNested(chain: List<String>, size: Long, write: (java.io.OutputStream) -> Unit): File {
        val key = MessageDigest.getInstance("SHA-1")
            .digest("${source.config.id}|${chain.joinToString("|")}|$size".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ext = chain.last().substringAfterLast('.', "bin")
        val target = File(nestedDir, "$key.$ext")
        synchronized(this) {
            if (target.exists() && target.length() == size) {
                target.setLastModified(System.currentTimeMillis())
                return target
            }
            nestedDir.mkdirs()
            trim(size)
            val tmp = File(nestedDir, "$key.part")
            tmp.outputStream().buffered().use(write)
            if (!tmp.renameTo(target)) throw java.io.IOException("cannot cache nested archive")
            return target
        }
    }

    private fun trim(incoming: Long) {
        val files = nestedDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() } + incoming
        for (f in files) {
            if (total <= maxNestedBytes) break
            total -= f.length()
            f.delete()
        }
    }

    fun close() = synchronized(slots) {
        slots.values.forEach { runCatching { it.reader.close() } }
        slots.clear()
    }
}
