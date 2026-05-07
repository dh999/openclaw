package ai.openclaw.app.voice

import ai.openclaw.app.gateway.GatewaySession
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Thin client around the `jina.live.*` gateway RPC surface exposed by the
 * jina-live third-party plugin. The plugin owns the actual Gemini Live
 * bridge; this Android-side client just shovels mic frames, text, and
 * screen frames into the gateway and lets broadcast events flow back to
 * the player.
 *
 * Method namespace mirrors talk.* — see jina-live README and
 * docs/PHASE4-ANDROID.md for wire details.
 */
internal class JinaLiveBridgeClient(
  private val session: GatewaySession,
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  @Serializable
  internal data class StartParams(
    val sessionId: String? = null,
    val instructions: String? = null,
  )

  @Serializable
  internal data class StartResult(val sessionId: String)

  @Serializable
  internal data class StopParams(val sessionId: String)

  @Serializable
  internal data class StopResult(val stopped: Boolean = false)

  @Serializable
  internal data class InputAudioParams(val sessionId: String, val audio: String)

  @Serializable
  internal data class InputFrameParams(
    val sessionId: String,
    val mimeType: String,
    val frame: String,
  )

  @Serializable
  internal data class InputTextParams(val sessionId: String, val text: String)

  @Serializable
  internal data class GenericOk(val ok: Boolean = true)

  internal sealed interface CallResult<out T> {
    data class Success<T>(val value: T) : CallResult<T>

    data class Failure(val message: String) : CallResult<Nothing>
  }

  suspend fun start(instructions: String? = null, sessionId: String? = null): CallResult<StartResult> =
    callTyped("jina.live.start", StartParams(sessionId = sessionId, instructions = instructions))

  suspend fun stop(sessionId: String): CallResult<StopResult> =
    callTyped("jina.live.stop", StopParams(sessionId = sessionId))

  /**
   * Push a μ-law 8 kHz mono audio chunk. Recommended chunk size is
   * 320 bytes (= 20 ms) so the call rate sits at ~50 Hz.
   */
  suspend fun sendAudio(sessionId: String, muLaw: ByteArray): CallResult<GenericOk> {
    val payload = Base64.encodeToString(muLaw, Base64.NO_WRAP)
    return callTyped(
      "jina.live.input.audio",
      InputAudioParams(sessionId = sessionId, audio = payload),
    )
  }

  /**
   * Push a single screen / camera frame. mimeType must be image/jpeg or
   * image/png. Throttle the caller to ~1 fps for ambient screen sharing.
   */
  suspend fun sendFrame(
    sessionId: String,
    frame: ByteArray,
    mimeType: String = "image/jpeg",
  ): CallResult<GenericOk> {
    val payload = Base64.encodeToString(frame, Base64.NO_WRAP)
    return callTyped(
      "jina.live.input.frame",
      InputFrameParams(sessionId = sessionId, mimeType = mimeType, frame = payload),
    )
  }

  suspend fun sendText(sessionId: String, text: String): CallResult<GenericOk> =
    callTyped(
      "jina.live.input.text",
      InputTextParams(sessionId = sessionId, text = text),
    )

  private suspend inline fun <reified P : Any, reified R : Any> callTyped(
    method: String,
    params: P,
  ): CallResult<R> {
    val response =
      try {
        session.requestDetailed(
          method = method,
          paramsJson = json.encodeToString(params),
          timeoutMs = 30_000,
        )
      } catch (err: Throwable) {
        return CallResult.Failure(err.message ?: "$method request failed")
      }
    if (!response.ok) {
      return CallResult.Failure(
        response.error?.message ?: "$method failed",
      )
    }
    val raw = response.payloadJson ?: "{}"
    return try {
      CallResult.Success(json.decodeFromString<R>(raw))
    } catch (err: Throwable) {
      CallResult.Failure(err.message ?: "$method payload decode failed")
    }
  }
}
