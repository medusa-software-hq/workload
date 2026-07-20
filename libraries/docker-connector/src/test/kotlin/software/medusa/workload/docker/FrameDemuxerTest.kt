package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the multiplexed-log frame decoder. No daemon needed — these feed hand-built frames
 * (whole, split across chunks, several per chunk) and assert whole frames come out in order, since
 * the wire behavior we care about is exactly "reassemble frames regardless of chunk boundaries".
 */
class FrameDemuxerTest {

  private fun frame(stream: LogStream, payload: String): ByteArray {
    val bytes = payload.toByteArray()
    val streamId = if (stream == LogStream.STDERR) 2 else 1
    val header =
        byteArrayOf(
            streamId.toByte(),
            0,
            0,
            0,
            ((bytes.size ushr 24) and 0xFF).toByte(),
            ((bytes.size ushr 16) and 0xFF).toByte(),
            ((bytes.size ushr 8) and 0xFF).toByte(),
            (bytes.size and 0xFF).toByte(),
        )
    return header + bytes
  }

  private fun LogFrame.text() = bytes.toString(Charsets.UTF_8)

  @Test
  fun `decodes a single whole frame`() {
    val out = FrameDemuxer().feed(frame(LogStream.STDOUT, "hello"))
    assertEquals(1, out.size)
    assertEquals(LogStream.STDOUT, out[0].stream)
    assertEquals("hello", out[0].text())
  }

  @Test
  fun `decodes several frames from one chunk, preserving order and stream`() {
    val chunk =
        frame(LogStream.STDOUT, "a") + frame(LogStream.STDERR, "b") + frame(LogStream.STDOUT, "c")
    val out = FrameDemuxer().feed(chunk)
    assertEquals(listOf("a", "b", "c"), out.map { it.text() })
    assertEquals(
        listOf(LogStream.STDOUT, LogStream.STDERR, LogStream.STDOUT),
        out.map { it.stream },
    )
  }

  @Test
  fun `reassembles a frame whose header is split across chunks`() {
    val whole = frame(LogStream.STDERR, "boom")
    val demuxer = FrameDemuxer()
    // Split mid-header (after 3 of the 8 header bytes).
    assertTrue(demuxer.feed(whole.copyOfRange(0, 3)).isEmpty())
    val out = demuxer.feed(whole.copyOfRange(3, whole.size))
    assertEquals(1, out.size)
    assertEquals(LogStream.STDERR, out[0].stream)
    assertEquals("boom", out[0].text())
  }

  @Test
  fun `reassembles a frame whose payload is split across three chunks`() {
    val whole = frame(LogStream.STDOUT, "abcdefgh")
    val demuxer = FrameDemuxer()
    // header + first 2 payload bytes, then 3, then the rest.
    assertTrue(demuxer.feed(whole.copyOfRange(0, 10)).isEmpty())
    assertTrue(demuxer.feed(whole.copyOfRange(10, 13)).isEmpty())
    val out = demuxer.feed(whole.copyOfRange(13, whole.size))
    assertEquals(listOf("abcdefgh"), out.map { it.text() })
  }

  @Test
  fun `emits completed frames and holds back a partial trailer`() {
    val demuxer = FrameDemuxer()
    val firstComplete = frame(LogStream.STDOUT, "one")
    val secondPartial = frame(LogStream.STDERR, "two")
    // One whole frame plus the header-only prefix of the next.
    val out1 = demuxer.feed(firstComplete + secondPartial.copyOfRange(0, 8))
    assertEquals(listOf("one"), out1.map { it.text() })
    // The rest of the second frame's payload now completes it.
    val out2 = demuxer.feed(secondPartial.copyOfRange(8, secondPartial.size))
    assertEquals(listOf("two"), out2.map { it.text() })
    assertEquals(LogStream.STDERR, out2[0].stream)
  }
}
