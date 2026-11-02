package app.noti.ink

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ink surface. The native engine attaches its wet (front-buffered) and dry
 * layers as SurfaceControl children of this SurfaceView's surface; this class
 * only forwards input, and does so without allocating:
 *
 *  - unbuffered dispatch: each pen sample arrives as soon as it exists (no vsync batching)
 *  - every historical sample, with its hardware timestamp (API 34 nanos)
 *  - stylus/eraser only: fingers are rejected (palm rejection)
 *  - FLAG_CANCELED / ACTION_CANCEL roll the stroke back
 *  - hover enter/exit pre-warm / relax the pipeline (144 Hz, ADPF)
 */
class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var handle: Long = NotiInk.nativeCreate(/* horizonMs = */ 12f, /* gpuTiming = */ false, /* flipY = */ false)
    private val staging: ByteBuffer = NotiInk.nativeStaging(handle).order(ByteOrder.nativeOrder())
    private var pending = 0
    private val stats = LongArray(16)

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSPARENT)
        // Deliver pointer events (stylus) unbatched for this view (API 30+).
        requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
    }

    // ---- brush / predictor ----

    fun setColor(r: Float, g: Float, b: Float, a: Float = 1f) = NotiInk.nativeSetColor(handle, r, g, b, a)
    fun setWidth(px: Float) = NotiInk.nativeSetWidth(handle, px)
    fun setPressureCurve(gamma: Float, floor: Float) = NotiInk.nativeSetPressureCurve(handle, gamma, floor)
    /** Prediction horizon: measured software latency + feel-calibrated offset (plan §5.4). */
    fun setPredictionHorizonMs(ms: Float) = NotiInk.nativeSetHorizon(handle, ms)
    fun setPredictor(model: Int) = NotiInk.nativeSetPredictor(handle, model)

    /** See jni_bridge.cpp nativeStats for the layout (ns, except counters and 1/100 px). */
    fun latencyStats(): LongArray = stats.also { NotiInk.nativeStats(handle, it) }
    fun resetLatencyStats() = NotiInk.nativeResetStats(handle)

    fun release() {
        if (handle != 0L) {
            NotiInk.nativeDestroy(handle)
            handle = 0L
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    // ---- surface ----

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NotiInk.nativeSurfaceChanged(handle, holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NotiInk.nativeSurfaceDestroyed(handle)
    }

    // ---- input ----

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) {
            return false  // palm / finger: not ink
        }
        val recv = System.nanoTime()
        val flags = baseFlags(e, tool)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                put(e, -1, NotiInk.ACTION_DOWN, flags, recv)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    put(e, h, NotiInk.ACTION_MOVE, flags or NotiInk.FLAG_HISTORICAL, recv)
                }
                put(e, -1, NotiInk.ACTION_MOVE, flags, recv)
            }
            MotionEvent.ACTION_UP -> {
                val canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0  // API 33
                put(e, -1, if (canceled) NotiInk.ACTION_CANCEL else NotiInk.ACTION_UP, flags, recv)
            }
            MotionEvent.ACTION_CANCEL -> put(e, -1, NotiInk.ACTION_CANCEL, flags, recv)
            else -> return true
        }
        flush()
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> NotiInk.ACTION_HOVER_ENTER
            MotionEvent.ACTION_HOVER_EXIT -> NotiInk.ACTION_HOVER_EXIT
            else -> return true  // hover moves: nothing to do yet (cursor preview is Phase 4)
        }
        put(e, -1, action, 0, System.nanoTime())
        flush()
        return true
    }

    private fun baseFlags(e: MotionEvent, tool: Int): Int {
        var f = 0
        if (tool == MotionEvent.TOOL_TYPE_ERASER) f = f or NotiInk.FLAG_ERASER
        if (e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)) f = f or NotiInk.FLAG_BUTTON_PRIMARY
        if (e.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)) f = f or NotiInk.FLAG_BUTTON_SECONDARY
        return f
    }

    /** Writes one sample into the native staging buffer; h = -1 for the current sample. */
    private fun put(e: MotionEvent, h: Int, action: Short, flags: Int, recv: Long) {
        if (pending == NotiInk.STAGING_SAMPLES) flush()
        val t = if (Build.VERSION.SDK_INT >= 34) {
            if (h < 0) e.eventTimeNanos else e.getHistoricalEventTimeNanos(h)
        } else {
            (if (h < 0) e.eventTime else e.getHistoricalEventTime(h)) * 1_000_000L
        }
        val o = pending * NotiInk.SAMPLE_BYTES
        val b = staging
        b.putLong(o, t)
        b.putLong(o + 8, recv)
        if (h < 0) {
            b.putFloat(o + 16, e.x)
            b.putFloat(o + 20, e.y)
            b.putFloat(o + 24, e.pressure)
            b.putFloat(o + 28, e.getAxisValue(MotionEvent.AXIS_TILT))
            b.putFloat(o + 32, e.orientation)
        } else {
            b.putFloat(o + 16, e.getHistoricalX(h))
            b.putFloat(o + 20, e.getHistoricalY(h))
            b.putFloat(o + 24, e.getHistoricalPressure(h))
            b.putFloat(o + 28, e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h))
            b.putFloat(o + 32, e.getHistoricalOrientation(h))
        }
        b.putShort(o + 36, action)
        b.putShort(o + 38, flags.toShort())
        pending++
    }

    private fun flush() {
        if (pending > 0) {
            NotiInk.nativePush(handle, pending)
            pending = 0
        }
    }
}
