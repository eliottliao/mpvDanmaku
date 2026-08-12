package app.marlboroadvance.mpvex.repository.dandanplay

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Golden-vector tests for the dandanplay request signature.
 *
 * The AppId/AppSecret below are fictitious test-only credentials; the expected values
 * were computed independently (PowerShell/.NET SHA-256) and are additionally
 * re-derived inside the tests with the JVM [MessageDigest] as a cross-check.
 */
class DandanplayRequestSignerTest {
  private val appId = "dandanTestAppId"
  private val appSecret = "testAppSecretValue000"
  private val fixedTimestamp = 1_700_000_000L
  private val signer = DandanplayRequestSigner(appId, appSecret)

  private fun expectedBase64Sha256(input: String): String =
    Base64.getEncoder().encodeToString(
      MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8)),
    )

  @Test
  fun `signature matches golden vector for ascii path`() {
    val path = "/api/v2/comment/12345"
    val signature = signer.signature(path, fixedTimestamp)

    // Pre-computed golden value for base64(sha256(appId + timestamp + path + appSecret)).
    assertEquals("cxx/3PVxSUXomaAjITaB4/ykA4tKs/wXEU3RPhUlPTQ=", signature)
    // Cross-check against an independent in-test computation.
    assertEquals(
      expectedBase64Sha256("${appId}${fixedTimestamp}${path}${appSecret}"),
      signature,
    )
  }

  @Test
  fun `signature handles multibyte UTF-8 paths`() {
    val path = "/api/v2/search/弹幕"
    val signature = signer.signature(path, fixedTimestamp)

    // Golden value computed externally over the UTF-8 encoded input.
    assertEquals("g4+Und8fjM9PsWj9s3gaBCq985w5s2jWHggdbSZORcY=", signature)
    assertEquals(
      expectedBase64Sha256("${appId}${fixedTimestamp}${path}${appSecret}"),
      signature,
    )
  }

  @Test
  fun `signature changes with timestamp and path`() {
    val first = signer.signature("/api/v2/comment/1", fixedTimestamp)
    val second = signer.signature("/api/v2/comment/2", fixedTimestamp)
    val third = signer.signature("/api/v2/comment/1", fixedTimestamp + 1)

    assertFalse(first == second)
    assertFalse(first == third)
  }

  @Test
  fun `signature never contains line breaks`() {
    for (path in listOf("/a", "/api/v2/comment/弹幕/123", "/x/y/z")) {
      val signature = signer.signature(path, fixedTimestamp)
      assertFalse("signature contains \\n", '\n' in signature)
      assertFalse("signature contains \\r", '\r' in signature)
    }
  }

  @Test
  fun `path without leading slash is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      signer.signature("api/v2/match", fixedTimestamp)
    }
  }

  @Test
  fun `path with query string is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      signer.signature("/api/v2/comment/1?withRelated=true", fixedTimestamp)
    }
  }

  @Test
  fun `blank credentials are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      DandanplayRequestSigner("", appSecret)
    }
    assertThrows(IllegalArgumentException::class.java) {
      DandanplayRequestSigner(appId, "   ")
    }
  }

  @Test
  fun `timestamp uses the injected clock`() {
    val clocked = DandanplayRequestSigner(appId, appSecret, epochSeconds = { 1_234_567L })
    assertEquals(1_234_567L, clocked.timestamp())
  }
}
