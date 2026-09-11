package com.ethran.notable.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.utils.Timing
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.ShipBook
import java.io.File


private val log = ShipBook.getLogger("renderFromFile")

/**
 * Converts a URI to a Bitmap using the provided [context] and [uri].
 *
 * @param context The context used to access the content resolver.
 * @param uri The URI of the image to be converted to a Bitmap.
 * @return The Bitmap representation of the image, or null if conversion fails.
 * https://medium.com/@munbonecci/how-to-display-an-image-loaded-from-the-gallery-using-pick-visual-media-in-jetpack-compose-df83c78a66bf
 */
fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        // Obtain the content resolver from the context
        val contentResolver: ContentResolver = context.contentResolver

        // Since the minimum SDK is 29, we can directly use ImageDecoder to decode the Bitmap.
        // Synced images are stored as a plain absolute path (no scheme); the content resolver
        // cannot open those ("No content provider"), so decode them straight from the file.
        val source = if (uri.scheme == null && !uri.path.isNullOrEmpty()) {
            ImageDecoder.createSource(File(uri.path!!))
        } else {
            ImageDecoder.createSource(contentResolver, uri)
        }
        ImageDecoder.decodeBitmap(source)
    } catch (e: SecurityException) {
        log.e("SecurityException: ${e.message}", e)
        null
    } catch (e: ImageDecoder.DecodeException) {
        log.e("DecodeException: ${e.message}", e)
        null
    } catch (e: Exception) {
        log.e("Unexpected error: ${e.message}", e)
        null
    }
}


/**
 * A page's `background` is either an absolute path (locally chosen file, linked PDF) or a name
 * relative to the managed backgrounds folder -- which is what WebDAV sync stores and uploads
 * (`image/foo.png`, or just `foo.png` for a notebook created on the server). Relative names used
 * to be opened as-is, which never exists, so synced image backgrounds rendered white.
 */
fun resolveBackgroundFile(filePath: String): File {
    val direct = File(filePath)
    if (direct.isAbsolute) return direct
    return File(ensureBackgroundsFolder(), filePath)
}

fun loadBackgroundBitmap(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
    // TODO: it's very slow, needs to be changed for better tool
    if (filePath.isEmpty()) return null
    ensureNotMainThread("loadBackgroundBitmap")
    log.v("Reloading background, path: $filePath, scale: $scale")
    val file = resolveBackgroundFile(filePath)
    if (!file.exists()) {
        log.w("loadBackgroundBitmap: file does not exist: $filePath -> ${file.absolutePath}")
        return null
    }
    log.i("loadBackgroundBitmap: $filePath -> ${file.absolutePath} (${file.length()} bytes)")
    if (!File(filePath).isAbsolute) {
        com.ethran.notable.sync.SyncLogger.i(
            "Render", "background '$filePath' -> ${file.absolutePath}, ${file.length()} bytes"
        )
    }
    val timer = Timing("loadBackgroundBitmap")
    if (!filePath.endsWith(".pdf", ignoreCase = true)) {
        try {
            timer.step("decode bitmap image")
            val result = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            if (!File(filePath).isAbsolute) {
                com.ethran.notable.sync.SyncLogger.i(
                    "Render",
                    "background '$filePath' decoded: " +
                        (result?.let { "${it.width}x${it.height}" } ?: "null")
                )
            }
            if (result == null)
                log.e(
                    "loadBackgroundBitmap: result is null, couldn't decode image, file name ends with ${
                        filePath.takeLast(
                            4
                        )
                    }"
                )
            timer.end("loaded background")
            return result?.asAndroidBitmap()
        } catch (e: Exception) {
            log.e("getOrLoadBackground: Error loading background - ${e.message}", e)
        }
    }
    val targetWidth = SCREEN_WIDTH * (scale.coerceAtMost(2f))
    timer.step("start android Pdf")
    log.d("Rendering using ${if (GlobalAppSettings.current.muPdfRendering) "MuPdf" else "Android Pdf"}")
    val newBitmap: Bitmap? = if (GlobalAppSettings.current.muPdfRendering)
        renderPdfPageMuPdf(file.absolutePath, pageNumber, targetWidth.toInt(), resolutionModifier = 1.5f)
    else
        renderPdfPageAndroid(file, pageNumber, targetWidth.toInt(), resolutionModifier = 1.2f)
    timer.end("loaded background")
    return newBitmap
}