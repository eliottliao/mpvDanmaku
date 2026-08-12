package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-confirmed or exact association between one media item and a danmaku library. */
@Entity(
  tableName = "danmaku_media_bindings",
  indices = [Index(value = ["episodeId"])],
)
data class DanmakuMediaBindingEntity(
  @PrimaryKey
  val mediaKey: String,
  val episodeId: Long,
  val animeId: Long? = null,
  val animeTitle: String = "",
  val episodeTitle: String = "",
  val matchSource: String,
  val serverShiftSeconds: Double = 0.0,
  val userOffsetSeconds: Double = 0.0,
  val fileHash: String? = null,
  val fileSize: Long = 0L,
  val createdAt: Long,
  val updatedAt: Long,
)

