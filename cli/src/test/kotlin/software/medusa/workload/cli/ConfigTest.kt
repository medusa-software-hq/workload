package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigTest {

  private val tempDir = Files.createTempDirectory("ms-workload-config-test")

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  @Test
  fun `configDir prefers XDG_CONFIG_HOME when set`() {
    val dir = configDir(xdgConfigHome = "/xdg/config", userHome = "/home/someone")
    assertEquals("/xdg/config/ms-workload", dir.toString())
  }

  @Test
  fun `configDir falls back to userHome slash config`() {
    val dir = configDir(xdgConfigHome = null, userHome = "/home/someone")
    assertEquals("/home/someone/.config/ms-workload", dir.toString())
  }

  @Test
  fun `configDir treats a blank XDG_CONFIG_HOME as unset`() {
    val dir = configDir(xdgConfigHome = "", userHome = "/home/someone")
    assertEquals("/home/someone/.config/ms-workload", dir.toString())
  }

  @Test
  fun `loadConfig returns null when no config file exists`() {
    assertNull(loadConfig(dir = tempDir.resolve("nonexistent")))
  }

  @Test
  fun `saveConfig round-trips through loadConfig`() {
    val config =
        WorkloadConfig(
            workerId = "44456e85-4e62-406d-9b86-cc95d33f5e54",
            workerSecret = "top-secret",
            workerName = "jakub-mbp",
        )

    saveConfig(config, dir = tempDir)

    assertEquals(config, loadConfig(dir = tempDir))
  }

  @Test
  fun `saveConfig sets 0700 on the directory and 0600 on the file`() {
    val config =
        WorkloadConfig(
            workerId = "44456e85-4e62-406d-9b86-cc95d33f5e54",
            workerSecret = "top-secret",
            workerName = "jakub-mbp",
        )

    saveConfig(config, dir = tempDir)

    assertEquals(
        PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(tempDir),
    )
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(configFile(tempDir)),
    )
  }

  @Test
  fun `saveConfig overwrites an existing config`() {
    val first =
        WorkloadConfig(
            workerId = "44456e85-4e62-406d-9b86-cc95d33f5e54",
            workerSecret = "first-secret",
            workerName = "worker-1",
        )
    val second = first.copy(workerName = "worker-2")

    saveConfig(first, dir = tempDir)
    saveConfig(second, dir = tempDir)

    assertEquals(second, loadConfig(dir = tempDir))
  }

  @Test
  fun `deleteConfig removes the config file`() {
    val config =
        WorkloadConfig(
            workerId = "44456e85-4e62-406d-9b86-cc95d33f5e54",
            workerSecret = "top-secret",
            workerName = "jakub-mbp",
        )
    saveConfig(config, dir = tempDir)

    deleteConfig(dir = tempDir)

    assertNull(loadConfig(dir = tempDir))
  }
}
