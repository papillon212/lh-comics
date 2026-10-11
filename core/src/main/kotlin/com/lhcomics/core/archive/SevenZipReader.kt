package com.lhcomics.core.archive

import com.lhcomics.core.io.RandomAccessChannel
import com.lhcomics.core.io.RandomAccessData
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.OutputStream

/** 7Z/CB7. SevenZFile은 스레드 안전하지 않아 읽기를 직렬화한다. */
class SevenZipReader(private val data: RandomAccessData) : ArchiveReader {
    private val file: SevenZFile = SevenZFile.builder()
        .setSeekableByteChannel(RandomAccessChannel(data))
        .get()

    private val byEntry = HashMap<ArchiveEntry, SevenZArchiveEntry>()

    override val entries: List<ArchiveEntry> = file.entries.filter { !it.isAntiItem }.map { raw ->
        val entry = ArchiveEntry(normalizeEntryPath(raw.name), raw.size, raw.isDirectory)
        byEntry[entry] = raw
        entry
    }

    @Synchronized
    override fun copyTo(entry: ArchiveEntry, out: OutputStream) {
        val raw = byEntry[entry] ?: throw NoSuchElementException(entry.path)
        file.getInputStream(raw).use { it.copyTo(out) }
    }

    override fun close() {
        file.close()
        data.close()
    }
}
