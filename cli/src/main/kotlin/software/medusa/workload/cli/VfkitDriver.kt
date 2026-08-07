package software.medusa.workload.cli

import com.linecorp.armeria.client.BlockingWebClient
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.util.DomainSocketAddress
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val successRange = 200..299

internal fun defaultVfkitStateDir(): Path =
    Path.of(System.getProperty("user.home"), ".workload/vz-nodes")

internal data class VfkitConfig(
    val stateDir: Path = defaultVfkitStateDir(),
    val vfkitPath: String = "vfkit",
    val cpus: Int = 2,
    val memoryMiB: Int = 4096,
)

/**
 * Abstracts launching `vfkit` as a detached, long-running process so [VfkitNodeVmDriver] is
 * unit-testable without a real binary (or macOS) present — the same seam [ProcessRunner] gives
 * [UtmNodeVmDriver]. Unlike `ProcessRunner`, this doesn't wait for the process to exit: `vfkit`
 * runs for as long as the VM is up, so waiting here would block `node create`/`node start` forever.
 */
internal fun interface VfkitProcessLauncher {
  /** Launches [command] detached, appending its combined stdout/stderr to `<nodeDir>/vfkit.log`. */
  fun launch(command: List<String>, nodeDir: Path)
}

internal object RealVfkitProcessLauncher : VfkitProcessLauncher {
  override fun launch(command: List<String>, nodeDir: Path) {
    val log = nodeDir.resolve("vfkit.log")
    val builder =
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectErrorStream(true)
    val process =
        try {
          builder.start()
        } catch (e: IOException) {
          throw NodeVmDriverException(
              "Failed to launch '${command.first()}': ${e.message} — is vfkit installed " +
                  "(brew install vfkit) and on PATH? Pass --vfkit to point at it explicitly."
          )
        }
    Files.writeString(nodeDir.resolve("vfkit.pid"), process.pid().toString())
  }
}

@Serializable
internal data class VfkitStateResponse(
    val state: String,
    val canStart: Boolean = false,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canStop: Boolean = false,
    val canHardStop: Boolean = false,
)

@Serializable internal data class VfkitStateRequest(val state: String)

/**
 * Abstracts vfkit's `/vm/state` REST endpoint (see doc/usage.md in the vfkit repo) so
 * [VfkitNodeVmDriver] is unit-testable without a real socket. `getState` returning `null` means the
 * socket couldn't be reached at all — vfkit isn't running (the VM is stopped), not an error:
 * `vfkit` exits as soon as its VM does, taking the socket with it.
 */
internal interface VfkitRestClient {
  fun getState(socketPath: Path): VfkitStateResponse?

  fun setState(socketPath: Path, state: String)
}

internal object RealVfkitRestClient : VfkitRestClient {
  private val json = Json { ignoreUnknownKeys = true }

  override fun getState(socketPath: Path): VfkitStateResponse? {
    if (!Files.exists(socketPath)) return null
    return runCatching {
          withClient(socketPath) { client ->
            val response = client.get("/vm/state")
            if (response.status().code() !in successRange) return@withClient null
            json.decodeFromString<VfkitStateResponse>(response.contentUtf8())
          }
        }
        .getOrNull()
  }

  override fun setState(socketPath: Path, state: String) {
    val response =
        try {
          withClient(socketPath) { client ->
            client.execute(
                RequestHeaders.of(
                    HttpMethod.POST,
                    "/vm/state",
                    "content-type",
                    MediaType.JSON.toString(),
                ),
                HttpData.ofUtf8(json.encodeToString(VfkitStateRequest(state))),
            )
          }
        } catch (e: Exception) {
          throw NodeVmDriverException(
              "Couldn't reach vfkit's REST API at $socketPath to set state '$state': ${e.message}"
          )
        }
    if (response.status().code() !in successRange) {
      throw NodeVmDriverException(
          "vfkit REST /vm/state (\"$state\") failed: HTTP ${response.status().code()}"
      )
    }
  }

  private fun <T> withClient(socketPath: Path, block: (BlockingWebClient) -> T): T {
    val address = DomainSocketAddress.of(socketPath)
    val factory = ClientFactory.builder().build()
    try {
      val client =
          WebClient.builder("http://" + address.authority())
              .factory(factory)
              .responseTimeoutMillis(5_000)
              .build()
              .blocking()
      return block(client)
    } finally {
      factory.close()
    }
  }
}

/**
 * Drives a local VM via [vfkit](https://github.com/crc-org/vfkit) — a headless wrapper around
 * macOS's Virtualization.framework, the same helper `podman machine`/`crc` use. Unlike
 * [UtmNodeVmDriver], there's no GUI app or on-disk VM-bundle format to fight: `vfkit` *is* the
 * running VM (it starts the VM when it starts and the VM dies when it exits), and it's driven
 * entirely by this process's own CLI args plus a REST API over a unix socket it opens for us.
 *
 * A node's whole state lives under `<stateDir>/<nodeName>/`: the raw disk (a copy of the staged
 * base image — see [VfkitDiskStager]), copies of the two rendered ISOs, an EFI variable store
 * `vfkit` creates on first boot, and its console log. `create`/`start` (re)launch `vfkit` pointed
 * at that directory; `stop`/`status` talk to the socket it opened last time it was launched.
 * `templateBundle` in [create] is the raw base disk image staged by [VfkitDiskStager] (or an
 * operator-provided one) — Apple's Virtualization.framework only accepts raw or ISO images, never
 * qcow2, so unlike [UtmNodeVmDriver] there's no bundle directory to clone.
 */
