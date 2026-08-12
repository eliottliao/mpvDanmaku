package app.marlboroadvance.mpvex.ui.player.danmaku

/** Rendering modes understood by [DanmakuOverlayView]. */
enum class DanmakuMode {
  SCROLLING,
  BOTTOM,
  TOP,
}

/** A renderer-ready comment. The timestamp is expressed in media time. */
data class DanmakuItem(
  val id: Long,
  val timeMillis: Long,
  val text: String,
  val mode: DanmakuMode,
  val colorArgb: Int = 0xFFFFFFFF.toInt(),
)

/**
 * Runtime-only renderer settings.
 *
 * Fractions and multipliers are sanitized by [DanmakuOverlayView], so callers may safely pass
 * preference values while a slider is being edited.
 */
data class DanmakuRenderConfig(
  val enabled: Boolean = true,
  val showScrolling: Boolean = true,
  val showBottom: Boolean = true,
  val showTop: Boolean = true,
  val opacity: Float = 0.85f,
  val fontSizeSp: Float = 28f,
  val speed: Float = 1f,
  val density: Float = 1f,
  val displayArea: Float = 0.75f,
  val maxOnScreen: Int = 60,
  val fixedDurationMillis: Long = 4_000L,
  val strokeWidthDp: Float = 2f,
  val strokeColorArgb: Int = 0xFF000000.toInt(),
  val laneSpacingDp: Float = 4f,
)

