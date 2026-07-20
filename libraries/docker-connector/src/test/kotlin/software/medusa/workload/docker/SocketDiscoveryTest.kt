package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The discovery matrix, driven through a fake host so every platform (stock Ubuntu, rootless,
 * Docker Desktop, Colima, a tcp context) is exercised on any machine — no real sockets required.
 *
 * Discovery never shells out to `docker`; parsing the context files ourselves is what lets the
 * product work with no CLI on PATH (Drydock §B1).
 */
class SocketDiscoveryTest {

  /** A synthetic machine: the env, home, existing paths, and file contents discovery may see. */
  private class FakeHost(
      private val env: Map<String, String> = emptyMap(),
      private val home: String = "/home/jakub",
      private val existing: Set<String> = emptySet(),
      private val files: Map<String, String> = emptyMap(),
  ) : DiscoveryHost {
    override fun env(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

    override fun homeDir(): String = home

    override fun exists(path: String): Boolean = path in existing

    override fun readFile(path: String): String? = files[path]

    override fun listDirectories(path: String): List<String> = emptyList()
  }

  private fun contextMeta(host: String) =
      """{"Name":"whatever","Endpoints":{"docker":{"Host":"$host"}}}"""

  // --- DOCKER_HOST wins ---

  @Test
  fun `DOCKER_HOST wins over everything, scheme stripped`() {
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                env = mapOf("DOCKER_HOST" to "unix:///tmp/custom.sock"),
                existing = setOf("/var/run/docker.sock"),
            )
        )
    assertEquals("/tmp/custom.sock", discovered.path)
    assertEquals(SocketSource.DOCKER_HOST, discovered.source)
  }

  @Test
  fun `a blank DOCKER_HOST is treated as unset`() {
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(env = mapOf("DOCKER_HOST" to ""), existing = setOf("/var/run/docker.sock"))
        )
    assertEquals("/var/run/docker.sock", discovered.path)
  }

  @Test
  fun `a non-unix DOCKER_HOST is a clear, actionable error, not a silent fallback`() {
    val e =
        assertFailsWith<DockerConnectionException> {
          DockerSocketDiscovery.discover(
              FakeHost(env = mapOf("DOCKER_HOST" to "tcp://127.0.0.1:2375"))
          )
        }
    assertTrue(e.message!!.contains("only unix:// sockets are supported"), e.message!!)
  }

  // --- docker context ---

  @Test
  fun `the active context from config json is resolved through its meta file`() {
    // This is the Docker Desktop / Colima shape: no DOCKER_HOST, just a context.
    val home = "/Users/jakub"
    val id = DockerSocketDiscovery.contextDirectoryId("desktop-linux")
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                home = home,
                files =
                    mapOf(
                        "$home/.docker/config.json" to """{"currentContext":"desktop-linux"}""",
                        "$home/.docker/contexts/meta/$id/meta.json" to
                            contextMeta("unix://$home/.docker/run/docker.sock"),
                    ),
                // The stock path exists too — the context must still win.
                existing = setOf("/var/run/docker.sock"),
            )
        )
    assertEquals("$home/.docker/run/docker.sock", discovered.path)
    assertEquals(SocketSource.DOCKER_CONTEXT, discovered.source)
  }

  @Test
  fun `DOCKER_CONTEXT overrides currentContext`() {
    val home = "/Users/jakub"
    val id = DockerSocketDiscovery.contextDirectoryId("colima")
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                env = mapOf("DOCKER_CONTEXT" to "colima"),
                home = home,
                files =
                    mapOf(
                        "$home/.docker/config.json" to """{"currentContext":"desktop-linux"}""",
                        "$home/.docker/contexts/meta/$id/meta.json" to
                            contextMeta("unix://$home/.colima/default/docker.sock"),
                    ),
            )
        )
    assertEquals("$home/.colima/default/docker.sock", discovered.path)
  }

  @Test
  fun `the built-in default context falls through to well-known paths`() {
    // "default" has no meta.json — it means "the normal socket", not "no daemon".
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                files =
                    mapOf("/home/jakub/.docker/config.json" to """{"currentContext":"default"}"""),
                existing = setOf("/var/run/docker.sock"),
            )
        )
    assertEquals("/var/run/docker.sock", discovered.path)
    assertEquals(SocketSource.WELL_KNOWN_PATH, discovered.source)
  }

  @Test
  fun `a tcp context is a clear error rather than silently using another daemon`() {
    // Running the workload against the wrong daemon would be worse than refusing.
    val home = "/home/jakub"
    val id = DockerSocketDiscovery.contextDirectoryId("remote")
    val e =
        assertFailsWith<DockerConnectionException> {
          DockerSocketDiscovery.discover(
              FakeHost(
                  home = home,
                  files =
                      mapOf(
                          "$home/.docker/config.json" to """{"currentContext":"remote"}""",
                          "$home/.docker/contexts/meta/$id/meta.json" to
                              contextMeta("tcp://10.0.0.5:2375"),
                      ),
                  existing = setOf("/var/run/docker.sock"),
              )
          )
        }
    assertTrue(e.message!!.contains("'remote'"), e.message!!)
    assertTrue(e.message!!.contains("only unix:// sockets"), e.message!!)
  }

  @Test
  fun `a context whose meta file is missing falls through instead of failing`() {
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                files =
                    mapOf("/home/jakub/.docker/config.json" to """{"currentContext":"stale"}"""),
                existing = setOf("/var/run/docker.sock"),
            )
        )
    assertEquals("/var/run/docker.sock", discovered.path)
  }

  @Test
  fun `a malformed config json falls through instead of failing`() {
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                files = mapOf("/home/jakub/.docker/config.json" to "{ not json at all"),
                existing = setOf("/var/run/docker.sock"),
            )
        )
    assertEquals("/var/run/docker.sock", discovered.path)
  }

  @Test
  fun `context directory id is the sha256 of the context name`() {
    // Docker's own naming scheme — get this wrong and we silently never find any context. These
    // are the real directory names: `fe9c6bd7...` is what Docker Desktop actually creates under
    // ~/.docker/contexts/meta (verified against a real install).
    assertEquals(
        "fe9c6bd7a66301f49ca9b6a70b217107cd1284598bfc254700c989b916da791e",
        DockerSocketDiscovery.contextDirectoryId("desktop-linux"),
    )
    assertEquals(
        "f24fd3749c1368328e2b149bec149cb6795619f244c5b584e844961215dadd16",
        DockerSocketDiscovery.contextDirectoryId("colima"),
    )
  }

  // --- well-known paths ---

  @Test
  fun `stock Ubuntu is found first`() {
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                env = mapOf("XDG_RUNTIME_DIR" to "/run/user/1000"),
                existing = setOf("/var/run/docker.sock", "/run/user/1000/docker.sock"),
            )
        )
    // Ubuntu-first: when both exist, the stock daemon wins.
    assertEquals("/var/run/docker.sock", discovered.path)
  }

  @Test
  fun `rootless Ubuntu is found via XDG_RUNTIME_DIR`() {
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(
                env = mapOf("XDG_RUNTIME_DIR" to "/run/user/1000"),
                existing = setOf("/run/user/1000/docker.sock"),
            )
        )
    assertEquals("/run/user/1000/docker.sock", discovered.path)
    assertEquals(SocketSource.WELL_KNOWN_PATH, discovered.source)
  }

  @Test
  fun `Docker Desktop's socket under home is found`() {
    val home = "/Users/jakub"
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(home = home, existing = setOf("$home/.docker/run/docker.sock"))
        )
    assertEquals("$home/.docker/run/docker.sock", discovered.path)
  }

  @Test
  fun `Colima's socket is found`() {
    val home = "/Users/jakub"
    val discovered =
        DockerSocketDiscovery.discover(
            FakeHost(home = home, existing = setOf("$home/.colima/default/docker.sock"))
        )
    assertEquals("$home/.colima/default/docker.sock", discovered.path)
  }

  @Test
  fun `with nothing at all we fall back to the Ubuntu default so the error names a real path`() {
    val discovered = DockerSocketDiscovery.discover(FakeHost())
    assertEquals("/var/run/docker.sock", discovered.path)
    assertEquals(SocketSource.DEFAULT, discovered.source)
  }

  @Test
  fun `config fromEnvironment carries the discovered path and source`() {
    val config =
        DockerConnectorConfig.fromEnvironment(
            FakeHost(env = mapOf("DOCKER_HOST" to "unix:///tmp/x.sock"))
        )
    assertEquals("/tmp/x.sock", config.socketPath)
    assertEquals(SocketSource.DOCKER_HOST, config.source)
  }
}
