package software.medusa.workload.cli

import java.nio.file.Files
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UtmTemplateAssemblerTest {

  private val tempDir = Files.createTempDirectory("ms-workload-utm-assembler-test")

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  @Test
  fun `assemble builds a bundle with the base disk plus two empty removable ISO placeholders`() {
    val baseDisk = tempDir.resolve("noble-server-cloudimg-arm64.img")
    Files.writeString(baseDisk, "fake-qcow2-bytes")

    val bundle = UtmTemplateAssembler.assemble(baseDisk, arch = "arm64", cacheDir = tempDir)

    assertTrue(Files.isDirectory(bundle))
    assertEquals(
        "fake-qcow2-bytes",
        Files.readString(bundle.resolve("Images/noble-server-cloudimg-arm64.img")),
    )
    assertTrue(Files.isRegularFile(bundle.resolve("Images/boot.iso")))
    assertEquals(0L, Files.size(bundle.resolve("Images/boot.iso")))
    assertTrue(Files.isRegularFile(bundle.resolve("Images/identity.iso")))
    assertEquals(0L, Files.size(bundle.resolve("Images/identity.iso")))

    val plist = Files.readString(bundle.resolve("config.plist"))
    assertTrue(plist.contains("<key>Backend</key><string>QEMU</string>"))
    assertTrue(plist.contains("noble-server-cloudimg-arm64.img"))
    assertTrue(plist.contains("boot.iso"))
    assertTrue(plist.contains("identity.iso"))
    assertTrue(plist.contains("aarch64"))
  }

  @Test
  fun `assemble picks x86_64 System settings for amd64`() {
    val baseDisk = tempDir.resolve("noble-server-cloudimg-amd64.img")
    Files.writeString(baseDisk, "amd64-bytes")

    val bundle = UtmTemplateAssembler.assemble(baseDisk, arch = "amd64", cacheDir = tempDir)

    val plist = Files.readString(bundle.resolve("config.plist"))
    assertTrue(plist.contains("x86_64"))
    assertTrue(plist.contains("q35"))
  }

  @Test
  fun `assemble is idempotent for the same disk content and doesn't rebuild`() {
    val baseDisk = tempDir.resolve("disk.img")
    Files.writeString(baseDisk, "same-bytes")

    val first = UtmTemplateAssembler.assemble(baseDisk, arch = "arm64", cacheDir = tempDir)
    val marker = first.resolve("marker")
    Files.writeString(marker, "still here")

    val second = UtmTemplateAssembler.assemble(baseDisk, arch = "arm64", cacheDir = tempDir)

    assertEquals(first, second)
    assertTrue(Files.exists(marker))
  }

  @Test
  fun `assemble keys the template by disk content, not file name`() {
    val diskA = tempDir.resolve("a.img")
    val diskB = tempDir.resolve("b.img")
    Files.writeString(diskA, "identical-bytes")
    Files.writeString(diskB, "identical-bytes")

    val bundleA = UtmTemplateAssembler.assemble(diskA, arch = "arm64", cacheDir = tempDir)
    val bundleB = UtmTemplateAssembler.assemble(diskB, arch = "arm64", cacheDir = tempDir)

    assertEquals(bundleA, bundleB)
  }
}
