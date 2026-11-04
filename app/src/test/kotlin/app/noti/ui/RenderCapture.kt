package app.noti.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.math.roundToInt

/**
 * Render the actual Compose host using Robolectric's native Android Canvas. PixelCopy
 * waits for a physical display callback that isn't available in a host graphics test.
 */
internal fun ComposeContentTestRule.captureRenderedView(view: View, tag: String): Bitmap {
    waitForIdle()
    val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    lateinit var result: Bitmap
    runOnUiThread {
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        result = Bitmap.createBitmap(full, bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.width.roundToInt(), bounds.height.roundToInt())
        if (result !== full) full.recycle()
    }
    return result
}
