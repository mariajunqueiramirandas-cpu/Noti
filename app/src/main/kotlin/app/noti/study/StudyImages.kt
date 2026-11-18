package app.noti.study

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

object StudyImages {
    /** One page as a bitmap [widthPx] wide (source previews). */
    fun renderPage(source: File, index: Int, widthPx: Int): Bitmap? = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            if (index !in 0 until renderer.pageCount) return null
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(64, 2400)
                val h = (w * page.height.toFloat() / page.width).roundToInt().coerceIn(64, 4000)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }.getOrNull()
}
