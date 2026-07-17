package software.medusa.workload.docker

import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * The low-level Engine API request surface, shared by the endpoint groups (system, containers, …).
 * [DockerConnector] is the implementation; [ContainerApi] and friends are built on top of it so
 * each endpoint group stays in its own file without re-implementing transport, negotiation, or
 * error mapping.
 */
internal interface DockerEngine {
  val json: Json

  /** The `/v<negotiated>` prefix + [path] (e.g. `versionedPath("/containers/create")`). */
  suspend fun versionedPath(path: String): String

  /**
   * Sends one request and aggregates the response. Transport/connection failures are already mapped
   * to [DockerConnectionException] here; callers handle HTTP status via [ensureSuccess].
   */
  suspend fun exchange(
      method: HttpMethod,
      pathWithQuery: String,
      jsonBody: String? = null,
  ): AggregatedHttpResponse

  /**
   * Opens a streaming request and emits the response body as raw byte chunks (chunk boundaries are
   * whatever the transport delivers — callers must not assume they align to anything). A cold Flow:
   * each collection opens a fresh request, and cancelling the collector aborts the request and
   * closes the socket. Connection failures map to [DockerConnectionException]; a non-2xx status
   * maps to [DockerApiException] (with the daemon's message) before any body byte is emitted.
   *
   * [headers] adds request headers (e.g. `X-Registry-Auth` for a pull).
   */
  fun streamBytes(
      method: HttpMethod,
      pathWithQuery: String,
      headers: Map<String, String> = emptyMap(),
  ): Flow<ByteArray>
}

/**
 * Splits a byte stream into newline-delimited JSON records. The daemon reports pull progress as one
 * JSON object per line, but transport chunks don't align to line boundaries, so this buffers across
 * calls and yields only whole lines — the same problem (and shape) as [FrameDemuxer].
 */
internal class NdjsonSplitter {
  private val buffer = StringBuilder()

  /** Appends [chunk] and returns every complete, non-blank line now available. */
  fun feed(chunk: ByteArray): List<String> {
    buffer.append(String(chunk, Charsets.UTF_8))
    val lines = mutableListOf<String>()
    while (true) {
      val newline = buffer.indexOf("\n")
      if (newline < 0) break
      val line = buffer.substring(0, newline).trim()
      buffer.delete(0, newline + 1)
      if (line.isNotEmpty()) lines += line
    }
    return lines
  }

  /** Any trailing content not terminated by a newline — the daemon may not end with one. */
  fun flush(): List<String> {
    val rest = buffer.toString().trim()
    buffer.clear()
    return if (rest.isEmpty()) emptyList() else listOf(rest)
  }
}

private val successRange = 200..299

/** Throws [DockerApiException] (with the daemon's own message when present) on a non-2xx status. */
internal fun AggregatedHttpResponse.ensureSuccess(json: Json) {
  val code = status().code()
  if (code in successRange) {
    return
  }
  val message =
      runCatching { json.decodeFromString<DaemonErrorBody>(contentUtf8()).message }
          .getOrNull()
          ?.takeIf { it.isNotBlank() } ?: status().reasonPhrase()
  throw DockerApiException(code, message)
}

/** Decodes a 2xx JSON body to [T], mapping parse failures to [DockerProtocolException]. */
internal inline fun <reified T> AggregatedHttpResponse.decodeBody(json: Json, what: String): T =
    try {
      json.decodeFromString<T>(contentUtf8())
    } catch (e: Exception) {
      throw DockerProtocolException("Malformed Docker daemon response for $what", e)
    }
