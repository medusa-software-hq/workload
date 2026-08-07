package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VfkitDriverTest {

  private val tempDir = Files.createTempDirectory("ms-workload-vfkit-test")

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  /** Records every command it was asked to launch; never actually starts a process. */
  private class FakeVfkitProcessLauncher : VfkitProcessLauncher {
    val invocations = mutableListOf<List<String>>()

    override fun launch(command: List<String>, nodeDir: Path) {
      invocations += command
    }
  }

  /** In-memory socket-path -> state, so tests never touch a real vfkit REST socket. */
  private class FakeVfkitRestClient(
      private val states: MutableMap<Path, String?> = mutableMapOf()
  ) : VfkitRestClient {
    val setStateCalls = mutableListOf<Pair<Path, String>>()

    fun seed(socketPath: Path, state: String?) {
      states[socketPath] = state
    }

    override fun getState(socketPath: Path): VfkitStateResponse? =
        states[socketPath]?.let { VfkitStateResponse(state = it) }

    override fun setState(socketPath: Path, state: String) {
      setStateCalls += socketPath to state
      // Mirrors the real vfkit process exiting (and its socket disappearing) once stopped.
      if (state == "Stop" || state == "HardStop") states[socketPath] = null
    }
  }

  private fun makeDisk(content: String = "raw-disk-bytes"): Path {
    val path = tempDir.resolve("disk-${Files.createTempFile(tempDir, "d", "").fileName}.raw")
    Files.writeString(path, content)
    return path
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
  fun `create copies the disk and both ISOs into the node dir and launches vfkit with the right devices`() {
    val disk = makeDisk()
    val bootIso = makeIso("boot-real.iso", "real-boot")
    val identityIso = makeIso("identity-real.iso", "real-identity")
    val stateDir = tempDir.resolve("state")
    val launcher = FakeVfkitProcessLauncher()
    val driver =
        VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), launcher, FakeVfkitRestClient())

    driver.create("node-1", disk, bootIso, identityIso)

    val nodeDir = stateDir.resolve("node-1")
    assertEquals("raw-disk-bytes", Files.readString(nodeDir.resolve("disk.raw")))
    assertEquals("real-boot", Files.readString(nodeDir.resolve("boot.iso")))
    assertEquals("real-identity", Files.readString(nodeDir.resolve("identity.iso")))

    assertEquals(1, launcher.invocations.size)
    val command = launcher.invocations.single()
    assertEquals("vfkit", command.first())
    assertTrue(command.contains("virtio-blk,path=${nodeDir.resolve("disk.raw")}"))
    assertTrue(command.contains("virtio-blk,path=${nodeDir.resolve("boot.iso")}"))
    assertTrue(command.contains("virtio-blk,path=${nodeDir.resolve("identity.iso")}"))
    assertTrue(command.contains("unix://${nodeDir.resolve("vfkit.sock")}"))
    assertTrue(command.any { it.startsWith("efi,variable-store=") && it.endsWith(",create") })
  }

  @Test
  fun `create fails when the base disk image doesn't exist`() {
    val stateDir = tempDir.resolve("state")
    val driver =
        VfkitNodeVmDriver(
            VfkitConfig(stateDir = stateDir),
            FakeVfkitProcessLauncher(),
            FakeVfkitRestClient(),
        )

    val error =
        assertFailsWith<NodeVmDriverException> {
          driver.create(
              "node-1",
              tempDir.resolve("does-not-exist.raw"),
              makeIso("boot.iso", "b"),
              makeIso("identity.iso", "i"),
          )
        }
    assertTrue(error.message!!.contains("not found"))
  }

  @Test
  fun `create refuses to clobber an existing node directory`() {
    val stateDir = tempDir.resolve("state")
    Files.createDirectories(stateDir.resolve("node-1"))
    val driver =
        VfkitNodeVmDriver(
            VfkitConfig(stateDir = stateDir),
            FakeVfkitProcessLauncher(),
            FakeVfkitRestClient(),
        )

    val error =
        assertFailsWith<NodeVmDriverException> {
          driver.create(
              "node-1",
              makeDisk(),
              makeIso("boot.iso", "b"),
              makeIso("identity.iso", "i"),
          )
        }
    assertTrue(error.message!!.contains("already exists"))
  }

  // ---------------------------------------------------------------------
  // start / stop / status
  // ---------------------------------------------------------------------

  @Test
  fun `start relaunches vfkit when the node exists but isn't running`() {
    val stateDir = tempDir.resolve("state")
    val nodeDir = stateDir.resolve("node-1")
    Files.createDirectories(nodeDir)
    val launcher = FakeVfkitProcessLauncher()
    val rest = FakeVfkitRestClient() // no state seeded -> socket unreachable -> stopped
    val driver = VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), launcher, rest)

    driver.start("node-1")

    assertEquals(1, launcher.invocations.size)
  }

  @Test
  fun `start is a no-op when the node is already running`() {
    val stateDir = tempDir.resolve("state")
    val nodeDir = stateDir.resolve("node-1")
    Files.createDirectories(nodeDir)
    val launcher = FakeVfkitProcessLauncher()
    val rest = FakeVfkitRestClient()
    rest.seed(nodeDir.resolve("vfkit.sock"), "VirtualMachineStateRunning")
    val driver = VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), launcher, rest)

    driver.start("node-1")

    assertTrue(launcher.invocations.isEmpty())
  }

  @Test
  fun `start fails clearly when the node was never created`() {
    val stateDir = tempDir.resolve("state")
    val driver =
        VfkitNodeVmDriver(
            VfkitConfig(stateDir = stateDir),
            FakeVfkitProcessLauncher(),
            FakeVfkitRestClient(),
        )

    val error = assertFailsWith<NodeVmDriverException> { driver.start("node-1") }
    assertTrue(error.message!!.contains("No vz node named"))
  }

  @Test
  fun `stop posts Stop, or HardStop when forced, and waits for the socket to go quiet`() {
    val stateDir = tempDir.resolve("state")
    val nodeDir = stateDir.resolve("node-1")
    Files.createDirectories(nodeDir)
    val rest = FakeVfkitRestClient()
    rest.seed(nodeDir.resolve("vfkit.sock"), "VirtualMachineStateRunning")
    val driver =
        VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), FakeVfkitProcessLauncher(), rest)

    driver.stop("node-1")

    assertEquals(listOf(nodeDir.resolve("vfkit.sock") to "Stop"), rest.setStateCalls)

    val rest2 = FakeVfkitRestClient()
    rest2.seed(nodeDir.resolve("vfkit.sock"), "VirtualMachineStateRunning")
    val driver2 =
        VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), FakeVfkitProcessLauncher(), rest2)
    driver2.stop("node-1", force = true)
    assertEquals(listOf(nodeDir.resolve("vfkit.sock") to "HardStop"), rest2.setStateCalls)
  }

  @Test
  fun `stop is a no-op when the node is already stopped`() {
    val stateDir = tempDir.resolve("state")
    val nodeDir = stateDir.resolve("node-1")
    Files.createDirectories(nodeDir)
    val rest = FakeVfkitRestClient()
    val driver =
        VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), FakeVfkitProcessLauncher(), rest)

    driver.stop("node-1")

    assertTrue(rest.setStateCalls.isEmpty())
  }

  @Test
  fun `status maps vfkit REST states to NodeVmStatus`() {
    val stateDir = tempDir.resolve("state")
    val nodeDir = stateDir.resolve("node-1")
    Files.createDirectories(nodeDir)

    fun statusFor(state: String?): NodeVmStatus {
      val rest = FakeVfkitRestClient()
      state?.let { rest.seed(nodeDir.resolve("vfkit.sock"), it) }
      return VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), FakeVfkitProcessLauncher(), rest)
          .status("node-1")
    }

    assertEquals(NodeVmStatus.RUNNING, statusFor("VirtualMachineStateRunning"))
    assertEquals(NodeVmStatus.PAUSED, statusFor("VirtualMachineStatePaused"))
    assertEquals(NodeVmStatus.STOPPED, statusFor("VirtualMachineStateStopped"))
    // No socket reachable at all (vfkit isn't running) -> stopped, not an error.
    assertEquals(NodeVmStatus.STOPPED, statusFor(null))
  }

  @Test
  fun `status is NOT_FOUND when the node was never created`() {
    val stateDir = tempDir.resolve("state")
    val driver =
        VfkitNodeVmDriver(
            VfkitConfig(stateDir = stateDir),
            FakeVfkitProcessLauncher(),
            FakeVfkitRestClient(),
        )

    assertEquals(NodeVmStatus.NOT_FOUND, driver.status("node-1"))
  }

  // ---------------------------------------------------------------------
  // rotateIdentity
  // ---------------------------------------------------------------------

  @Test
  fun `rotateIdentity stops, swaps the identity image, and restarts a running node`() {
    val stateDir = tempDir.resolve("state")
    val nodeDir = stateDir.resolve("node-1")
    Files.createDirectories(nodeDir)
    Files.writeString(nodeDir.resolve("identity.iso"), "old-identity")
    val newIdentity = makeIso("new-identity.iso", "new-identity")

    val launcher = FakeVfkitProcessLauncher()
    val rest = FakeVfkitRestClient()
    rest.seed(nodeDir.resolve("vfkit.sock"), "VirtualMachineStateRunning")
    val driver = VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), launcher, rest)

    driver.rotateIdentity("node-1", newIdentity)

    assertEquals("new-identity", Files.readString(nodeDir.resolve("identity.iso")))
    assertEquals(listOf(nodeDir.resolve("vfkit.sock") to "Stop"), rest.setStateCalls)
    assertEquals(1, launcher.invocations.size) // relaunched once, after the swap
  }

  @Test
  fun `rotateIdentity only swaps the image when the node is already stopped`() {
    val stateDir = tempDir.resolve("state")
    val nodeDir = stateDir.resolve("node-1")
    Files.createDirectories(nodeDir)
    Files.writeString(nodeDir.resolve("identity.iso"), "old-identity")
    val newIdentity = makeIso("new-identity.iso", "new-identity")

    val launcher = FakeVfkitProcessLauncher()
    val rest = FakeVfkitRestClient() // stopped: no state seeded
    val driver = VfkitNodeVmDriver(VfkitConfig(stateDir = stateDir), launcher, rest)

    driver.rotateIdentity("node-1", newIdentity)

    assertEquals("new-identity", Files.readString(nodeDir.resolve("identity.iso")))
    assertTrue(rest.setStateCalls.isEmpty())
    assertTrue(launcher.invocations.isEmpty())
  }
}
