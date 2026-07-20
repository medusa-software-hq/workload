package software.medusa.workload.docker

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.QueryParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Which of a container's two output streams a [LogFrame] came from. */
enum class LogStream {
  STDOUT,
  STDERR,
}

/**
 * One decoded chunk of container output: [bytes] as they were written to [stream]. Frames preserve
 * per-stream byte order but carry no line semantics — a single write may span frames and a frame
 * may hold several lines or a partial one, exactly as the process emitted it.
 */
class LogFrame(val stream: LogStream, val bytes: ByteArray)

/**
 * Container log streaming — reached as `connector.logs`. Returns a cold [Flow] of [LogFrame]s
 * decoded from Docker's log stream.
 *
 * **Demux.** For a non-TTY container the daemon multiplexes stdout and stderr over one connection
 * using an 8-byte frame header (`[stream, 0, 0, 0, size big-endian]`); [logs] decodes that back
 * into per-stream [LogFrame]s. For a TTY container there is no framing — a single raw stream — so
 * every chunk is reported as [LogStream.STDOUT]. TTY is auto-detected from the container's config
 * unless the caller overrides it.
 *
 * **Lifecycle.** In `follow` mode the flow completes when the container exits and the daemon closes
 * the stream. Cancelling the collector aborts the request and closes the socket (see
 * [DockerEngine.streamBytes]), so a cancelled follow leaves no socket or thread behind.
 */
class LogApi
internal constructor(
    private val engine: DockerEngine,
    private val containers: ContainerApi,
) {

  /**
   * Streams `GET /containers/{id}/logs`. [follow] keeps the stream open until the container exits.
   * [stdout]/[stderr] select which streams to include. [tty] forces raw/framed decoding; when null
   * (default) it is auto-detected via `inspect`.
   */
  fun logs(
      id: String,
      follow: Boolean = false,
      stdout: Boolean = true,
      stderr: Boolean = true,
      tty: Boolean? = null,
  ): Flow<LogFrame> = flow {
    val isTty = tty ?: containers.inspect(id).config.tty
    val query =
        QueryParams.builder()
            .add("follow", follow.toString())
            .add("stdout", stdout.toString())
            .add("stderr", stderr.toString())
            .add("timestamps", "false")
            .add("tail", "all")
            .build()
            .toQueryString()
    val path = engine.versionedPath("/containers/$id/logs") + "?" + query
    val raw = engine.streamBytes(HttpMethod.GET, path)

    if (isTty) {
      // Raw stream: no frame headers, everything is stdout.
      raw.collect { chunk -> if (chunk.isNotEmpty()) emit(LogFrame(LogStream.STDOUT, chunk)) }
    } else {
      val demuxer = FrameDemuxer()
      raw.collect { chunk ->
        for (frame in demuxer.feed(chunk)) {
          emit(frame)
        }
      }
    }
  }
}

/**
 * Decodes Docker's multiplexed log stream. Bytes arrive in transport-sized chunks that don't align
 * to frame boundaries, so this buffers across [feed] calls and yields only whole frames. Docker
 * guarantees ordering **within** a stream, not across the two — this preserves per-stream order by
 * emitting frames in arrival order.
 */
internal class FrameDemuxer {
  private var buffer = ByteArray(0)

  /** Appends [chunk] and returns every complete frame now available (possibly none). */
  fun feed(chunk: ByteArray): List<LogFrame> {
    buffer = if (buffer.isEmpty()) chunk else buffer + chunk
    val frames = mutableListOf<LogFrame>()
    var offset = 0
    while (buffer.size - offset >= HEADER_SIZE) {
      val streamType = buffer[offset].toInt()
      val size = readBigEndianInt(buffer, offset + SIZE_OFFSET)
      val frameEnd = offset + HEADER_SIZE + size
      if (size < 0 || buffer.size < frameEnd) {
        break // header says the payload isn't fully here yet; wait for more bytes
      }
      val payload = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
      frames += LogFrame(streamOf(streamType), payload)
      offset = frameEnd
    }
    // Drop the consumed prefix so the buffer holds only the unfinished tail.
    buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
    return frames
  }

  private fun streamOf(streamType: Int): LogStream =
      // Docker stream ids: 0=stdin, 1=stdout, 2=stderr. Anything non-2 is treated as stdout.
      if (streamType == STDERR_ID) LogStream.STDERR else LogStream.STDOUT

  private fun readBigEndianInt(bytes: ByteArray, at: Int): Int =
      ((bytes[at].toInt() and 0xFF) shl 24) or
          ((bytes[at + 1].toInt() and 0xFF) shl 16) or
          ((bytes[at + 2].toInt() and 0xFF) shl 8) or
          (bytes[at + 3].toInt() and 0xFF)

  private companion object {
    const val HEADER_SIZE = 8
    const val SIZE_OFFSET = 4
    const val STDERR_ID = 2
  }
}
