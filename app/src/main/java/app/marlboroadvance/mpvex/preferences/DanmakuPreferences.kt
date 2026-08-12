package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum

/** Persistent user-facing defaults for dandanplay danmaku playback. */
class DanmakuPreferences(
  preferenceStore: PreferenceStore,
) {
  val enabled = preferenceStore.getBoolean("danmaku_enabled", false)
  val privacyAccepted = preferenceStore.getBoolean("danmaku_privacy_accepted", false)
  val autoMatch = preferenceStore.getBoolean("danmaku_auto_match", true)
  val autoShow = preferenceStore.getBoolean("danmaku_auto_show", true)
  val showRelated = preferenceStore.getBoolean("danmaku_show_related", true)
  val chConvert = preferenceStore.getEnum("danmaku_ch_convert", DanmakuChineseConversion.None)

  val opacity = preferenceStore.getFloat("danmaku_opacity", 0.85f)
  val fontSize = preferenceStore.getFloat("danmaku_font_size", 28f)
  val outlineWidth = preferenceStore.getFloat("danmaku_outline_width", 2f)
  val speed = preferenceStore.getFloat("danmaku_speed", 1f)
  val fixedDurationSeconds = preferenceStore.getFloat("danmaku_fixed_duration_seconds", 4f)
  val density = preferenceStore.getFloat("danmaku_density", 1f)
  val displayArea = preferenceStore.getFloat("danmaku_display_area", 0.75f)
  val maxOnScreen = preferenceStore.getInt("danmaku_max_on_screen", 60)

  val showScrolling = preferenceStore.getBoolean("danmaku_mode_scrolling", true)
  val showTop = preferenceStore.getBoolean("danmaku_mode_top", true)
  val showBottom = preferenceStore.getBoolean("danmaku_mode_bottom", true)
  val showInPip = preferenceStore.getBoolean("danmaku_show_in_pip", false)

  val blockedKeywords = preferenceStore.getStringSet("danmaku_blocked_keywords")
  val keywordRegexEnabled = preferenceStore.getBoolean("danmaku_keyword_regex_enabled", false)
  val deepMatchOnWifi = preferenceStore.getBoolean("danmaku_deep_match_on_wifi", false)
}

enum class DanmakuChineseConversion {
  None,
  Simplified,
  Traditional,
}
