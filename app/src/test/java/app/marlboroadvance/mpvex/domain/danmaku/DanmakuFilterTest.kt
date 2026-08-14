package app.marlboroadvance.mpvex.domain.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuFilterTest {
  @Test
  fun `plain text rules match exact substrings without case sensitivity`() {
    val filters = DanmakuFilter.parseRules(listOf("Spoiler"), regexEnabled = false)

    assertTrue(DanmakuFilter.isBlocked("spoiler", filters))
    assertTrue(DanmakuFilter.isBlocked("Contains a SPOILER here", filters))
    assertFalse(DanmakuFilter.isBlocked("unrelated", filters))
  }

  @Test
  fun `plain text rules match unicode and Chinese text`() {
    val filters = DanmakuFilter.parseRules(listOf("剧透", "CAFÉ"), regexEnabled = false)

    assertTrue(DanmakuFilter.isBlocked("前方剧透警告", filters))
    assertTrue(DanmakuFilter.isBlocked("café scene", filters))
  }

  @Test
  fun `global regex mode supports wildcards classes and case insensitivity`() {
    val filters = DanmakuFilter.parseRules(
      listOf("^test.*", "episode[0-9]+"),
      regexEnabled = true,
    )

    assertTrue(DanmakuFilter.isBlocked("TEST message", filters))
    assertTrue(DanmakuFilter.isBlocked("Episode42", filters))
    assertFalse(DanmakuFilter.isBlocked("a test message", filters))
  }

  @Test
  fun `inline regex rules work when global regex mode is disabled`() {
    val filters = DanmakuFilter.parseRules(
      listOf("/spoiler\\d+/", "regex:^rawr+$", "plain"),
      regexEnabled = false,
    )

    assertTrue(DanmakuFilter.isBlocked("SPOILER123", filters))
    assertTrue(DanmakuFilter.isBlocked("RAWRRR", filters))
    assertTrue(DanmakuFilter.isBlocked("a plain comment", filters))
  }

  @Test
  fun `malformed regex rules are ignored without affecting valid rules`() {
    val filters = DanmakuFilter.parseRules(
      listOf("[broken", "valid.*"),
      regexEnabled = true,
    )

    assertEquals(1, filters.size)
    assertTrue(DanmakuFilter.isBlocked("VALID rule", filters))
    assertFalse(DanmakuFilter.isBlocked("[broken", filters))
  }

  @Test
  fun `multiple rules block when any rule matches`() {
    val filters = DanmakuFilter.parseRules(
      listOf("first", "second", "/third/"),
      regexEnabled = false,
    )

    assertTrue(DanmakuFilter.isBlocked("the SECOND rule", filters))
    assertTrue(DanmakuFilter.isBlocked("third rule", filters))
    assertFalse(DanmakuFilter.isBlocked("fourth rule", filters))
  }

  @Test
  fun `blank rules and empty inline patterns are ignored`() {
    val filters = DanmakuFilter.parseRules(
      listOf("", "   ", "//", "regex:"),
      regexEnabled = false,
    )

    assertTrue(filters.isEmpty())
    assertFalse(DanmakuFilter.isValidRule("   ", regexEnabled = false))
    assertFalse(DanmakuFilter.isValidRule("/[/", regexEnabled = false))
  }
}
