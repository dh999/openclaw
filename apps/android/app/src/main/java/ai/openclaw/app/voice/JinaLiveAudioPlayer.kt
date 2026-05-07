package ai.openclaw.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Base64
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Plays 24 kHz mono PCM L16 audio frames coming from
 * `plugin.jina.live.audio` broadcasts (Gemini Live native format) through
 * a streaming AudioTrack.
 *
 * Producer/consumer split: broadcast dispatcher threads only enqueue PCM
 * byte buffers into a lock-free queue and return immediately. A dedicated
 * writer thread drains the queue and feeds AudioTrack. Keeping broadcast
 * handlers off the AudioTrack ring buffer avoids crashes seen on Samsung
 * devices under concurrent writers.
 */
internal class JinaLiveAudioPlayer(
  private val sampleRateHz: Int = OUTPUT_SAMPLE_RATE_HZ,
) {
  companion object {
    private const val TAG = "JinaLiveAudioPlayer"

    /**
     * Gemini Live's native PCM L16 sample rate. The jina-live plugin
     * forwards model audio in this format unchanged, so clients can
     * write it straight into a 24 kHz AudioTrack with no resampling and
     * no codec round-trip.
     */
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000

    /** Cap pending PCM bytes at ~4 s of audio (24 kHz mono, 16-bit). */
    private const val MAX_QUEUED_PCM_BYTES = 4 * OUTPUT_SAMPLE_RATE_HZ * 2
  }

  private val started = AtomicBoolean(false)
  private val running = AtomicBoolean(false)
  private val queue = LinkedBlockingQueue<ShortArray>()
  @Volatile private var queuedPcmBytes: Int = 0

  // AudioTrack and its writer thread are owned by start()/stop() only.
  // Reads from broadcast threads only ever look at the queue.
  private var track: AudioTrack? = null
  private var writer: Thread? = null

  fun start() {
    if (started.getAndSet(true)) return

    val minBuffer =
      AudioTrack.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    // 4 seconds of headroom at 24 kHz mono 16-bit (= 192_000 bytes). Gemini
    // Live sends audio in bursts with gaps that occasionally exceed 1 s;
    // a 2 s buffer was still draining between bursts. 4 s gives the next
    // burst plenty of room to land before the track underruns.
    val bufferSize = (minBuffer * 32).coerceAtLeast(192_000)

    val built =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            // USAGE_MEDIA reliably accepts 24 kHz mono on Samsung devices.
            // USAGE_VOICE_COMMUNICATION routes through the telephony audio
            // path which prefers 8/16 kHz and silently drops 24 kHz frames.
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
    if (built.state != AudioTrack.STATE_INITIALIZED) {
      Log.w(TAG, "AudioTrack failed to initialize (state=${built.state})")
      runCatching { built.release() }
      started.set(false)
      return
    }
    runCatching { built.play() }
    Log.i(
      TAG,
      "AudioTrack started: requestedBufferBytes=$bufferSize " +
        "actualBufferFrames=${built.bufferSizeInFrames} " +
        "capacityFrames=${built.bufferCapacityInFrames} " +
        "sampleRateHz=$sampleRateHz",
    )
    track = built
    running.set(true)
    writer = thread(name = "JinaLiveAudioWriter", isDaemon = true) { runWriteLoop() }
  }

  fun stop() {
    if (!started.getAndSet(false)) return
    running.set(false)
    queue.clear()
    queuedPcmBytes = 0
    val t = track
    track = null
    runCatching { t?.pause() }
    runCatching { t?.flush() }
    runCatching { t?.release() }
    writer?.let { runCatching { it.join(500) } }
    writer = null
  }

  /**
   * Enqueue a base64-encoded PCM L16 24 kHz mono chunk straight from a
   * `plugin.jina.live.audio` broadcast payload. Returns true when the
   * PCM was queued.
   */
  fun enqueueBase64MuLaw(audioBase64: String): Boolean {
    // Method name retained for callsite stability — payload is now 24 kHz
    // PCM L16, not μ-law. The jina-live plugin forwards Gemini Live's
    // native 24 kHz PCM and includes encoding/sampleRate metadata fields
    // in the broadcast for clients that want to assert.
    if (!running.get()) return false
    val pcmBytes =
      try {
        Base64.decode(audioBase64, Base64.DEFAULT)
      } catch (err: Throwable) {
        Log.w(TAG, "audio base64 decode failed: ${err.message}")
        return false
      }
    if (pcmBytes.isEmpty() || pcmBytes.size % 2 != 0) return false
    return enqueuePcm16Le(pcmBytes)
  }

  /** Queue raw 16-bit little-endian PCM bytes for the writer thread. */
  fun enqueuePcm16Le(pcmBytes: ByteArray): Boolean {
    if (!running.get()) return false
    val srcSamples = pcmBytes.size / 2
    if (srcSamples == 0) return false
    val pcm = ShortArray(srcSamples)
    var i = 0
    var s = 0
    while (i + 1 < pcmBytes.size) {
      // Both halves must be unsigned-extended; sign-extending the high byte
      // poisons the upper bits and turns valid PCM samples into garbage,
      // which is what was producing the metallic shimmer/tremor on speech.
      val lo = pcmBytes[i].toInt() and 0xFF
      val hi = pcmBytes[i + 1].toInt() and 0xFF
      pcm[s] = ((hi shl 8) or lo).toShort()
      i += 2
      s += 1
    }
    val frameBytes = pcm.size * 2
    // Backpressure: if the queue exceeds our cap (network burst we cannot
    // play out fast enough), drop the oldest buffered audio rather than
    // letting the queue grow unbounded. Live conversation prefers freshness.
    while (queuedPcmBytes + frameBytes > MAX_QUEUED_PCM_BYTES) {
      val dropped = queue.poll() ?: break
      queuedPcmBytes -= dropped.size * 2
    }
    queue.offer(pcm)
    queuedPcmBytes += frameBytes
    return true
  }

  /** Drop pending buffer. Used on plugin.jina.live.audio.clear (interrupt). */
  fun clear() {
    queue.clear()
    queuedPcmBytes = 0
    val t = track ?: return
    runCatching { t.pause() }
    runCatching { t.flush() }
    runCatching { t.play() }
  }

  fun isStarted(): Boolean = started.get()

  private fun runWriteLoop() {
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
    // 400 ms prebuffer so the very first response (when the AudioTrack
    // ring buffer is still cold) doesn't drain mid-sentence. The server
    // already paces frames at 40 ms intervals, so this only matters for
    // the cold-start case. 24 kHz mono 16-bit = 48_000 bytes/s, so
    // 19_200 bytes ≈ 400 ms.
    val prebufferTargetBytes = 19_200
    val prebufferMaxWaitMs = 600L
    var prebufferStartMs = 0L
    // Comfort silence: 40 ms of zeros at 24 kHz mono = 960 samples. When
    // the queue empties between bursts we feed silence to AudioTrack via
    // NON_BLOCKING write so it never underruns. Was previously disabled
    // because of a sample-loss bug elsewhere; with the byte→short unpack
    // sign-extension bug fixed, silence padding no longer corrupts speech.
    val silenceFrame = ShortArray(960)
    var prebuffered = false
    while (running.get()) {
      val t = track ?: continue
      if (t.state != AudioTrack.STATE_INITIALIZED) continue

      // AudioTrack on Samsung devices can drop into STOPPED/PAUSED state
      // after a long underrun and stay there even when new PCM arrives.
      // Re-arm it before every write so the second/third assistant turn
      // doesn't go silent after the first response settles a long pause.
      if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
        runCatching { t.play() }
      }

      if (!prebuffered) {
        val now = System.currentTimeMillis()
        if (queuedPcmBytes > 0 && prebufferStartMs == 0L) {
          prebufferStartMs = now
        }
        val waited = if (prebufferStartMs == 0L) 0L else now - prebufferStartMs
        val ready = queuedPcmBytes >= prebufferTargetBytes ||
          (queuedPcmBytes > 0 && waited >= prebufferMaxWaitMs)
        if (!ready) {
          try {
            Thread.sleep(10)
          } catch (err: InterruptedException) {
            break
          }
          continue
        }
        prebuffered = true
      }

      // Tight poll timeout so that when the queue is empty the writer wakes
      // promptly to feed comfort silence into the AudioTrack ring buffer
      // before hardware can drain it. 20 ms timeout was leaving long enough
      // gaps between silence writes for underrun to fire.
      val first =
        try {
          queue.poll(2, TimeUnit.MILLISECONDS)
        } catch (err: InterruptedException) {
          break
        }
      if (first != null) {
        // Drain any additional chunks already sitting in the queue and
        // concatenate them with `first` so AudioTrack receives one big
        // write instead of many tiny ones. Per-chunk BLOCKING writes were
        // adding boundary jitter on Samsung devices; coalescing keeps the
        // hardware ring buffer fed in larger steps and removes click
        // artefacts at chunk boundaries.
        val extras = mutableListOf<ShortArray>()
        var totalSamples = first.size
        while (true) {
          val more = queue.poll() ?: break
          extras.add(more)
          totalSamples += more.size
        }
        val combined =
          if (extras.isEmpty()) {
            first
          } else {
            val out = ShortArray(totalSamples)
            System.arraycopy(first, 0, out, 0, first.size)
            var pos = first.size
            for (e in extras) {
              System.arraycopy(e, 0, out, pos, e.size)
              pos += e.size
            }
            out
          }
        queuedPcmBytes -= combined.size * 2
        val written =
          try {
            t.write(combined, 0, combined.size, AudioTrack.WRITE_BLOCKING)
          } catch (err: Throwable) {
            Log.w(TAG, "AudioTrack.write threw: ${err.message}")
            break
          }
        if (written < 0) {
          Log.w(TAG, "AudioTrack.write returned error code $written")
          break
        }
      } else {
        // Queue empty — feed comfort silence. NON_BLOCKING returns 0 once
        // the kernel ring buffer is full, so silence never accumulates
        // beyond the buffer's capacity; real PCM bursts arriving next
        // queue right after whatever silence sits in the ring buffer.
        runCatching {
          t.write(silenceFrame, 0, silenceFrame.size, AudioTrack.WRITE_NON_BLOCKING)
        }
      }
    }
  }
}
