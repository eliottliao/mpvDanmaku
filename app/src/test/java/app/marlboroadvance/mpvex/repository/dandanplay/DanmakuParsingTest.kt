package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentSource
import app.marlboroadvance.mpvex.domain.danmaku.model.RemoteDanmakuType
import app.marlboroadvance.mpvex.testing.FakeDanmakuDao
import app.marlboroadvance.mpvex.testing.FakeDandanplayTransport
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the dandanplay comment `p` field parsing.
 *
 * `parseComments` is `internal` and therefore visible to the unit test compilation,
 * so the discard/normalize rules are asserted directly; end-to-end coverage through
 * the public `getComments` entry point lives in [DandanplayRepositoryTest].
 */
class DanmakuParsingTest {
  private lateinit var tempDir: File
  private lateinit var repository: DandanplayRepository

  @Before
  fun setUp() {
    tempDir = Files.createTempDirectory("danmaku-parse-test").toFile()
    repository = DandanplayRepository(
      transport = FakeDandanplayTransport(),
      commentCache = DanmakuCacheStore(
        diskCache = RawCommentDiskCache(tempDir),
        dao = FakeDanmakuDao(),
      ),
    )
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private fun parse(
    vararg comments: DandanplayCommentDto,
    count: Int = comments.size,
  ) = repository.parseComments(
    response = DandanplayCommentResponseDto(count = count, comments = comments.toList()),
    source = DandanplayCommentSource.NETWORK,
    isStale = false,
  )

  private fun comment(cid: Long, p: String?, m: String?) =
    DandanplayCommentDto(cid = cid, p = p, m = m)

  @Test
  fun `valid comment is mapped with opaque color and sender id`() {
    val result = parse(comment(10001L, "12.34,1,16777215,10001", "hello"))

    assertEquals(1, result.comments.size)
    assertEquals(0, result.discardedCount)
    val parsed = result.comments.single()
    assertEquals(10001L, parsed.id)
    assertEquals(12.34, parsed.timeSeconds, 1e-9)
    assertEquals(RemoteDanmakuType.SCROLLING, parsed.type)
    // 0xFF000000 or (16777215 and 0xFFFFFF) == opaque white
    assertEquals(-1, parsed.colorArgb)
    assertEquals("hello", parsed.text)
    assertEquals("10001", parsed.senderId)
  }

  @Test
  fun `danmaku modes map to scrolling bottom and top`() {
    val result = parse(
      comment(1L, "1.0,1,0,1", "scrolling"),
      comment(2L, "2.0,4,0,1", "bottom"),
      comment(3L, "3.0,5,0,1", "top"),
    )

    assertEquals(
      listOf(RemoteDanmakuType.SCROLLING, RemoteDanmakuType.BOTTOM, RemoteDanmakuType.TOP),
      result.comments.map { it.type },
    )
  }

  @Test
  fun `unknown danmaku mode is discarded`() {
    val result = parse(comment(1L, "1.0,6,0,1", "unknown mode"))
    assertTrue(result.comments.isEmpty())
    assertEquals(1, result.discardedCount)
  }

  @Test
  fun `missing p segments are discarded`() {
    val result = parse(
      comment(1L, "1.0,1,0", "only three segments"),
      comment(2L, null, "null p"),
      comment(3L, "", "empty p"),
    )
    assertTrue(result.comments.isEmpty())
    assertEquals(3, result.discardedCount)
  }

  @Test
  fun `extra segments are ignored after the first four`() {
    val result = parse(comment(1L, "1.0,1,255,42,trailing,noise", "extra"))

    // The parser splits with limit 5, so trailing fields never invalidate a comment.
    assertEquals(1, result.comments.size)
    assertEquals("42", result.comments.single().senderId)
  }

  @Test
  fun `invalid times are discarded`() {
    val result = parse(
      comment(1L, "NaN,1,0,1", "not a number"),
      comment(2L, "-0.5,1,0,1", "negative time"),
      comment(3L, "Infinity,1,0,1", "infinite time"),
      comment(4L, "abc,1,0,1", "unparseable time"),
    )
    assertTrue(result.comments.isEmpty())
    assertEquals(4, result.discardedCount)
  }

  @Test
  fun `out of range colors are discarded`() {
    val result = parse(
      comment(1L, "1.0,1,16777216,1", "above 0xFFFFFF"),
      comment(2L, "1.0,1,-1,1", "negative color"),
      comment(3L, "1.0,1,abc,1", "non numeric color"),
    )
    assertTrue(result.comments.isEmpty())
    assertEquals(3, result.discardedCount)
  }

  @Test
  fun `color keeps only rgb bits and forces opaque alpha`() {
    val result = parse(comment(1L, "1.0,1,66051,1", "color"))
    // 66051 == 0x010203 -> 0xFF010203
    assertEquals(0xFF010203.toInt(), result.comments.single().colorArgb)
  }

  @Test
  fun `blank or control-only text is discarded after sanitizing`() {
    val result = parse(
      comment(1L, "1.0,1,0,1", ""),
      comment(2L, "1.0,1,0,1", "   "),
      comment(3L, "1.0,1,0,1", "\n\r\t"),
    )
    assertTrue(result.comments.isEmpty())
    assertEquals(3, result.discardedCount)
  }

  @Test
  fun `control characters are stripped and long text truncated`() {
    val longText = "弹".repeat(350)
    val result = parse(comment(1L, "1.0,1,0,1", "a\u0000b\u0007c $longText"))

    val text = result.comments.single().text
    assertTrue(text.startsWith("abc"))
    assertEquals(300, text.codePointCount(0, text.length))
    assertFalse(text.contains('\u0000'))
  }

  @Test
  fun `duplicate cid keeps only the first comment`() {
    val result = parse(
      comment(5L, "1.0,1,0,1", "first"),
      comment(5L, "2.0,4,255,1", "second"),
      comment(6L, "3.0,5,255,1", "other"),
    )

    assertEquals(2, result.comments.size)
    assertEquals("first", result.comments.first().text)
    assertEquals(1, result.discardedCount)
  }

  @Test
  fun `whitespace-only sender id becomes null`() {
    val result = parse(comment(1L, "1.0,1,0,   ", "text"))
    assertNull(result.comments.single().senderId)
  }

  @Test
  fun `unknown json fields do not break decoding`() {
    val json = defaultDandanplayJson()
    val payload = """
      {
        "count": 1,
        "futureField": {"nested": true},
        "comments": [
          {"cid": 9, "p": "1.0,1,0,1", "m": "hi", "extra": [1, 2, 3]}
        ]
      }
    """.trimIndent()

    val decoded = json.decodeFromString(DandanplayCommentResponseDto.serializer(), payload)
    val result = repository.parseComments(decoded, DandanplayCommentSource.NETWORK, false)
    assertEquals(1, result.comments.size)
    assertEquals("hi", result.comments.single().text)
  }

  @Test
  fun `production json configuration ignores unknown keys`() {
    // The shared client configuration must stay lenient for forward compatibility.
    val json: Json = defaultDandanplayJson()
    val decoded = json.decodeFromString(
      DandanplayCommentDto.serializer(),
      """{"cid":1,"p":"1,1,0,1","m":"x","brandNewField":"ignored"}""",
    )
    assertEquals(1L, decoded.cid)
  }
}
