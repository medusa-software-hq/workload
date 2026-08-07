package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import software.medusa.workload.runtime.localHostname

/**
 * Groups node provisioning — minting a node's enrollment credential and rendering its boot/
 * identity media — under a `node` prefix. A node is a machine (cloud VM or manually attached static
 * VM) that runs the unified image built from `node/cloud-init/node.yaml.tmpl`: it installs Docker
 * and the `workload` CLI, then registers itself as a worker using whatever's on its identity
 * volume. See node/README.md.
 */
class NodeCommand : NoOpCliktCommand(name = "node") {
  override fun help(context: Context) =
      "Provision node boot/identity media: mint an enrollment credential and render the unified " +
          "cloud-init template into files you attach to a VM."
}

// ---------------------------------------------------------------------------
// Shared plumbing
// ---------------------------------------------------------------------------

/** node.yaml.tmpl's placeholders — the exact set the Terraform node-template module also fills. */
internal data class NodeTemplateVars(
    val workloadEnvironment: String,
    val nodeName: String,
    val cliVersion: String,
)

/**
 * Substitutes exactly [NodeTemplateVars]' three placeholders — nothing else in the template is
 * touched, by design (see the template's header comment): every other `$` in it is a literal shell
 * variable meant to expand on the node at boot, not a template placeholder.
 */
internal fun renderNodeCloudInit(template: String, vars: NodeTemplateVars): String =
    template
        .replace("\${workload_environment}", vars.workloadEnvironment)
        .replace("\${node_name}", vars.nodeName)
        .replace("\${cli_version}", vars.cliVersion)

/** Loads the template bundled into the CLI jar as a resource — see cli/build.gradle.kts. */
internal fun loadNodeCloudInitTemplate(): String =
    (object {}).javaClass.getResourceAsStream("/node.yaml.tmpl")?.bufferedReader()?.readText()
        ?: throw IllegalStateException(
            "node.yaml.tmpl is missing from this build of the CLI — see cli/build.gradle.kts's " +
                "resources.srcDir wiring."
        )

internal fun defaultNodeName(): String {
  val user = System.getProperty("user.name") ?: "node"
  val host = localHostname() ?: "unknown-host"
  return "$user-$host"
}

/**
 * Creates [dir] and every missing ancestor at 0700 — unlike [Files.createDirectories], which would
 * otherwise leave a freshly-created *parent* (e.g. `node-1/` above `node-1/identity/`) at the
 * platform default, wide-open permissions.
 */
