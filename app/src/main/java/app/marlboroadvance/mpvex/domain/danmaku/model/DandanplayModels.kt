package app.marlboroadvance.mpvex.domain.danmaku.model

import java.io.IOException

enum class DandanplayMatchMode {
  HASH_AND_FILE_NAME,
  FILE_NAME_ONLY,
  HASH_ONLY,
}

data class MediaFingerprint(
  /** A local, irreversible identity suitable for persistent media bindings. */
  val mediaKey: String,
  /** File name without its directory or extension, as required by the API. */
  val fileName: String,
  /** Lower-case MD5 of at most the first 16 MiB, or null when content is unavailable. */
  val fileHash: String?,
  val fileSize: Long,
  val durationSeconds: Int,
  val suggestedMatchMode: DandanplayMatchMode,
) {
  fun toMatchQuery(): DandanplayMatchQuery =
    DandanplayMatchQuery(
      fileName = fileName,
      fileHash = fileHash,
      fileSize = fileSize,
      videoDurationSeconds = durationSeconds,
      matchMode = suggestedMatchMode,
    )
}

data class DandanplayMatchQuery(
  val fileName: String,
  val fileHash: String? = null,
  val fileSize: Long = 0,
  val videoDurationSeconds: Int = 0,
  val matchMode: DandanplayMatchMode = DandanplayMatchMode.HASH_AND_FILE_NAME,
)

data class DandanplayMatchCandidate(
  val episodeId: Long,
  val animeId: Long,
  val animeTitle: String,
  val episodeTitle: String,
  val type: String,
  val typeDescription: String,
  val shiftSeconds: Double,
  val imageUrl: String?,
)

data class DandanplayMatchResult(
  val isMatched: Boolean,
  val candidates: List<DandanplayMatchCandidate>,
)

enum class TmdbIdType(val apiValue: Int) {
  TV(0),
  MOVIE(1),
}

data class DandanplayEpisodeSearchQuery(
  val anime: String? = null,
  val tmdbId: Int? = null,
  val tmdbIdType: TmdbIdType = TmdbIdType.TV,
  val episode: String? = null,
)

data class DandanplayEpisode(
  val episodeId: Long,
  val episodeTitle: String,
)

data class DandanplayAnimeEpisodes(
  val animeId: Long,
  val animeTitle: String,
  val type: String,
  val typeDescription: String,
  val episodes: List<DandanplayEpisode>,
)

data class DandanplayEpisodeSearchResult(
  val hasMore: Boolean,
  val animes: List<DandanplayAnimeEpisodes>,
)

enum class ChineseConversion(val apiValue: Int) {
  NONE(0),
  SIMPLIFIED(1),
  TRADITIONAL(2),
}

data class DandanplayCommentQuery(
  val episodeId: Long,
  val fromCommentId: Long = 0,
  val withRelated: Boolean = true,
  val chineseConversion: ChineseConversion = ChineseConversion.NONE,
)

enum class RemoteDanmakuType {
  SCROLLING,
  BOTTOM,
  TOP,
}

data class RemoteDanmakuComment(
  val id: Long,
  val timeSeconds: Double,
  val type: RemoteDanmakuType,
  val colorArgb: Int,
  val text: String,
  val senderId: String?,
)

enum class DandanplayCommentSource {
  NETWORK,
  CACHE,
}

data class DandanplayCommentsResult(
  val comments: List<RemoteDanmakuComment>,
  val reportedCount: Int,
  val discardedCount: Int,
  val source: DandanplayCommentSource,
  val isStale: Boolean,
)

sealed class DandanplayException(
  message: String,
  cause: Throwable? = null,
) : IOException(message, cause)

class DandanplayInvalidQueryException(message: String) : DandanplayException(message)

class DandanplayAuthenticationException(
  val statusCode: Int,
  val serverReason: String?,
) : DandanplayException(
  buildString {
    append("Dandanplay authentication failed (HTTP ")
    append(statusCode)
    append(')')
    if (!serverReason.isNullOrBlank()) {
      append(": ")
      append(serverReason)
    }
  },
)

class DandanplayHttpException(
  val statusCode: Int,
  val serverMessage: String?,
) : DandanplayException(
  "Dandanplay request failed (HTTP $statusCode)" +
    if (serverMessage.isNullOrBlank()) "" else ": $serverMessage",
)

class DandanplayApiException(
  val errorCode: Int,
  val errorDetail: String?,
  message: String,
) : DandanplayException(message)

class DandanplayResponseTooLargeException(
  val maximumBytes: Long,
) : DandanplayException("Dandanplay response exceeds the $maximumBytes-byte limit")

class DandanplayInvalidResponseException(
  message: String,
  cause: Throwable? = null,
) : DandanplayException(message, cause)
