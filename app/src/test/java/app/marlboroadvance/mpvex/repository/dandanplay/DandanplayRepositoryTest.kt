package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayApiException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentSource
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayEpisodeSearchQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayInvalidQueryException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchMode
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchQuery
import app.marlboroadvance.mpvex.testing.FakeDanmakuDao
import app.marlboroadvance.mpvex.testing.FakeDandanplayTransport
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Public-API behaviour of [DandanplayRepository]: caching, retries of stale data and validation. */
class DandanplayRepositoryTest {
  private val hour = 60L * 60L * 1_000L
  private val day = 24L * hour
  private val baseTime = 1_700_000_000_000L

  private var now = baseTime
  private lateinit var tempDir: File
  private lateinit var transport: FakeDandanplayTransport
  private lateinit var repository: DandanplayRepository

  @Before
  fun setUp() {
    now = baseTime
    tempDir = Files.createTempDirectory("danmaku-repo-test").toFile()
    transport = FakeDandanplayTransport()
    repository = DandanplayRepository(
      transport = transport,
      commentCache = DanmakuCacheStore(
        diskCache = RawCommentDiskCache(tempDir, currentTimeMillis = { now }),
        dao = FakeDanmakuDao(),
        currentTimeMillis = { now },
      ),
    )
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val query = DandanplayCommentQuery(episodeId = 42L)

  private fun commentsResponse(vararg comments: DandanplayCommentDto) =
    DandanplayCommentResponseDto(count = comments.size, comments = comments.toList())

  @Test
  fun `getComments deduplicates repeated cid values`() = runBlocking {
    transport.commentsResponse = commentsResponse(
      DandanplayCommentDto(cid = 5L, p = "1.0,1,0,1", m = "first"),
      DandanplayCommentDto(cid = 5L, p = "2.0,4,255,1", m = "second"),
      DandanplayCommentDto(cid = 6L, p = "3.0,5,255,1", m = "third"),
    )

    val result = repository.getComments(query)

    assertEquals(DandanplayCommentSource.NETWORK, result.source)
    assertEquals(listOf("first", "third"), result.comments.map { it.text })
    assertEquals(1, result.discardedCount)
  }

  @Test
  fun `concurrent fetches for the same episode share one transport call`() = runBlocking {
    transport.commentsResponse = commentsResponse(
      DandanplayCommentDto(cid = 1L, p = "1.0,1,0,1", m = "only"),
    )
    transport.commentsGate = CompletableDeferred()

    val first = async(Dispatchers.Unconfined) { repository.getComments(query) }
    val second = async(Dispatchers.Unconfined) { repository.getComments(query) }
    transport.commentsGate!!.complete(Unit)

    val r1 = withTimeout(10_000) { first.await() }
    val r2 = withTimeout(10_000) { second.await() }

    assertEquals(1, transport.commentsCalls.get())
    assertEquals(r1.comments, r2.comments)
  }

  @Test
  fun `forceRefresh cancels in-flight non-forced fetch and concurrent non-forced joins forced fetch`() = runBlocking {
    transport.commentsResponse = commentsResponse(
      DandanplayCommentDto(cid = 1L, p = "1.0,1,0,1", m = "initial"),
    )
    transport.commentsGate = CompletableDeferred()

    val nonForced = async(Dispatchers.Unconfined) { repository.getComments(query, forceRefresh = false) }

    // Trigger forced refresh while nonForced is in-flight
    val forced = async(Dispatchers.Unconfined) { repository.getComments(query, forceRefresh = true) }

    // Trigger subsequent non-forced caller while forced is in-flight
    val subsequent = async(Dispatchers.Unconfined) { repository.getComments(query, forceRefresh = false) }

    transport.commentsGate!!.complete(Unit)

    val forcedResult = withTimeout(10_000) { forced.await() }
    val subsequentResult = withTimeout(10_000) { subsequent.await() }

    assertEquals(DandanplayCommentSource.NETWORK, forcedResult.source)
    assertEquals(forcedResult, subsequentResult)
  }

  @Test
  fun `fresh cache short-circuits the network`() = runBlocking {
    transport.commentsResponse = commentsResponse(
      DandanplayCommentDto(cid = 1L, p = "1.0,1,0,1", m = "cached"),
    )

    val networkResult = repository.getComments(query)
    assertEquals(DandanplayCommentSource.NETWORK, networkResult.source)

    val cacheResult = repository.getComments(query)
    assertEquals(DandanplayCommentSource.CACHE, cacheResult.source)
    assertEquals(1, transport.commentsCalls.get())
  }

  @Test
  fun `manual refresh inside the cooldown degrades to a cache read`() = runBlocking {
    transport.commentsResponse = commentsResponse(
      DandanplayCommentDto(cid = 1L, p = "1.0,1,0,1", m = "once"),
    )

    val refreshed = repository.getComments(query, forceRefresh = true)
    assertEquals(DandanplayCommentSource.NETWORK, refreshed.source)
    assertEquals(1, transport.commentsCalls.get())

    // Second manual refresh within 60 seconds must not hit the network again.
    val degraded = repository.getComments(query, forceRefresh = true)
    assertEquals(DandanplayCommentSource.CACHE, degraded.source)
    assertEquals(1, transport.commentsCalls.get())
  }

  @Test
  fun `network failure serves the stale cache inside the offline grace window`() = runBlocking {
    transport.commentsResponse = commentsResponse(
      DandanplayCommentDto(cid = 1L, p = "1.0,1,0,1", m = "offline fallback"),
    )
    repository.getComments(query)

    now += 7 * hour // beyond the 6h TTL, well inside the 30 day offline window
    transport.commentsError = IOException("network down")

    val stale = repository.getComments(query)

    assertEquals(DandanplayCommentSource.CACHE, stale.source)
    assertTrue(stale.isStale)
    assertEquals("offline fallback", stale.comments.single().text)
  }

  @Test
  fun `network failure rethrows once the offline grace window expires`() = runBlocking {
    transport.commentsResponse = commentsResponse(
      DandanplayCommentDto(cid = 1L, p = "1.0,1,0,1", m = "old"),
    )
    repository.getComments(query)

    now += 31 * day
    transport.commentsError = IOException("network down")

    assertThrows(IOException::class.java) {
      runBlocking { repository.getComments(query) }
    }
    Unit
  }

  @Test
  fun `match surfaces business errors as DandanplayApiException`() = runBlocking {
    transport.matchResponse = DandanplayMatchResponseDto(
      success = false,
      errorCode = 400,
      errorMessage = "参数错误",
    )

    val error = assertThrows(DandanplayApiException::class.java) {
      runBlocking {
        repository.match(
          DandanplayMatchQuery(
            fileName = "Show.S01E01.mkv",
            matchMode = DandanplayMatchMode.FILE_NAME_ONLY,
          ),
        )
      }
    }
    assertEquals(400, error.errorCode)
    assertTrue(error.message!!.contains("参数错误"))
    assertEquals(1, transport.matchCalls.get())
  }

  @Test
  fun `match validation rejects blank file names and invalid hashes`() {
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking {
        repository.match(DandanplayMatchQuery(fileName = "  ", fileSize = 10L))
      }
    }
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking {
        repository.match(
          DandanplayMatchQuery(
            fileName = "Show.mkv",
            fileHash = "not-an-md5",
            fileSize = 10L,
          ),
        )
      }
    }
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking {
        repository.match(
          DandanplayMatchQuery(fileName = "Show.mkv", fileSize = -1L, matchMode = DandanplayMatchMode.FILE_NAME_ONLY),
        )
      }
    }
    assertEquals("invalid queries must never reach the transport", 0, transport.matchCalls.get())
  }

  @Test
  fun `search validation requires usable queries`() {
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking { repository.searchEpisodes(DandanplayEpisodeSearchQuery(anime = "a")) }
    }
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking { repository.searchEpisodes(DandanplayEpisodeSearchQuery(tmdbId = 0)) }
    }
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking { repository.searchEpisodes(DandanplayEpisodeSearchQuery()) }
    }
    assertEquals(0, transport.searchCalls.get())
  }

  @Test
  fun `comment queries must reference a positive episode id`() {
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking { repository.getComments(DandanplayCommentQuery(episodeId = 0L)) }
    }
    assertThrows(DandanplayInvalidQueryException::class.java) {
      runBlocking { repository.getComments(DandanplayCommentQuery(episodeId = 1L, fromCommentId = -1L)) }
    }
    assertEquals(0, transport.commentsCalls.get())
  }
}
