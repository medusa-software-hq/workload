package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentSelectionTest {
  @Test
  fun `an absent WORKLOAD_ENVIRONMENT selects prod`() {
    assertEquals(Environment.Prod, Environment.current(raw = null))
    assertEquals(Environment.Prod, Environment.current(raw = "  "))
  }

  @Test
  fun `prod and production and case variants all select prod`() {
    assertEquals(Environment.Prod, Environment.current(raw = "prod"))
    assertEquals(Environment.Prod, Environment.current(raw = "production"))
    assertEquals(Environment.Prod, Environment.current(raw = "PROD"))
  }

  @Test
  fun `staging selects staging`() {
    assertEquals(Environment.Staging, Environment.current(raw = "staging"))
    assertEquals(Environment.Staging, Environment.current(raw = "STAGING"))
  }

  @Test
  fun `an unknown value fails cleanly`() {
    val e = assertFailsWith<EnvironmentSelectionException> { Environment.current(raw = "prd") }
    assertTrue(e.message!!.contains("prod"))
    assertTrue(e.message!!.contains("staging"))
  }

  @Test
  fun `local requires the config path`() {
    val e =
        assertFailsWith<EnvironmentSelectionException> {
          Environment.current(raw = "local", localConfigPath = null, localPort = "8081")
        }
    assertTrue(e.message!!.contains(Environment.LOCAL_CONFIG_PATH_ENV))
  }

  @Test
  fun `local requires the port`() {
    val e =
        assertFailsWith<EnvironmentSelectionException> {
          Environment.current(raw = "local", localConfigPath = "/tmp/x", localPort = null)
        }
    assertTrue(e.message!!.contains(Environment.LOCAL_PORT_ENV))
  }

  @Test
  fun `local rejects a non-numeric or out-of-range port`() {
    assertFailsWith<EnvironmentSelectionException> {
      Environment.current(raw = "local", localConfigPath = "/tmp/x", localPort = "nope")
    }
    assertFailsWith<EnvironmentSelectionException> {
      Environment.current(raw = "local", localConfigPath = "/tmp/x", localPort = "70000")
    }
  }

  @Test
  fun `local wires the given path and port`() {
    val env =
        Environment.current(raw = "local", localConfigPath = "/tmp/wl-local", localPort = "8099")
    assertTrue(env is Environment.Local)
    assertEquals(Path.of("/tmp/wl-local"), env.configDir)
    assertEquals("http://127.0.0.1:8099", env.apiBaseUrl)
    assertEquals("[local]", env.marker)
  }
}

class EnvironmentPropertiesTest {
  @Test
  fun `prod is silent and points at the prod front door`() {
    assertNull(Environment.Prod.marker)
    assertEquals("https://api.workload-baseline.medusa.software", Environment.Prod.apiBaseUrl)
    assertTrue(Environment.Prod.configDir.endsWith("ms-workload/prod"))
  }

  @Test
  fun `staging shows a marker and points at the staging front door`() {
    assertEquals("[staging]", Environment.Staging.marker)
    assertEquals(
        "https://api.workload-baseline-staging.medusa.software",
        Environment.Staging.apiBaseUrl,
    )
    assertTrue(Environment.Staging.configDir.endsWith("ms-workload/staging"))
  }

  @Test
  fun `prod and staging carry distinct OAuth client ids`() {
    // The per-project cli_client_id from infra/common's environment_config — different projects,
    // so a staging token can never satisfy the prod API's audience and vice-versa.
    assertTrue(Environment.Prod.oauthClientId.startsWith("136908422577-"))
    assertTrue(Environment.Staging.oauthClientId.startsWith("983402080078-"))
    assertTrue(Environment.Prod.oauthClientId != Environment.Staging.oauthClientId)
  }

  @Test
  fun `prod and staging state directories are siblings, never the same`() {
    assertTrue(Environment.Prod.configDir != Environment.Staging.configDir)
    assertEquals(Environment.Prod.configDir.parent, Environment.Staging.configDir.parent)
  }
}

class LegacyConfigMigrationTest {
  private val base: Path = Files.createTempDirectory("ms-workload-migrate")

  @AfterTest
  fun cleanup() {
    Files.walk(base).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  @Test
  fun `moves loose legacy files into prod on first run and logs each`() {
    Files.createDirectories(base)
    Files.writeString(base.resolve("config.json"), "{\"workerId\":\"w\"}")
    Files.writeString(base.resolve("admin.json"), "{\"refreshToken\":\"r\"}")
    val logged = mutableListOf<String>()

    migrateLegacyConfig(base = base, log = { logged.add(it) })

    val prod = base.resolve("prod")
    assertTrue(Files.exists(prod.resolve("config.json")))
    assertTrue(Files.exists(prod.resolve("admin.json")))
    // The loose originals are gone (moved, not copied).
    assertTrue(!Files.exists(base.resolve("config.json")))
    assertTrue(!Files.exists(base.resolve("admin.json")))
    assertEquals(2, logged.size)
  }

  @Test
  fun `is a no-op when there is nothing loose to migrate`() {
    Files.createDirectories(base.resolve("prod"))
    val logged = mutableListOf<String>()

    migrateLegacyConfig(base = base, log = { logged.add(it) })

    assertTrue(logged.isEmpty())
  }

  @Test
  fun `never clobbers a file already present under prod`() {
    Files.createDirectories(base.resolve("prod"))
    Files.writeString(base.resolve("config.json"), "loose")
    Files.writeString(base.resolve("prod").resolve("config.json"), "already-there")
    val logged = mutableListOf<String>()

    migrateLegacyConfig(base = base, log = { logged.add(it) })

    // The existing prod copy wins; the loose one is left untouched (not moved over it).
    assertEquals("already-there", Files.readString(base.resolve("prod").resolve("config.json")))
    assertEquals("loose", Files.readString(base.resolve("config.json")))
    assertTrue(logged.isEmpty())
  }
}
