package software.medusa.workload.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudflaredAccessSidecarTest {

  @Test
  fun `cloudflaredAccessSidecarEnv carries hostname and port, and defaults the port`() {
    val env = cloudflaredAccessSidecarEnv(hostname = "private.example.test")
    assertEquals(
        setOf(
            "CFA_SIDECAR_HOSTNAME=private.example.test",
            "CFA_SIDECAR_PORT=$defaultCloudflaredAccessPort",
        ),
        env.toSet(),
    )
  }

  @Test
  fun `cloudflaredAccessSidecarEnv honors an explicit port`() {
    val env = cloudflaredAccessSidecarEnv(hostname = "private.example.test", port = 9090)
    assertTrue("CFA_SIDECAR_PORT=9090" in env)
  }

  @Test
  fun `cloudflaredAccessSidecarEnv omits the service token pair when unset`() {
    val env = cloudflaredAccessSidecarEnv(hostname = "private.example.test")
    assertTrue(env.none { it.startsWith("CFA_SIDECAR_SERVICE_TOKEN") })
  }

  @Test
  fun `cloudflaredAccessSidecarEnv carries a service token pair when given`() {
    val env =
        cloudflaredAccessSidecarEnv(
            hostname = "private.example.test",
            serviceTokenId = "token-id",
            serviceTokenSecret = "token-secret",
        )
    assertTrue("CFA_SIDECAR_SERVICE_TOKEN_ID=token-id" in env)
    assertTrue("CFA_SIDECAR_SERVICE_TOKEN_SECRET=token-secret" in env)
  }

  @Test
  fun `privateServicePointerEnv carries the hostname and the sidecar address`() {
    val pointers = privateServicePointerEnv("private.example.test", "172.18.0.2:8080")
    assertEquals(
        mapOf(
            "WORKLOAD_PRIVATE_SERVICE_HOST" to "private.example.test",
            "WORKLOAD_PRIVATE_SERVICE_ADDR" to "172.18.0.2:8080",
        ),
        pointers,
    )
  }
}
