package ai.openclaw.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes μ-law 8 kHz mono frames coming from `jina.live.audio` broadcast
 * events into 16-bit PCM and plays them through a streaming AudioTrack.
 *
 * Designed to be cheap to construct and reusable across sessions: keep one
 * instance per JinaLiveSessionService lifetime and call `enqueue(...)` for
 * every audio frame received from the gateway. `clear()` flushes the
 * pending buffer when the bridge sends `jina.live.audio.clear`
 * (Gemini Live "interrupted" signal).
 */
internal class JinaLiveAudioPlayer(
  private val sampleRateHz: Int = OUTPUT_SAMPLE_RATE_HZ,
) {
  companion object {
    private const val TAG = "JinaLiveAudioPlayer"

    /**
     * AudioTrack output rate. We up-sample 8 kHz μ-law -> 16 kHz PCM by
     * doubling each sample so the track sounds the same as the source
     * but stays compatible with hardware that prefers >= 16 kHz tracks.
     */
    const val OUTPUT_SAMPLE_RATE_HZ = 16_000

    /** μ-law decoding table built once. */
    private val MU_LAW_DECODE = ShortArray(256).also { tbl ->
      for (i in 0..255) {
        val mu = i.inv() and 0xFF
        val sign = mu and 0x80
        val exponent = (mu shr 4) and 0x07
        val mantissa = mu and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        tbl[i] = (if (sign != 0) -sample else sample).toShort()
      }
    }
  }

  private val started = AtomicBoolean(false)
  private var track: AudioTrack? = null

  fun start() {
    if (started.getAndSet(true)) return

    val minBuffer =
      AudioTrack.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    val bufferSize = (minBuffer * 4).coerceAtLeast(8_192)

    track =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferSize)
        .build()
        .apply { play() }
  }

  fun stop() {
    if (!started.getAndSet(false)) return
    runCatching { track?.pause() }
    runCatching { track?.flush() }
    runCatching { track?.release() }
    track = null
  }

  /**
   * Enqueue a base64-encoded μ-law 8 kHz mono chunk straight from a
   * `jina.live.audio` broadcast payload. Returns true when bytes were
   * written to the track, false on decode/track failure.
   */
  fun enqueueBase64MuLaw(audioBase64: String): Boolean {
    if (!started.get()) start()
    val muLaw =
      try {
        Base64.decode(audioBase64, Base64.DEFAULT)
      } catch (err: Throwable) {
        Log.w(TAG, "audio base64 decode failed: ${err.message}")
        return false
      }
    if (muLaw.isEmpty()) return false
    return enqueueMuLaw(muLaw)
  }

  /** Decode μ-law -> PCM 16 kHz and write to the track. */
  fun enqueueMuLaw(muLaw: ByteArray): Boolean {
    val t = track ?: return false
    val srcLen = muLaw.size
    // 8 kHz μ-law -> 16 kHz PCM 16-bit by doubling samples.
    val pcm = ShortArray(srcLen * 2)
    for (i in 0 until srcLen) {
      val sample = MU_LAW_DECODE[muLaw[i].toInt() and 0xFF]
      pcm[i * 2] = sample
      pcm[i * 2 + 1] = sample
    }
    val written = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    if (written < 0) {
      Log.w(TAG, "AudioTrack.write returned error code $written")
      return false
    }
    return true
  }

  /** Drop any pending buffer. Used on jina.live.audio.clear (interrupt). */
  fun clear() {
    runCatching { track?.pause() }
    runCatching { track?.flush() }
    runCatching { track?.play() }
  }

  fun isStarted(): Boolean = started.get()
}
