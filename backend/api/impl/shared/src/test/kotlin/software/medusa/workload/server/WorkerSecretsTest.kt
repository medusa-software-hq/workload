package software.medusa.workload.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

class WorkerSecretsTest {

  @Test
  fun `hashWorkerSecret is deterministic and never equals the plaintext`() {
    val secret = WorkloadToken.generate(TokenKind.WORKER)
    val hash1 = hashWorkerSecret(secret)
    val hash2 = hashWorkerSecret(secret)
    assertTrue(hash1 == hash2)
    assertFalse(hash1.bytes.contentEquals(secret.toByteArray()))
  }

  @Test
  fun `verifyWorkerSecret accepts the matching secret and rejects everything else`() {
    val secret = WorkloadToken.generate(TokenKind.WORKER)
    val hash = hashWorkerSecret(secret)
    assertTrue(verifyWorkerSecret(secret, hash))
    assertFalse(verifyWorkerSecret(WorkloadToken.generate(TokenKind.WORKER), hash))
    assertFalse(verifyWorkerSecret("", hash))
  }
}
