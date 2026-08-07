package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UtmDriverTest {

  private val tempDir = Files.createTempDirectory("ms-workload-utm-test")

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  /** Records every command it was asked to run, and answers per-tool from a fixed script. */
  private class FakeProcessRunner(
      private val results: Map<String, ProcessResult> = emptyMap(),
      private val default: ProcessResult = ProcessResult(0, "", ""),
  ) : ProcessRunner {
    val invocations = mutableListOf<List<String>>()

    override fun run(command: List<String>): ProcessResult {
      invocations += command
      val key = command.take(2).joinToString(" ")
      return results.entries.firstOrNull { command.joinToString(" ").startsWith(it.key) }?.value
          ?: results[key]
          ?: default
    }
  }

  private fun makeTemplateBundle(): Path {
    val bundle = tempDir.resolve("template.utm")
    val images = bundle.resolve("Images")
    Files.createDirectories(images)
    Files.writeString(images.resolve("boot.iso"), "template-boot")
    Files.writeString(images.resolve("identity.iso"), "template-identity")
    Files.writeString(bundle.resolve("config.plist"), "<plist/>")
    return bundle
  }

  private fun makeIso(name: String, content: String): Path {
    val path = tempDir.resolve(name)
    Files.writeString(path, content)
    return path
  }

  // ---------------------------------------------------------------------
  // create
  // ---------------------------------------------------------------------

  @Test
  fun `create clones the template, swaps both drive images, registers via AppleScript, and starts`() {
    val template = makeTemplateBundle()
    val bootIso = makeIso("boot-real.iso", "real-boot")
    val identityIso = makeIso("identity-real.iso", "real-identity")
    val vmsDir = tempDir.resolve("vms")
    val runner = FakeProcessRunner()
    val driver = UtmNodeVmDriver(UtmVmConfig(vmsDir = vmsDir), runner)

    driver.create("node-1", template, bootIso, identityIso)

    val bundle = vmsDir.resolve("node-1.utm")
    assertEquals("real-boot", Files.readString(bundle.resolve("Images/boot.iso")))
    assertEquals("real-identity", Files.readString(bundle.resolve("Images/identity.iso")))
    assertTrue(Files.exists(bundle.resolve("config.plist")))

    // The template itself must be untouched.
    assertEquals("template-boot", Files.readString(template.resolve("Images/boot.iso")))

    assertTrue(
        runner.invocations.any {
          it.first() == "osascript" && it.any { arg -> arg.contains("open POSIX file") }
        }
    )
    assertTrue(runner.invocations.any { it == listOf("utmctl", "start", "node-1") })
  }

  @Test
  fun `create fails when the template bundle doesn't exist`() {
    val vmsDir = tempDir.resolve("vms")
    val driver = UtmNodeVmDriver(UtmVmConfig(vmsDir = vmsDir), FakeProcessRunner())

    val error =
        assertFailsWith<NodeVmDriverException> {
          driver.create(
              "node-1",
              tempDir.resolve("does-not-exist.utm"),
              makeIso("boot.iso", "b"),
              makeIso("identity.iso", "i"),
          )
        }
    assertTrue(error.message!!.contains("not found"))
  }

  @Test
  fun `create refuses to clobber an existing bundle for the same node name`() {
    val template = makeTemplateBundle()
    val vmsDir = tempDir.resolve("vms")
    Files.createDirectories(vmsDir.resolve("node-1.utm"))
    val driver = UtmNodeVmDriver(UtmVmConfig(vmsDir = vmsDir), FakeProcessRunner())

    val error =
        assertFailsWith<NodeVmDriverException> {
          driver.create(
              "node-1",
              template,
              makeIso("boot.iso", "b"),
              makeIso("identity.iso", "i"),
          )
        }
    assertTrue(error.message!!.contains("already exists"))
  }

  @Test
  fun `create surfaces an AppleScript registration failure without starting the VM`() {
    val template = makeTemplateBundle()
    val vmsDir = tempDir.resolve("vms")
    val runner =
        FakeProcessRunner(results = mapOf("osascript" to ProcessResult(1, "", "not authorized")))
    val driver = UtmNodeVmDriver(UtmVmConfig(vmsDir = vmsDir), runner)

    val error =
        assertFailsWith<NodeVmDriverException> {
          driver.create(
              "node-1",
              template,
              makeIso("boot.iso", "b"),
              makeIso("identity.iso", "i"),
          )
        }
    assertTrue(error.message!!.contains("not authorized"))
    assertTrue(runner.invocations.none { it.first() == "utmctl" })
  }

  // ---------------------------------------------------------------------
  // start / stop / status
  // ---------------------------------------------------------------------

  @Test
  fun `start and stop shell out to utmctl and surface failures`() {
    val ok = FakeProcessRunner()
    UtmNodeVmDriver(runner = ok).start("node-1")
    assertEquals(listOf(listOf("utmctl", "start", "node-1")), ok.invocations)

    val okStop = FakeProcessRunner()
    UtmNodeVmDriver(runner = okStop).stop("node-1")
    assertEquals(listOf(listOf("utmctl", "stop", "node-1")), okStop.invocations)

    val force = FakeProcessRunner()
    UtmNodeVmDriver(runner = force).stop("node-1", force = true)
    assertEquals(listOf(listOf("utmctl", "stop", "node-1", "--force")), force.invocations)

    val failing = FakeProcessRunner(default = ProcessResult(1, "", "no such VM"))
    val error =
        assertFailsWith<NodeVmDriverException> { UtmNodeVmDriver(runner = failing).start("x") }
    assertTrue(error.message!!.contains("no such VM"))
  }

  @Test
  fun `status maps utmctl output to NodeVmStatus`() {
    fun statusFor(stdout: String, exitCode: Int = 0): NodeVmStatus =
        UtmNodeVmDriver(runner = FakeProcessRunner(default = ProcessResult(exitCode, stdout, "")))
            .status("node-1")

    assertEquals(NodeVmStatus.RUNNING, statusFor("started"))
    assertEquals(NodeVmStatus.RUNNING, statusFor("running"))
    assertEquals(NodeVmStatus.PAUSED, statusFor("paused"))
    assertEquals(NodeVmStatus.STOPPED, statusFor("stopped"))
    assertEquals(NodeVmStatus.NOT_FOUND, statusFor("", exitCode = 1))
  }

  // ---------------------------------------------------------------------
  // rotateIdentity
  // ---------------------------------------------------------------------

  @Test
  fun `rotateIdentity stops, swaps the identity image, and restarts a running VM`() {
    val vmsDir = tempDir.resolve("vms")
    val bundle = vmsDir.resolve("node-1.utm")
    Files.createDirectories(bundle.resolve("Images"))
    Files.writeString(bundle.resolve("Images/identity.iso"), "old-identity")
    val newIdentity = makeIso("new-identity.iso", "new-identity")

    val runner =
        FakeProcessRunner(results = mapOf("utmctl status" to ProcessResult(0, "started", "")))
    val driver = UtmNodeVmDriver(UtmVmConfig(vmsDir = vmsDir), runner)

    driver.rotateIdentity("node-1", newIdentity)

    assertEquals("new-identity", Files.readString(bundle.resolve("Images/identity.iso")))
    assertEquals(
        listOf(
            listOf("utmctl", "status", "node-1"),
            listOf("utmctl", "stop", "node-1"),
            listOf("utmctl", "start", "node-1"),
        ),
        runner.invocations,
    )
  }

  @Test
  fun `rotateIdentity only swaps the image when the VM is already stopped`() {
    val vmsDir = tempDir.resolve("vms")
    val bundle = vmsDir.resolve("node-1.utm")
    Files.createDirectories(bundle.resolve("Images"))
    Files.writeString(bundle.resolve("Images/identity.iso"), "old-identity")
    val newIdentity = makeIso("new-identity.iso", "new-identity")

    val runner =
        FakeProcessRunner(results = mapOf("utmctl status" to ProcessResult(0, "stopped", "")))
    val driver = UtmNodeVmDriver(UtmVmConfig(vmsDir = vmsDir), runner)

    driver.rotateIdentity("node-1", newIdentity)

    assertEquals("new-identity", Files.readString(bundle.resolve("Images/identity.iso")))
    assertEquals(listOf(listOf("utmctl", "status", "node-1")), runner.invocations)
  }
}
