package software.medusa.workload.cli

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The Ubuntu LTS cloud image `node create --driver utm` stages by default when no `--base-image`/
 * `--utm-template` override is given — see [ImageCache.resolveUbuntuCloudImage]. Ubuntu publishes
 * these at a stable `<release>/current/` URL (rolling — always the latest point release for that
 * series) alongside a `SHA256SUMS` listing every image in that directory.
 */
internal data class UbuntuCloudImageSpec(val release: String, val arch: String) {
  val imageFileName: String
    get() = "$release-server-cloudimg-$arch.img"

  val imageUrl: String
    get() = "$BASE_URL/$release/current/$imageFileName"

  val checksumsUrl: String
    get() = "$BASE_URL/$release/current/SHA256SUMS"

  companion object {
    private const val BASE_URL = "https://cloud-images.ubuntu.com"

    // The current Ubuntu LTS codename at the time this was written. Bump this when a newer LTS
    // ships; `--image-release` overrides it per invocation in the meantime without a CLI release.
    const val DEFAULT_RELEASE = "noble"
  }
}

/** Maps a JVM `os.arch` value to the arch suffix Ubuntu's cloud-image file names use. */
internal fun hostImageArch(osArch: String = System.getProperty("os.arch")): String =
    when (osArch.lowercase()) {
      "aarch64",
      "arm64" -> "arm64"
      else -> "amd64"
    }

/** Abstracts fetching a URL to a file so [ImageCache] is unit-testable without the network. */
internal fun interface Downloader {
  fun download(url: String, dest: Path)
}

internal object HttpDownloader : Downloader {
  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

  override fun download(url: String, dest: Path) {
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    val response =
        try {
          client.send(request, HttpResponse.BodyHandlers.ofFile(dest))
        } catch (e: IOException) {
          throw NodeVmDriverException("Failed to download $url: ${e.message}")
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw NodeVmDriverException("Interrupted while downloading $url")
        }
    if (response.statusCode() !in 200..299) {
      Files.deleteIfExists(dest)
      throw NodeVmDriverException("Failed to download $url: HTTP ${response.statusCode()}")
    }
  }
}

/** Where staged base images (and the templates assembled from them) are cached across creates. */
internal fun defaultImageCacheDir(): Path =
    Path.of(System.getProperty("user.home"), ".cache/workload/images")

internal fun sha256File(path: Path): String {
  val digest = MessageDigest.getInstance("SHA-256")
  Files.newInputStream(path).use { input ->
    val buffer = ByteArray(1 shl 16)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Parses a GNU-coreutils-style `SHA256SUMS` listing (`<hex> <filename>` per line, binary-mode
 * entries `*`-prefixed) for [fileName]'s expected checksum.
 */
internal fun parseSha256Sums(contents: String, fileName: String): String? =
    contents
        .lineSequence()
        .mapNotNull { line ->
          val parts = line.trim().split(Regex("\\s+"), limit = 2)
          if (parts.size != 2) return@mapNotNull null
          val name = parts[1].removePrefix("*")
          if (name == fileName) parts[0].lowercase() else null
        }
        .firstOrNull()

/**
 * Fetches and caches base VM images in [cacheDir] so `node create --driver utm` downloads its
 * default Ubuntu cloud image at most once and reuses it on every later create — the "Fetch + cache"
 * half of staging the driver's own base image (see node/utm-template-staging.md). Keyed by file
 * name: a cache hit is only trusted if a sidecar `.sha256` recorded alongside it still matches the
 * file's actual contents, so a partial/corrupted prior download can't poison later creates
 * silently.
 */
internal class ImageCache(
    private val cacheDir: Path = defaultImageCacheDir(),
    private val downloader: Downloader = HttpDownloader,
) {

  /**
   * Downloads (unless already cached) and checksum-verifies the default Ubuntu cloud image for
   * [spec] against its release's `SHA256SUMS`, returning its local path.
   */
  fun resolveUbuntuCloudImage(spec: UbuntuCloudImageSpec): Path {
    val cached = cachedIfValid(spec.imageFileName)
    if (cached != null) return cached

    Files.createDirectories(cacheDir)
    val checksumsTmp = Files.createTempFile(cacheDir, "SHA256SUMS", ".tmp")
    try {
      downloader.download(spec.checksumsUrl, checksumsTmp)
      val expected =
          parseSha256Sums(Files.readString(checksumsTmp), spec.imageFileName)
              ?: throw NodeVmDriverException(
                  "${spec.imageFileName} isn't listed in ${spec.checksumsUrl} — Ubuntu may have " +
                      "renamed or retired this release/arch's cloud image. Pass --image-release " +
                      "or --base-image to use a different one."
              )
      return downloadAndVerify(spec.imageUrl, spec.imageFileName, expected)
    } finally {
      Files.deleteIfExists(checksumsTmp)
    }
  }

  /**
   * Resolves `--base-image`: a URL is downloaded and cached verbatim (no checksum — there's no
   * standard listing to verify an arbitrary URL against); an existing local path is used as-is.
   */
  fun resolveBaseImage(baseImage: String): Path {
    if (baseImage.startsWith("http://") || baseImage.startsWith("https://")) {
      val fileName = baseImage.substringAfterLast('/').ifBlank { "base-image" }
      val cached = cachedIfValid(fileName, requireChecksumSidecar = false)
      if (cached != null) return cached
      Files.createDirectories(cacheDir)
      val tmp = Files.createTempFile(cacheDir, fileName, ".tmp")
      return try {
        downloader.download(baseImage, tmp)
        moveIntoCache(tmp, fileName, sha256File(tmp))
      } finally {
        Files.deleteIfExists(tmp)
      }
    }
    val path = Path.of(baseImage)
    if (!Files.isRegularFile(path)) {
      throw NodeVmDriverException(
          "--base-image '$baseImage' is neither an http(s) URL nor an existing file."
      )
    }
    return path
  }

  private fun downloadAndVerify(url: String, fileName: String, expectedSha256: String): Path {
    val tmp = Files.createTempFile(cacheDir, fileName, ".tmp")
    return try {
      downloader.download(url, tmp)
      val actual = sha256File(tmp)
      if (!actual.equals(expectedSha256, ignoreCase = true)) {
        throw NodeVmDriverException(
            "Checksum mismatch for $fileName: expected $expectedSha256, got $actual — the " +
                "download may be corrupt or truncated. Try again, or pass --base-image to use a " +
                "different image."
        )
      }
      moveIntoCache(tmp, fileName, actual)
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  private fun moveIntoCache(tmp: Path, fileName: String, sha256: String): Path {
    val dest = cacheDir.resolve(fileName)
    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    Files.writeString(cacheDir.resolve("$fileName.sha256"), sha256)
    return dest
  }

  private fun cachedIfValid(fileName: String, requireChecksumSidecar: Boolean = true): Path? {
    val dest = cacheDir.resolve(fileName)
    if (!Files.isRegularFile(dest)) return null
    val sidecar = cacheDir.resolve("$fileName.sha256")
    if (!Files.isRegularFile(sidecar)) return if (requireChecksumSidecar) null else dest
    return if (Files.readString(sidecar).trim().equals(sha256File(dest), ignoreCase = true)) {
      dest
    } else {
      null
    }
  }
}
