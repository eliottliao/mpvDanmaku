package app.marlboroadvance.mpvex.ui.player.danmaku

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import kotlin.math.roundToInt

private val WideSheetBreakpoint = 720.dp

/**
 * Player controls for the active dandanplay match and danmaku renderer.
 *
 * The composable is deliberately state-only: the player owns loading, persistence, and renderer updates.
 * Values emitted by the slider callbacks use the units documented by [DanmakuUiState].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DanmakuSheet(
  state: DanmakuUiState,
  onEnabledChange: (Boolean) -> Unit,
  onCandidateSelect: (DanmakuMatchCandidate) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onRefresh: () -> Unit,
  onRematch: () -> Unit,
  onOffsetChange: (Long) -> Unit,
  onOpacityChange: (Float) -> Unit,
  onFontSizeChange: (Float) -> Unit,
  onDensityChange: (Float) -> Unit,
  onDisplayAreaChange: (Float) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PlayerSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    customMaxWidth = 960.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      DanmakuSheetHeader(
        enabled = state.enabled,
        onEnabledChange = onEnabledChange,
        onDismissRequest = onDismissRequest,
      )
      HorizontalDivider()

      BoxWithConstraints(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
      ) {
        if (maxWidth >= WideSheetBreakpoint) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
          ) {
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              DanmakuSourceColumn(
                state = state,
                onCandidateSelect = onCandidateSelect,
                onSearchQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onRefresh = onRefresh,
                onRematch = onRematch,
              )
            }
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              DanmakuDisplayColumn(
                state = state,
                onOffsetChange = onOffsetChange,
                onOpacityChange = onOpacityChange,
                onFontSizeChange = onFontSizeChange,
                onDensityChange = onDensityChange,
                onDisplayAreaChange = onDisplayAreaChange,
              )
            }
          }
        } else {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            DanmakuSourceColumn(
              state = state,
              onCandidateSelect = onCandidateSelect,
              onSearchQueryChange = onSearchQueryChange,
              onSearch = onSearch,
              onRefresh = onRefresh,
              onRematch = onRematch,
            )
            DanmakuDisplayColumn(
              state = state,
              onOffsetChange = onOffsetChange,
              onOpacityChange = onOpacityChange,
              onFontSizeChange = onFontSizeChange,
              onDensityChange = onDensityChange,
              onDisplayAreaChange = onDisplayAreaChange,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DanmakuSheetHeader(
  enabled: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onDismissRequest: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.player_danmaku_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = stringResource(R.string.player_danmaku_power_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = enabled,
      onCheckedChange = onEnabledChange,
    )
    IconButton(onClick = onDismissRequest) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = stringResource(R.string.player_danmaku_close),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DanmakuSourceColumn(
  state: DanmakuUiState,
  onCandidateSelect: (DanmakuMatchCandidate) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onRefresh: () -> Unit,
  onRematch: () -> Unit,
) {
  StatusCard(state)

  SectionCard(title = stringResource(R.string.player_danmaku_source)) {
    SearchField(
      query = state.searchQuery,
      enabled = state.enabled,
      isSearching = state.isSearching,
      onQueryChange = onSearchQueryChange,
      onSearch = onSearch,
    )

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
        onClick = onRefresh,
        enabled = state.enabled && !state.isRefreshing && state.selectedEpisodeId != null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
      ) {
        if (state.isRefreshing) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
          )
        } else {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
          )
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.player_danmaku_refresh))
      }
      FilledTonalButton(
        onClick = onRematch,
        enabled = state.enabled && state.status != DanmakuUiStatus.Matching,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
      ) {
        Icon(
          imageVector = Icons.Default.RestartAlt,
          contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.player_danmaku_rematch))
      }
    }
  }

  SectionCard(title = stringResource(R.string.player_danmaku_matches)) {
    CandidateList(
      candidates = state.candidates,
      selectedEpisodeId = state.selectedEpisodeId,
      enabled = state.enabled,
      onCandidateSelect = onCandidateSelect,
    )
  }
}

@Composable
private fun DanmakuDisplayColumn(
  state: DanmakuUiState,
  onOffsetChange: (Long) -> Unit,
  onOpacityChange: (Float) -> Unit,
  onFontSizeChange: (Float) -> Unit,
  onDensityChange: (Float) -> Unit,
  onDisplayAreaChange: (Float) -> Unit,
) {
  SectionCard(title = stringResource(R.string.player_danmaku_timing)) {
    SliderControl(
      label = stringResource(R.string.player_danmaku_offset),
      valueText = formatOffset(state.offsetMillis),
      value = state.offsetMillis.coerceIn(-10_000L, 10_000L).toFloat(),
      onValueChange = { onOffsetChange((it / 100f).roundToInt() * 100L) },
      valueRange = -10_000f..10_000f,
      steps = 199,
      enabled = state.enabled,
      action = {
        IconButton(
          onClick = { onOffsetChange(0L) },
          enabled = state.enabled && state.offsetMillis != 0L,
        ) {
          Icon(
            imageVector = Icons.Default.RestartAlt,
            contentDescription = stringResource(R.string.player_danmaku_reset_offset),
          )
        }
      },
    )
    Text(
      text = stringResource(R.string.player_danmaku_offset_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  SectionCard(title = stringResource(R.string.player_danmaku_appearance)) {
    SliderControl(
      label = stringResource(R.string.player_danmaku_opacity),
      valueText = "${(state.opacity.coerceIn(0f, 1f) * 100).roundToInt()}%",
      value = state.opacity.coerceIn(0.1f, 1f),
      onValueChange = onOpacityChange,
      valueRange = 0.1f..1f,
      steps = 17,
      enabled = state.enabled,
    )
    SliderControl(
      label = stringResource(R.string.player_danmaku_font_size),
      valueText = stringResource(R.string.player_danmaku_font_size_value, state.fontSize.roundToInt()),
      value = state.fontSize.coerceIn(14f, 64f),
      onValueChange = { onFontSizeChange(it.roundToInt().toFloat()) },
      valueRange = 14f..64f,
      steps = 49,
      enabled = state.enabled,
    )
    SliderControl(
      label = stringResource(R.string.player_danmaku_density),
      valueText = "${(state.density.coerceIn(0f, 1f) * 100).roundToInt()}%",
      value = state.density.coerceIn(0.1f, 1f),
      onValueChange = onDensityChange,
      valueRange = 0.1f..1f,
      steps = 8,
      enabled = state.enabled,
    )
    SliderControl(
      label = stringResource(R.string.player_danmaku_display_area),
      valueText = "${(state.displayArea.coerceIn(0f, 1f) * 100).roundToInt()}%",
      value = state.displayArea.coerceIn(0.25f, 1f),
      onValueChange = onDisplayAreaChange,
      valueRange = 0.25f..1f,
      steps = 14,
      enabled = state.enabled,
    )
  }
}

@Composable
private fun StatusCard(state: DanmakuUiState) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (state.status == DanmakuUiStatus.Matching || state.status == DanmakuUiStatus.Loading) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
          )
        }
        Text(
          text = statusText(state.status),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        if (state.commentCount > 0) {
          Text(
            text = stringResource(R.string.player_danmaku_comment_count, state.commentCount),
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }

      val mediaTitle = listOfNotNull(state.currentAnimeTitle, state.currentEpisodeTitle).joinToString(" · ")
      if (mediaTitle.isNotBlank()) {
        Text(
          text = mediaTitle,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      state.lastUpdatedAtMillis?.let { updatedAt ->
        Text(
          text =
            stringResource(
              R.string.player_danmaku_last_updated,
              DateUtils.getRelativeTimeSpanString(updatedAt),
            ),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.alpha(0.8f),
        )
      }
      state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
        Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun SearchField(
  query: String,
  enabled: Boolean,
  isSearching: Boolean,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
) {
  val focusManager = LocalFocusManager.current
  val submitSearch = {
    if (enabled && !isSearching && query.isNotBlank()) {
      focusManager.clearFocus()
      onSearch()
    }
  }

  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = Modifier.fillMaxWidth(),
    enabled = enabled,
    singleLine = true,
    label = { Text(stringResource(R.string.player_danmaku_search_label)) },
    placeholder = { Text(stringResource(R.string.player_danmaku_search_hint)) },
    supportingText = { Text(stringResource(R.string.player_danmaku_search_supporting_text)) },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
    trailingIcon = {
      if (isSearching) {
        CircularProgressIndicator(
          modifier = Modifier.size(22.dp),
          strokeWidth = 2.dp,
        )
      } else {
        IconButton(
          onClick = submitSearch,
          enabled = enabled && query.isNotBlank(),
        ) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.player_danmaku_search_action),
          )
        }
      }
    },
  )
}

@Composable
private fun CandidateList(
  candidates: List<DanmakuMatchCandidate>,
  selectedEpisodeId: Long?,
  enabled: Boolean,
  onCandidateSelect: (DanmakuMatchCandidate) -> Unit,
) {
  if (candidates.isEmpty()) {
    Text(
      text = stringResource(R.string.player_danmaku_no_candidates),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }

  Column(
    modifier = Modifier.selectableGroup(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    candidates.forEach { candidate ->
      val selected = candidate.episodeId == selectedEpisodeId
      Surface(
        modifier =
          Modifier
            .fillMaxWidth()
            .selectable(
              selected = selected,
              enabled = enabled,
              role = Role.RadioButton,
              onClick = { onCandidateSelect(candidate) },
            ),
        shape = MaterialTheme.shapes.medium,
        color =
          if (selected) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
          },
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = candidate.animeTitle,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = candidateTitle(candidate),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            CandidateMetadata(candidate)
          }
        }
      }
    }
  }
}

@Composable
private fun CandidateMetadata(candidate: DanmakuMatchCandidate) {
  val metadata =
    buildList {
      candidate.commentCount?.let {
        add(stringResource(R.string.player_danmaku_candidate_comments, it))
      }
      candidate.confidence?.let {
        add(
          stringResource(
            R.string.player_danmaku_candidate_confidence,
            (it.coerceIn(0f, 1f) * 100).roundToInt(),
          ),
        )
      }
    }
  if (metadata.isNotEmpty()) {
    Text(
      text = metadata.joinToString(" · "),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

@Composable
private fun SectionCard(
  title: String,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
      )
      content()
    }
  }
}

@Composable
private fun SliderControl(
  label: String,
  valueText: String,
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int,
  enabled: Boolean,
  action: (@Composable () -> Unit)? = null,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = valueText,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
      )
      if (action != null) {
        Spacer(Modifier.width(4.dp))
        action()
      }
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = valueRange,
      steps = steps,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun statusText(status: DanmakuUiStatus): String =
  stringResource(
    when (status) {
      DanmakuUiStatus.Disabled -> R.string.player_danmaku_status_disabled
      DanmakuUiStatus.Idle -> R.string.player_danmaku_status_idle
      DanmakuUiStatus.Matching -> R.string.player_danmaku_status_matching
      DanmakuUiStatus.Loading -> R.string.player_danmaku_status_loading
      DanmakuUiStatus.Ready -> R.string.player_danmaku_status_ready
      DanmakuUiStatus.NoMatch -> R.string.player_danmaku_status_no_match
      DanmakuUiStatus.OfflineCache -> R.string.player_danmaku_status_offline_cache
      DanmakuUiStatus.Error -> R.string.player_danmaku_status_error
    },
  )

private fun candidateTitle(candidate: DanmakuMatchCandidate): String =
  listOfNotNull(candidate.episodeNumber, candidate.episodeTitle.takeIf { it.isNotBlank() }).joinToString(" · ")

private fun formatOffset(offsetMillis: Long): String {
  val seconds = offsetMillis / 1_000f
  return if (offsetMillis == 0L) "0.0 s" else "%+.1f s".format(seconds)
}
