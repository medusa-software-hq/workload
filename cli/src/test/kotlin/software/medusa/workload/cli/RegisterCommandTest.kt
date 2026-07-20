package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class RegisterCommandTest {
  @Test
  fun `a 404 explains it can be a wrong URL or a bad token`() {
    val message = registerErrorMessage(WorkerApiException(404, "unknown_error"))
    assertTrue(message.contains("broker-url"))
    assertTrue(message.contains("token"))
  }

  @Test
  fun `a non-404 surfaces the underlying error code`() {
    val message = registerErrorMessage(WorkerApiException(429, "rate_limited"))
    assertTrue(message.contains("rate_limited"))
  }
}
