package app.noti.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The engine's canonical ink archive (StrokeStore::encode): little-endian magic "NTI1",
 * version 1, a stroke count, then per stroke its page, capsule count, premultiplied RGBA
 * and capsules (x0 y0 r0 x1 y1 r1) in page units. Any thread.
 */
object InkArchive {
    class Stroke(val page: Int, val color: FloatArray, val capsules: FloatArray)

    private const val MAGIC = 0x3149544e
    private const val VERSION = 1

    fun decode(bytes: ByteArray?): List<Stroke> {
        if (bytes == null) return emptyList()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 12 && b.int == MAGIC && b.int == VERSION) { "Invalid ink file" }
        val count = b.int
        require(count in 0..262144)
        val result = ArrayList<Stroke>(count)
        repeat(count) {
            require(b.remaining() >= 24)
            val page = b.int; val n = b.int
            require(page >= 0 && n > 0 && n <= (b.remaining() - 16) / 24)
            val color = FloatArray(4) { b.float.also { require(it.isFinite() && it in 0f..1f) } }
            val caps = FloatArray(n * 6) { b.float.also { require(it.isFinite()) } }
            result += Stroke(page, color, caps)
        }
        require(!b.hasRemaining())
        return result
    }

    fun encode(strokes: List<Stroke>): ByteArray {
        val size = 12 + strokes.sumOf { 24 + it.capsules.size * 4 }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(VERSION).putInt(strokes.size)
        for (s in strokes) {
            b.putInt(s.page).putInt(s.capsules.size / 6)
            s.color.forEach { b.putFloat(it) }
            s.capsules.forEach { b.putFloat(it) }
        }
        return b.array()
    }
}
