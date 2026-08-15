package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.database.dao.DanmakuDao
import app.marlboroadvance.mpvex.database.entities.DanmakuCacheEntity
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery

/** A disk-cached comment response together with its Room metadata, when available. */
data class StoredCommentEntry(
  val response: DandanplayCommentResponseDto,
  val metadata: DanmakuCacheEntity?,
  val fetchedAtEpochMillis: Long,
  val isFresh: Boolean,
)

/**
 * Metadata-backed wrapper around [RawCommentDiskCache].
 *
 * Owns the Room bookkeeping ([DanmakuCacheEntity]), the adaptive TTL back-off ladder,
 * the 50 MiB LRU eviction policy and the offline grace window, while the raw gzip
 * storage stays in [RawCommentDiskCache].
 */
class DanmakuCacheStore(
  private val diskCache: RawCommentDiskCache,
  private val dao: DanmakuDao,
  private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
  /** Cache key for a query; identical to the on-disk file name. */
  fun cacheKey(query: DandanplayCommentQuery): String = diskCache.cacheKey(query)

  /**
   * Reads a cached response and touches its metadata so the LRU order stays current.
   * Freshness is derived from the metadata `expiresAt`, falling back to the default TTL.
   */
  suspend fun get(query: DandanplayCommentQuery): StoredCommentEntry? {
    val cached = diskCache.get(query) ?: return null
    val key = cacheKey(query)
    val metadata = runCatching { dao.getCacheMetadata(key) }.getOrNull()
    val now = currentTimeMillis()
    val isFresh = if (metadata != null) {
      now < metadata.expiresAt
    } else {
      now - cached.fetchedAtEpochMillis in 0L..DEFAULT_FRESHNESS_MILLIS
    }
    if (metadata != null) {
      // LRU touch; a bookkeeping failure must not break playback.
      runCatching { dao.upsertCacheMetadata(metadata.copy(lastValidatedAt = now)) }
    }
    return StoredCommentEntry(
      response = cached.response,
      metadata = metadata,
      fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
      isFresh = isFresh,
    )
  }

  /**
   * Stores a fresh network response, updates the TTL ladder and enforces the size cap.
   * Returns the written metadata row, or null when the query is not cacheable.
   */
  suspend fun put(
    query: DandanplayCommentQuery,
    response: DandanplayCommentResponseDto,
  ): DanmakuCacheEntity? {
    if (query.fromCommentId != 0L) return null
    val key = cacheKey(query)
    val previous = runCatching { dao.getCacheMetadata(key) }.getOrNull()
    diskCache.put(query, response)

    val now = currentTimeMillis()
    val commentCount = response.count
    val maxCid = response.comments.orEmpty().maxOfOrNull { it.cid } ?: 0L
    val unchangedFetches: Int
    val ttlMillis: Long
    when {
      // Two consecutive fetches without change: extend the TTL along the ladder.
      previous != null && previous.commentCount == commentCount && previous.maxCid == maxCid -> {
        unchangedFetches = previous.unchangedFetches + 1
        val ladderIndex = (unchangedFetches - 1).coerceIn(0, TTL_LADDER_MILLIS.lastIndex)
        ttlMillis = TTL_LADDER_MILLIS[ladderIndex]
      }
      // A growing comment library should be re-validated quickly.
      previous != null &&
        (commentCount > previous.commentCount || maxCid > previous.maxCid) -> {
        unchangedFetches = 0
        ttlMillis = GROWING_TTL_MILLIS
      }
      else -> {
        unchangedFetches = 0
        ttlMillis = DEFAULT_FRESHNESS_MILLIS
      }
    }

    val entity = DanmakuCacheEntity(
      cacheKey = key,
      episodeId = query.episodeId,
      fileName = key,
      commentCount = commentCount,
      maxCid = maxCid,
      fetchedAt = now,
      expiresAt = now + ttlMillis,
      lastValidatedAt = now,
      etag = previous?.etag,
      fileSize = diskCache.sizeOf(query),
      unchangedFetches = unchangedFetches,
    )
    dao.upsertCacheMetadata(entity)
    evictOldestIfNeeded(protectedKey = key)
    return entity
  }

  /** True while a stale entry may still be served as an offline fallback. */
  fun isWithinOfflineGrace(entry: StoredCommentEntry): Boolean {
    val fetchedAt = entry.metadata?.fetchedAt ?: entry.fetchedAtEpochMillis
    return currentTimeMillis() - fetchedAt <= OFFLINE_GRACE_MILLIS
  }

  /** Deletes all cached files and metadata rows, returning the number of bytes freed. */
  suspend fun clearAll(): Long {
    val freed = diskCache.clearAll()
    dao.clearCacheMetadata()
    return freed
  }

  private suspend fun evictOldestIfNeeded(protectedKey: String) {
    val entries = dao.getCacheMetadataOldestFirst()
    var totalBytes = 0L
    val validEntries = ArrayList<DanmakuCacheEntity>(entries.size)
    for (entry in entries) {
      val diskSize = diskCache.sizeOfFile(entry.fileName)
      if (diskSize <= 0L) {
        // Orphaned Room row where Android wiped the cacheDir file; clean up
        runCatching { dao.deleteCacheMetadata(entry.cacheKey) }
      } else {
        totalBytes += maxOf(entry.fileSize, diskSize)
        validEntries += entry
      }
    }
    if (totalBytes <= MAX_TOTAL_BYTES) return

    for (entry in validEntries) {
      if (totalBytes <= MAX_TOTAL_BYTES) break
      if (entry.cacheKey == protectedKey) continue
      val diskSize = diskCache.sizeOfFile(entry.fileName)
      runCatching { diskCache.deleteFile(entry.fileName) }
      runCatching { dao.deleteCacheMetadata(entry.cacheKey) }
      totalBytes -= maxOf(entry.fileSize, diskSize)
    }
  }

  companion object {
    const val DEFAULT_FRESHNESS_MILLIS: Long = 6L * 60L * 60L * 1_000L
    const val MAX_TOTAL_BYTES: Long = 50L * 1024L * 1024L
    const val OFFLINE_GRACE_MILLIS: Long = 30L * 24L * 60L * 60L * 1_000L

    /** TTL ladder indexed by unchangedFetches: 6h, 24h, 3d, 7d. */
    val TTL_LADDER_MILLIS: LongArray = longArrayOf(
      6L * 60L * 60L * 1_000L,
      24L * 60L * 60L * 1_000L,
      3L * 24L * 60L * 60L * 1_000L,
      7L * 24L * 60L * 60L * 1_000L,
    )

    /** Actively growing comment libraries are re-validated after one hour. */
    const val GROWING_TTL_MILLIS: Long = 60L * 60L * 1_000L
  }
}
