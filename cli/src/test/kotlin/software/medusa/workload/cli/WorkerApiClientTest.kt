package software.medusa.workload.cli

import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkerApiClientTest {
  @Test
  fun `BrokerUnreachableException message names the host and warns the URL should have no path`() {
    val message =
        BrokerUnreachableException(
                "https://api.example.com",
                UnknownHostException("api.example.com"),
            )
            .message
    assertTrue(message!!.contains("https://api.example.com"))
    assertTrue(message.contains("/worker/"))
    assertTrue(message.contains("base"))
  }

  @Test
  fun `registering against an unresolvable host throws BrokerUnreachableException, not a raw IOException`() {
    // `.invalid` is a reserved TLD that never resolves, so this fails fast at DNS.
    val e =
        assertFailsWith<BrokerUnreachableException> {
          registerWorker("https://broker.invalid", "worker-1", "wle_token")
        }
    assertTrue(e.brokerHost.contains("broker.invalid"))
  }
}
