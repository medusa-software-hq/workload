package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class RegisterCommandTest {
  @Test
  fun `a 404 points at the enrollment token`() {
    val message = registerErrorMessage(WorkerApiException(404, "unknown_error"))
    assertTrue(message.contains("token"))
  }

  @Test
  fun `a non-404 surfaces the underlying error code`() {
    val message = registerErrorMessage(WorkerApiException(429, "rate_limited"))
    assertTrue(message.contains("rate_limited"))
  }
}
