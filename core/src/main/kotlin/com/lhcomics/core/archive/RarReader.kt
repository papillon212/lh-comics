package com.lhcomics.core.archive

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import com.lhcomics.core.io.RandomAccessData
import java.io.EOFException
import java.io.OutputStream

/**
 * RAR/CBR (RAR4). junrar는 RAR5를 지원하지 않아 명확한 오류로 알린다.
 * 분할 압축(.part2.rar)은 지원하지 않는다.
 */
class RarReader(private val data: RandomAccessData) : ArchiveReader {
    private val archive: Archive = try {
        Archive(SingleVolumeManager(data), null, null)
    } catch (e: UnsupportedRarV5Exception) {
        throw UnsupportedArchiveException("RAR5 형식은 아직 지원하지 않습니다")
    }

    private val byEntry = HashMap<ArchiveEntry, FileHeader>()

    override val entries: List<ArchiveEntry> = archive.fileHeaders.map { header ->
        val name = header.fileName
        val entry = ArchiveEntry(normalizeEntryPath(name), header.fullUnpackSize, header.isDirectory)
        byEntry[entry] = header
        entry
    }

    @Synchronized
    override fun copyTo(entry: ArchiveEntry, out: OutputStream) {
        val header = byEntry[entry] ?: throw NoSuchElementException(entry.path)
        archive.extractFile(header, out)
    }

    override fun close() {
        archive.close()
        data.close()
    }

    private class SingleVolumeManager(private val data: RandomAccessData) : VolumeManager {
        override fun nextVolume(archive: Archive, last: Volume?): Volume? =
            if (last == null) DataVolume(archive, data) else null
    }

    private class DataVolume(private val archive: Archive, private val data: RandomAccessData) : Volume {
        override fun getChannel(): SeekableReadOnlyByteChannel = DataChannel(data)
        override fun getLength(): Long = data.size
        override fun getArchive(): Archive = archive
    }

    private class DataChannel(private val data: RandomAccessData) : SeekableReadOnlyByteChannel {
        private var pos = 0L
        override fun getPosition() = pos
        override fun setPosition(p: Long) {
            pos = p
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) == 1) b[0].toInt() and 0xFF else -1
        }

        override fun read(buffer: ByteArray, off: Int, count: Int): Int {
            if (count == 0) return 0
            val n = data.read(pos, buffer, off, count)
            if (n > 0) pos += n
            return n
        }

        override fun readFully(buffer: ByteArray, count: Int): Int {
            var done = 0
            while (done < count) {
                val n = read(buffer, done, count - done)
                if (n < 0) throw EOFException()
                done += n
            }
            return done
        }

        // 채널을 닫아도 원본은 리더가 닫을 때 정리한다.
        override fun close() {}
    }
}
