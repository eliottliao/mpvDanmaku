package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayAuthenticationException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayHttpException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end HTTPS tests for [DirectSignedTransport] against a local [MockWebServer].
 *
 * The transport insists on HTTPS, so the mock server runs with a self-signed
 * [HeldCertificate] for `localhost` and the client trusts exactly that certificate.
 */
class DirectSignedTransportTest {
  private val appId = "dandanTestAppId"
  private val appSecret = "testAppSecretValue000"

  private lateinit var certificate: HeldCertificate
  private lateinit var server: MockWebServer
  private lateinit var transport: DirectSignedTransport
  private lateinit var signer: DandanplayRequestSigner

  @Before
  fun setUp() {
    certificate = HeldCertificate.Builder()
      .commonName("localhost")
      .addSubjectAlternativeName("localhost")
      .build()
    val serverCertificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .build()
    server = MockWebServer()
    server.useHttps(serverCertificates.sslSocketFactory())
    server.start()

    val clientCertificates = HandshakeCertificates.Builder()
      .addTrustedCertificate(certificate.certificate)
      .build()
    val client = OkHttpClient.Builder()
      .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
      .build()

    transport = DirectSignedTransport(
      httpClient = client,
      appId = appId,
      appSecret = appSecret,
      baseUrl = server.url("/"),
    )
    signer = DandanplayRequestSigner(appId, appSecret)
  }

  @After
  fun tearDown() {
    runCatching { server.close() }
  }

  private fun json(code: Int, body: String): MockResponse =
    MockResponse.Builder()
      .code(code)
      .addHeader("Content-Type", "application/json; charset=utf-8")
      .body(body)
      .build()

  private val commentQuery = DandanplayCommentQuery(episodeId = 99L)
  private val commentsJson =
    """{"count":1,"comments":[{"cid":101,"p":"5.0,1,16777215,1","m":"hi"}]}"""

  @Test
  fun `match success returns candidates and signs the request`() = runBlocking {
    server.enqueue(
      json(
        200,
        """
        {
          "errorCode": 0,
          "success": true,
          "isMatched": true,
          "matches": [{
            "episodeId": 42, "animeId": 7, "animeTitle": "Test Anime",
            "episodeTitle": "EP01", "type": "tv", "typeDescription": "TV",
            "shift": 1.5
          }]
        }
        """.trimIndent(),
      ),
    )

    val result = transport.match(DandanplayMatchRequestDto(fileName = "Show.S01E01.mkv"))

    assertTrue(result.isMatched)
    val match = result.matches!!.single()
    assertEquals(42L, match.episodeId)
    assertEquals("Test Anime", match.animeTitle)
    assertEquals(1.5, match.shift, 1e-9)

    val recorded = server.takeRequest()
    assertEquals("POST", recorded.method)
    assertEquals("/api/v2/match", recorded.url.encodedPath)
    assertEquals(appId, recorded.headers["X-AppId"])
    val timestamp = recorded.headers["X-Timestamp"]!!.toLong()
    assertEquals(
      "signature must be base64(sha256(appId+timestamp+path+secret))",
      signer.signature("/api/v2/match", timestamp),
      recorded.headers["X-Signature"],
    )
    assertTrue(recorded.body!!.utf8().contains("Show.S01E01.mkv"))
  }

  @Test
  fun `business failure is decoded without throwing at transport level`() = runBlocking {
    server.enqueue(json(200, """{"errorCode":2,"success":false,"errorMessage":"nope"}"""))

    val response = transport.match(DandanplayMatchRequestDto(fileName = "Show.mkv"))

    assertFalse(response.success)
    assertEquals(2, response.errorCode)
    assertEquals("nope", response.errorMessage)
  }

  @Test
  fun `302 redirect is followed to the accelerated address`() = runBlocking {
    val accelerated = server.url("/api/v2/comment/77").toString()
    server.enqueue(
      MockResponse.Builder()
        .code(302)
        .addHeader("Location", accelerated)
        .body("{}")
        .build(),
    )
    server.enqueue(json(200, commentsJson))

    val result = transport.getComments(commentQuery)

    assertEquals(1, result.comments!!.size)
    assertEquals(2, server.requestCount)
    assertEquals("/api/v2/comment/99", server.takeRequest().url.encodedPath)
    assertEquals("/api/v2/comment/77", server.takeRequest().url.encodedPath)
  }

  @Test
  fun `403 exposes X-Error-Message and is not retried`() {
    server.enqueue(
      MockResponse.Builder()
        .code(403)
        .addHeader("X-Error-Message", "invalid-signature")
        .body("{}")
        .build(),
    )

    val error = assertThrows(DandanplayAuthenticationException::class.java) {
      runBlocking { transport.getComments(commentQuery) }
    }

    assertEquals(403, error.statusCode)
    assertEquals("invalid-signature", error.serverReason)
    assertEquals("401/403 must not be retried", 1, server.requestCount)
  }

  @Test
  fun `401 is not retried`() {
    server.enqueue(json(401, """{"errorMessage":"expired"}"""))

    val error = assertThrows(DandanplayAuthenticationException::class.java) {
      runBlocking { transport.getComments(commentQuery) }
    }

    assertEquals(401, error.statusCode)
    assertEquals("expired", error.serverReason)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `GET retries server errors until success with at most three attempts`() = runBlocking {
    server.enqueue(json(500, """{"errorMessage":"boom"}"""))
    server.enqueue(json(503, """{"errorMessage":"unavailable"}"""))
    server.enqueue(json(200, commentsJson))

    val result = transport.getComments(commentQuery)

    assertEquals(1, result.comments!!.size)
    assertEquals(3, server.requestCount)
  }

  @Test
  fun `GET gives up after three server errors`() {
    repeat(3) { server.enqueue(json(500, """{"errorMessage":"boom"}""")) }

    val error = assertThrows(DandanplayHttpException::class.java) {
      runBlocking { transport.getComments(commentQuery) }
    }

    assertEquals(500, error.statusCode)
    assertEquals("boom", error.serverMessage)
    assertEquals(3, server.requestCount)
  }

  @Test
  fun `POST requests are not retried`() {
    server.enqueue(json(500, """{"errorMessage":"boom"}"""))

    assertThrows(DandanplayHttpException::class.java) {
      runBlocking { transport.match(DandanplayMatchRequestDto(fileName = "Show.mkv")) }
    }

    assertEquals("match uses a single attempt", 1, server.requestCount)
  }

  @Test
  fun `connection failures surface as IOException`() {
    server.close()

    assertThrows(IOException::class.java) {
      runBlocking { transport.getComments(commentQuery) }
    }
  }
}