private fun createSecureDirectories(dir: Path) {
  if (Files.exists(dir)) return
  dir.parent?.let(::createSecureDirectories)
  runCatching {
        Files.createDirectory(
            dir,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
      }
      .getOrElse { Files.createDirectory(dir) }
}

/**
 * Writes [content] at 0600, creating its directory chain at 0700 — mirrors [saveConfig]'s pattern.
 */
private fun writeSecret(path: Path, content: String) {
  createSecureDirectories(path.parent)
  Files.deleteIfExists(path)
  runCatching {
        Files.createFile(
            path,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
      }
      .getOrElse { Files.createFile(path) }
  Files.writeString(path, content)
}

private fun writePlain(path: Path, content: String) {
  Files.createDirectories(path.parent)
  Files.writeString(path, content)
}

/** Where a node's rendered artifacts land when `--out` isn't given. */
internal fun defaultNodeOutDir(nodeName: String): Path = Path.of("node-$nodeName")

/**
 * The paths [writeNodeArtifacts] produced. [bootIso]/[identityIso] are null when no ISO tool
 * (genisoimage/mkisofs/xorriso) was found on PATH — the raw files are always written regardless.
 */
internal data class NodeArtifacts(
    val outDir: Path,
    val userData: Path?,
    val metaData: Path?,
    val bootIso: Path?,
    val enrollmentTokenFile: Path,
    val identityIso: Path?,
)

/**
 * Renders this node's boot media (cloud-init user-data + meta-data, [bootMedia] = true) and/or its
 * identity volume (the enrollment token, always) into [outDir], building ISOs for whichever an ISO
 * tool on PATH can produce. Never throws on a missing ISO tool — the raw files are the source of
 * truth; the ISO is a convenience.
 */
internal fun writeNodeArtifacts(
    outDir: Path,
    nodeName: String,
    workloadEnvironment: String,
    cliVersion: String,
    enrollmentToken: String,
    bootMedia: Boolean,
    isoBuilder: IsoBuilder = ProcessIsoBuilder,
): NodeArtifacts {
  val identityDir = outDir.resolve("identity")
  val tokenFile = identityDir.resolve("enrollment-token")
  writeSecret(tokenFile, enrollmentToken)
  val identityIso =
      isoBuilder.build(outDir.resolve("identity.iso"), "WLIDENTITY", listOf(identityDir))

  if (!bootMedia) {
    return NodeArtifacts(outDir, null, null, null, tokenFile, identityIso)
  }

  val rendered =
      renderNodeCloudInit(
          loadNodeCloudInitTemplate(),
          NodeTemplateVars(workloadEnvironment, nodeName, cliVersion),
      )
  val userData = outDir.resolve("user-data")
  val metaData = outDir.resolve("meta-data")
  writePlain(userData, rendered)
  writePlain(metaData, "instance-id: $nodeName\nlocal-hostname: $nodeName\n")
  val bootIso =
      isoBuilder.build(outDir.resolve("node-seed.iso"), "cidata", listOf(userData, metaData))

  return NodeArtifacts(outDir, userData, metaData, bootIso, tokenFile, identityIso)
}

/**
 * Abstracts the external ISO-building step so it's fakeable in tests without a real tool on PATH.
 */
internal fun interface IsoBuilder {
  /** Builds [output] (volume id [volId]) from [inputs]; returns null if no tool is available. */
  fun build(output: Path, volId: String, inputs: List<Path>): Path?
}

/** Shells out to whichever of genisoimage/mkisofs/xorriso is on PATH first. */
internal object ProcessIsoBuilder : IsoBuilder {
  private val candidates = listOf("genisoimage", "mkisofs", "xorriso")

  private fun which(name: String): Boolean =
      System.getenv("PATH")?.split(java.io.File.pathSeparatorChar).orEmpty().any {
        Path.of(it).resolve(name).let(Files::isExecutable)
      }

  override fun build(output: Path, volId: String, inputs: List<Path>): Path? {
    val tool = candidates.firstOrNull(::which) ?: return null
    val command =
        if (tool == "xorriso") {
          listOf(
              "xorriso",
              "-as",
              "mkisofs",
              "-output",
              output.toString(),
              "-volid",
              volId,
              "-joliet",
              "-rock",
          ) + inputs.map(Path::toString)
        } else {
          listOf(tool, "-output", output.toString(), "-volid", volId, "-joliet", "-rock") +
              inputs.map(Path::toString)
        }
    val exit = ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
    return if (exit == 0) output else null
  }
}

/** Prints the manual VM-attach instructions common to `node enroll` and `node create`. */
internal fun printAttachInstructions(command: CliktCommand, artifacts: NodeArtifacts) {
  with(command) {
    echo()
    echo("Node artifacts written to ${artifacts.outDir}:")
    if (artifacts.userData != null) {
      echo("  ${artifacts.userData} / ${artifacts.metaData}  — cloud-init boot media")
      if (artifacts.bootIso != null) {
        echo("  ${artifacts.bootIso}  — the same, as a NoCloud seed ISO (volume id 'cidata')")
      } else {
        echo(
            "  (no genisoimage/mkisofs/xorriso on PATH — build the seed ISO yourself, or use " +
                "node/cloud-init/render-iso.sh)"
        )
      }
    }
    echo("  ${artifacts.enrollmentTokenFile}  — the one-time enrollment token, shown once")
    if (artifacts.identityIso != null) {
      echo(
          "  ${artifacts.identityIso}  — the same, as an identity volume ISO (volume id 'WLIDENTITY')"
      )
    } else {
      echo(
          "  (no genisoimage/mkisofs/xorriso on PATH — build the identity ISO yourself, or use " +
              "node/cloud-init/render-identity-volume.sh)"
      )
    }
    echo()
    if (artifacts.bootIso != null || artifacts.userData != null) {
      echo("To attach this node manually:")
      echo(
          "  1. Attach the boot/seed image as this VM's boot CD-ROM (or its cloud-init datasource)."
      )
      echo("  2. Attach the identity image as a second, separate CD-ROM/disk.")
      echo(
          "  3. Boot the VM. It installs Docker + the workload CLI, then registers using the " +
              "identity volume's token."
      )
    }
    echo(
        "To rotate this node's identity later without recreating the VM: mint a fresh token " +
            "('workload node enroll --identity-only --name <name>'), then detach the old identity " +
            "volume and attach the new one — the node re-registers itself in place."
    )
  }
}

// ---------------------------------------------------------------------------
// enroll / create
// ---------------------------------------------------------------------------

private const val defaultNoteForNode = "node enroll"

/** Options shared by [NodeEnrollCommand] and [NodeCreateCommand]. */
abstract class NodeProvisionCommand(name: String) : AdminActionCommand(name) {
  val name by
      option("--name", help = "Node name; defaults to <user>-<hostname>, same as worker register.")
  val note by option("--note", help = "Free-text label recorded with the enrollment token.")
  val expiresInDays by
      option("--expires-in-days", help = "Days until the token expires (server default: 7).").int()
  val requireApproval by
      option(
              "--require-approval",
              help = "Land the node PENDING for admin approval instead of straight to ACTIVE.",
          )
          .flag(default = false)
  val cliVersion by
      option("--cli-version", help = "workload-cli release tag to install, or 'latest'.")
          .default("latest")
  val identityOnly by
      option(
              "--identity-only",
              help =
                  "Mint and render only the identity volume (no boot media) — for rotating an " +
                      "already-running node's identity without recreating it.",
          )
          .flag(default = false)
  val out by option("--out", help = "Directory to write artifacts to (default: ./node-<name>).")

  fun resolvedName(): String = name?.ifBlank { null } ?: defaultNodeName()

  internal fun provision(nodeName: String): NodeArtifacts {
    val result = runAdmin {
      client()
          .createEnrollmentToken(
              note ?: "$defaultNoteForNode: $nodeName",
              expiresInDays ?: 0,
              requireApproval,
          )
    }
    val outDir = out?.let(Path::of) ?: defaultNodeOutDir(nodeName)
    return writeNodeArtifacts(
        outDir = outDir,
        nodeName = nodeName,
        workloadEnvironment = env.label,
        cliVersion = cliVersion,
        enrollmentToken = result.token,
        bootMedia = !identityOnly,
    )
  }
}

class NodeEnrollCommand : NodeProvisionCommand(name = "enroll") {
  override fun help(context: Context) =
      "Mint a one-time enrollment token and render this node's boot/identity media — then print " +
          "manual VM-attach instructions."

  override fun run() {
    val nodeName = resolvedName()
    val artifacts = provision(nodeName)
    echo("Enrolled node '$nodeName'.")
    printAttachInstructions(this, artifacts)
  }
}

class NodeCreateCommand : NodeProvisionCommand(name = "create") {
  // "none" mints credentials and renders media for a human to manually attach to a VM they
  // provision themselves — still the only cross-platform option. "utm" is a convenience layer
  // over that exact same flow, scripted against a local UTM.app (macOS) instead of a human; see
  // UtmDriver.kt. A future cloud driver (e.g. "gce") would implement the same NodeVmDriver seam
  // against the Google API instead of utmctl/AppleScript.
  private val driver by
      option(
              "--driver",
              help =
                  "Provisioning driver: 'none' (manual attach, default) or 'utm' (local UTM.app " +
                      "VM, macOS only).",
          )
          .choice("none", "utm")
          .required()
  private val utmTemplate by
      option(
          "--utm-template",
          help =
              "Path to a pre-built template .utm VM bundle to clone for --driver utm instead of " +
                  "staging one automatically — base OS already installed, plus two empty " +
                  "removable CD-ROM drives named boot.iso / identity.iso in its Images/ dir.",
      )
  private val baseImage by
      option(
          "--base-image",
          help =
              "Base disk image for a staged --driver utm template: an http(s) URL or a local " +
                  "path to a qcow2/img file. Defaults to the current Ubuntu LTS cloud image for " +
                  "the host arch, fetched and cached under ~/.cache/workload/images/. Ignored " +
                  "if --utm-template is given.",
      )
  private val imageRelease by
      option(
          "--image-release",
          help =
              "Ubuntu release codename to stage the default cloud image from (e.g. 'noble'). " +
                  "Defaults to ${UbuntuCloudImageSpec.DEFAULT_RELEASE}. Ignored if --utm-template " +
                  "or --base-image is given.",
      )
  private val utmctlPath by
      option("--utmctl", help = "Path to the utmctl binary (--driver utm only).").default("utmctl")

  override fun help(context: Context) =
      "Create a node. With --driver none, this mints credentials and renders boot/identity media " +
          "for a VM you attach by hand — no hypervisor is touched. With --driver utm, it also " +
          "stages a template UTM VM (a cached Ubuntu cloud image by default, or --base-image / " +
          "--utm-template to override), clones it, attaches the rendered media, and starts it."

  override fun run() {
    val nodeName = resolvedName()
    val artifacts = provision(nodeName)
    when (driver) {
      "none" -> {
        echo(
            "Driver 'none' does not provision a VM for you — attach the media below to one " +
                "yourself."
        )
        echo("Node '$nodeName' is enrolled and waiting for that VM to boot.")
        printAttachInstructions(this, artifacts)
      }
      "utm" -> createUtmVm(nodeName, artifacts)
    }
  }

  private fun createUtmVm(nodeName: String, artifacts: NodeArtifacts) {
    val bootIso =
        artifacts.bootIso
            ?: throw PrintMessage(
                "--driver utm needs a boot ISO, but no genisoimage/mkisofs/xorriso was found on " +
                    "PATH.",
                statusCode = 1,
                printError = true,
            )
    val identityIso =
        artifacts.identityIso
            ?: throw PrintMessage(
                "--driver utm needs an identity ISO, but no genisoimage/mkisofs/xorriso was " +
                    "found on PATH.",
                statusCode = 1,
                printError = true,
            )
    val template = utmTemplate?.let(Path::of) ?: stageUtmTemplate()
    val vmDriver: NodeVmDriver = UtmNodeVmDriver(UtmVmConfig(utmctlPath = utmctlPath))
    echo("Cloning UTM template and starting '$nodeName'...")
    try {
      vmDriver.create(nodeName, template, bootIso, identityIso)
    } catch (e: NodeVmDriverException) {
      throw PrintMessage(e.message ?: "UTM driver failed.", statusCode = 1, printError = true)
    }
    echo("Node '$nodeName' created and started via UTM.")
    echo("  status: workload node status --name $nodeName --driver utm")
    echo("  stop:   workload node stop --name $nodeName --driver utm")
  }

  /**
   * No --utm-template given: stage one instead of asking the operator to hand-build it — resolve
   * the base disk (cached Ubuntu LTS cloud image, or --base-image), then assemble/cache a `.utm`
   * template bundle around it. Both steps are cached (by file name + checksum, and by the disk's
   * content hash, respectively) so this is a no-op download/assembly on every create after the
   * first. See node/utm-template-staging.md.
   */
  private fun stageUtmTemplate(): Path {
    val arch = hostImageArch()
    val cache = ImageCache()
    val baseDisk =
        try {
          baseImage?.let(cache::resolveBaseImage)
              ?: cache.resolveUbuntuCloudImage(
                  UbuntuCloudImageSpec(
                      release = imageRelease ?: UbuntuCloudImageSpec.DEFAULT_RELEASE,
                      arch = arch,
                  )
              )
        } catch (e: NodeVmDriverException) {
          throw PrintMessage(
              e.message ?: "Failed to stage base image.",
              statusCode = 1,
              printError = true,
          )
        }
    echo("Staging UTM template from $baseDisk...")
    return UtmTemplateAssembler.assemble(baseDisk, arch)
  }
}

// ---------------------------------------------------------------------------
// start / stop / status / rotate-identity — driver-controlled VM lifecycle
// ---------------------------------------------------------------------------

/** Options shared by the driver-controlled VM lifecycle commands below. */
abstract class NodeVmCommand(name: String) : CliktCommand(name) {
  val nodeName by option("--name", help = "Node/VM name.").required()
  private val driver by
      option("--driver", help = "VM driver. Only 'utm' controls a VM today.")
          .choice("utm")
          .default("utm")
  private val utmctlPath by
      option("--utmctl", help = "Path to the utmctl binary (--driver utm only).").default("utmctl")

  internal fun driverFor(): NodeVmDriver =
      when (driver) {
        "utm" -> UtmNodeVmDriver(UtmVmConfig(utmctlPath = utmctlPath))
        else -> throw PrintMessage("Unknown driver '$driver'.", statusCode = 1, printError = true)
      }

  internal inline fun <T> runDriver(block: NodeVmDriver.() -> T): T =
      try {
        driverFor().block()
      } catch (e: NodeVmDriverException) {
        throw PrintMessage(e.message ?: "Driver call failed.", statusCode = 1, printError = true)
      }
}

class NodeStartCommand : NodeVmCommand(name = "start") {
  override fun help(context: Context) = "Start a driver-managed node VM."

  override fun run() {
    runDriver { start(nodeName) }
    echo("Started '$nodeName'.")
  }
}

class NodeStopCommand : NodeVmCommand(name = "stop") {
  private val force by
      option("--force", help = "Force stop (power off) instead of a graceful shutdown.")
          .flag(default = false)

  override fun help(context: Context) = "Stop a driver-managed node VM."

  override fun run() {
    runDriver { stop(nodeName, force) }
    echo("Stopped '$nodeName'.")
  }
}

class NodeStatusCommand : NodeVmCommand(name = "status") {
  override fun help(context: Context) = "Show a driver-managed node VM's power state."

  override fun run() {
    val status = runDriver { status(nodeName) }
    echo(status.name.lowercase())
  }
}

class NodeRotateIdentityCommand : AdminActionCommand(name = "rotate-identity") {
  private val nodeName by option("--name", help = "Node/VM name.").required()
  private val note by option("--note", help = "Free-text label recorded with the enrollment token.")
  private val expiresInDays by
      option("--expires-in-days", help = "Days until the token expires (server default: 7).").int()
  private val requireApproval by
      option(
              "--require-approval",
              help = "Land the node PENDING for admin approval instead of straight to ACTIVE.",
          )
          .flag(default = false)
  private val out by
      option(
          "--out",
          help = "Directory to write the fresh identity volume to (default: ./node-<name>).",
      )
  private val driver by
      option("--driver", help = "VM driver to swap the identity volume on. Only 'utm' today.")
          .choice("utm")
          .default("utm")
  private val utmctlPath by
      option("--utmctl", help = "Path to the utmctl binary (--driver utm only).").default("utmctl")

  override fun help(context: Context) =
      "Mint a fresh enrollment token, render a new identity volume, and swap it into a " +
          "driver-managed node VM. See node/utm-driver-spike.md: this is a stop -> swap -> start " +
          "cycle under the hood, not a live hot-swap."

  override fun run() {
    val result = runAdmin {
      client()
          .createEnrollmentToken(
              note ?: "$defaultNoteForNode (rotate-identity): $nodeName",
              expiresInDays ?: 0,
              requireApproval,
          )
    }
    val outDir = out?.let(Path::of) ?: defaultNodeOutDir(nodeName)
    val artifacts =
        writeNodeArtifacts(
            outDir = outDir,
            nodeName = nodeName,
            workloadEnvironment = env.label,
            cliVersion = "unused",
            enrollmentToken = result.token,
            bootMedia = false,
        )
    val identityIso =
        artifacts.identityIso
            ?: throw PrintMessage(
                "No genisoimage/mkisofs/xorriso on PATH — can't build the identity ISO --driver " +
                    "utm needs.",
                statusCode = 1,
                printError = true,
            )
    val vmDriver: NodeVmDriver =
        when (driver) {
          "utm" -> UtmNodeVmDriver(UtmVmConfig(utmctlPath = utmctlPath))
          else -> throw PrintMessage("Unknown driver '$driver'.", statusCode = 1, printError = true)
        }
    try {
      vmDriver.rotateIdentity(nodeName, identityIso)
    } catch (e: NodeVmDriverException) {
      throw PrintMessage(
          e.message ?: "Identity rotation failed.",
          statusCode = 1,
          printError = true,
      )
    }
    echo("Rotated '$nodeName''s identity volume via UTM.")
  }
}
