package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.marlboroadvance.mpvex.database.entities.DanmakuCacheEntity
import app.marlboroadvance.mpvex.database.entities.DanmakuMediaBindingEntity

@Dao
interface DanmakuDao {
  @Query("SELECT * FROM danmaku_media_bindings WHERE mediaKey = :mediaKey LIMIT 1")
  suspend fun getBinding(mediaKey: String): DanmakuMediaBindingEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertBinding(binding: DanmakuMediaBindingEntity)

  @Query("DELETE FROM danmaku_media_bindings WHERE mediaKey = :mediaKey")
  suspend fun deleteBinding(mediaKey: String)

  @Query(
    """
    UPDATE danmaku_media_bindings
    SET userOffsetSeconds = :offsetSeconds, updatedAt = :updatedAt
    WHERE mediaKey = :mediaKey
    """,
  )
  suspend fun updateUserOffset(
    mediaKey: String,
    offsetSeconds: Double,
    updatedAt: Long,
  )

  @Query("SELECT * FROM danmaku_cache_metadata WHERE cacheKey = :cacheKey LIMIT 1")
  suspend fun getCacheMetadata(cacheKey: String): DanmakuCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCacheMetadata(metadata: DanmakuCacheEntity)

  @Query("DELETE FROM danmaku_cache_metadata WHERE cacheKey = :cacheKey")
  suspend fun deleteCacheMetadata(cacheKey: String)

  @Query("SELECT * FROM danmaku_cache_metadata ORDER BY lastValidatedAt ASC")
  suspend fun getCacheMetadataOldestFirst(): List<DanmakuCacheEntity>

  @Query("DELETE FROM danmaku_cache_metadata")
  suspend fun clearCacheMetadata()
}

