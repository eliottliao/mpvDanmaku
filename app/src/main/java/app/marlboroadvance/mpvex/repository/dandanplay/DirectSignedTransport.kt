package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayEpisodeSearchQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

interface DandanplayTransport {
  suspend fun match(request: DandanplayMatchRequestDto): DandanplayMatchResponseDto

  suspend fun searchEpisodes(query: DandanplayEpisodeSearchQuery): DandanplaySearchEpisodesResponseDto

  suspend fun getComments(query: DandanplayCommentQuery): DandanplayCommentResponseDto
}

/** Direct HTTPS transport using the app-level signed authentication scheme. */
class DirectSignedTransport(
  private val apiClient: DandanplayApiClient,
  private val json: Json = defaultDandanplayJson(),
  private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : DandanplayTransport {
  constructor(
    httpClient: OkHttpClient,
    appId: String,
    appSecret: String,
    json: Json = defaultDandanplayJson(),
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
  ) : this(
    apiClient = DandanplayApiClient(
      httpClient = httpClient,
      signer = DandanplayRequestSigner(appId, appSecret),
      json = json,
    ),
    json = json,
    baseUrl = baseUrl,
  )

  init {
    require(baseUrl.isHttps) { "Dandanplay base URL must use HTTPS" }
  }

  override suspend fun match(request: DandanplayMatchRequestDto): DandanplayMatchResponseDto {
    val httpRequest = Request.Builder()
      .url(endpoint(MATCH_PATH))
      .post(json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE))
      .build()
    return apiClient.execute(
      request = httpRequest,
      deserializer = DandanplayMatchResponseDto.serializer(),
    )
  }

  override suspend fun searchEpisodes(
    query: DandanplayEpisodeSearchQuery,
  ): DandanplaySearchEpisodesResponseDto {
    val url = endpoint(SEARCH_EPISODES_PATH).newBuilder()
      .apply {
        query.anime?.trim()?.takeIf { it.isNotEmpty() }
          ?.let { addQueryParameter("anime", it) }
        query.tmdbId?.let { addQueryParameter("tmdbId", it.toString()) }
        if (query.tmdbId != null) {
          addQueryParameter("tmdbIdType", query.tmdbIdType.apiValue.toString())
        }
        query.episode?.trim()?.takeIf { it.isNotEmpty() }
          ?.let { addQueryParameter("episode", it) }
        addQueryParameter("v2", "true")
      }
      .build()
    return apiClient.execute(
      request = Request.Builder().url(url).get().build(),
      deserializer = DandanplaySearchEpisodesResponseDto.serializer(),
      maximumAttempts = GET_MAXIMUM_ATTEMPTS,
    )
  }

  override suspend fun getComments(query: DandanplayCommentQuery): DandanplayCommentResponseDto {
    val url = endpoint("$COMMENT_PATH/${query.episodeId}").newBuilder()
      .addQueryParameter("withRelated", query.withRelated.toString())
      .addQueryParameter("chConvert", query.chineseConversion.apiValue.toString())
      .apply {
        if (query.fromCommentId > 0L) {
          addQueryParameter("from", query.fromCommentId.toString())
        }
      }
      .build()
    return apiClient.execute(
      request = Request.Builder().url(url).get().build(),
      deserializer = DandanplayCommentResponseDto.serializer(),
      maximumAttempts = GET_MAXIMUM_ATTEMPTS,
    )
  }

  private fun endpoint(path: String): HttpUrl =
    baseUrl.newBuilder()
      .addPathSegments(path.trimStart('/'))
      .build()

  companion object {
    const val DEFAULT_BASE_URL = "https://api.dandanplay.net/"

    private const val MATCH_PATH = "api/v2/match"
    private const val SEARCH_EPISODES_PATH = "api/v2/search/episodes"
    private const val COMMENT_PATH = "api/v2/comment"
    private const val GET_MAXIMUM_ATTEMPTS = 3
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

