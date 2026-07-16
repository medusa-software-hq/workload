package software.medusa.workload.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull

/** Pure tests for image-ref parsing and the local always-resolved fake — no registry needed. */
class ImageDigestResolverTest {

  @Test
  fun `parses an Artifact Registry ref with a tag`() {
    val parsed = parseImageRef("us-docker.pkg.dev/my-project/repo/app:v1.2.3")
    assertNotNull(parsed)
    assertEquals("us-docker.pkg.dev", parsed!!.host)
    assertEquals("my-project/repo/app", parsed.repository)
    assertEquals("v1.2.3", parsed.reference)
    assertEquals(
        "https://us-docker.pkg.dev/v2/my-project/repo/app/manifests/v1.2.3",
        parsed.manifestUrl,
    )
  }

  @Test
  fun `defaults to the latest tag when none is given`() {
    val parsed = parseImageRef("us-docker.pkg.dev/p/repo/app")
    assertEquals("latest", parsed?.reference)
    assertEquals("p/repo/app", parsed?.repository)
  }

  @Test
  fun `parses a digest reference`() {
    val parsed = parseImageRef("us-docker.pkg.dev/p/repo/app@sha256:deadbeef")
    assertEquals("p/repo/app", parsed?.repository)
    assertEquals("sha256:deadbeef", parsed?.reference)
  }

  @Test
  fun `rejects bare Docker Hub shorthand (no registry host)`() {
    // No dot/colon in the first segment → not a registry host → rejected (workload images live in
    // Artifact Registry, not implicit Docker Hub).
    assertNull(parseImageRef("busybox:latest"))
    assertNull(parseImageRef("library/busybox"))
  }

  @Test
  fun `rejects empty or hostless refs`() {
    assertNull(parseImageRef(""))
    assertNull(parseImageRef("/no-host"))
    assertNull(parseImageRef("us-docker.pkg.dev/"))
  }

  @Test
  fun `local always-resolved fake reports RESOLVED with a digest`() = runBlocking {
    val resolution =
        AlwaysResolvedImageDigestResolver.resolve("sa@p.iam", "us-docker.pkg.dev/p/repo/app:v1")
    assertEquals(ImageStatus.RESOLVED, resolution.status)
    assertNotNull(resolution.digest)
  }
}
