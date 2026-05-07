package ai.openclaw.app.voice

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process bridge between NodeRuntime's gateway event stream and the
 * (lifecycle-independent) JinaLiveSessionService.
 *
 * NodeRuntime owns the GatewaySession and gets every broadcast event via a
 * single onEvent callback. The session service we want to feed those
 * events into can come up and go down independently of NodeRuntime, so we
 * bridge them through a process-level dispatcher with cheap subscribe /
 * unsubscribe.
 *
 * Only events whose name starts with `jina.live.` reach listeners — every
 * other broadcast is short-circuited so subscribing here costs nothing on
 * the hot path of unrelated talk.* / device.* / chat.* events.
 */
internal object JinaLiveBroadcastDispatcher {
  private const val TAG = "JinaLiveBroadcast"
  private const val PREFIX = "jina.live."

  private val listeners = CopyOnWriteArrayList<(event: String, payloadJson: String?) -> Unit>()

  fun register(listener: (event: String, payloadJson: String?) -> Unit) {
    listeners.addIfAbsent(listener)
  }

  fun unregister(listener: (event: String, payloadJson: String?) -> Unit) {
    listeners.remove(listener)
  }

  /**
   * Called from NodeRuntime.handleGatewayEvent for every gateway broadcast.
   * Returns immediately when no jina.live.* listeners are interested.
   */
  fun handleGatewayEvent(event: String, payloadJson: String?) {
    if (!event.startsWith(PREFIX)) return
    if (listeners.isEmpty()) return
    for (listener in listeners) {
      try {
        listener.invoke(event, payloadJson)
      } catch (err: Throwable) {
        Log.w(TAG, "listener threw on $event: ${err.message}")
      }
    }
  }
}
