package com.lhcomics.core.archive

import com.lhcomics.core.io.RandomAccessChannel
import com.lhcomics.core.io.RandomAccessData
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * ZIP/CBZ. 중앙 디렉터리만 읽어 목록을 만들고, 페이지는 요청할 때 해당 엔트리만 읽는다.
 */
class ZipReader(private val data: RandomAccessData, fallbackCharset: String = "") : ArchiveReader {
    private val zip: ZipFile = ZipFile.builder()
        .setSeekableByteChannel(RandomAccessChannel(data))
        // 원본 바이트를 잃지 않도록 ISO-8859-1로 연 뒤 이름은 직접 디코딩한다.
        .setCharset(Charsets.ISO_8859_1)
        .setUseUnicodeExtraFields(true)
        // 기본값은 열 때 모든 엔트리의 로컬 헤더를 읽는데, 네트워크에서는 엔트리마다 왕복이 생긴다.
        // 중앙 디렉터리만 읽고 로컬 헤더는 페이지를 실제로 열 때 읽는다.
        .setIgnoreLocalFileHeader(true)
        .get()

    private val legacyCharset: Charset = resolveCharset(fallbackCharset)
    private val byEntry = HashMap<ArchiveEntry, ZipArchiveEntry>()

    override val entries: List<ArchiveEntry> = zip.entries.toList().map { raw ->
        val entry = ArchiveEntry(normalizeEntryPath(decodeName(raw)), raw.size, raw.isDirectory)
        byEntry[entry] = raw
        entry
    }

    private fun decodeName(raw: ZipArchiveEntry): String {
        if (raw.generalPurposeBit.usesUTF8ForNames()) return raw.name
        val bytes = raw.rawName ?: return raw.name
        // 유니코드 확장 필드가 있으면 라이브러리가 이미 올바르게 디코딩했다.
        if (raw.name != String(bytes, Charsets.ISO_8859_1)) return raw.name
        return strictUtf8(bytes) ?: String(bytes, legacyCharset)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    // 채널 위치를 공유하므로 엔트리 읽기를 직렬화한다.
    @Synchronized
    override fun copyTo(entry: ArchiveEntry, out: OutputStream) {
        val raw = byEntry[entry] ?: throw NoSuchElementException(entry.path)
        zip.getInputStream(raw).use { it.copyTo(out) }
    }

    override fun close() {
        zip.close()
        data.close()
    }

    companion object {
        /** 한국어 윈도우에서 만든 zip이 가장 흔하므로 CP949를 기본으로 쓴다. */
        fun resolveCharset(name: String): Charset {
            val candidates = listOfNotNull(name.ifEmpty { null }, "MS949", "x-windows-949", "windows-949", "EUC-KR")
            for (c in candidates) {
                runCatching { return Charset.forName(c) }
            }
            return Charsets.UTF_8
        }
    }
}
