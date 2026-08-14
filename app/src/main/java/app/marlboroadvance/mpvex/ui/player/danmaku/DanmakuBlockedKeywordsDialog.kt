package app.marlboroadvance.mpvex.ui.player.danmaku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuFilter

@Composable
fun DanmakuBlockedKeywordsDialog(
  blockedKeywords: Set<String>,
  keywordRegexEnabled: Boolean,
  onAddKeyword: (String) -> Unit,
  onRemoveKeyword: (String) -> Unit,
  onKeywordRegexEnabledChange: (Boolean) -> Unit,
  onDismissRequest: () -> Unit,
) {
  var newKeyword by rememberSaveable { mutableStateOf("") }
  val candidate = newKeyword.trim()
  val isDuplicate = candidate in blockedKeywords
  val isInvalid = candidate.isNotEmpty() &&
    !DanmakuFilter.isValidRule(candidate, keywordRegexEnabled)
  val canAdd = candidate.isNotEmpty() && !isDuplicate && !isInvalid
  val supportingMessage = when {
    isInvalid -> stringResource(R.string.danmaku_invalid_regex)
    isDuplicate -> stringResource(R.string.danmaku_duplicate_blocked_word)
    else -> null
  }
  val addKeyword = {
    if (canAdd) {
      onAddKeyword(candidate)
      newKeyword = ""
    }
  }

  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text(stringResource(R.string.danmaku_blocked_words)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.danmaku_blocked_dialog_description),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .toggleable(
                value = keywordRegexEnabled,
                role = Role.Switch,
                onValueChange = onKeywordRegexEnabledChange,
              )
              .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.danmaku_regex_enabled),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              text = stringResource(R.string.danmaku_regex_enabled_summary),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = keywordRegexEnabled,
            onCheckedChange = null,
          )
        }
        HorizontalDivider()
        if (blockedKeywords.isEmpty()) {
          Text(
            text = stringResource(R.string.danmaku_blocked_empty),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LazyColumn(
            modifier =
              Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
          ) {
            items(
              items = blockedKeywords.sortedWith(String.CASE_INSENSITIVE_ORDER),
              key = { it },
            ) { keyword ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = keyword,
                  modifier = Modifier.weight(1f),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { onRemoveKeyword(keyword) }) {
                  Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(
                      R.string.danmaku_remove_blocked_word,
                      keyword,
                    ),
                    tint = MaterialTheme.colorScheme.error,
                  )
                }
              }
            }
          }
        }
        OutlinedTextField(
          value = newKeyword,
          onValueChange = { newKeyword = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          isError = isInvalid,
          label = {
            Text(
              stringResource(
                if (keywordRegexEnabled) {
                  R.string.danmaku_new_pattern
                } else {
                  R.string.danmaku_new_blocked_word
                },
              ),
            )
          },
          supportingText = if (supportingMessage != null) {
            { Text(supportingMessage) }
          } else {
            null
          },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { addKeyword() }),
          trailingIcon = {
            IconButton(
              onClick = addKeyword,
              enabled = canAdd,
            ) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.danmaku_add_blocked_word),
              )
            }
          },
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismissRequest) {
        Text(stringResource(R.string.generic_close))
      }
    },
  )
}
