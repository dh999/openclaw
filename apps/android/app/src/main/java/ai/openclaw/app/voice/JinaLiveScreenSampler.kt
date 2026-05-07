package ai.openclaw.app.voice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Captures the device screen at a throttled rate using MediaProjection +
 * ImageReader, encodes each frame as JPEG, and hands the bytes to the
 * caller. Designed to feed `jina.live.input.frame` so the user can ask
 * Jina questions about whatever is on screen.
 *
 * Lifecycle:
 *   1. The hosting service obtains a MediaProjection from
 *      MediaProjectionManager (one Activity-side consent dialog, then
 *      keep the projection token alive in the foreground service).
 *   2. Construct this sampler with the projection + a callback.
 *   3. start() begins capturing at `fps` (default 1).
 *   4. stop() releases the virtual display + image reader; the projection
 *      itself is owned by the caller.
 */
internal class JinaLiveScreenSampler(
  private val context: Context,
  private val mediaProjection: MediaProjection,
  private val fps: Float = 1.0f,
  private val jpegQuality: Int = 70,
  /**
   * Cap so giant phone displays (Galaxy S26 Ultra is ~3088x1440) do not
   * push 5+ MB JPEGs per frame at high settings. Aspect-ratio preserved.
   */
  private val maxLongerEdgePx: Int = 1280,
  private val onFrame: (jpeg: ByteArray) -> Unit,
) {
  companion object {
    private const val TAG = "JinaLiveScreenSampler"
  }

  private var imageReader: ImageReader? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var captureThread: HandlerThread? = null
  private var captureHandler: Handler? = null
  private var projectionCallback: MediaProjection.Callback? = null
  private val lastEmitMs = AtomicLong(0L)

  fun start() {
    if (imageReader != null) return

    val (width, height, density) = resolveCaptureSize()
    val intervalMs = (1000f / fps.coerceAtLeast(0.1f)).toLong()

    captureThread = HandlerThread("JinaLiveScreenSampler").apply { start() }
    captureHandler = Handler(captureThread!!.looper)

    // Android 14+ (API 34) requires a MediaProjection.Callback to be
    // registered before createVirtualDisplay is called. Without this the
    // platform throws SecurityException and the foreground service crashes.
    val cb =
      object : MediaProjection.Callback() {
        override fun onStop() {
          // Caller (the session service) owns projection lifecycle, but if
          // the system tears it down (user taps "Stop sharing" in the
          // notification) we need to release our virtual display so the
          // ImageReader doesn't keep firing into a dead surface.
          runCatching { virtualDisplay?.release() }
          virtualDisplay = null
        }
      }
    projectionCallback = cb
    runCatching { mediaProjection.registerCallback(cb, captureHandler) }

    val reader =
      ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also {
        imageReader = it
      }

    reader.setOnImageAvailableListener({ r ->
      val now = System.currentTimeMillis()
      val last = lastEmitMs.get()
      if (now - last < intervalMs) {
        // Drain without copying so the buffer pool stays healthy.
        runCatching { r.acquireLatestImage()?.close() }
        return@setOnImageAvailableListener
      }
      val image =
        try {
          r.acquireLatestImage()
        } catch (err: Throwable) {
          Log.w(TAG, "acquireLatestImage failed: ${err.message}")
          null
        } ?: return@setOnImageAvailableListener
      try {
        val jpeg = encodeJpegFromImage(image, width, height, jpegQuality)
        if (jpeg != null) {
          lastEmitMs.set(now)
          onFrame(jpeg)
        }
      } finally {
        runCatching { image.close() }
      }
    }, captureHandler)

    virtualDisplay =
      mediaProjection.createVirtualDisplay(
        "JinaLiveScreen",
        width,
        height,
        density,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface,
        null,
        captureHandler,
      )
  }

  fun stop() {
    val cb = projectionCallback
    projectionCallback = null
    if (cb != null) {
      runCatching { mediaProjection.unregisterCallback(cb) }
    }
    runCatching { virtualDisplay?.release() }
    virtualDisplay = null
    runCatching { imageReader?.close() }
    imageReader = null
    captureThread?.quitSafely()
    captureThread = null
    captureHandler = null
  }

  private fun resolveCaptureSize(): Triple<Int, Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val rawW = metrics.widthPixels
    val rawH = metrics.heightPixels
    val density = metrics.densityDpi
    val longer = maxOf(rawW, rawH)
    if (longer <= maxLongerEdgePx) {
      return Triple(rawW, rawH, density)
    }
    val scale = maxLongerEdgePx.toFloat() / longer.toFloat()
    val w = (rawW * scale).toInt().coerceAtLeast(1)
    val h = (rawH * scale).toInt().coerceAtLeast(1)
    return Triple(w, h, density)
  }

  private fun encodeJpegFromImage(
    image: android.media.Image,
    width: Int,
    height: Int,
    quality: Int,
  ): ByteArray? {
    val planes = image.planes
    if (planes.isEmpty()) return null
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    // Re-pack into a contiguous bitmap of exactly width x height.
    val bitmapWidth = width + rowPadding / pixelStride
    val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(buffer)
    val cropped =
      if (bitmapWidth == width) {
        bitmap
      } else {
        Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
      }

    val baos = ByteArrayOutputStream()
    val ok = cropped.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), baos)
    runCatching { cropped.recycle() }
    return if (ok) baos.toByteArray() else null
  }
}
