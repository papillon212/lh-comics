package com.lhcomics.core.io

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * 위치 기반 읽기 인터페이스. 로컬 파일, SMB, WebDAV(Range) 등 원본에 상관없이
 * 압축 파일 리더가 필요한 부분만 읽을 수 있게 한다.
 */
interface RandomAccessData : Closeable {
    val size: Long

    /** [position]부터 최대 [len]바이트를 읽는다. 파일 끝이면 -1. */
    fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int

    fun readFully(position: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        var done = 0
        while (done < len) {
            val n = read(position + done, buf, off + done, len - done)
            if (n < 0) throw EOFException("unexpected end at ${position + done}")
            done += n
        }
    }
}

class FileRandomAccess(file: File) : RandomAccessData {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        return raf.channel.read(ByteBuffer.wrap(buf, off, len), position)
    }

    override fun close() = raf.close()
}

class ByteArrayRandomAccess(private val bytes: ByteArray) : RandomAccessData {
    override val size: Long = bytes.size.toLong()

    override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        val n = minOf(len.toLong(), size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buf, off, n)
        return n
    }

    override fun close() {}
}

/**
 * 네트워크 원본 앞단에 두는 블록 LRU 캐시. 압축 파일의 중앙 디렉터리처럼
 * 같은 영역을 여러 번 읽는 패턴에서 왕복 횟수를 크게 줄인다.
 */
class CachedRandomAccess(
    private val delegate: RandomAccessData,
    private val blockSize: Int = 256 * 1024,
    maxBlocks: Int = 64,
) : RandomAccessData {
    override val size: Long get() = delegate.size

    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > maxBlocks
    }

    override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        val index = position / blockSize
        val block = block(index)
        val inBlock = (position - index * blockSize).toInt()
        val n = minOf(len, block.size - inBlock)
        if (n <= 0) return -1
        System.arraycopy(block, inBlock, buf, off, n)
        return n
    }

    private fun block(index: Long): ByteArray {
        synchronized(blocks) { blocks[index]?.let { return it } }
        val start = index * blockSize
        val length = minOf(blockSize.toLong(), size - start).toInt()
        val data = ByteArray(length)
        delegate.readFully(start, data, 0, length)
        synchronized(blocks) { blocks[index] = data }
        return data
    }

    override fun close() = delegate.close()
}

/** commons-compress(ZipFile, SevenZFile)용 읽기 전용 채널 어댑터. */
class RandomAccessChannel(private val data: RandomAccessData) : SeekableByteChannel {
    private var pos = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (pos >= data.size) return -1
        val len = minOf(dst.remaining().toLong(), data.size - pos).toInt()
        if (len == 0) return 0
        val tmp = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = data.read(pos + done, tmp, done, len - done)
            if (n < 0) break
            done += n
        }
        dst.put(tmp, 0, done)
        pos += done
        return if (done == 0) -1 else done
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel = apply { pos = newPosition }
    override fun size(): Long = data.size
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() {
        open = false
    }
}

fun RandomAccessData.readAllBytes(): ByteArray {
    if (size > Int.MAX_VALUE) throw IOException("file too large: $size")
    val out = ByteArray(size.toInt())
    readFully(0, out)
    return out
}
