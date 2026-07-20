package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for image-ref splitting and the NDJSON framing a pull's progress stream arrives in.
 */
class ImagesTest {

  @Test
  fun `a digest ref splits into repository and digest`() {
    val (repo, ref) = splitRepositoryAndReference("us-docker.pkg.dev/p/repo/app@sha256:abc")
    assertEquals("us-docker.pkg.dev/p/repo/app", repo)
    assertEquals("sha256:abc", ref)
  }

  @Test
  fun `a tag ref splits into repository and tag`() {
    val (repo, ref) = splitRepositoryAndReference("us-docker.pkg.dev/p/repo/app:v1")
    assertEquals("us-docker.pkg.dev/p/repo/app", repo)
    assertEquals("v1", ref)
  }

  @Test
  fun `a registry port is not mistaken for a tag`() {
    val (repo, ref) = splitRepositoryAndReference("localhost:5000/app")
    assertEquals("localhost:5000/app", repo)
    assertNull(ref, "no tag was given, so the daemon's default applies")
  }

  @Test
  fun `registry host is recognized only when it looks like one`() {
    assertEquals("us-docker.pkg.dev", registryOf("us-docker.pkg.dev/p/repo/app:v1"))
    assertEquals("localhost:5000", registryOf("localhost:5000/app"))
    // Docker Hub shorthand has no registry segment — anonymous/default applies.
    assertNull(registryOf("busybox:latest"))
    assertNull(registryOf("library/busybox"))
  }

  @Test
  fun `ndjson splitter reassembles records across chunk boundaries`() {
    val splitter = NdjsonSplitter()
    assertTrue(splitter.feed("""{"status":"Pull""".toByteArray()).isEmpty())
    val out = splitter.feed(""" complete"}""".plus("\n").toByteArray())
    assertEquals(listOf("""{"status":"Pull complete"}"""), out)
  }

  @Test
  fun `ndjson splitter yields several records from one chunk`() {
    val splitter = NdjsonSplitter()
    val out = splitter.feed("{\"status\":\"a\"}\n{\"status\":\"b\"}\n".toByteArray())
    assertEquals(listOf("""{"status":"a"}""", """{"status":"b"}"""), out)
  }

  @Test
  fun `ndjson splitter flushes a trailing record with no newline`() {
    val splitter = NdjsonSplitter()
    assertTrue(splitter.feed("""{"status":"last"}""".toByteArray()).isEmpty())
    assertEquals(listOf("""{"status":"last"}"""), splitter.flush())
  }

  @Test
  fun `ndjson splitter ignores blank lines`() {
    val splitter = NdjsonSplitter()
    assertEquals(listOf("""{"a":1}"""), splitter.feed("\n\n{\"a\":1}\n\n".toByteArray()))
    assertTrue(splitter.flush().isEmpty())
  }
}
