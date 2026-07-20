package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Discovery itself is covered by [SocketDiscoveryTest]; this is just the config value type. */
class DockerConnectorConfigTest {

  @Test
  fun `defaults to the Ubuntu-first socket`() {
    assertEquals("/var/run/docker.sock", DockerConnectorConfig().socketPath)
  }

  @Test
  fun `an explicitly passed path is used as-is`() {
    assertEquals("/tmp/custom.sock", DockerConnectorConfig("/tmp/custom.sock").socketPath)
  }

  @Test
  fun `blank explicit socket path is rejected`() {
    assertFailsWith<IllegalArgumentException> { DockerConnectorConfig("  ") }
  }
}
