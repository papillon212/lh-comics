package com.lhcomics.core.source

import com.lhcomics.core.io.FileRandomAccess
import com.lhcomics.core.io.RandomAccessData
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * FTP/FTPS. FTP는 임의 위치 읽기가 비싸서(매번 데이터 연결) 압축 파일은
 * 캐시 폴더에 내려받은 뒤 로컬 파일로 연다. FTPClient는 스레드 안전하지 않아 직렬화한다.
 */
class FtpSource(override val config: SourceConfig, private val cacheDir: File) : FileSource {
    private var client: FTPClient? = null

    @Synchronized
    private fun <T> withClient(block: (FTPClient) -> T): T = try {
        block(connected())
    } catch (e: IOException) {
        disconnect()
        block(connected())
    }

    private fun connected(): FTPClient {
        client?.takeIf { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) }?.let { return it }
        disconnect()
        val c = if (config.secure) FTPSClient() else FTPClient()
        c.controlEncoding = config.encoding.ifEmpty { "UTF-8" }
        c.connectTimeout = 15_000
        c.connect(config.host, if (config.port > 0) config.port else 21)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("FTP refused: ${c.replyString}")
        val user = config.username.ifEmpty { "anonymous" }
        if (!c.login(user, config.password)) throw IOException("FTP login failed: ${c.replyString}")
        if (c is FTPSClient) {
            c.execPBSZ(0)
            c.execPROT("P")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        if (config.encoding.isEmpty()) c.sendCommand("OPTS UTF8 ON")
        client = c
        return c
    }

    private fun disconnect() {
        client?.let { runCatching { it.logout() }; runCatching { it.disconnect() } }
        client = null
    }

    private fun ftpPath(path: String) = Paths.normalize("${config.basePath}/$path")

    override fun list(path: String): List<FileEntry> = withClient { c ->
        c.listFiles(ftpPath(path))
            .filter { it != null && it.name != "." && it.name != ".." && !it.name.startsWith(".") }
            .map {
                FileEntry(
                    name = it.name,
                    path = Paths.join(path, it.name),
                    isDirectory = it.isDirectory,
                    size = it.size,
                    modified = it.timestamp?.timeInMillis ?: 0L,
                )
            }
    }

    override fun readBytes(path: String): ByteArray = withClient { c ->
        val out = ByteArrayOutputStream()
        if (!c.retrieveFile(ftpPath(path), out)) throw IOException("FTP download failed: ${c.replyString}")
        out.toByteArray()
    }

    override fun openRandom(path: String): RandomAccessData {
        val key = MessageDigest.getInstance("SHA-1")
            .digest("${config.id}|${ftpPath(path)}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(cacheDir, key)
        if (!target.exists()) {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "$key.part")
            withClient { c ->
                tmp.outputStream().use { out ->
                    if (!c.retrieveFile(ftpPath(path), out)) throw IOException("FTP download failed: ${c.replyString}")
                }
            }
            tmp.renameTo(target)
        }
        target.setLastModified(System.currentTimeMillis())
        return FileRandomAccess(target)
    }

    override fun close() = synchronized(this) { disconnect() }
}
