package com.lhcomics.core.archive

import com.lhcomics.core.io.RandomAccessData
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

data class ArchiveEntry(
    /** 압축 파일 안의 경로. "/"로 구분하고 앞에 "/"가 없다. */
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
)

interface ArchiveReader : Closeable {
    val entries: List<ArchiveEntry>

    /** 큰 내부 압축 파일을 메모리에 올리지 않고 파일로 풀 때 쓴다. */
    fun copyTo(entry: ArchiveEntry, out: OutputStream)

    fun readBytes(entry: ArchiveEntry): ByteArray {
        val out = ByteArrayOutputStream(entry.size.coerceIn(32, Int.MAX_VALUE.toLong()).toInt())
        copyTo(entry, out)
        return out.toByteArray()
    }
}

enum class ArchiveFormat(val extensions: Set<String>) {
    ZIP(setOf("zip", "cbz")),
    RAR(setOf("rar", "cbr")),
    SEVEN_ZIP(setOf("7z", "cb7"));

    companion object {
        fun fromName(name: String): ArchiveFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }

        /** 확장자가 틀린 파일을 위해 시그니처로 판별한다. */
        fun sniff(data: RandomAccessData): ArchiveFormat? {
            if (data.size < 8) return null
            val head = ByteArray(8)
            data.readFully(0, head)
            return when {
                head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> ZIP
                head.copyOf(4).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21)) -> RAR
                head.copyOf(6).contentEquals(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)) -> SEVEN_ZIP
                else -> null
            }
        }
    }
}

class UnsupportedArchiveException(message: String) : IOException(message)

object Archives {
    fun isArchive(name: String) = ArchiveFormat.fromName(name) != null

    fun open(name: String, data: RandomAccessData, fallbackCharset: String = ""): ArchiveReader {
        val format = ArchiveFormat.sniff(data) ?: ArchiveFormat.fromName(name)
            ?: throw UnsupportedArchiveException("unknown archive: $name")
        return when (format) {
            ArchiveFormat.ZIP -> ZipReader(data, fallbackCharset)
            ArchiveFormat.RAR -> RarReader(data)
            ArchiveFormat.SEVEN_ZIP -> SevenZipReader(data)
        }
    }
}

internal fun normalizeEntryPath(path: String) =
    path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
