package app.noti.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Result of copying a PDF into the app: everything LibraryStore.addPdf needs. */
data class ImportedPdf(val id: String, val name: String, val pageCount: Int)

/**
 * Copies a PDF picked with the system file picker into [pdfDir] as `<id>.pdf`, checks
 * that it opens, counts its pages and renders a first-page thumbnail (`<id>.png`).
 * Blocking: call from a background thread. Throws with a user-readable message on failure.
 */
object PdfImport {
    private const val THUMB_WIDTH = 360

    fun import(context: Context, uri: Uri, pdfDir: File): ImportedPdf {
        val id = UUID.randomUUID().toString()
        val dest = File(pdfDir, "$id.pdf")
        val tmp = File(pdfDir, "$id.pdf.part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open that file.")
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            if (!tmp.renameTo(dest)) error("Couldn't save the PDF.")
            val pages = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val renderer = try {
                    PdfRenderer(fd)
                } catch (e: SecurityException) {
                    error("This PDF is password-protected.")
                } catch (e: Exception) {
                    error("This file isn't a PDF Noti can open.")
                }
                renderer.use { r ->
                    if (r.pageCount == 0) error("This PDF has no pages.")
                    writeThumbnail(r, File(pdfDir, "$id.png"))
                    r.pageCount
                }
            }
            return ImportedPdf(id, displayName(context, uri), pages)
        } catch (e: Throwable) {
            tmp.delete()
            dest.delete()
            File(pdfDir, "$id.png").delete()
            throw IllegalStateException(e.message ?: "Couldn't import that PDF.", e)
        }
    }

    private fun writeThumbnail(r: PdfRenderer, out: File) {
        r.openPage(0).use { page ->
            val w = THUMB_WIDTH
            val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, w * 4)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "PDF"
        return name.substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "PDF" }
    }
}
