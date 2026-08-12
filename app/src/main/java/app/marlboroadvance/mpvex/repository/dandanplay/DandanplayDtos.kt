package app.marlboroadvance.mpvex.repository.dandanplay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DandanplayMatchModeDto {
  @SerialName("hashAndFileName")
  HASH_AND_FILE_NAME,

  @SerialName("fileNameOnly")
  FILE_NAME_ONLY,

  @SerialName("hashOnly")
  HASH_ONLY,
}

@Serializable
data class DandanplayMatchRequestDto(
  val fileName: String,
  val fileHash: String? = null,
  val fileSize: Long = 0,
  val videoDuration: Int = 0,
  val matchMode: DandanplayMatchModeDto = DandanplayMatchModeDto.HASH_AND_FILE_NAME,
)

@Serializable
data class DandanplayMatchResponseDto(
  val errorCode: Int = 0,
  val success: Boolean = true,
  val errorMessage: String? = null,
  val errorDetail: String? = null,
  val isMatched: Boolean = false,
  val matches: List<DandanplayMatchResultDto>? = null,
)

@Serializable
data class DandanplayMatchResultDto(
  val episodeId: Long = 0,
  val animeId: Long = 0,
  val animeTitle: String? = null,
  val episodeTitle: String? = null,
  val type: String? = null,
  val typeDescription: String? = null,
  val shift: Double = 0.0,
  val imageUrl: String? = null,
)

@Serializable
data class DandanplaySearchEpisodesResponseDto(
  val errorCode: Int = 0,
  val success: Boolean = true,
  val errorMessage: String? = null,
  val errorDetail: String? = null,
  val hasMore: Boolean = false,
  val animes: List<DandanplaySearchAnimeDto>? = null,
)

@Serializable
data class DandanplaySearchAnimeDto(
  val animeId: Long = 0,
  val animeTitle: String? = null,
  val type: String? = null,
  val typeDescription: String? = null,
  val episodes: List<DandanplaySearchEpisodeDto>? = null,
)

@Serializable
data class DandanplaySearchEpisodeDto(
  val episodeId: Long = 0,
  val episodeTitle: String? = null,
)

@Serializable
data class DandanplayCommentResponseDto(
  val count: Int = 0,
  val comments: List<DandanplayCommentDto>? = null,
)

@Serializable
data class DandanplayCommentDto(
  val cid: Long = 0,
  val p: String? = null,
  val m: String? = null,
)

