package app.noti.transcribe

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws capsules the way the engine does: each pixel's coverage is clamp(0.5 - sdf) of a
 * capsule (noti::capsule_sdf, engine/include/noti/geometry.h), and a stroke's capsules
 * merge by maximum (GL_MAX in gl_renderer.cpp). Coverage per pixel, 0..1, row-major.
 */
internal fun engineCoverage(capsules: FloatArray, scale: Float, width: Int, height: Int): FloatArray {
    val out = FloatArray(width * height)
    for (i in capsules.indices step 6) {
        val x0 = capsules[i] * scale; val y0 = capsules[i + 1] * scale; val r0 = capsules[i + 2] * scale
        val x1 = capsules[i + 3] * scale; val y1 = capsules[i + 4] * scale; val r1 = capsules[i + 5] * scale
        val lx = max(0, floor(min(x0 - r0, x1 - r1) - 1).toInt()); val hx = min(width - 1, ceil(max(x0 + r0, x1 + r1) + 1).toInt())
        val ly = max(0, floor(min(y0 - r0, y1 - r1) - 1).toInt()); val hy = min(height - 1, ceil(max(y0 + r0, y1 + r1) + 1).toInt())
        for (y in ly..hy) for (x in lx..hx) {
            val cov = (0.5f - capsuleSdf(x + 0.5f, y + 0.5f, x0, y0, r0, x1, y1, r1)).coerceIn(0f, 1f)
            if (cov > out[y * width + x]) out[y * width + x] = cov
        }
    }
    return out
}

/** A line-by-line port of noti::capsule_sdf. */
internal fun capsuleSdf(px: Float, py: Float, cx0: Float, cy0: Float, r0: Float, cx1: Float, cy1: Float, r1: Float): Float {
    val bx = cx1 - cx0; val by = cy1 - cy0
    val h = bx * bx + by * by
    val ax = px - cx0; val ay = py - cy0
    val dr = r0 - r1
    if (h <= dr * dr) {
        val d0 = sqrt(ax * ax + ay * ay) - r0
        val qx = px - cx1; val qy = py - cy1
        return min(d0, sqrt(qx * qx + qy * qy) - r1)
    }
    val invH = 1f / h
    val qx = abs(ax * by - ay * bx) * invH
    val qy = (ax * bx + ay * by) * invH
    val cx = sqrt(h - dr * dr); val cy = dr
    val k = cx * qy - cy * qx
    val m = cx * qx + cy * qy
    val n = qx * qx + qy * qy
    if (k < 0f) return sqrt(h * n) - r0
    if (k > cx) return sqrt(h * (n + 1f - 2f * qy)) - r1
    return m - r0
}
