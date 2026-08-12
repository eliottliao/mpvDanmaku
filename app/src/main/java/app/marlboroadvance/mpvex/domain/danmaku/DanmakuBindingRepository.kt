package app.marlboroadvance.mpvex.domain.danmaku

import app.marlboroadvance.mpvex.database.dao.DanmakuDao
import app.marlboroadvance.mpvex.database.entities.DanmakuMediaBindingEntity

class DanmakuBindingRepository(
  private val dao: DanmakuDao,
) {
  suspend fun get(mediaKey: String): DanmakuMediaBindingEntity? = dao.getBinding(mediaKey)

  suspend fun save(
    mediaKey: String,
    episodeId: Long,
    animeId: Long?,
    animeTitle: String,
    episodeTitle: String,
    matchSource: DanmakuMatchSource,
    serverShiftSeconds: Double,
    fileHash: String?,
    fileSize: Long,
  ): DanmakuMediaBindingEntity {
    val now = System.currentTimeMillis()
    val existing = dao.getBinding(mediaKey)
    return DanmakuMediaBindingEntity(
      mediaKey = mediaKey,
      episodeId = episodeId,
      animeId = animeId,
      animeTitle = animeTitle,
      episodeTitle = episodeTitle,
      matchSource = matchSource.name,
      serverShiftSeconds = serverShiftSeconds,
      userOffsetSeconds = existing?.userOffsetSeconds ?: 0.0,
      fileHash = fileHash,
      fileSize = fileSize,
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    ).also { dao.upsertBinding(it) }
  }

  suspend fun updateOffset(
    mediaKey: String,
    offsetSeconds: Double,
  ) {
    dao.updateUserOffset(
      mediaKey = mediaKey,
      offsetSeconds = offsetSeconds.coerceIn(-600.0, 600.0),
      updatedAt = System.currentTimeMillis(),
    )
  }

  suspend fun remove(mediaKey: String) = dao.deleteBinding(mediaKey)
}

enum class DanmakuMatchSource {
  HASH_EXACT,
  FILE_NAME,
  MANUAL,
  TMDB,
}

