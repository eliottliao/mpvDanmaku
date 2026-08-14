package app.marlboroadvance.mpvex.domain.danmaku

sealed interface KeywordFilter {
  fun matches(text: String): Boolean

  data class PlainText(val query: String) : KeywordFilter {
    override fun matches(text: String): Boolean = text.contains(query, ignoreCase = true)
  }

  data class RegexFilter(val regex: Regex) : KeywordFilter {
    override fun matches(text: String): Boolean = regex.containsMatchIn(text)
  }
}

/** Parses and applies user-authored danmaku block rules. */
object DanmakuFilter {
  fun parseRules(
    rules: Iterable<String>,
    regexEnabled: Boolean,
  ): List<KeywordFilter> = rules.mapNotNull { parseRule(it, regexEnabled) }

  fun isBlocked(
    text: String,
    filters: Iterable<KeywordFilter>,
  ): Boolean = filters.any { it.matches(text) }

  fun isValidRule(
    rule: String,
    regexEnabled: Boolean,
  ): Boolean = parseRule(rule, regexEnabled) != null

  private fun parseRule(
    rawRule: String,
    regexEnabled: Boolean,
  ): KeywordFilter? {
    val rule = rawRule.trim()
    if (rule.isEmpty()) return null

    val inlinePattern = when {
      rule.length >= 2 && rule.startsWith('/') && rule.endsWith('/') ->
        rule.substring(1, rule.lastIndex)
      rule.startsWith(REGEX_PREFIX, ignoreCase = true) ->
        rule.substring(REGEX_PREFIX.length).trim()
      else -> null
    }
    val pattern = inlinePattern ?: rule.takeIf { regexEnabled }

    return if (pattern != null) {
      if (pattern.isEmpty()) return null
      runCatching {
        KeywordFilter.RegexFilter(Regex(pattern, RegexOption.IGNORE_CASE))
      }.getOrNull()
    } else {
      KeywordFilter.PlainText(rule)
    }
  }

  private const val REGEX_PREFIX = "regex:"
}
