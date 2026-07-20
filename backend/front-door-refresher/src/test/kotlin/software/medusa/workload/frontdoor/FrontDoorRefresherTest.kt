package software.medusa.workload.frontdoor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontDoorRefresherTest {
  @Test
  fun `worker secret body carries the name, token, and secret_text type`() {
    val body = workerSecretBody("INVOKER_ID_TOKEN", "eyJhbGciOi.payload.sig")
    assertTrue(body.contains("\"name\":\"INVOKER_ID_TOKEN\""))
    assertTrue(body.contains("\"text\":\"eyJhbGciOi.payload.sig\""))
    assertTrue(body.contains("\"type\":\"secret_text\""))
  }

  @Test
  fun `a token with JSON-special characters is escaped`() {
    val body = workerSecretBody("X", "a\"b\\c")
    assertTrue(body.contains("a\\\"b\\\\c"))
  }

  @Test
  fun `isSuccess reads the Cloudflare success field`() {
    assertTrue(isSuccess("""{"result":{"name":"INVOKER_ID_TOKEN"},"success":true,"errors":[]}"""))
    assertFalse(isSuccess("""{"success":false,"errors":[{"code":10000}]}"""))
    assertFalse(isSuccess("not json"))
    assertFalse(isSuccess("{}"))
  }

  @Test
  fun `config falls back to the default worker secret name`() {
    assertEquals("INVOKER_ID_TOKEN", System.getenv("WORKER_SECRET_NAME") ?: "INVOKER_ID_TOKEN")
  }
}
