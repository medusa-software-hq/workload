package software.medusa.workload.tokenformat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkloadTokenTest {
  @Test
  fun `generated token round-trips through validation`() {
    for (kind in TokenKind.entries) {
      val token = WorkloadToken.generate(kind)
      assertTrue(token.startsWith(kind.prefix), "expected $token to start with ${kind.prefix}")
      assertTrue(WorkloadToken.isValid(token, kind), "expected $token to validate as $kind")
      assertEquals(kind, WorkloadToken.kindOf(token))
    }
  }

  @Test
  fun `every generated token has the same fixed length`() {
    // prefix + 43-char base62 body + 6-char base62 checksum.
    val lengths = (1..200).map { WorkloadToken.generate(TokenKind.ENROLLMENT).length }.toSet()
    assertEquals(setOf("wle_".length + 43 + 6), lengths)
  }

  @Test
  fun `tokens are unique across many mints`() {
    val minted = (1..1_000).map { WorkloadToken.generate(TokenKind.WORKER) }.toSet()
    assertEquals(1_000, minted.size)
  }

  @Test
  fun `single-character corruption in the body fails the checksum`() {
    val token = WorkloadToken.generate(TokenKind.ENROLLMENT)
    // Flip a character in the body region (right after the prefix) to a different base62 char.
    val at = TokenKind.ENROLLMENT.prefix.length + 3
    val flipped = if (token[at] == 'a') 'b' else 'a'
    val corrupted = token.substring(0, at) + flipped + token.substring(at + 1)
    assertNotEquals(token, corrupted)
    assertFalse(WorkloadToken.isValid(corrupted, TokenKind.ENROLLMENT))
    assertNull(WorkloadToken.kindOf(corrupted))
  }

  @Test
  fun `corruption in the checksum itself fails validation`() {
    val token = WorkloadToken.generate(TokenKind.WORKER)
    val last = token.length - 1
    val flipped = if (token[last] == 'a') 'b' else 'a'
    val corrupted = token.substring(0, last) + flipped
    assertFalse(WorkloadToken.isValid(corrupted, TokenKind.WORKER))
  }

  @Test
  fun `wrong prefix does not validate even with a valid body and checksum`() {
    val enrollment = WorkloadToken.generate(TokenKind.ENROLLMENT)
    assertFalse(WorkloadToken.isValid(enrollment, TokenKind.WORKER))
    // Swapping only the prefix leaves the body+checksum intact but the kind wrong.
    val reprefixed = TokenKind.WORKER.prefix + enrollment.removePrefix(TokenKind.ENROLLMENT.prefix)
    assertTrue(WorkloadToken.isValid(reprefixed, TokenKind.WORKER))
    assertFalse(WorkloadToken.isValid(reprefixed, TokenKind.ENROLLMENT))
  }

  @Test
  fun `malformed inputs are rejected without throwing`() {
    for (bad in listOf("", "wle_", "wle_short", "nope", "wlw_" + "!".repeat(49))) {
      assertFalse(WorkloadToken.isValid(bad, TokenKind.ENROLLMENT))
      assertFalse(WorkloadToken.isValid(bad, TokenKind.WORKER))
      assertNull(WorkloadToken.kindOf(bad))
    }
  }

  @Test
  fun `wrong length is rejected`() {
    val token = WorkloadToken.generate(TokenKind.ENROLLMENT)
    assertFalse(WorkloadToken.isValid(token + "x", TokenKind.ENROLLMENT))
    assertFalse(WorkloadToken.isValid(token.dropLast(1), TokenKind.ENROLLMENT))
  }
}
