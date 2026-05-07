package ai.openclaw.app.voice

import ai.openclaw.app.gateway.GatewaySession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a single Jina Live conversation. Owns the
 * MediaProjection token (so screen sharing survives across mic frames),
 * the AudioTrack player, the optional screen sampler, and the lifecycle
 * for the gateway-side bridge session.
 *
 * Wire order:
 *   1. Activity calls MediaProjectionManager.createScreenCaptureIntent,
 *      receives Intent + resultCode, EXTRA_PROJECTION_INTENT/RESULT.
 *   2. Activity binds this service or starts it with EXTRA_PROJECTION_*
 *      so the service holds the token.
 *   3. Service calls JinaLiveBridgeClient.start() → sessionId.
 *   4. Service starts AudioPlayer + (if enabled) ScreenSampler.
 *   5. Broadcast events from the gateway (jina.live.audio,
 *      jina.live.audio.clear, jina.live.transcript) are routed in
 *      via [handleBroadcastEvent]; the dispatcher wire from
 *      NodeRuntime / GatewaySession is the next commit.
 *
 * For now this is a scaffold: enough lifecycle to be foreground-safe,
 * enough public surface for the dispatcher commit to plug into. Real
 * mic capture (AudioRecord -> μ-law) and broadcast wiring land next.
 */
class JinaLiveSessionService : Service() {
  companion object {
    private const val TAG = "JinaLiveSessionService"

    const val EXTRA_PROJECTION_RESULT_CODE = "ai.openclaw.app.jinalive.projection.resultCode"
    const val EXTRA_PROJECTION_INTENT = "ai.openclaw.app.jinalive.projection.intent"

    private const val NOTIFICATION_CHANNEL_ID = "jina_live_session"
    private const val NOTIFICATION_CHANNEL_NAME = "Jina Live Session"
    private const val NOTIFICATION_ID = 0xC1A8 // arbitrary stable id
  }

  inner class LocalBinder : Binder() {
    fun service(): JinaLiveSessionService = this@JinaLiveSessionService
  }

