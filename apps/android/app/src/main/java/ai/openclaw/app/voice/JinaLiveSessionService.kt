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
  private var mediaProjection: MediaProjection? = null
  private var sessionId: String? = null
  private var sessionJob: Job? = null

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification = buildNotification("Live mode standby")
    val foregroundType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
      } else {
        0
      }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, foregroundType)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    // Optional MediaProjection handoff from Activity.
    if (intent != null) {
      adoptMediaProjectionFrom(intent)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
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
    if (sessionId != null) {
      startScreenSamplerIfReady()
    }
  }

  /**
   * Route a single jina.live.* broadcast payload to the player /
   * transcript listeners. Wire this from NodeRuntime / GatewaySession
   * (next commit).
   */
  fun handleBroadcastEvent(event: String, payloadJson: String?) {
    when (event) {
      "jina.live.audio" -> {
        if (payloadJson == null) return
        val sid = extractStringField(payloadJson, "sessionId")
        if (sid != null && sid != sessionId) return
        val audioB64 = extractStringField(payloadJson, "audio") ?: return
        audioPlayer?.enqueueBase64MuLaw(audioB64)
      }
      "jina.live.audio.clear" -> {
        audioPlayer?.clear()
      }
      "jina.live.session.closed", "jina.live.session.error" -> {
        Log.i(TAG, "session ended via broadcast: $event")
        stopSession()
        stopSelfSafely()
      }
    }
  }

  // ---------- Internals ----------

  private fun startScreenSamplerIfReady() {
    val projection = mediaProjection ?: return
    val client = bridge ?: return
    val sid = sessionId ?: return
    if (screenSampler != null) return
    val sampler =
      JinaLiveScreenSampler(
        context = applicationContext,
        mediaProjection = projection,
        onFrame = { jpeg ->
          scope.launch {
            runCatching { client.sendFrame(sid, jpeg, "image/jpeg") }
          }
        },
      )
    screenSampler = sampler
    sampler.start()
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
    if (resultCode == 0 || data == null) return
    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
    val projection = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
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
