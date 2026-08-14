package app.marlboroadvance.mpvex.ui.player.danmaku

/** A selectable episode returned by automatic matching or manual search. */
data class DanmakuMatchCandidate(
  val episodeId: Long,
  val animeTitle: String,
  val episodeTitle: String,
  val episodeNumber: String? = null,
  val commentCount: Int? = null,
  val confidence: Float? = null,
)

enum class DanmakuUiStatus {
  Disabled,
  Idle,
  Matching,
  Loading,
  Ready,
  NoMatch,
  OfflineCache,
  Error,
}

/**
 * Immutable snapshot consumed by the player sheet.
 *
 * Slider values use renderer-friendly units: opacity/density/displayArea are 0..1,
 * fontSize is sp, speed is a multiplier, and offset is milliseconds.
 */
data class DanmakuUiState(
  val enabled: Boolean = false,
  val status: DanmakuUiStatus = DanmakuUiStatus.Disabled,
  val currentAnimeTitle: String? = null,
  val currentEpisodeTitle: String? = null,
  val selectedEpisodeId: Long? = null,
  val candidates: List<DanmakuMatchCandidate> = emptyList(),
  val commentCount: Int = 0,
  val lastUpdatedAtMillis: Long? = null,
  val searchQuery: String = "",
  val isSearching: Boolean = false,
  val isRefreshing: Boolean = false,
  val errorMessage: String? = null,
  val offsetMillis: Long = 0L,
  val showScrolling: Boolean = true,
  val showTop: Boolean = true,
  val showBottom: Boolean = true,
  val opacity: Float = 0.85f,
  val fontSize: Float = 28f,
  val speed: Float = 1f,
  val density: Float = 1f,
  val displayArea: Float = 0.75f,
  val blockedKeywords: Set<String> = emptySet(),
  val keywordRegexEnabled: Boolean = false,
)