  private val binder = LocalBinder()
  private val supervisor = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + supervisor)

  private var bridge: JinaLiveBridgeClient? = null
  private var audioPlayer: JinaLiveAudioPlayer? = null
  private var screenSampler: JinaLiveScreenSampler? = null
  private var micCapture: JinaLiveMicCapture? = null
  private var mediaProjection: MediaProjection? = null
  private var sessionId: String? = null
  private var sessionJob: Job? = null

  override fun onBind(intent: Intent?): IBinder = binder

  private val broadcastListener: (String, String?) -> Unit = { event, payloadJson ->
    handleBroadcastEvent(event, payloadJson)
  }

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
    JinaLiveBroadcastDispatcher.register(broadcastListener)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification = buildNotification("Live mode standby")
    val hasProjectionExtras =
      intent != null &&
        intent.hasExtra(EXTRA_PROJECTION_RESULT_CODE) &&
        intent.hasExtra(EXTRA_PROJECTION_INTENT)

    // Android 14+ FGS rules are chicken-and-egg for MediaProjection: the
    // service can only flip the MEDIA_PROJECTION foreground type bit once
    // it actually holds an `android:project_media` AppOp permission, which
    // itself is only granted by holding a live MediaProjection token. So
    // we always start microphone-only first; if the start intent carried
    // consent extras we then call MediaProjectionManager.getMediaProjection
    // and re-foreground the service with the additional MEDIA_PROJECTION
    // type bit before any createVirtualDisplay call.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    if (hasProjectionExtras) {
      adoptMediaProjectionFrom(requireNotNull(intent))
    }
    return START_STICKY
  }

  /** Re-foreground the service with the MEDIA_PROJECTION type added. */
  private fun upgradeForegroundForMediaProjection() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val notification = buildNotification("Live mode active (screen)")
    runCatching {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
      )
    }.onFailure {
      Log.w(TAG, "FGS type upgrade to MEDIA_PROJECTION failed: ${it.message}")
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    JinaLiveBroadcastDispatcher.unregister(broadcastListener)
    stopSession()
    scope.cancel()
  }

  // ---------- Public API used by the rest of the app ----------

  /**
   * Start a Jina Live session. The gateway round-trip happens off the
   * main thread; check [currentSessionId] to know when it has succeeded.
   */
  fun startSession(session: GatewaySession, instructions: String? = null) {
    if (sessionJob?.isActive == true) {
      Log.i(TAG, "startSession: session already active, ignoring")
      return
    }
    val client = JinaLiveBridgeClient(session)
    bridge = client

    val player = JinaLiveAudioPlayer().also { it.start() }
    audioPlayer = player

    sessionJob =
      scope.launch {
        when (val result = client.start(instructions = instructions)) {
          is JinaLiveBridgeClient.CallResult.Success -> {
            sessionId = result.value.sessionId
            updateNotification("Live mode active (${result.value.sessionId.take(8)}…)")
            Log.i(TAG, "session started ${result.value.sessionId}")
            startMicCaptureIfReady()
            startScreenSamplerIfReady()
          }
          is JinaLiveBridgeClient.CallResult.Failure -> {
            Log.w(TAG, "session start failed: ${result.message}")
            updateNotification("Live mode failed")
            stopSelfSafely()
          }
        }
      }
  }

  fun stopSession() {
    val sid = sessionId
    val client = bridge
    sessionId = null
    sessionJob?.cancel()
    sessionJob = null

    runCatching { micCapture?.stop() }
    micCapture = null

    runCatching { screenSampler?.stop() }
    screenSampler = null
    runCatching { mediaProjection?.stop() }
    mediaProjection = null

    runCatching { audioPlayer?.stop() }
    audioPlayer = null

    if (sid != null && client != null) {
      scope.launch {
        runCatching { client.stop(sid) }
      }
    }
    bridge = null
    updateNotification("Live mode stopped")
  }

  fun currentSessionId(): String? = sessionId

  /**
   * Hand a MediaProjection token in (e.g. activity result). Saved until
   * the session reaches "started" state, then the screen sampler is
   * spun up. Pass null to disable screen sharing for the next session.
   */
  fun setMediaProjection(projection: MediaProjection?) {
    runCatching { screenSampler?.stop() }
    screenSampler = null
    runCatching { this.mediaProjection?.stop() }
    this.mediaProjection = projection
    if (projection != null) {
      // Now that we hold a live MediaProjection token, the FGS may legally
      // declare MEDIA_PROJECTION type. createVirtualDisplay below will
      // refuse to run otherwise on Android 14+.
      upgradeForegroundForMediaProjection()
    }
    if (sessionId != null) {
      startScreenSamplerIfReady()
    }
  }

  /**
   * Route a single jina.live.* broadcast payload to the player /
   * transcript listeners. Wire this from NodeRuntime / GatewaySession
   * (next commit).
   */
  private var firstAudioBroadcastLogged = false
  private var firstTranscriptLogged = false

  fun handleBroadcastEvent(event: String, payloadJson: String?) {
    when (event) {
      "plugin.jina.live.audio" -> {
        if (payloadJson == null) return
        val sid = extractStringField(payloadJson, "sessionId")
        if (sid != null && sid != sessionId) return
        val audioB64 = extractStringField(payloadJson, "audio") ?: return
        if (!firstAudioBroadcastLogged) {
          firstAudioBroadcastLogged = true
          Log.i(TAG, "first plugin.jina.live.audio frame received (b64Len=${audioB64.length})")
        }
        audioPlayer?.enqueueBase64MuLaw(audioB64)
      }
      "plugin.jina.live.transcript" -> {
        if (payloadJson == null) return
        if (!firstTranscriptLogged) {
          firstTranscriptLogged = true
          val role = extractStringField(payloadJson, "role")
          val text = extractStringField(payloadJson, "text")
          Log.i(TAG, "first plugin.jina.live.transcript: role=$role text=${text?.take(80)}")
        }
      }
      "plugin.jina.live.audio.clear" -> {
        audioPlayer?.clear()
      }
      "plugin.jina.live.session.closed", "plugin.jina.live.session.error" -> {
        Log.i(TAG, "session ended via broadcast: $event")
        stopSession()
        stopSelfSafely()
      }
    }
  }

  // ---------- Internals ----------

  private fun startMicCaptureIfReady() {
    val client = bridge ?: return
    val sid = sessionId ?: return
    if (micCapture != null) return
    var firstFrameLogged = false
    var firstFailureLogged = false
    var sentFrameCount = 0L
    val capture =
      JinaLiveMicCapture { chunk ->
        scope.launch {
          val r = runCatching { client.sendAudio(sid, chunk) }.getOrNull()
          if (r is JinaLiveBridgeClient.CallResult.Success<*>) {
            if (!firstFrameLogged) {
              firstFrameLogged = true
              Log.i(TAG, "first mic frame ack from gateway (size=${chunk.size})")
            }
            sentFrameCount += 1
            if (sentFrameCount % 250L == 0L) {
              Log.i(TAG, "mic frames sent: $sentFrameCount")
            }
          } else if (r is JinaLiveBridgeClient.CallResult.Failure && !firstFailureLogged) {
            firstFailureLogged = true
            Log.w(TAG, "mic frame send failed: ${r.message}")
          }
        }
      }
    micCapture = capture
    capture.start()
    Log.i(TAG, "mic capture started for session $sid")
  }

  private fun startScreenSamplerIfReady() {
    val projection = mediaProjection
    val client = bridge
    val sid = sessionId
    Log.i(
      TAG,
      "startScreenSamplerIfReady: projection=${projection != null} bridge=${client != null} sid=${sid != null} alreadyRunning=${screenSampler != null}",
    )
    if (projection == null || client == null || sid == null) return
    if (screenSampler != null) return
    var firstFrameLogged = false
    var firstFailureLogged = false
    var sentFrameCount = 0L
    val sampler =
      JinaLiveScreenSampler(
        context = applicationContext,
        mediaProjection = projection,
        onFrame = { jpeg ->
          scope.launch {
            val r = runCatching { client.sendFrame(sid, jpeg, "image/jpeg") }.getOrNull()
            if (r is JinaLiveBridgeClient.CallResult.Success<*>) {
              if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(TAG, "first screen frame ack from gateway (jpegBytes=${jpeg.size})")
              }
              sentFrameCount += 1
              if (sentFrameCount % 10L == 0L) {
                Log.i(TAG, "screen frames sent: $sentFrameCount (jpegBytes=${jpeg.size})")
              }
            } else if (r is JinaLiveBridgeClient.CallResult.Failure && !firstFailureLogged) {
              firstFailureLogged = true
              Log.w(TAG, "screen frame send failed: ${r.message}")
            }
          }
        },
      )
    screenSampler = sampler
    sampler.start()
    Log.i(TAG, "screen sampler started for session $sid")
  }

  private fun adoptMediaProjectionFrom(intent: Intent) {
    val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
    val data: Intent? =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_PROJECTION_INTENT, Intent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(EXTRA_PROJECTION_INTENT)
      }
    Log.i(TAG, "adoptMediaProjectionFrom: resultCode=$resultCode dataNull=${data == null}")
    if (resultCode == 0 || data == null) {
      Log.w(TAG, "adoptMediaProjectionFrom: missing resultCode/data — bailing")
      return
    }
    val mgr =
      getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    if (mgr == null) {
      Log.w(TAG, "adoptMediaProjectionFrom: MediaProjectionManager service unavailable")
      return
    }
    // Android 14+ requires the FGS to already be running with
    // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before getMediaProjection
    // succeeds. We can't pre-set that type until we *have* the token, so
    // we attempt the type upgrade first and fall back to legacy behavior
    // on older platforms.
    upgradeForegroundForMediaProjection()
    val projection =
      try {
        mgr.getMediaProjection(resultCode, data)
      } catch (err: Throwable) {
        Log.w(TAG, "getMediaProjection threw: ${err.javaClass.simpleName}: ${err.message}")
        null
      }
    if (projection == null) {
      Log.w(TAG, "getMediaProjection returned null — screen sharing disabled for this session")
    } else {
      Log.i(TAG, "MediaProjection acquired")
    }
    setMediaProjection(projection)
  }

  private fun stopSelfSafely() {
    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    runCatching { stopSelf() }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
    val channel =
      NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        NOTIFICATION_CHANNEL_NAME,
        NotificationManager.IMPORTANCE_LOW,
      )
    mgr.createNotificationChannel(channel)
  }

  private fun buildNotification(text: String): Notification =
    NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle("지나 Live")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .build()

  private fun updateNotification(text: String) {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.notify(NOTIFICATION_ID, buildNotification(text))
  }

  /** Tiny JSON helper to avoid pulling another dependency for one field. */
  private fun extractStringField(json: String, field: String): String? {
    val key = "\"$field\""
    val idx = json.indexOf(key)
    if (idx < 0) return null
    val colon = json.indexOf(':', idx + key.length)
    if (colon < 0) return null
    val firstQuote = json.indexOf('"', colon)
    if (firstQuote < 0) return null
    val sb = StringBuilder()
    var i = firstQuote + 1
    while (i < json.length) {
      val c = json[i]
      if (c == '\\' && i + 1 < json.length) {
        sb.append(c)
        sb.append(json[i + 1])
        i += 2
        continue
      }
      if (c == '"') break
      sb.append(c)
      i += 1
    }
    return sb.toString()
  }
}
