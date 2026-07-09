package software.medusa.workload.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkerSecretsTest {

  @Test
  fun `generateWorkerSecret produces 32 bytes of entropy, url-safe encoded`() {
    val secret = generateWorkerSecret()
    val decoded = java.util.Base64.getUrlDecoder().decode(secret)
    assertEquals(32, decoded.size)
  }

  @Test
  fun `generateWorkerSecret does not repeat`() {
    assertNotEquals(generateWorkerSecret(), generateWorkerSecret())
  }

  @Test
  fun `hashWorkerSecret is deterministic and never equals the plaintext`() {
    val secret = generateWorkerSecret()
    val hash1 = hashWorkerSecret(secret)
    val hash2 = hashWorkerSecret(secret)
    assertTrue(hash1 == hash2)
    assertFalse(hash1.bytes.contentEquals(secret.toByteArray()))
  }

  @Test
  fun `verifyWorkerSecret accepts the matching secret and rejects everything else`() {
    val secret = generateWorkerSecret()
    val hash = hashWorkerSecret(secret)
    assertTrue(verifyWorkerSecret(secret, hash))
    assertFalse(verifyWorkerSecret(generateWorkerSecret(), hash))
    assertFalse(verifyWorkerSecret("", hash))
  }
}
