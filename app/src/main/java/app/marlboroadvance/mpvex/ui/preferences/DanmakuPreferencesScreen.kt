package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.DanmakuChineseConversion
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.repository.dandanplay.DanmakuCacheStore
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuBlockedKeywordsDialog
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Serializable
object DanmakuPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val preferences = koinInject<DanmakuPreferences>()
    val cacheStore = koinInject<DanmakuCacheStore>()
    val scope = rememberCoroutineScope()
    val enabled by preferences.enabled.collectAsState()
    val privacyAccepted by preferences.privacyAccepted.collectAsState()
    val blockedKeywords by preferences.blockedKeywords.collectAsState()
    val regexEnabled by preferences.keywordRegexEnabled.collectAsState()
    var showConsent by remember { mutableStateOf(false) }
    var showConversionPicker by remember { mutableStateOf(false) }
    var showKeywordEditor by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var isClearingCache by remember { mutableStateOf(false) }
    var clearedSpace by remember { mutableStateOf<String?>(null) }

    if (showConsent) {
      AlertDialog(
        onDismissRequest = { showConsent = false },
        title = { Text("Enable dandanplay danmaku?") },
        text = {
          Text(
            "To identify the current episode, mpvDanmaku sends the file name without its path, " +
              "the MD5 of the first 16 MiB, file size, and duration to the dandanplay open danmaku network. " +
              "Video contents, local paths, server credentials, and full stream URLs are not uploaded.",
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              preferences.privacyAccepted.set(true)
              preferences.enabled.set(true)
              showConsent = false
            },
          ) { Text("Enable") }
        },
        dismissButton = {
          TextButton(onClick = { showConsent = false }) { Text("Cancel") }
        },
      )
    }

    if (showConversionPicker) {
      AlertDialog(
        onDismissRequest = { showConversionPicker = false },
        title = { Text("Chinese conversion") },
        text = {
          androidx.compose.foundation.layout.Column {
            DanmakuChineseConversion.entries.forEach { conversion ->
              val selected = preferences.chConvert.get() == conversion
              androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
              ) {
                RadioButton(
                  selected = selected,
                  onClick = {
                    preferences.chConvert.set(conversion)
                    showConversionPicker = false
                  },
                )
                Text(
                  when (conversion) {
                    DanmakuChineseConversion.None -> "Do not convert"
                    DanmakuChineseConversion.Simplified -> "Simplified Chinese"
                    DanmakuChineseConversion.Traditional -> "Traditional Chinese"
                  },
                )
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showConversionPicker = false }) { Text("Close") }
        },
      )
    }

    if (showKeywordEditor) {
      DanmakuBlockedKeywordsDialog(
        blockedKeywords = blockedKeywords,
        keywordRegexEnabled = regexEnabled,
        onAddKeyword = { keyword ->
          preferences.blockedKeywords.set(blockedKeywords + keyword)
        },
        onRemoveKeyword = { keyword ->
          preferences.blockedKeywords.set(blockedKeywords - keyword)
        },
        onKeywordRegexEnabledChange = preferences.keywordRegexEnabled::set,
        onDismissRequest = { showKeywordEditor = false },
      )
    }

    if (showClearCacheConfirm) {
      AlertDialog(
        onDismissRequest = { if (!isClearingCache) showClearCacheConfirm = false },
        title = { Text("Clear danmaku cache?") },
        text = {
          Text(
            "All cached dandanplay comments will be removed from this device. " +
              "Comments are downloaded again the next time they are needed.",
          )
        },
        confirmButton = {
          TextButton(
            enabled = !isClearingCache,
            onClick = {
              isClearingCache = true
              scope.launch {
                val freedBytes = try {
                  cacheStore.clearAll()
                } catch (_: Exception) {
                  0L
                }
                isClearingCache = false
                showClearCacheConfirm = false
                clearedSpace = formatByteCount(freedBytes)
              }
            },
          ) { Text(if (isClearingCache) "Clearing…" else "Clear") }
        },
        dismissButton = {
          TextButton(
            enabled = !isClearingCache,
            onClick = { showClearCacheConfirm = false },
          ) { Text("Cancel") }
        },
      )
    }

    clearedSpace?.let { freed ->
      AlertDialog(
        onDismissRequest = { clearedSpace = null },
        title = { Text("Danmaku cache cleared") },
        text = { Text("Freed $freed of storage space.") },
        confirmButton = {
          TextButton(onClick = { clearedSpace = null }) { Text("OK") }
        },
      )
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "Danmaku",
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          item { PreferenceSectionHeader("Dandanplay open danmaku network") }
          item {
            PreferenceCard {
              SwitchPreference(
                value = enabled,
                onValueChange = { requested ->
                  when {
                    !requested -> preferences.enabled.set(false)
                    privacyAccepted -> preferences.enabled.set(true)
                    else -> showConsent = true
                  }
                },
                title = { Text("Enable danmaku") },
                summary = { Text("Match comments only while a video is being played") },
              )

              PreferenceDivider()

              val autoMatch by preferences.autoMatch.collectAsState()
              SwitchPreference(
                value = autoMatch,
                onValueChange = preferences.autoMatch::set,
                title = { Text("Automatic matching") },
                summary = { Text("Only exact matches are selected automatically") },
                enabled = enabled,
              )

              PreferenceDivider()

              val showRelated by preferences.showRelated.collectAsState()
              SwitchPreference(
                value = showRelated,
                onValueChange = preferences.showRelated::set,
                title = { Text("Include related comments") },
                summary = { Text("Merge supported third-party comment sources") },
                enabled = enabled,
              )

              PreferenceDivider()

              val conversion by preferences.chConvert.collectAsState()
              Preference(
                title = { Text("Chinese conversion") },
                summary = {
                  Text(
                    when (conversion) {
                      DanmakuChineseConversion.None -> "Do not convert"
                      DanmakuChineseConversion.Simplified -> "Simplified Chinese"
                      DanmakuChineseConversion.Traditional -> "Traditional Chinese"
                    },
                  )
                },
                enabled = enabled,
                onClick = { showConversionPicker = true },
              )
            }
          }

          item { PreferenceSectionHeader("Appearance") }
          item {
            PreferenceCard {
              val opacity by preferences.opacity.collectAsState()
              SliderPreference(
                value = opacity,
                onValueChange = preferences.opacity::set,
                sliderValue = opacity,
                onSliderValueChange = preferences.opacity::set,
                title = { Text("Opacity") },
                summary = { Text("${(opacity * 100).roundToInt()}%") },
                valueRange = 0.2f..1f,
                enabled = enabled,
              )

              PreferenceDivider()

              val fontSize by preferences.fontSize.collectAsState()
              SliderPreference(
                value = fontSize,
                onValueChange = preferences.fontSize::set,
                sliderValue = fontSize,
                onSliderValueChange = preferences.fontSize::set,
                title = { Text("Font size") },
                summary = { Text("${fontSize.roundToInt()} sp") },
                valueRange = 16f..48f,
                enabled = enabled,
              )

              PreferenceDivider()

              val speed by preferences.speed.collectAsState()
              SliderPreference(
                value = speed,
                onValueChange = preferences.speed::set,
                sliderValue = speed,
                onSliderValueChange = preferences.speed::set,
                title = { Text("Scroll speed") },
                summary = { Text(String.format("%.1fx", speed)) },
                valueRange = 0.5f..2f,
                enabled = enabled,
              )

              PreferenceDivider()

              val density by preferences.density.collectAsState()
              SliderPreference(
                value = density,
                onValueChange = preferences.density::set,
                sliderValue = density,
                onSliderValueChange = preferences.density::set,
                title = { Text("Density") },
                summary = { Text("${(density * 100).roundToInt()}%") },
                valueRange = 0.1f..1f,
                enabled = enabled,
              )

              PreferenceDivider()

              val displayArea by preferences.displayArea.collectAsState()
              SliderPreference(
                value = displayArea,
                onValueChange = preferences.displayArea::set,
                sliderValue = displayArea,
                onSliderValueChange = preferences.displayArea::set,
                title = { Text("Display area") },
                summary = { Text("Top ${(displayArea * 100).roundToInt()}% of the player") },
                valueRange = 0.25f..1f,
                enabled = enabled,
              )
            }
          }

          item { PreferenceSectionHeader("Modes and playback") }
          item {
            PreferenceCard {
              val showScrolling by preferences.showScrolling.collectAsState()
              SwitchPreference(
                value = showScrolling,
                onValueChange = preferences.showScrolling::set,
                title = { Text("Scrolling comments") },
                enabled = enabled,
              )
              PreferenceDivider()
              val showTop by preferences.showTop.collectAsState()
              SwitchPreference(
                value = showTop,
                onValueChange = preferences.showTop::set,
                title = { Text("Top comments") },
                enabled = enabled,
              )
              PreferenceDivider()
              val showBottom by preferences.showBottom.collectAsState()
              SwitchPreference(
                value = showBottom,
                onValueChange = preferences.showBottom::set,
                title = { Text("Bottom comments") },
                enabled = enabled,
              )
              PreferenceDivider()
              val showInPip by preferences.showInPip.collectAsState()
              SwitchPreference(
                value = showInPip,
                onValueChange = preferences.showInPip::set,
                title = { Text("Show in picture-in-picture") },
                summary = { Text("Disabled by default for readability and performance") },
                enabled = enabled,
              )
            }
          }

          item { PreferenceSectionHeader("Cache and filtering") }
          item {
            PreferenceCard {
              SwitchPreference(
                value = regexEnabled,
                onValueChange = preferences.keywordRegexEnabled::set,
                title = { Text("Regular expression keywords") },
                summary = { Text("Interpret blocked keywords as regular expressions") },
                enabled = enabled,
              )
              PreferenceDivider()
              Preference(
                title = { Text("Blocked keywords") },
                summary = {
                  Text(
                    if (blockedKeywords.isEmpty()) {
                      "No blocked keywords"
                    } else {
                      "${blockedKeywords.size} keyword${if (blockedKeywords.size == 1) "" else "s"} blocked"
                    },
                  )
                },
                enabled = enabled,
                onClick = { showKeywordEditor = true },
              )
              PreferenceDivider()
              Preference(
                title = { Text("Clear danmaku cache") },
                summary = { Text("Remove all cached dandanplay comments") },
                onClick = { showClearCacheConfirm = true },
              )
            }
          }
        }
      }
    }
  }
}

private fun formatByteCount(bytes: Long): String = when {
  bytes >= 1024L * 1024L -> String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
  bytes >= 1024L -> String.format("%.1f KiB", bytes / 1024.0)
  else -> "$bytes B"
}
