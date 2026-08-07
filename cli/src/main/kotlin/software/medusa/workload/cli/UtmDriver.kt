package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What `node create --driver <x>` needs from a hypervisor to be a convenience layer over the manual
 * attach flow in [printAttachInstructions]: register a VM seeded with the two rendered ISOs and
 * start it, then control that VM afterward. `none` doesn't implement this — there's no VM for the
 * CLI to reach. `utm` ([UtmNodeVmDriver]) was the first driver that does; `vz`
 * ([VfkitNodeVmDriver]) is the primary one today — see node/README.md. A future cloud driver (e.g.
 * `gce`) would implement this same interface against the Google API instead.
 */
internal interface NodeVmDriver {
  /**
   * Clones [templateBundle] as [nodeName]'s VM, swaps its two removable drives for [bootIso] /
   * [identityIso], registers it with the hypervisor, and starts it.
   */
  fun create(nodeName: String, templateBundle: Path, bootIso: Path, identityIso: Path)

  fun start(nodeName: String)

  fun stop(nodeName: String, force: Boolean = false)

  fun status(nodeName: String): NodeVmStatus

  /**
   * Swaps [nodeName]'s identity volume for [identityIso]. See node/utm-driver-spike.md: no driver
   * here is required to do this without a restart — the node's udev rule re-runs
   * `workload-node-identity-sync` whenever the `WLIDENTITY` device (re-)appears, so a stop → swap →
   * start cycle is a legitimate implementation, not just a fallback.
   */
  fun rotateIdentity(nodeName: String, identityIso: Path)
}

internal enum class NodeVmStatus {
  RUNNING,
  STOPPED,
  PAUSED,
  NOT_FOUND,
}

/** Anything a [NodeVmDriver] can't recover from — always caught at the CLI boundary. */
internal class NodeVmDriverException(message: String) : Exception(message)

/**
 * Abstracts shelling out so [UtmNodeVmDriver] is unit-testable without `utmctl`/`osascript` (or
 * macOS) present — the same seam [IsoBuilder] gives the ISO-building step.
 */
internal fun interface ProcessRunner {
  fun run(command: List<String>): ProcessResult
}

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

internal object RealProcessRunner : ProcessRunner {
  override fun run(command: List<String>): ProcessResult {
    val process =
        try {
          ProcessBuilder(command).start()
        } catch (e: java.io.IOException) {
          throw NodeVmDriverException("Failed to run '${command.joinToString(" ")}': ${e.message}")
        }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return ProcessResult(exitCode, stdout, stderr)
  }
}

/** Where UTM.app keeps the `.utm` bundles it manages, on a stock macOS install. */
internal fun defaultUtmVmsDir(): Path =
    Path.of(System.getProperty("user.home"), "Library/Containers/com.utmapp.UTM/Data/Documents")

internal data class UtmVmConfig(
    val vmsDir: Path = defaultUtmVmsDir(),
    val utmctlPath: String = "utmctl",
    val osascriptPath: String = "osascript",
)

/**
 * Drives a local [UTM.app](https://mac.getutm.app) instance via `utmctl` (start/stop/status — the
 * commands it actually has) and AppleScript (registering a newly cloned bundle — the one thing
 * `utmctl` can't do; see [registerWithUtm]). This is a convenience layer, not a from-scratch VM
 * builder: `create` clones a pre-built template bundle rather than installing an OS, exactly the
 * way a human following node/README.md's manual instructions would start from an existing VM shell
 * and attach the two rendered ISOs to it.
 *
 * The template bundle must already contain two empty removable CD-ROM drives whose backing files
 * are named exactly `boot.iso` and `identity.iso` inside its `Images/` directory — `create` and
 * `rotateIdentity` replace those files directly rather than parsing/rewriting UTM's `config.plist`,
 * whose schema has changed across UTM versions and isn't worth coupling to for a convenience layer
 * over a manual flow.
 */
