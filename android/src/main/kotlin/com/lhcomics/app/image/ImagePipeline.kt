package com.lhcomics.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.lhcomics.app.data.LibraryManager
import com.lhcomics.core.library.Images
import com.lhcomics.core.library.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** 뷰어 페이지 한 장. */
data class PageImage(val sourceId: String, val location: String)

/** 목록에 쓰는 표지 썸네일. 폴더/압축 위치를 받는다. */
data class CoverImage(val sourceId: String, val location: String)

class PageFetcher(private val data: PageImage, private val libraries: LibraryManager) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = withContext(Dispatchers.IO) {
            libraries.library(data.sourceId).read(Location.parse(data.location))
        }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val libraries: LibraryManager) : Fetcher.Factory<PageImage> {
        override fun create(data: PageImage, options: Options, imageLoader: ImageLoader) = PageFetcher(data, libraries)
    }
}

/**
 * 표지는 원본(수 MB)을 매번 네트워크로 읽으면 느려서 작은 JPEG로 줄여 디스크에 보관한다.
 * 다른 앱들의 "NAS 폴더 로딩이 느리다"는 불만을 줄이는 핵심 부분이다.
 */
class CoverFetcher(
    private val data: CoverImage,
    private val dir: File,
    private val libraries: LibraryManager,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val file = File(dir, key(data) + ".jpg")
        if (!file.exists()) {
            val library = libraries.library(data.sourceId)
            val location = Location.parse(data.location)
            // 이미지 파일 자체면 그대로, 폴더/압축이면 첫 페이지를 표지로 쓴다.
            val pageLocation = if (data.location.isImagePath()) location
            else library.cover(location)?.let { Location.parse(it.location) } ?: return@withContext null
            val thumb = downscale(library.read(pageLocation)) ?: return@withContext null
            dir.mkdirs()
            val tmp = File(dir, file.name + ".part")
            tmp.writeBytes(thumb)
            tmp.renameTo(file)
        }
        SourceFetchResult(
            source = ImageSource(file = file.absolutePath.toPath(), fileSystem = FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private fun String.isImagePath() = Images.isImage(this) && !endsWith(Location.SEP)

    private fun downscale(bytes: ByteArray, target: Int = 360): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = target.toFloat() / bitmap.width
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, target, (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    class Factory(context: Context, private val libraries: LibraryManager) : Fetcher.Factory<CoverImage> {
        private val dir = File(context.cacheDir, "covers")
        override fun create(data: CoverImage, options: Options, imageLoader: ImageLoader) = CoverFetcher(data, dir, libraries)
    }

    companion object {
        fun key(data: CoverImage): String = MessageDigest.getInstance("SHA-1")
            .digest("${data.sourceId}|${data.location}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

object ImageKeyer {
    val Page = object : Keyer<PageImage> {
        override fun key(data: PageImage, options: Options) = "page:${data.sourceId}|${data.location}"
    }
    val Cover = object : Keyer<CoverImage> {
        override fun key(data: CoverImage, options: Options) = "cover:${data.sourceId}|${data.location}"
    }
}
