package software.medusa.workload.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val secretByteLength = 32

private val secureRandom = SecureRandom()
private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

/** A fresh, high-entropy worker secret — the only time the plaintext value ever exists. */
fun generateWorkerSecret(): String {
  val bytes = ByteArray(secretByteLength)
  secureRandom.nextBytes(bytes)
  return urlEncoder.encodeToString(bytes)
}

/** Hashes [secret] for storage. Never store the plaintext — only this. */
fun hashWorkerSecret(secret: String): SecretHash =
    SecretHash(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)))

/** Verifies a claimed [secret] against a stored [expected] hash, in constant time. */
fun verifyWorkerSecret(secret: String, expected: SecretHash): Boolean =
    constantTimeEquals(hashWorkerSecret(secret).bytes, expected.bytes)

/**
 * Hashes an enrollment token for storage — same SHA-256-of-UTF-8 as [hashWorkerSecret], the only
 * form of the token ever persisted. The plaintext `wle_` token is shown once at creation and
 * dropped.
 */
fun hashEnrollmentToken(token: String): SecretHash =
    SecretHash(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

/**
 * A 4-digit, zero-padded code shown to the human approving a pending worker — never a credential.
 */
fun generateConfirmationCode(): String = "%04d".format(secureRandom.nextInt(10_000))
