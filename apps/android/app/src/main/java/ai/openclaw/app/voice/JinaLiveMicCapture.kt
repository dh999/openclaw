package ai.openclaw.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures raw mic audio at 8 kHz mono PCM16, encodes each chunk to
 * G.711 μ-law, and hands ~20 ms (320-byte) chunks to a callback. Pairs
 * with `JinaLiveBridgeClient.sendAudio` so the gateway-side bridge can
 * forward frames to Gemini Live (which up-samples to 16 kHz internally).
 *
 * Usage from JinaLiveSessionService once a sessionId is known:
 *
 *   mic = JinaLiveMicCapture { chunk ->
 *     scope.launch { runCatching { bridge.sendAudio(sessionId, chunk) } }
 *   }
 *   mic.start()
 *   ...
 *   mic.stop()
 *
 * Caller must hold android.permission.RECORD_AUDIO. The service surface
 * already declares FOREGROUND_SERVICE_TYPE_MICROPHONE on Android 14+.
 */
internal class JinaLiveMicCapture(
  private val sampleRateHz: Int = 8_000,
  /** Frame size in μ-law bytes (= PCM16 samples = mic samples). */
  private val frameSize: Int = 320,
  private val onFrame: (muLaw: ByteArray) -> Unit,
) {
  companion object {
    private const val TAG = "JinaLiveMicCapture"

    /** PCM 16-bit signed → μ-law 8-bit (G.711). */
    fun pcm16ToMuLawByte(sample: Int): Byte {
      var s = sample
      var sign = 0
      if (s < 0) {
        s = -s
        sign = 0x80
      }
      if (s > 32635) s = 32635
      s += 0x84
      var exponent = 7
      var mask = 0x4000
      while ((s and mask) == 0 && exponent > 0) {
        exponent -= 1
        mask = mask shr 1
      }
      val mantissa = (s shr (exponent + 3)) and 0x0F
      return (sign or (exponent shl 4) or mantissa).inv().and(0xFF).toByte()
    }
  }

  private val running = AtomicBoolean(false)
  private var recorder: AudioRecord? = null
  private var thread: Thread? = null

  @SuppressLint("MissingPermission")
  fun start() {
    if (running.getAndSet(true)) return

    val minBuffer =
      AudioRecord.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minBuffer <= 0) {
      Log.w(TAG, "AudioRecord.getMinBufferSize returned $minBuffer; aborting")
      running.set(false)
      return
    }
    val bufferBytes = (minBuffer * 4).coerceAtLeast(frameSize * 2 * 4)

    val rec =
      try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          sampleRateHz,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          bufferBytes,
        )
      } catch (err: Throwable) {
        Log.w(TAG, "AudioRecord ctor failed: ${err.message}")
        running.set(false)
        return
      }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      Log.w(TAG, "AudioRecord not initialized (state=${rec.state})")
      runCatching { rec.release() }
      running.set(false)
      return
    }
    recorder = rec
    runCatching { rec.startRecording() }

    thread =
      thread(name = "JinaLiveMic", isDaemon = true) { runReadLoop(rec) }
  }

  fun stop() {
    if (!running.getAndSet(false)) return
    runCatching { recorder?.stop() }
    runCatching { recorder?.release() }
    recorder = null
    thread?.let { runCatching { it.join(500) } }
    thread = null
  }

  fun isRunning(): Boolean = running.get()

  private fun runReadLoop(rec: AudioRecord) {
    val pcm = ShortArray(frameSize)
    val muLaw = ByteArray(frameSize)
    while (running.get()) {
      val read =
        try {
          rec.read(pcm, 0, frameSize)
        } catch (err: Throwable) {
          Log.w(TAG, "AudioRecord.read threw: ${err.message}")
          break
        }
      if (read <= 0) {
        // Negative codes are AudioRecord errors (ERROR_INVALID_OPERATION
        // etc); a zero read is also abnormal during a started recorder.
        if (read < 0) Log.w(TAG, "AudioRecord.read err=$read")
        break
      }
      for (i in 0 until read) {
        muLaw[i] = pcm16ToMuLawByte(pcm[i].toInt())
      }
      val out =
        if (read == frameSize) muLaw else muLaw.copyOf(read)
      try {
        onFrame(out)
      } catch (err: Throwable) {
        Log.w(TAG, "onFrame callback threw: ${err.message}")
      }
    }
  }
}
