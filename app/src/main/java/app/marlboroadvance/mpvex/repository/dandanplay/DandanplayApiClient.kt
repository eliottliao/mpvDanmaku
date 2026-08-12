package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayAuthenticationException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayHttpException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayInvalidResponseException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayResponseTooLargeException
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Small signed JSON client shared by direct dandanplay transports. */
class DandanplayApiClient(
  private val httpClient: OkHttpClient,
  private val signer: DandanplayRequestSigner,
  private val json: Json = defaultDandanplayJson(),
  private val maximumResponseBytes: Long = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) {
  init {
    require(maximumResponseBytes > 0) { "maximumResponseBytes must be positive" }
  }

  suspend fun <T> execute(
    request: Request,
    deserializer: DeserializationStrategy<T>,
    maximumAttempts: Int = 1,
  ): T {
    require(maximumAttempts > 0) { "maximumAttempts must be positive" }
    require(request.url.isHttps) { "Dandanplay requests must use HTTPS" }

    val timestamp = signer.timestamp()
    val signedRequest = request.newBuilder()
      .header(HEADER_APP_ID, signer.appId)
      .header(HEADER_TIMESTAMP, timestamp.toString())
      .header(HEADER_SIGNATURE, signer.signature(request.url.encodedPath, timestamp))
      .build()

    var attempt = 1
    while (true) {
      try {
        return executeOnce(signedRequest, deserializer)
      } catch (error: IOException) {
        val retryable = error !is DandanplayException ||
          (error is DandanplayHttpException && error.statusCode >= 500)
        if (!retryable || attempt >= maximumAttempts) throw error
        delay(RETRY_DELAYS_MILLIS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MILLIS.lastIndex)])
        attempt += 1
      }
    }
  }

  private suspend fun <T> executeOnce(
    request: Request,
    deserializer: DeserializationStrategy<T>,
  ): T = withContext(Dispatchers.IO) {
    httpClient.newCall(request).execute().use { response ->
      if (!response.request.url.isHttps) {
        throw DandanplayInvalidResponseException("Dandanplay redirected to a non-HTTPS URL")
      }

      val payload = readBody(response)
      if (response.code == 401 || response.code == 403) {
        throw DandanplayAuthenticationException(
          statusCode = response.code,
          serverReason = response.header(HEADER_ERROR_MESSAGE) ?: serverMessage(payload),
        )
      }
      if (!response.isSuccessful) {
        throw DandanplayHttpException(response.code, serverMessage(payload))
      }

      try {
        json.decodeFromString(deserializer, payload)
      } catch (error: SerializationException) {
        throw DandanplayInvalidResponseException("Dandanplay returned invalid JSON", error)
      } catch (error: IllegalArgumentException) {
        throw DandanplayInvalidResponseException("Dandanplay returned an invalid response", error)
      }
    }
  }

  private fun readBody(response: Response): String {
    val body = response.body
    val contentLength = body.contentLength()
    if (contentLength > maximumResponseBytes) {
      throw DandanplayResponseTooLargeException(maximumResponseBytes)
    }

    val output = ByteArrayOutputStream(
      when {
        contentLength <= 0L -> DEFAULT_BUFFER_SIZE
        contentLength > Int.MAX_VALUE -> DEFAULT_BUFFER_SIZE
        else -> contentLength.toInt()
      },
    )
    body.byteStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      var total = 0L
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumResponseBytes) {
          throw DandanplayResponseTooLargeException(maximumResponseBytes)
        }
        output.write(buffer, 0, read)
      }
    }
    return output.toString(Charsets.UTF_8.name())
  }

  private fun serverMessage(payload: String): String? =
    runCatching {
      val response = json.parseToJsonElement(payload).jsonObject
      response["errorMessage"]?.jsonPrimitive?.contentOrNull
        ?: response["errorDetail"]?.jsonPrimitive?.contentOrNull
        ?: response["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

  companion object {
    const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Long = 32L * 1024L * 1024L

    private const val HEADER_APP_ID = "X-AppId"
    private const val HEADER_TIMESTAMP = "X-Timestamp"
    private const val HEADER_SIGNATURE = "X-Signature"
    private const val HEADER_ERROR_MESSAGE = "X-Error-Message"
    private val RETRY_DELAYS_MILLIS = longArrayOf(250L, 750L)
  }
}

internal fun defaultDandanplayJson(): Json =
  Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }

