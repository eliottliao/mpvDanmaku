package app.marlboroadvance.mpvex.repository.dandanplay

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Creates the application signature required by the dandanplay open API. */
class DandanplayRequestSigner(
  val appId: String,
  private val appSecret: String,
  private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
  init {
    require(appId.isNotBlank()) { "Dandanplay AppId must not be blank" }
    require(appSecret.isNotBlank()) { "Dandanplay AppSecret must not be blank" }
  }

  fun timestamp(): Long = epochSeconds()

  fun signature(
    encodedPath: String,
    timestamp: Long,
  ): String {
    require(encodedPath.startsWith('/')) { "The signed path must start with '/'" }
    require('?' !in encodedPath) { "The signed path must not contain a query string" }

    val input = "$appId$timestamp$encodedPath$appSecret"
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(input.toByteArray(StandardCharsets.UTF_8))
    return Base64.getEncoder().encodeToString(digest)
  }
}

