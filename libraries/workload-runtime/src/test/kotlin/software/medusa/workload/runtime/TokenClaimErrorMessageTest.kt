package software.medusa.workload.runtime

import kotlin.test.Test
import kotlin.test.assertTrue

class TokenClaimErrorMessageTest {

  @Test
  fun `unauthorized maps to an approval-status message`() {
    val message = tokenClaimErrorMessage(WorkerApiException(401, "unauthorized"), "my-profile")
    assertTrue(message.contains("not approved"))
  }

  @Test
  fun `profile_not_found names the profile and suggests asking an admin`() {
    val message = tokenClaimErrorMessage(WorkerApiException(403, "profile_not_found"), "my-profile")
    assertTrue(message.contains("my-profile"))
    assertTrue(message.contains("admin"))
  }

  @Test
  fun `profile_archived names the profile`() {
    val message = tokenClaimErrorMessage(WorkerApiException(403, "profile_archived"), "my-profile")
    assertTrue(message.contains("my-profile"))
    assertTrue(message.contains("archived"))
  }

  @Test
  fun `not_granted suggests asking an admin to grant it`() {
    val message = tokenClaimErrorMessage(WorkerApiException(403, "not_granted"), "my-profile")
    assertTrue(message.contains("my-profile"))
    assertTrue(message.contains("admin"))
  }

  @Test
  fun `failed_to_mint_token points at an IAM misconfiguration`() {
    val message =
        tokenClaimErrorMessage(WorkerApiException(502, "failed_to_mint_token"), "my-profile")
    assertTrue(message.contains("IAM"))
  }

  @Test
  fun `unknown error codes fall back to a generic message that still names the code`() {
    val message = tokenClaimErrorMessage(WorkerApiException(500, "something_else"), "my-profile")
    assertTrue(message.contains("something_else"))
  }

  @Test
  fun `a 404 explains it can be a wrong URL or a rejected credential`() {
    // The v2 plane returns a bare 404 (empty body -> unknown_error) for any rejected credential.
    val message = tokenClaimErrorMessage(WorkerApiException(404, "unknown_error"), "my-profile")
    assertTrue(message.contains("404"))
    assertTrue(message.contains("status") || message.contains("register"))
  }
}
