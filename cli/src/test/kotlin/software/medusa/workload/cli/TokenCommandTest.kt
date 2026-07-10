package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class TokenCommandTest {

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
}
