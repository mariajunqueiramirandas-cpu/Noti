package app.noti

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import app.noti.ink.InkLayer

/** A blank page with the low-latency ink layer over it. */
class MainActivity : Activity() {
    private var ink: InkLayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val canvas = View(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(canvas)
        ink = InkLayer(canvas).apply {
            setColor(0.1f, 0.1f, 0.12f)
            setWidth(5f)
        }
    }

    override fun onDestroy() {
        ink?.release()
        ink = null
        super.onDestroy()
    }
}