internal class UtmNodeVmDriver(
    private val config: UtmVmConfig = UtmVmConfig(),
    private val runner: ProcessRunner = RealProcessRunner,
) : NodeVmDriver {

  private fun bundlePath(nodeName: String): Path = config.vmsDir.resolve("$nodeName.utm")

  private fun utmctl(vararg args: String): ProcessResult =
      runner.run(listOf(config.utmctlPath) + args)

  private fun requireOk(result: ProcessResult, action: String) {
    if (result.exitCode != 0) {
      throw NodeVmDriverException(
          "utmctl $action failed (exit ${result.exitCode}): " +
              result.stderr.ifBlank { result.stdout }.trim()
      )
    }
  }

  override fun create(nodeName: String, templateBundle: Path, bootIso: Path, identityIso: Path) {
    if (!Files.isDirectory(templateBundle)) {
      throw NodeVmDriverException(
          "UTM template bundle not found at $templateBundle — build one by hand first (base OS " +
              "installed, two empty removable CD-ROM drives named boot.iso / identity.iso in its " +
              "Images/ dir), then pass its path via --utm-template."
      )
    }
    val bundle = bundlePath(nodeName)
    if (Files.exists(bundle)) {
      throw NodeVmDriverException(
          "$bundle already exists — stop and delete it (or pick a different node name) before " +
              "recreating."
      )
    }
    copyBundle(templateBundle, bundle)
    replaceDriveImage(bundle, "boot.iso", bootIso)
    replaceDriveImage(bundle, "identity.iso", identityIso)
    registerWithUtm(bundle)
    start(nodeName)
  }

  override fun start(nodeName: String) {
    requireOk(utmctl("start", nodeName), "start")
  }

  override fun stop(nodeName: String, force: Boolean) {
    val result = if (force) utmctl("stop", nodeName, "--force") else utmctl("stop", nodeName)
    requireOk(result, "stop")
  }

  override fun status(nodeName: String): NodeVmStatus {
    // utmctl exits non-zero (and prints nothing useful on stdout) when it can't find the VM at
    // all — that's the only signal we get for "not found" short of parsing `utmctl list`.
    val result = utmctl("status", nodeName)
    if (result.exitCode != 0) return NodeVmStatus.NOT_FOUND
    val out = result.stdout.trim().lowercase()
    return when {
      out.contains("start") || out.contains("run") -> NodeVmStatus.RUNNING
      out.contains("pause") -> NodeVmStatus.PAUSED
      else -> NodeVmStatus.STOPPED
    }
  }

  override fun rotateIdentity(nodeName: String, identityIso: Path) {
    val wasRunning = status(nodeName) == NodeVmStatus.RUNNING
    if (wasRunning) stop(nodeName)
    replaceDriveImage(bundlePath(nodeName), "identity.iso", identityIso)
    if (wasRunning) start(nodeName)
  }

  private fun copyBundle(source: Path, dest: Path) {
    Files.walk(source).use { paths ->
      paths.sorted().forEach { src ->
        val target = dest.resolve(source.relativize(src).toString())
        if (Files.isDirectory(src)) {
          Files.createDirectories(target)
        } else {
          Files.createDirectories(target.parent)
          Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }

  private fun replaceDriveImage(bundle: Path, imageName: String, source: Path) {
    val imagesDir = bundle.resolve("Images")
    if (!Files.isDirectory(imagesDir)) {
      throw NodeVmDriverException("$bundle has no Images/ directory — is it a valid UTM bundle?")
    }
    Files.copy(source, imagesDir.resolve(imageName), StandardCopyOption.REPLACE_EXISTING)
  }

  /**
   * `utmctl` has no `create`/`register` subcommand — a `.utm` bundle only becomes visible to it
   * (and to `utmctl status`/`stop`) after UTM.app has opened it once. The generic Apple Event
   * `open` — the one every document-based Mac app answers, UTM included — is the stable hook for
   * that; UTM's own scripting dictionary otherwise only covers the already-registered VM lifecycle
   * `utmctl` covers too. See node/utm-driver-spike.md for what was and wasn't scriptable here.
   */
  private fun registerWithUtm(bundle: Path) {
    val script = "tell application \"UTM\" to open POSIX file \"$bundle\""
    val result = runner.run(listOf(config.osascriptPath, "-e", script))
    if (result.exitCode != 0) {
      throw NodeVmDriverException(
          "Failed to register $bundle with UTM via AppleScript: ${result.stderr.trim()}"
      )
    }
  }
}
