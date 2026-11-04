package app.noti.ink

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver

/**
 * Tracks window-relative bounds, including movement of an ancestor, translations,
 * system-inset changes and Compose placement that doesn't lay out the anchor itself.
 * All methods and callbacks run on the main thread. Only changed, nonempty bounds
 * are sent to the ink thread; no work is added to pen input dispatch.
 */
internal class InkAnchorBounds(private val anchor: View, private val onBounds: (Rect) -> Unit) :
    ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private var tree: ViewTreeObserver? = null
    private var last: Rect? = null
    private var started = false
    private val location = IntArray(2)

    fun start() {
        if (started) return
        started = true
        anchor.addOnAttachStateChangeListener(this)
        if (anchor.isAttachedToWindow) observe()
    }

    private fun observe() {
        tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        tree = anchor.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        update()
    }

    fun current(): Rect {
        anchor.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
    }

    private fun update() {
        if (!anchor.isAttachedToWindow || anchor.width <= 0 || anchor.height <= 0) return
        val bounds = current()
        if (bounds != last) {
            last = bounds
            onBounds(Rect(bounds))
        }
    }

    override fun onPreDraw(): Boolean { update(); return true }
    override fun onViewAttachedToWindow(v: View) { last = null; observe() }
    override fun onViewDetachedFromWindow(v: View) {
        tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        tree = null
        last = null
    }

    fun stop() {
        started = false
        anchor.removeOnAttachStateChangeListener(this)
        tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        tree = null
        last = null
    }
}
