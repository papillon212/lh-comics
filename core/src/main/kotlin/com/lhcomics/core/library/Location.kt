package com.lhcomics.core.library

import com.lhcomics.core.archive.normalizeEntryPath
import com.lhcomics.core.source.Paths

/**
 * 소스 안의 위치. 압축 파일 경계는 "!/"로 표시하고, 중첩 압축은 이를 반복한다.
 *
 * - `/manga/One`                   폴더
 * - `/manga/One.zip!/`             압축 파일의 루트
 * - `/manga/All.zip!/vol1.zip!/ch` All.zip 안의 vol1.zip 안의 ch 폴더
 */
data class Location(
    /** 첫 항목은 소스 경로, 이후는 바로 앞 압축 파일 안의 경로. */
    val archives: List<String>,
    /** 가장 안쪽 압축 파일 안의 폴더(압축 밖이면 소스 경로). */
    val dir: String,
) {
    val inArchive: Boolean get() = archives.isNotEmpty()

    val name: String
        get() = when {
            dir.isNotEmpty() && dir != "/" -> dir.substringAfterLast('/')
            inArchive -> archives.last().substringAfterLast('/')
            else -> ""
        }

    fun childDir(name: String): Location =
        if (inArchive) copy(dir = normalizeEntryPath("$dir/$name")) else copy(dir = Paths.join(dir, name))

    fun childArchive(name: String): Location {
        val path = if (inArchive) normalizeEntryPath("$dir/$name") else Paths.join(dir, name)
        return Location(archives + path, "")
    }

    fun parent(): Location? = when {
        inArchive && dir.isNotEmpty() -> copy(dir = dir.substringBeforeLast('/', ""))
        inArchive -> {
            val archivePath = archives.last()
            val rest = archives.dropLast(1)
            if (rest.isEmpty()) Location(emptyList(), Paths.parent(archivePath))
            else Location(rest, archivePath.substringBeforeLast('/', ""))
        }
        dir == "/" -> null
        else -> Location(emptyList(), Paths.parent(dir))
    }

    fun encode(): String =
        if (!inArchive) dir else archives.joinToString(SEP) + SEP + dir

    override fun toString() = encode()

    companion object {
        const val SEP = "!/"
        val ROOT = Location(emptyList(), "/")

        fun parse(value: String): Location {
            val parts = value.split(SEP)
            if (parts.size == 1) return Location(emptyList(), Paths.normalize(value))
            val archives = parts.dropLast(1).mapIndexed { i, p -> if (i == 0) Paths.normalize(p) else normalizeEntryPath(p) }
            return Location(archives, normalizeEntryPath(parts.last()))
        }
    }
}
