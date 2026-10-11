package com.lhcomics.core.source

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.lhcomics.core.io.CachedRandomAccess
import com.lhcomics.core.io.RandomAccessData
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 공유 폴더. 오래된 앱들이 SMB1에 묶여 있는 문제를 피하려고 smbj를 쓴다.
 * 안드로이드에는 NTLM에 필요한 MD4가 없어서 BouncyCastle 보안 공급자를 지정한다.
 */
class SmbSource(override val config: SourceConfig) : FileSource {
    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        closeQuietly()
        val conn = if (config.port > 0) client.connect(config.host, config.port) else client.connect(config.host)
        val auth = if (config.username.isEmpty()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifEmpty { null })
        }
        val sess = conn.authenticate(auth)
        val disk = sess.connectShare(config.share) as? DiskShare
            ?: throw IllegalStateException("'${config.share}' is not a disk share")
        connection = conn
        session = sess
        share = disk
        return disk
    }

    /** 연결이 끊긴 경우 한 번 다시 연결해서 재시도한다. */
    private fun <T> withShare(block: (DiskShare) -> T): T = try {
        block(share())
    } catch (e: Exception) {
        synchronized(this) { closeQuietly() }
        block(share())
    }

    private fun smbPath(path: String): String =
        Paths.normalize("${config.basePath}/$path").removePrefix("/").replace('/', '\\')

    override fun list(path: String): List<FileEntry> = withShare { disk ->
        disk.list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value == 0L }
            .map {
                val isDir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                FileEntry(
                    name = it.fileName,
                    path = Paths.join(path, it.fileName),
                    isDirectory = isDir,
                    size = it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }
    }

    private fun openFile(path: String) = withShare { disk ->
        disk.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    override fun openRandom(path: String): RandomAccessData {
        val file = openFile(path)
        val raw = object : RandomAccessData {
            override val size = file.fileInformation.standardInformation.endOfFile
            override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (position >= size) return -1
                return file.read(buf, position, off, len).takeIf { it > 0 } ?: -1
            }
            override fun close() = file.close()
        }
        return CachedRandomAccess(raw)
    }

    override fun readBytes(path: String): ByteArray = openFile(path).use { file ->
        val size = file.fileInformation.standardInformation.endOfFile.toInt()
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = file.read(out, done.toLong(), done, size - done)
            if (n <= 0) break
            done += n
        }
        out
    }

    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null
        session = null
        connection = null
    }

    override fun close() {
        synchronized(this) { closeQuietly() }
        client.close()
    }
}
