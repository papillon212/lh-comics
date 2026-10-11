package com.lhcomics.core.source

import com.lhcomics.core.io.RandomAccessData
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File

data class FileEntry(
    val name: String,
    /** 소스 루트 기준 경로. 항상 "/"로 시작하고 "/"로 구분한다. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/** 하나의 저장소(로컬 폴더, SMB 공유, FTP 서버 등)에 대한 읽기 전용 접근. */
interface FileSource : Closeable {
    val config: SourceConfig

    fun list(path: String): List<FileEntry>

    /** 압축 파일처럼 일부만 읽어야 하는 파일. 반환값은 호출자가 닫는다. */
    fun openRandom(path: String): RandomAccessData

    /** 이미지 한 장처럼 통째로 읽는 파일. */
    fun readBytes(path: String): ByteArray

    override fun close() {}
}

@Serializable
enum class SourceType { LOCAL, SMB, FTP, WEBDAV }

@Serializable
data class SourceConfig(
    val id: String,
    val name: String,
    val type: SourceType,
    /** LOCAL: 디렉터리 경로(서버) 또는 SAF 트리 URI(안드로이드). */
    val host: String = "",
    val port: Int = 0,
    /** SMB 공유 이름. */
    val share: String = "",
    /** 소스 안에서 루트로 삼을 하위 경로. */
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    /** WebDAV는 https, FTP는 FTPS. */
    val secure: Boolean = false,
    /** 오래된 NAS/FTP의 한글 파일명 대응용 (예: "CP949"). 비우면 UTF-8. */
    val encoding: String = "",
)

object Paths {
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayDeque<String>()
        for (p in parts) if (p == "..") stack.removeLastOrNull() else stack.addLast(p)
        return "/" + stack.joinToString("/")
    }

    fun join(parent: String, name: String): String = normalize("$parent/$name")

    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "").ifEmpty { "/" }

    fun name(path: String): String = normalize(path).substringAfterLast('/')
}

/**
 * 플랫폼 공통 소스 생성기. 안드로이드는 LOCAL(SAF)을 직접 구현하므로
 * [localFactory]로 덮어쓴다.
 */
class SourceFactory(
    private val cacheDir: File,
    private val localFactory: (SourceConfig) -> FileSource = { LocalDirSource(it) },
) {
    fun create(config: SourceConfig): FileSource = when (config.type) {
        SourceType.LOCAL -> localFactory(config)
        SourceType.SMB -> SmbSource(config)
        SourceType.FTP -> FtpSource(config, File(cacheDir, "ftp"))
        SourceType.WEBDAV -> WebDavSource(config)
    }
}
