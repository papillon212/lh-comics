package com.lhcomics.core.source

import com.lhcomics.core.io.FileRandomAccess
import com.lhcomics.core.io.RandomAccessData
import java.io.File
import java.io.FileNotFoundException

/** 일반 파일 시스템 디렉터리. 서버(도커 볼륨)와 테스트에서 쓴다. */
class LocalDirSource(override val config: SourceConfig) : FileSource {
    private val root = File(config.host, config.basePath).canonicalFile

    private fun resolve(path: String): File {
        val file = File(root, Paths.normalize(path).removePrefix("/")).canonicalFile
        // 심볼릭 링크나 ".."로 루트 밖을 읽지 못하게 막는다.
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw SecurityException("path escapes source root: $path")
        }
        return file
    }

    override fun list(path: String): List<FileEntry> {
        val dir = resolve(path)
        val files = dir.listFiles() ?: throw FileNotFoundException(path)
        return files.filter { !it.isHidden }.map {
            FileEntry(it.name, Paths.join(path, it.name), it.isDirectory, it.length(), it.lastModified())
        }
    }

    override fun openRandom(path: String): RandomAccessData = FileRandomAccess(resolve(path))

    override fun readBytes(path: String): ByteArray = resolve(path).readBytes()
}
