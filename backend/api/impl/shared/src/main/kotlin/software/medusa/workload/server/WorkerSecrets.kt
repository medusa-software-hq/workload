package software.medusa.workload.server

import java.security.MessageDigest

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
