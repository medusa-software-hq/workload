package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Stages the raw disk image `--driver vz` needs from whatever [ImageCache] resolved (the default
 * Ubuntu LTS cloud image, or a `--base-image` override) — Apple's Virtualization.framework only
 * accepts raw (or ISO) disk images, never qcow2, unlike QEMU-backed `--driver utm`. Ubuntu's own
 * cloud images are qcow2 despite their `.img` extension, so that's the common case; a
 * `--base-image` that's already raw is just copied through unconverted.
 *
 * Reuses [ImageCache] as-is (see node/README.md's `--driver vz` section and workload#165) — nothing
 * about *fetching* the base image is driver-specific, only what's done with it once it's local. The
 * converted result is cached under `<cacheDir>/raw/<content-hash>.raw`, keyed the same way
 * [UtmTemplateAssembler] keys its bundle: by the source file's own SHA-256, not its file name, so a
 * `--base-image` override that happens to share a cached file's name can't reuse a stale conversion
 * built from different bytes.
 */
internal object VfkitDiskStager {

  /** The qcow2 magic number (`QFI\xfb`) every Ubuntu cloud image starts with. */
  private val QCOW2_MAGIC = byteArrayOf(0x51, 0x46, 0x49, 0xfb.toByte())

  fun stageRawDisk(
      baseImage: Path,
      cacheDir: Path = defaultImageCacheDir(),
      converter: RawDiskConverter = ProcessRawDiskConverter,
  ): Path {
    val rawDir = cacheDir.resolve("raw")
    val dest = rawDir.resolve("${sha256File(baseImage).take(16)}.raw")
    if (Files.isRegularFile(dest)) return dest

    Files.createDirectories(rawDir)
    val tmp = Files.createTempFile(rawDir, "staging-", ".raw")
    try {
      if (isQcow2(baseImage)) {
        converter.convertToRaw(baseImage, tmp)
      } else {
        Files.copy(baseImage, tmp, StandardCopyOption.REPLACE_EXISTING)
      }
      Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
    } finally {
      Files.deleteIfExists(tmp)
    }
    return dest
  }

  private fun isQcow2(path: Path): Boolean {
    val header = ByteArray(4)
    Files.newInputStream(path).use { input ->
      val read = input.read(header)
      if (read < header.size) return false
    }
    return header.contentEquals(QCOW2_MAGIC)
  }
}

/**
 * Abstracts the qcow2-to-raw conversion so [VfkitDiskStager] is unit-testable without `qemu-img` on
 * PATH — the same seam [IsoBuilder] gives ISO building.
 */
internal fun interface RawDiskConverter {
  fun convertToRaw(source: Path, dest: Path)
}

/**
 * Shells out to `qemu-img convert -O raw` — the standard tool for this, ubiquitous alongside QEMU.
 */
internal object ProcessRawDiskConverter : RawDiskConverter {
  private const val QEMU_IMG = "qemu-img"

  override fun convertToRaw(source: Path, dest: Path) {
    if (!isExecutableOnPath(QEMU_IMG)) {
      throw NodeVmDriverException(
          "--driver vz needs qemu-img to convert the cached Ubuntu cloud image (qcow2) to the " +
              "raw format Apple's Virtualization.framework requires. Install it (brew install " +
              "qemu) and retry, or pass --base-image pointing at an already-raw disk image."
      )
    }
    val process =
        ProcessBuilder(listOf(QEMU_IMG, "convert", "-O", "raw", source.toString(), dest.toString()))
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      throw NodeVmDriverException("qemu-img convert failed (exit $exitCode): ${output.trim()}")
    }
  }
}

/**
 * Shared by [ProcessRawDiskConverter] and [ProcessIsoBuilder]'s `which` check; a plain PATH scan.
 */
internal fun isExecutableOnPath(name: String, pathEnv: String? = System.getenv("PATH")): Boolean =
    pathEnv?.split(java.io.File.pathSeparatorChar).orEmpty().any {
      Path.of(it).resolve(name).let(Files::isExecutable)
    }
