package ai.openclaw.app.voice

import ai.openclaw.app.gateway.GatewaySession
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * App-side controller for JinaLiveSessionService. Hides the bind/start
 * dance behind two methods so MainActivity / Compose code can stay
 * declarative.
 *
 *  - [start] starts the foreground service (with optional MediaProjection
 *    handoff), binds, and once bound calls startSession with the supplied
 *    GatewaySession. Safe to call repeatedly; the service no-ops a second
 *    startSession while one is active.
 *  - [stop] tells the service to stop the session and unbinds.
 *
 * Single-instance: the [companion] holds one controller per process, so
 * multiple Activity instances during config changes do not race.
 */
class JinaLiveController private constructor(private val appContext: Context) {
  companion object {
    private const val TAG = "JinaLiveController"

    @Volatile private var instance: JinaLiveController? = null

    fun get(context: Context): JinaLiveController {
      val existing = instance
      if (existing != null) return existing
      synchronized(this) {
        val again = instance
        if (again != null) return again
        val created = JinaLiveController(context.applicationContext)
        instance = created
        return created
      }
    }
  }

  private var service: JinaLiveSessionService? = null
  private var bound = false
  private var pendingStart: PendingStart? = null

  private data class PendingStart(
    val session: GatewaySession,
    val instructions: String?,
  )

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val local = (binder as? JinaLiveSessionService.LocalBinder)?.service()
        service = local
        bound = true
        val pending = pendingStart
        pendingStart = null
        if (local != null && pending != null) {
          local.startSession(pending.session, pending.instructions)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        bound = false
      }
    }

  fun start(
    session: GatewaySession,
    instructions: String? = null,
    projectionResultCode: Int? = null,
    projectionData: Intent? = null,
  ) {
    val intent = Intent(appContext, JinaLiveSessionService::class.java)
    if (projectionResultCode != null && projectionData != null) {
      intent.putExtra(JinaLiveSessionService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
      intent.putExtra(JinaLiveSessionService.EXTRA_PROJECTION_INTENT, projectionData)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      appContext.startForegroundService(intent)
    } else {
      appContext.startService(intent)
    }

    val live = service
    if (bound && live != null) {
      live.startSession(session, instructions)
      return
    }
    pendingStart = PendingStart(session = session, instructions = instructions)
    if (!bound) {
      val ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      if (!ok) {
        Log.w(TAG, "bindService failed for JinaLiveSessionService")
        pendingStart = null
      }
    }
  }

  fun stop() {
    val live = service
    runCatching { live?.stopSession() }
    if (bound) {
      runCatching { appContext.unbindService(connection) }
      bound = false
      service = null
    }
    pendingStart = null
    val intent = Intent(appContext, JinaLiveSessionService::class.java)
    runCatching { appContext.stopService(intent) }
  }

  fun isActive(): Boolean = service?.currentSessionId() != null
}
