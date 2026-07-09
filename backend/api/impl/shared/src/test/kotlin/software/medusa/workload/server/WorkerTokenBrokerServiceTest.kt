package software.medusa.workload.server

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.RequestHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkerTokenBrokerServiceTest {

  @Test
  fun `constantTimeEquals returns true for equal tokens`() {
    assertTrue(constantTimeEquals("secret-token", "secret-token"))
  }

  @Test
  fun `constantTimeEquals returns false for unequal tokens of the same length`() {
    assertFalse(constantTimeEquals("secret-token", "wrong-token!"))
  }

  @Test
  fun `constantTimeEquals returns false for tokens of different length`() {
    assertFalse(constantTimeEquals("short", "a-much-longer-token"))
  }

  @Test
  fun `extractBearerToken returns null when the header is missing`() {
    val req = HttpRequest.of(HttpMethod.POST, "/worker/v1/token")
    assertNull(extractBearerToken(req))
  }

  @Test
  fun `extractBearerToken returns null when the prefix is not Bearer`() {
    val req =
        HttpRequest.of(
            RequestHeaders.of(HttpMethod.POST, "/worker/v1/token", "Authorization", "Basic abc123")
        )
    assertNull(extractBearerToken(req))
  }

  @Test
  fun `extractBearerToken returns null when the token is empty after the prefix`() {
    val req =
        HttpRequest.of(
            RequestHeaders.of(HttpMethod.POST, "/worker/v1/token", "Authorization", "Bearer ")
        )
    assertNull(extractBearerToken(req))
  }

  @Test
  fun `extractBearerToken returns the token when well-formed`() {
    val req =
        HttpRequest.of(
            RequestHeaders.of(
                HttpMethod.POST,
                "/worker/v1/token",
                "Authorization",
                "Bearer my-bootstrap-token",
            )
        )
    assertEquals("my-bootstrap-token", extractBearerToken(req))
  }

  @Test
  fun `WorkerTokenResponse encodes with the exact field names from the API contract`() {
    val body =
        WorkerTokenResponse(
            accessToken = "ya29.example",
            expiresAt = "2026-07-07T12:34:56Z",
            serviceAccount = "worker-mvp@example.iam.gserviceaccount.com",
        )
    val encoded = Json.encodeToString(body)
    assertEquals(
        """{"accessToken":"ya29.example","expiresAt":"2026-07-07T12:34:56Z","serviceAccount":"worker-mvp@example.iam.gserviceaccount.com"}""",
        encoded,
    )
  }

  @Test
  fun `WorkerErrorResponse encodes with the exact field name from the API contract`() {
    val encoded = Json.encodeToString(WorkerErrorResponse("unauthorized"))
    assertEquals("""{"error":"unauthorized"}""", encoded)
  }
}
