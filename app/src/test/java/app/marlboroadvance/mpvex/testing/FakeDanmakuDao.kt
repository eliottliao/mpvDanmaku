package app.marlboroadvance.mpvex.testing

import app.marlboroadvance.mpvex.database.dao.DanmakuDao
import app.marlboroadvance.mpvex.database.entities.DanmakuCacheEntity
import app.marlboroadvance.mpvex.database.entities.DanmakuMediaBindingEntity

/**
 * In-memory fake of [DanmakuDao] for JVM unit tests.
 *
 * Rows are exposed through [bindings] and [cacheRows] so tests can inspect or seed
 * state directly (e.g. inflated file sizes for LRU eviction scenarios).
 */
class FakeDanmakuDao : DanmakuDao {
  val bindings = LinkedHashMap<String, DanmakuMediaBindingEntity>()
  val cacheRows = LinkedHashMap<String, DanmakuCacheEntity>()

  override suspend fun getBinding(mediaKey: String): DanmakuMediaBindingEntity? = bindings[mediaKey]

  override suspend fun upsertBinding(binding: DanmakuMediaBindingEntity) {
    bindings[binding.mediaKey] = binding
  }

  override suspend fun deleteBinding(mediaKey: String) {
    bindings.remove(mediaKey)
  }

  override suspend fun updateUserOffset(
    mediaKey: String,
    offsetSeconds: Double,
    updatedAt: Long,
  ) {
    bindings[mediaKey]?.let {
      bindings[mediaKey] = it.copy(userOffsetSeconds = offsetSeconds, updatedAt = updatedAt)
    }
  }

  override suspend fun getCacheMetadata(cacheKey: String): DanmakuCacheEntity? = cacheRows[cacheKey]

  override suspend fun upsertCacheMetadata(metadata: DanmakuCacheEntity) {
    cacheRows[metadata.cacheKey] = metadata
  }

  override suspend fun deleteCacheMetadata(cacheKey: String) {
    cacheRows.remove(cacheKey)
  }

  override suspend fun getCacheMetadataOldestFirst(): List<DanmakuCacheEntity> =
    cacheRows.values.sortedBy { it.lastValidatedAt }

  override suspend fun clearCacheMetadata() {
    cacheRows.clear()
  }
}
