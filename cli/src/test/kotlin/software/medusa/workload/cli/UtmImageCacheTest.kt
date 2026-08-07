package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UtmImageCacheTest {

  private val tempDir = Files.createTempDirectory("ms-workload-image-cache-test")

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  /** Serves fixed bytes per URL instead of hitting the network; records every URL asked for. */
  private class FakeDownloader(private val content: Map<String, String>) : Downloader {
    val requested = mutableListOf<String>()

    override fun download(url: String, dest: Path) {
      requested += url
      val body =
          content[url] ?: throw AssertionError("FakeDownloader has no content stubbed for $url")
      Files.writeString(dest, body)
    }
  }

  // ---------------------------------------------------------------------
  // hostImageArch
  // ---------------------------------------------------------------------

  @Test
  fun `hostImageArch maps aarch64 and arm64 to arm64, everything else to amd64`() {
    assertEquals("arm64", hostImageArch("aarch64"))
    assertEquals("arm64", hostImageArch("arm64"))
    assertEquals("amd64", hostImageArch("x86_64"))
    assertEquals("amd64", hostImageArch("amd64"))
  }

  // ---------------------------------------------------------------------
  // parseSha256Sums
  // ---------------------------------------------------------------------

  @Test
  fun `parseSha256Sums finds the requested file's hash and ignores others`() {
    val sums =
        """
        aaaa111  other-file.img
        bbbb222 *noble-server-cloudimg-arm64.img
        cccc333  yet-another.img
        """
            .trimIndent()

    assertEquals("bbbb222", parseSha256Sums(sums, "noble-server-cloudimg-arm64.img"))
    assertEquals(null, parseSha256Sums(sums, "missing.img"))
  }

  // ---------------------------------------------------------------------
  // resolveUbuntuCloudImage
  // ---------------------------------------------------------------------

  @Test
  fun `resolveUbuntuCloudImage downloads, verifies checksum, and caches`() {
    val spec = UbuntuCloudImageSpec(release = "noble", arch = "arm64")
    val body = "fake-qcow2-bytes"
    val checksum = sha256Of(body)
    val downloader =
        FakeDownloader(
            mapOf(
                spec.checksumsUrl to "$checksum  ${spec.imageFileName}\n",
                spec.imageUrl to body,
            )
        )
    val cache = ImageCache(cacheDir = tempDir, downloader = downloader)

    val path = cache.resolveUbuntuCloudImage(spec)

    assertEquals(body, Files.readString(path))
    assertEquals(listOf(spec.checksumsUrl, spec.imageUrl), downloader.requested)

    // Second resolve: cache hit, no network calls at all.
    val second = cache.resolveUbuntuCloudImage(spec)
    assertEquals(path, second)
    assertEquals(listOf(spec.checksumsUrl, spec.imageUrl), downloader.requested)
  }

  @Test
  fun `resolveUbuntuCloudImage fails clearly on a checksum mismatch and doesn't cache the bad file`() {
    val spec = UbuntuCloudImageSpec(release = "noble", arch = "arm64")
    val downloader =
        FakeDownloader(
            mapOf(
                spec.checksumsUrl to "deadbeef  ${spec.imageFileName}\n",
                spec.imageUrl to "actually-different-bytes",
            )
        )
    val cache = ImageCache(cacheDir = tempDir, downloader = downloader)

    val error = assertFailsWith<NodeVmDriverException> { cache.resolveUbuntuCloudImage(spec) }
    assertTrue(error.message!!.contains("Checksum mismatch"))
    assertTrue(Files.list(tempDir).use { it.toList() }.none { it.toString().endsWith(".img") })
  }

  @Test
  fun `resolveUbuntuCloudImage fails clearly when the file isn't listed in SHA256SUMS`() {
    val spec = UbuntuCloudImageSpec(release = "noble", arch = "arm64")
    val downloader = FakeDownloader(mapOf(spec.checksumsUrl to "aaaa  some-other-file.img\n"))
    val cache = ImageCache(cacheDir = tempDir, downloader = downloader)

    val error = assertFailsWith<NodeVmDriverException> { cache.resolveUbuntuCloudImage(spec) }
    assertTrue(error.message!!.contains("isn't listed"))
  }

  @Test
  fun `resolveUbuntuCloudImage re-downloads if the cached file no longer matches its checksum`() {
    val spec = UbuntuCloudImageSpec(release = "noble", arch = "arm64")
    val goodBody = "good-bytes"
    val checksum = sha256Of(goodBody)
    val downloader =
        FakeDownloader(
            mapOf(
                spec.checksumsUrl to "$checksum  ${spec.imageFileName}\n",
                spec.imageUrl to goodBody,
            )
        )
    val cache = ImageCache(cacheDir = tempDir, downloader = downloader)
    val path = cache.resolveUbuntuCloudImage(spec)

    // Simulate on-disk corruption after the fact.
    Files.writeString(path, "corrupted")

    val again = cache.resolveUbuntuCloudImage(spec)

    assertEquals(goodBody, Files.readString(again))
    // Downloaded checksums + image twice: once per resolve.
    assertEquals(2, downloader.requested.count { it == spec.imageUrl })
  }

  // ---------------------------------------------------------------------
  // resolveBaseImage (--base-image)
  // ---------------------------------------------------------------------

  @Test
  fun `resolveBaseImage uses an existing local path as-is`() {
    val localImage = tempDir.resolve("custom.qcow2")
    Files.writeString(localImage, "custom-bytes")
    val cache = ImageCache(cacheDir = tempDir, downloader = FakeDownloader(emptyMap()))

    assertEquals(localImage, cache.resolveBaseImage(localImage.toString()))
  }

  @Test
  fun `resolveBaseImage fails clearly when the local path doesn't exist`() {
    val cache = ImageCache(cacheDir = tempDir, downloader = FakeDownloader(emptyMap()))

    val error =
        assertFailsWith<NodeVmDriverException> {
          cache.resolveBaseImage(tempDir.resolve("nope.qcow2").toString())
        }
    assertTrue(error.message!!.contains("neither an http(s) URL nor an existing file"))
  }

  @Test
  fun `resolveBaseImage downloads and caches a custom URL, then reuses the cache`() {
    val url = "https://example.com/images/custom.qcow2"
    val downloader = FakeDownloader(mapOf(url to "custom-remote-bytes"))
    val cache = ImageCache(cacheDir = tempDir, downloader = downloader)

    val first = cache.resolveBaseImage(url)
    assertEquals("custom-remote-bytes", Files.readString(first))

    val second = cache.resolveBaseImage(url)
    assertEquals(first, second)
    assertEquals(listOf(url), downloader.requested)
  }

  private fun sha256Of(content: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
  }
}
