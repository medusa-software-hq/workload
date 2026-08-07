package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VfkitDiskStagerTest {

  private val tempDir = Files.createTempDirectory("ms-workload-vfkit-disk-test")

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  /**
   * Records every conversion it was asked to do; writes fixed "converted" bytes instead of real
   * ones.
   */
  private class FakeRawDiskConverter(private val output: String = "converted-raw-bytes") :
      RawDiskConverter {
    val invocations = mutableListOf<Pair<Path, Path>>()

    override fun convertToRaw(source: Path, dest: Path) {
      invocations += source to dest
      Files.writeString(dest, output)
    }
  }

  private val qcow2Magic = byteArrayOf(0x51, 0x46, 0x49, 0xfb.toByte())

  private fun makeQcow2(name: String = "noble-server-cloudimg-arm64.img"): Path {
    val path = tempDir.resolve(name)
    Files.write(path, qcow2Magic + "fake-qcow2-body".toByteArray())
    return path
  }

  private fun makeRaw(name: String = "already-raw.img", content: String = "raw-bytes"): Path {
    val path = tempDir.resolve(name)
    Files.writeString(path, content)
    return path
  }

  @Test
  fun `stageRawDisk converts a qcow2 base image via the converter and caches the result`() {
    val baseDisk = makeQcow2()
    val cacheDir = tempDir.resolve("cache")
    val converter = FakeRawDiskConverter()

    val staged = VfkitDiskStager.stageRawDisk(baseDisk, cacheDir, converter)

    assertEquals("converted-raw-bytes", Files.readString(staged))
    assertEquals(1, converter.invocations.size)
    assertEquals(baseDisk, converter.invocations.single().first)

    // Second stage of the same content: cache hit, no re-conversion.
    val again = VfkitDiskStager.stageRawDisk(baseDisk, cacheDir, converter)
    assertEquals(staged, again)
    assertEquals(1, converter.invocations.size)
  }

  @Test
  fun `stageRawDisk copies an already-raw base image through without converting`() {
    val baseDisk = makeRaw()
    val cacheDir = tempDir.resolve("cache")
    val converter = FakeRawDiskConverter()

    val staged = VfkitDiskStager.stageRawDisk(baseDisk, cacheDir, converter)

    assertEquals("raw-bytes", Files.readString(staged))
    assertTrue(converter.invocations.isEmpty())
  }

  @Test
  fun `stageRawDisk keys the cache by content, not file name`() {
    val cacheDir = tempDir.resolve("cache")
    val a = makeRaw("a.img", "identical-bytes")
    val b = makeRaw("b.img", "identical-bytes")
    val converter = FakeRawDiskConverter()

    val stagedA = VfkitDiskStager.stageRawDisk(a, cacheDir, converter)
    val stagedB = VfkitDiskStager.stageRawDisk(b, cacheDir, converter)

    assertEquals(stagedA, stagedB)
  }

  @Test
  fun `isExecutableOnPath finds an executable directory entry and rejects a non-executable or absent one`() {
    val binDir = tempDir.resolve("bin")
    Files.createDirectories(binDir)
    val tool = binDir.resolve("qemu-img")
    Files.writeString(tool, "#!/bin/sh\n")
    tool.toFile().setExecutable(true)

    assertTrue(isExecutableOnPath("qemu-img", pathEnv = binDir.toString()))
    assertTrue(!isExecutableOnPath("qemu-img", pathEnv = tempDir.resolve("empty").toString()))
    assertTrue(!isExecutableOnPath("qemu-img", pathEnv = null))
  }
}
