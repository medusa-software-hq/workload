package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DockerConnectorConfigTest {

  @Test
  fun `defaults to the standard socket when DOCKER_HOST is unset`() {
    val config = DockerConnectorConfig.fromEnvironment { null }
    assertEquals("/var/run/docker.sock", config.socketPath)
  }

  @Test
  fun `blank DOCKER_HOST is treated as unset`() {
    val config = DockerConnectorConfig.fromEnvironment { if (it == "DOCKER_HOST") "" else null }
    assertEquals("/var/run/docker.sock", config.socketPath)
  }

  @Test
  fun `a unix DOCKER_HOST is honored, scheme stripped`() {
    val config = DockerConnectorConfig.fromEnvironment {
      if (it == "DOCKER_HOST") "unix:///tmp/custom.sock" else null
    }
    assertEquals("/tmp/custom.sock", config.socketPath)
  }

  @Test
  fun `a non-unix DOCKER_HOST is a clear, actionable error, not a silent fallback`() {
    val e =
        assertFailsWith<DockerConnectionException> {
          DockerConnectorConfig.fromEnvironment {
            if (it == "DOCKER_HOST") "tcp://127.0.0.1:2375" else null
          }
        }
    assertTrue(e.message!!.contains("only unix:// sockets are supported"))
  }

  @Test
  fun `blank explicit socket path is rejected`() {
    assertFailsWith<IllegalArgumentException> { DockerConnectorConfig("  ") }
  }
}
