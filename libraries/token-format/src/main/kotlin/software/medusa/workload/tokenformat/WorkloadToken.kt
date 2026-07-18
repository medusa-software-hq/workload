package software.medusa.workload.tokenformat

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * The workload secret formats, GitHub-token style: a fixed prefix, a base62-encoded random body,
 * and a base62 CRC32 checksum of that body. Both kinds share the exact same shape and differ only
 * by prefix.
 *
 * The two payoffs (see M4 design 00-enrollment-tokens.md § Token format):
 * 1. **Offline parse-and-drop.** [WorkloadToken.isValid] verifies prefix, length, and checksum with
 *    zero I/O and no secret material, so the worker plane can reject malformed credentials before
 *    it ever touches the database. The checksum is *not* a secret — it guards against typos and
 *    truncation, not forgery (a real credential is still verified by hashing and comparing
 *    server-side).
 * 2. **Secret scanning.** A distinctive, greppable prefix makes a token pasted into a repo, log, or
 *    chat export findable.
 */
enum class TokenKind(val prefix: String) {
  /** One-time enrollment token an admin mints and hands to a teammate to register a worker. */
  ENROLLMENT("wle_"),
  /** Long-lived worker secret, the credential half of a worker's `<workerId>.<secret>` bearer. */
  WORKER("wlw_"),
}

/**
 * Generates and validates [TokenKind] secrets. Pure JDK, no I/O, no external dependencies — safe to
 * share between the backend and the CLI.
 */
object WorkloadToken {
  /** Base62 alphabet, digits-then-upper-then-lower so ordinal 0 is `'0'` (the pad character). */
  private const val BASE62_ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
  private val BASE62_INDEX: Set<Char> = BASE62_ALPHABET.toSet()
  private val BASE62_BASE = BigInteger.valueOf(BASE62_ALPHABET.length.toLong())

  /** 32 random bytes — 256 bits of entropy, matching the existing worker-secret strength. */
  private const val RANDOM_BYTES = 32

  /** base62 chars needed to represent 32 bytes: ceil(256 / log2(62)) = 43. */
  private const val BODY_LENGTH = 43

  /** base62 chars needed to represent a 32-bit CRC32: ceil(32 / log2(62)) = 6. */
  private const val CHECKSUM_LENGTH = 6

  private val secureRandom = SecureRandom()

  /** Mints a fresh token of [kind]. The returned string is the plaintext credential in full. */
  fun generate(kind: TokenKind): String {
    val randomBytes = ByteArray(RANDOM_BYTES).also { secureRandom.nextBytes(it) }
    val body = base62Encode(BigInteger(1, randomBytes), BODY_LENGTH)
    return kind.prefix + body + checksumOf(body)
  }

  /**
   * True iff [token] is a well-formed [kind] secret: correct prefix, exact length, all-base62 body
   * and checksum, and a checksum that matches the body. Does no I/O — this is the parse-and-drop
   * filter, not an authentication check.
   */
  fun isValid(token: String, kind: TokenKind): Boolean {
    if (!token.startsWith(kind.prefix)) return false
    val rest = token.substring(kind.prefix.length)
    if (rest.length != BODY_LENGTH + CHECKSUM_LENGTH) return false
    val body = rest.substring(0, BODY_LENGTH)
    val checksum = rest.substring(BODY_LENGTH)
    if (!body.all { it in BASE62_INDEX } || !checksum.all { it in BASE62_INDEX }) return false
    return checksumOf(body) == checksum
  }

  /** The [TokenKind] [token] is a valid instance of, or null if it is well-formed as neither. */
  fun kindOf(token: String): TokenKind? = TokenKind.entries.firstOrNull { isValid(token, it) }

  private fun checksumOf(body: String): String {
    val crc = CRC32().apply { update(body.toByteArray(StandardCharsets.US_ASCII)) }
    return base62Encode(BigInteger.valueOf(crc.value), CHECKSUM_LENGTH)
  }

  /** Left-pads to [width] with the zero digit; [value] must fit (it always does for our sizes). */
  private fun base62Encode(value: BigInteger, width: Int): String {
    val chars = CharArray(width) { BASE62_ALPHABET[0] }
    var remaining = value
    var index = width - 1
    while (remaining > BigInteger.ZERO) {
      val (quotient, remainder) = remaining.divideAndRemainder(BASE62_BASE)
      chars[index] = BASE62_ALPHABET[remainder.toInt()]
      remaining = quotient
      index--
    }
    return String(chars)
  }
}
