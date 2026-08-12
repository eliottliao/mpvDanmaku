package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.ChineseConversion
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayAnimeEpisodes
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayApiException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentSource
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentsResult
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayEpisode
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayEpisodeSearchQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayEpisodeSearchResult
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayInvalidQueryException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchCandidate
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchMode
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchResult
import app.marlboroadvance.mpvex.domain.danmaku.model.RemoteDanmakuComment
import app.marlboroadvance.mpvex.domain.danmaku.model.RemoteDanmakuType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Domain-facing entry point for matching, episode lookup, comment parsing and cache fallback. */
class DandanplayRepository(
  private val transport: DandanplayTransport,
  private val commentCache: DanmakuCacheStore,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val inflightMutex = Mutex()
  private val inflightRequests = mutableMapOf<String, Deferred<DandanplayCommentsResult>>()
  private val lastManualRefreshEpochMillis = mutableMapOf<Long, Long>()

  suspend fun match(query: DandanplayMatchQuery): DandanplayMatchResult {
    validateMatchQuery(query)
    val response = transport.match(
      DandanplayMatchRequestDto(
        fileName = query.fileName.trim(),
        fileHash = query.fileHash?.lowercase(),
        fileSize = query.fileSize,
        videoDuration = query.videoDurationSeconds,
        matchMode = query.matchMode.toDto(),
      ),
    )
    checkBusinessResponse(
      success = response.success,
      errorCode = response.errorCode,
      errorMessage = response.errorMessage,
      errorDetail = response.errorDetail,
    )
    return DandanplayMatchResult(
      isMatched = response.isMatched,
      candidates = response.matches.orEmpty().map { match ->
        DandanplayMatchCandidate(
          episodeId = match.episodeId,
          animeId = match.animeId,
          animeTitle = match.animeTitle.orEmpty(),
          episodeTitle = match.episodeTitle.orEmpty(),
          type = match.type.orEmpty(),
          typeDescription = match.typeDescription.orEmpty(),
          shiftSeconds = match.shift.takeIf(Double::isFinite) ?: 0.0,
          imageUrl = match.imageUrl,
        )
      },
    )
  }

  suspend fun searchEpisodes(
    query: DandanplayEpisodeSearchQuery,
  ): DandanplayEpisodeSearchResult {
    validateSearchQuery(query)
    val response = transport.searchEpisodes(query)
    checkBusinessResponse(
      success = response.success,
      errorCode = response.errorCode,
      errorMessage = response.errorMessage,
      errorDetail = response.errorDetail,
    )
    return DandanplayEpisodeSearchResult(
      hasMore = response.hasMore,
      animes = response.animes.orEmpty().map { anime ->
        DandanplayAnimeEpisodes(
          animeId = anime.animeId,
          animeTitle = anime.animeTitle.orEmpty(),
          type = anime.type.orEmpty(),
          typeDescription = anime.typeDescription.orEmpty(),
          episodes = anime.episodes.orEmpty().map { episode ->
            DandanplayEpisode(
              episodeId = episode.episodeId,
              episodeTitle = episode.episodeTitle.orEmpty(),
            )
          },
        )
      },
    )
  }

  suspend fun getComments(
    query: DandanplayCommentQuery,
    forceRefresh: Boolean = false,
  ): DandanplayCommentsResult {
    validateCommentQuery(query)
    val cacheKey = commentCache.cacheKey(query)
    // Concurrent requests for the same cache key share a single in-flight fetch.
    val deferred = inflightMutex.withLock {
      // A manual refresh inside its cooldown degrades to a plain cache read.
      val effectiveForceRefresh = forceRefresh && !isInManualRefreshCooldown(query.episodeId)
      inflightRequests.getOrPut(cacheKey) {
        scope.async {
          try {
            fetchComments(query, effectiveForceRefresh)
          } finally {
            inflightMutex.withLock { inflightRequests.remove(cacheKey) }
          }
        }
      }
    }
    return deferred.await()
  }

  private suspend fun fetchComments(
    query: DandanplayCommentQuery,
    forceRefresh: Boolean,
  ): DandanplayCommentsResult {
    val cached = commentCache.get(query)
    if (!forceRefresh && cached?.isFresh == true) {
      return parseComments(
        response = cached.response,
        source = DandanplayCommentSource.CACHE,
        isStale = false,
      )
    }

    return try {
      val response = transport.getComments(query)
      try {
        commentCache.put(query, response)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // A cache failure must not discard a valid network response.
      }
      if (forceRefresh) recordManualRefresh(query.episodeId)
      parseComments(
        response = response,
        source = DandanplayCommentSource.NETWORK,
        isStale = false,
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (networkError: IOException) {
      // Offline fallback: stale cache remains usable for up to 30 days.
      if (cached != null && commentCache.isWithinOfflineGrace(cached)) {
        parseComments(
          response = cached.response,
          source = DandanplayCommentSource.CACHE,
          isStale = true,
        )
      } else {
        throw networkError
      }
    }
  }

  private fun isInManualRefreshCooldown(episodeId: Long): Boolean {
    val lastRefresh = lastManualRefreshEpochMillis[episodeId] ?: return false
    return System.currentTimeMillis() - lastRefresh < MANUAL_REFRESH_COOLDOWN_MILLIS
  }

  private suspend fun recordManualRefresh(episodeId: Long) {
    inflightMutex.withLock {
      lastManualRefreshEpochMillis[episodeId] = System.currentTimeMillis()
    }
  }

  internal fun parseComments(
    response: DandanplayCommentResponseDto,
    source: DandanplayCommentSource,
    isStale: Boolean,
  ): DandanplayCommentsResult {
    val rawComments = response.comments.orEmpty()
    val seenIds = HashSet<Long>(rawComments.size)
    val comments = ArrayList<RemoteDanmakuComment>(rawComments.size)
    var discarded = 0

    for (raw in rawComments) {
      val fields = raw.p?.split(',', limit = 5)
      val time = fields?.getOrNull(0)?.toDoubleOrNull()
      val type = when (fields?.getOrNull(1)?.toIntOrNull()) {
        1 -> RemoteDanmakuType.SCROLLING
        4 -> RemoteDanmakuType.BOTTOM
        5 -> RemoteDanmakuType.TOP
        else -> null
      }
      val color = fields?.getOrNull(2)?.toIntOrNull()
      val text = sanitizeText(raw.m.orEmpty())
      if (
        fields == null || fields.size < 4 ||
        time == null || !time.isFinite() || time < 0.0 ||
        type == null || color == null || color !in 0..MAX_RGB ||
        text.isBlank() || !seenIds.add(raw.cid)
      ) {
        discarded += 1
        continue
      }

      comments += RemoteDanmakuComment(
        id = raw.cid,
        timeSeconds = time,
        type = type,
        colorArgb = OPAQUE_ALPHA or color,
        text = text,
        senderId = fields[3].trim().takeIf { it.isNotEmpty() },
      )
    }

    return DandanplayCommentsResult(
      comments = comments,
      reportedCount = response.count,
      discardedCount = discarded,
      source = source,
      isStale = isStale,
    )
  }

  private fun validateMatchQuery(query: DandanplayMatchQuery) {
    if (query.fileName.isBlank()) {
      throw DandanplayInvalidQueryException("The media file name must not be blank")
    }
    if (query.fileSize < 0L || query.videoDurationSeconds < 0) {
      throw DandanplayInvalidQueryException("File size and duration must not be negative")
    }
    if (
      query.matchMode != DandanplayMatchMode.FILE_NAME_ONLY &&
      !query.fileHash.isValidMd5()
    ) {
      throw DandanplayInvalidQueryException("Hash matching requires a 32-character MD5")
    }
  }

  private fun validateSearchQuery(query: DandanplayEpisodeSearchQuery) {
    val anime = query.anime?.trim()
    if (query.tmdbId == null && anime.isNullOrBlank()) {
      throw DandanplayInvalidQueryException("An anime name or TMDB ID is required")
    }
    if (query.tmdbId != null && query.tmdbId <= 0) {
      throw DandanplayInvalidQueryException("TMDB ID must be positive")
    }
    if (query.tmdbId == null && anime != null && anime.length < MINIMUM_SEARCH_LENGTH) {
      throw DandanplayInvalidQueryException("Anime search text must contain at least two characters")
    }
  }

  private fun validateCommentQuery(query: DandanplayCommentQuery) {
    if (query.episodeId <= 0L) {
      throw DandanplayInvalidQueryException("Episode ID must be positive")
    }
    if (query.fromCommentId < 0L) {
      throw DandanplayInvalidQueryException("Starting comment ID must not be negative")
    }
  }

  private fun checkBusinessResponse(
    success: Boolean,
    errorCode: Int,
    errorMessage: String?,
    errorDetail: String?,
  ) {
    if (success && errorCode == 0) return
    val message = errorMessage?.takeIf { it.isNotBlank() }
      ?: errorDetail?.takeIf { it.isNotBlank() }
      ?: "Dandanplay API error $errorCode"
    throw DandanplayApiException(errorCode, errorDetail, message)
  }

  private fun sanitizeText(value: String): String {
    val sanitized = buildString(value.length) {
      var offset = 0
      while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        if (!Character.isISOControl(codePoint)) appendCodePoint(codePoint)
        offset += Character.charCount(codePoint)
      }
    }.trim()
    val codePointCount = sanitized.codePointCount(0, sanitized.length)
    if (codePointCount <= MAXIMUM_COMMENT_CODE_POINTS) return sanitized
    val end = sanitized.offsetByCodePoints(0, MAXIMUM_COMMENT_CODE_POINTS)
    return sanitized.substring(0, end)
  }

  private fun String?.isValidMd5(): Boolean =
    this != null && length == 32 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

  private fun DandanplayMatchMode.toDto(): DandanplayMatchModeDto = when (this) {
    DandanplayMatchMode.HASH_AND_FILE_NAME -> DandanplayMatchModeDto.HASH_AND_FILE_NAME
    DandanplayMatchMode.FILE_NAME_ONLY -> DandanplayMatchModeDto.FILE_NAME_ONLY
    DandanplayMatchMode.HASH_ONLY -> DandanplayMatchModeDto.HASH_ONLY
  }

  companion object {
    private const val MINIMUM_SEARCH_LENGTH = 2
    private const val MAXIMUM_COMMENT_CODE_POINTS = 300
    private const val MAX_RGB = 0x00FFFFFF
    private const val OPAQUE_ALPHA = -0x1000000
    private const val MANUAL_REFRESH_COOLDOWN_MILLIS = 60L * 1_000L
  }
}

