package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Metadata for a compressed comment response stored in the application's cache directory. */
@Entity(
  tableName = "danmaku_cache_metadata",
  indices = [Index(value = ["episodeId"])],
)
data class DanmakuCacheEntity(
  @PrimaryKey
  val cacheKey: String,
  val episodeId: Long,
  val fileName: String,
  val commentCount: Int,
  val maxCid: Long,
  val fetchedAt: Long,
  val expiresAt: Long,
  val lastValidatedAt: Long,
  val etag: String? = null,
  val fileSize: Long = 0L,
  val unchangedFetches: Int = 0,
)

