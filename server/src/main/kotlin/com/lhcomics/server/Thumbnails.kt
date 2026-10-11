package com.lhcomics.server

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * 서버에서 이미지를 줄여 보낸다. 집 밖(LTE)에서 볼 때 원본 수 MB 대신
 * 화면 폭에 맞춘 JPEG을 보내 데이터 사용량과 iOS Safari 메모리 부담을 줄인다.
 */
object Thumbnails {
    init {
        ImageIO.setUseCache(false)
    }

    /** 폭이 [width]보다 크면 줄인 JPEG, 아니거나 디코딩할 수 없으면 null(원본 사용). */
    fun resize(bytes: ByteArray, width: Int, quality: Float = 0.85f): ByteArray? {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (src.width <= width) return null
        val height = (src.height.toLong() * width / src.width).toInt().coerceAtLeast(1)
        val dst = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, width, height, java.awt.Color.WHITE, null)
        g.dispose()
        return encodeJpeg(dst, quality)
    }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.write(null, IIOImage(image, null, null), param)
            writer.dispose()
        }
        return out.toByteArray()
    }
}

/** 표지 썸네일 디스크 캐시. */
class CoverCache(private val dir: File) {
    fun get(sourceId: String, location: String, produce: () -> ByteArray?): ByteArray? {
        val key = MessageDigest.getInstance("SHA-1").digest("$sourceId|$location".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(dir, "$key.jpg")
        if (file.exists()) return file.readBytes()
        val bytes = produce() ?: return null
        dir.mkdirs()
        val tmp = File(dir, "$key.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
        return bytes
    }
}
