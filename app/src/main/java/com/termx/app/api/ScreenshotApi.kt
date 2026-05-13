package com.termx.app.api

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * Screenshot API for TermX. Screen capture via MediaProjection (API 21+).
 *
 * Usage: am broadcast -a com.termx.app.api.SCREENSHOT --es path "/path/screenshot.png"
 *        am broadcast -a com.termx.app.api.SCREENSHOT_AREA --es path "/path/crop.png" \
 *            --ei x 100 --ei y 100 --ei width 400 --ei height 300
 * Note: Requires MediaProjection user permission (obtained via Activity).
 *       Requires FOREGROUND_SERVICE on API 29+.
 */
object ScreenshotApi {

    private const val TAG = "ScreenshotApi"
    @Volatile private var resultCode: Int = 0
    @Volatile private var resultData: Intent? = null
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var imgReader: ImageReader? = null

    /** Store MediaProjection permission from onActivityResult. */
    fun setProjectionPermission(code: Int, data: Intent) { resultCode = code; resultData = data; Log.i(TAG, "Permission stored") }

    /** Check if projection permission has been granted. */
    fun hasProjectionPermission() = resultData != null && resultCode != 0

    /** Request MediaProjection permission (requires Activity context). */
    fun requestPermission(context: Context): String = try {
        if (context !is android.app.Activity) return "Error: Requires Activity context"
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        context.startActivityForResult(mgr.createScreenCaptureIntent(), 7001)
        "Permission request launched"
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Take a screenshot of the entire display and save as PNG. */
    fun takeScreenshot(context: Context, path: String): String = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return "Error: Requires API 21+"
        if (!hasProjectionPermission()) return "Error: MediaProjection permission not granted. Call requestPermission() first."

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels; val dpi = metrics.densityDpi

        val projMgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projMgr.getMediaProjection(resultCode, resultData!!) ?: return "Error: Projection failed (permission may have expired)"

        imgReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        vDisplay = projection!!.createVirtualDisplay("TermXShot", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imgReader!!.surface, null, null)

        var image: Image? = null; var retries = 0
        while (image == null && retries < 10) { Thread.sleep(100); image = imgReader?.acquireLatestImage(); retries++ }
        if (image == null) { cleanup(); return "Error: Could not capture frame" }

        val bitmap = imageToBitmap(image, w, h); image.close()
        val file = File(path); file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.flush() }
        cleanup()
        Log.i(TAG, "Screenshot saved: $path"); "Screenshot saved: $path (${file.length()}B, ${w}x${h})"
    } catch (e: SecurityException) { cleanup(); "Error: Permission denied or expired. Re-grant permission." }
    catch (e: Exception) { Log.e(TAG, "Screenshot failed", e); cleanup(); "Error: ${e.message}" }

    /** Take a screenshot of a specific area. */
    fun takeScreenshotArea(context: Context, path: String, x: Int, y: Int, cropW: Int, cropH: Int): String = try {
        val temp = File(context.cacheDir, "ss_temp_${System.currentTimeMillis()}.png")
        val fullResult = takeScreenshot(context, temp.absolutePath)
        if (!temp.exists()) return fullResult
        val full = android.graphics.BitmapFactory.decodeFile(temp.absolutePath) ?: return "Error: Decode failed"
        val cx = x.coerceIn(0, full.width - 1); val cy = y.coerceIn(0, full.height - 1)
        val cw = cropW.coerceIn(1, full.width - cx); val ch = cropH.coerceIn(1, full.height - cy)
        val crop = Bitmap.createBitmap(full, cx, cy, cw, ch)
        val out = File(path); out.parentFile?.mkdirs()
        FileOutputStream(out).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        full.recycle(); crop.recycle(); temp.delete()
        "Area screenshot saved: $path (${cw}x${ch} at $cx,$cy)"
    } catch (e: Exception) { "Error: ${e.message}" }

    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap {
        val buf = image.planes[0].buffer
        val pad = image.planes[0].rowStride - image.planes[0].pixelStride * w
        val bmp = Bitmap.createBitmap(w + pad / image.planes[0].pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf); return bmp
    }

    private fun cleanup() {
        try { vDisplay?.release(); imgReader?.close(); projection?.stop() } catch (_: Exception) {}
        vDisplay = null; imgReader = null; projection = null
    }
}