internal class VfkitNodeVmDriver(
    private val config: VfkitConfig = VfkitConfig(),
    private val launcher: VfkitProcessLauncher = RealVfkitProcessLauncher,
    private val restClient: VfkitRestClient = RealVfkitRestClient,
) : NodeVmDriver {

  private fun nodeDir(nodeName: String): Path = config.stateDir.resolve(nodeName)

  private fun socketPath(nodeName: String): Path = nodeDir(nodeName).resolve("vfkit.sock")

  private fun diskPath(nodeName: String): Path = nodeDir(nodeName).resolve("disk.raw")

  private fun bootIsoPath(nodeName: String): Path = nodeDir(nodeName).resolve("boot.iso")

  private fun identityIsoPath(nodeName: String): Path = nodeDir(nodeName).resolve("identity.iso")

  private fun efiVarsPath(nodeName: String): Path = nodeDir(nodeName).resolve("efi-vars")

  private fun consoleLogPath(nodeName: String): Path = nodeDir(nodeName).resolve("console.log")

  override fun create(nodeName: String, templateBundle: Path, bootIso: Path, identityIso: Path) {
    if (!Files.isRegularFile(templateBundle)) {
      throw NodeVmDriverException(
          "vz base disk image not found at $templateBundle — pass --base-image, or omit it to " +
              "stage the default Ubuntu LTS cloud image automatically."
      )
    }
    val dir = nodeDir(nodeName)
    if (Files.exists(dir)) {
      throw NodeVmDriverException(
          "$dir already exists — stop and delete it (or pick a different node name) before " +
              "recreating."
      )
    }
    try {
      Files.createDirectories(dir)
      Files.copy(templateBundle, diskPath(nodeName))
      Files.copy(bootIso, bootIsoPath(nodeName))
      Files.copy(identityIso, identityIsoPath(nodeName))
    } catch (e: IOException) {
      throw NodeVmDriverException("Failed to stage $dir: ${e.message}")
    }
    launchVfkit(nodeName)
  }

  override fun start(nodeName: String) {
    requireExists(nodeName)
    if (status(nodeName) == NodeVmStatus.RUNNING) return
    launchVfkit(nodeName)
  }

  override fun stop(nodeName: String, force: Boolean) {
    requireExists(nodeName)
    val socket = socketPath(nodeName)
    if (restClient.getState(socket) == null) return
    restClient.setState(socket, if (force) "HardStop" else "Stop")
    awaitStopped(socket)
  }

  override fun status(nodeName: String): NodeVmStatus {
    val dir = nodeDir(nodeName)
    if (!Files.isDirectory(dir)) return NodeVmStatus.NOT_FOUND
    val state = restClient.getState(socketPath(nodeName)) ?: return NodeVmStatus.STOPPED
    return when {
      state.state.contains("Paus", ignoreCase = true) -> NodeVmStatus.PAUSED
      state.state.contains("Stop", ignoreCase = true) -> NodeVmStatus.STOPPED
      else -> NodeVmStatus.RUNNING
    }
  }

  /**
   * `vfkit`'s REST API has no "change this device's backing file" verb (its whole device set is
   * launch-time config — see doc/usage.md), so a live swap is no more possible here than it is for
   * [UtmNodeVmDriver.rotateIdentity] (see node/utm-driver-spike.md). Same fallback: stop (if
   * running) → overwrite `identity.iso`'s bytes at its fixed path → start, which relaunches vfkit
   * pointed at the same path with the new content.
   */
  override fun rotateIdentity(nodeName: String, identityIso: Path) {
    requireExists(nodeName)
    val wasRunning = status(nodeName) == NodeVmStatus.RUNNING
    if (wasRunning) stop(nodeName)
    Files.copy(identityIso, identityIsoPath(nodeName), StandardCopyOption.REPLACE_EXISTING)
    if (wasRunning) start(nodeName)
  }

  private fun requireExists(nodeName: String) {
    if (!Files.isDirectory(nodeDir(nodeName))) {
      throw NodeVmDriverException("No vz node named '$nodeName' — run 'node create' first.")
    }
  }

  private fun launchVfkit(nodeName: String) {
    val dir = nodeDir(nodeName)
    // A stale socket from a prior run vfkit didn't clean up on a hard crash would otherwise make
    // the first status() poll after relaunch race a dead file instead of the new process's socket.
    Files.deleteIfExists(socketPath(nodeName))
    val command = buildList {
      add(config.vfkitPath)
      add("--cpus")
      add(config.cpus.toString())
      add("--memory")
      add(config.memoryMiB.toString())
      add("--bootloader")
      add("efi,variable-store=${efiVarsPath(nodeName)},create")
      add("--device")
      add("virtio-blk,path=${diskPath(nodeName)}")
      add("--device")
      add("virtio-blk,path=${bootIsoPath(nodeName)}")
      add("--device")
      add("virtio-blk,path=${identityIsoPath(nodeName)}")
      add("--device")
      add("virtio-net,nat")
      add("--device")
      add("virtio-rng")
      add("--device")
      add("virtio-serial,logFilePath=${consoleLogPath(nodeName)}")
      add("--restful-uri")
      add("unix://${socketPath(nodeName)}")
    }
    launcher.launch(command, dir)
  }

  private fun awaitStopped(socket: Path) {
    repeat(50) {
      if (restClient.getState(socket) == null) return
      Thread.sleep(200)
    }
  }
}
