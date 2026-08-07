package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID

/**
 * Assembles a from-scratch `.utm` template bundle around a staged base disk image, so `node create
 * --driver utm` no longer requires an operator to hand-build one in UTM.app's GUI first (base OS
 * installed, two empty removable CD-ROM drives named `boot.iso` / `identity.iso`).
 * [UtmNodeVmDriver.create] then treats the result exactly like a hand-built template: it clones the
 * whole bundle and overwrites `Images/boot.iso` / `Images/identity.iso` with the node's real
 * rendered media.
 *
 * See node/utm-template-staging.md for the `config.plist` schema this targets and its caveats —
 * unlike [UtmNodeVmDriver]'s clone/attach/start flow, which is exercised against `utmctl`'s
 * documented CLI surface, this schema couldn't be validated against a real UTM.app in this
 * environment. If a UTM release rejects it, the fix is isolated to [renderConfigPlist].
 */
internal object UtmTemplateAssembler {

  /** MB of guest RAM given to an assembled node template — enough headroom for Docker + a JVM. */
  private const val MEMORY_MB = 4096

  /**
   * Returns a cached `.utm` template bundle wrapping [baseDisk] as its primary disk, building one
   * under `<cacheDir>/templates/` first if this exact disk hasn't been assembled before. Keyed by
   * [baseDisk]'s content hash (not its file name) so a `--base-image` override that reuses a cached
   * image's file name can't silently reuse a stale template built from different bytes.
   */
  fun assemble(
      baseDisk: Path,
      arch: String,
      cacheDir: Path = defaultImageCacheDir(),
      vmName: String = "workload-node-template",
  ): Path {
    val templatesDir = cacheDir.resolve("templates")
    val bundle = templatesDir.resolve("${sha256File(baseDisk).take(16)}.utm")
    if (Files.isDirectory(bundle)) return bundle

    Files.createDirectories(templatesDir)
    val staging = Files.createTempDirectory(templatesDir, "staging-")
    try {
      val images = staging.resolve("Images")
      Files.createDirectories(images)
      Files.copy(baseDisk, images.resolve(baseDisk.fileName), StandardCopyOption.REPLACE_EXISTING)
      // Empty placeholders: NodeVmDriver.create always overwrites these with the node's real
      // rendered media before the clone is ever booted, but a valid bundle should still resolve
      // both backing files if opened directly.
      Files.createFile(images.resolve("boot.iso"))
      Files.createFile(images.resolve("identity.iso"))
      Files.writeString(
          staging.resolve("config.plist"),
          renderConfigPlist(vmName, arch, baseDisk.fileName.toString()),
      )
      Files.move(staging, bundle, StandardCopyOption.ATOMIC_MOVE)
    } finally {
      if (Files.isDirectory(staging)) {
        Files.walk(staging).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
    return bundle
  }

  private fun renderConfigPlist(vmName: String, arch: String, diskFileName: String): String {
    val architecture = if (arch == "arm64") "aarch64" else "x86_64"
    val target = if (arch == "arm64") "virt" else "q35"
    val cpu = if (arch == "arm64") "host" else "host"
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>ConfigurationVersion</key><integer>4</integer>
          <key>Backend</key><string>QEMU</string>
          <key>Information</key>
          <dict>
            <key>Name</key><string>${xmlEscape(vmName)}</string>
            <key>Uuid</key><string>${UUID.randomUUID()}</string>
            <key>Notes</key><string>Assembled by workload node create --driver utm; see node/utm-template-staging.md.</string>
          </dict>
          <key>System</key>
          <dict>
            <key>Architecture</key><string>$architecture</string>
            <key>Target</key><string>$target</string>
            <key>CPUCount</key><integer>0</integer>
            <key>CPU</key><string>$cpu</string>
            <key>MemorySize</key><integer>$MEMORY_MB</integer>
          </dict>
          <key>QEMU</key>
          <dict>
            <key>UEFIBoot</key><true/>
            <key>HasDebugLog</key><false/>
          </dict>
          <key>Display</key>
          <array>
            <dict>
              <key>Hardware</key><string>virtio-ramfb</string>
            </dict>
          </array>
          <key>Networks</key>
          <array>
            <dict>
              <key>Mode</key><string>Shared</string>
              <key>Hardware</key><string>virtio-net-pci</string>
            </dict>
          </array>
          <key>Drives</key>
          <array>
            <dict>
              <key>Identifier</key><string>disk0</string>
              <key>ImageName</key><string>${xmlEscape(diskFileName)}</string>
              <key>ImageType</key><string>Disk</string>
              <key>Interface</key><string>VirtIO</string>
              <key>Removable</key><false/>
            </dict>
            <dict>
              <key>Identifier</key><string>boot-iso</string>
              <key>ImageName</key><string>boot.iso</string>
              <key>ImageType</key><string>CD</string>
              <key>Interface</key><string>USB</string>
              <key>Removable</key><true/>
            </dict>
            <dict>
              <key>Identifier</key><string>identity-iso</string>
              <key>ImageName</key><string>identity.iso</string>
              <key>ImageType</key><string>CD</string>
              <key>Interface</key><string>USB</string>
              <key>Removable</key><true/>
            </dict>
          </array>
        </dict>
        </plist>
        """
        .trimIndent()
  }

  private fun xmlEscape(value: String): String =
      value
          .replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
          .replace("\"", "&quot;")
          .replace("'", "&apos;")
}
