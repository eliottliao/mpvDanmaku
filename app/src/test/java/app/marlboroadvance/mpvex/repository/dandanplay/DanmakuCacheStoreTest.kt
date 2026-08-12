package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import app.marlboroadvance.mpvex.testing.FakeDanmakuDao
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** TTL ladder, LRU eviction, offline grace and freshness behaviour of [DanmakuCacheStore]. */
class DanmakuCacheStoreTest {
  private val hour = 60L * 60L * 1_000L
  private val day = 24L * hour
  private val mib = 1024L * 1024L
  private val baseTime = 1_700_000_000_000L

  private var now = baseTime
  private lateinit var tempDir: File
  private lateinit var dao: FakeDanmakuDao
  private lateinit var store: DanmakuCacheStore

  @Before
  fun setUp() {
    now = baseTime
    tempDir = Files.createTempDirectory("danmaku-cache-store-test").toFile()
    dao = FakeDanmakuDao()
    store = DanmakuCacheStore(
      diskCache = RawCommentDiskCache(tempDir, currentTimeMillis = { now }),
      dao = dao,
      currentTimeMillis = { now },
    )
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private fun query(episodeId: Long) = DandanplayCommentQuery(episodeId = episodeId)

  private fun response(maxCid: Long): DandanplayCommentResponseDto =
    DandanplayCommentResponseDto(
      count = maxCid.toInt(),
      comments = (1L..maxCid).map {
        DandanplayCommentDto(cid = it, p = "$it.0,1,0,$it", m = "comment $it")
      },
    )

  private fun put(episodeId: Long, maxCid: Long) =
    runBlocking { store.put(query(episodeId), response(maxCid)) }

  @Test
  fun `first put uses the default six hour TTL`() {
    val entity = put(1L, 3L)

    assertNotNull(entity)
    assertEquals(0, entity!!.unchangedFetches)
    assertEquals(now + 6 * hour, entity.expiresAt)
    assertEquals(now, entity.fetchedAt)
  }

  @Test
  fun `unchanged refetches climb the TTL ladder and cap at seven days`() {
    put(1L, 3L)

    val second = put(1L, 3L)
    assertEquals(1, second!!.unchangedFetches)
    assertEquals(now + 24 * hour, second.expiresAt)

    val third = put(1L, 3L)
    assertEquals(2, third!!.unchangedFetches)
    assertEquals(now + 3 * day, third.expiresAt)

    val fourth = put(1L, 3L)
    assertEquals(3, fourth!!.unchangedFetches)
    assertEquals(now + 7 * day, fourth.expiresAt)

    val fifth = put(1L, 3L)
    assertEquals(4, fifth!!.unchangedFetches)
    assertEquals("ladder must cap at the last rung", now + 7 * day, fifth.expiresAt)
  }

  @Test
  fun `growing comment library resets the ladder to one hour`() {
    put(1L, 3L)
    put(1L, 3L) // unchanged -> 24h rung

    val grown = put(1L, 5L)
    assertEquals(0, grown!!.unchangedFetches)
    assertEquals(now + hour, grown.expiresAt)
  }

  @Test
  fun `incremental queries are not cacheable`() {
    val result = runBlocking {
      store.put(DandanplayCommentQuery(episodeId = 1L, fromCommentId = 42L), response(5L))
    }
    assertNull(result)
    assertTrue(dao.cacheRows.isEmpty())
  }

  @Test
  fun `get derives freshness from metadata expiresAt`() = runBlocking {
    store.put(query(1L), response(3L))

    val fresh = store.get(query(1L))
    assertTrue(fresh!!.isFresh)

    now += 6 * hour + 1
    val expired = store.get(query(1L))
    assertFalse(expired!!.isFresh)
  }

  @Test
  fun `get without metadata falls back to the default freshness window`() = runBlocking {
    store.put(query(1L), response(3L))
    dao.cacheRows.clear() // simulate lost bookkeeping

    now += 5 * hour
    val stillFresh = store.get(query(1L))
    assertTrue(stillFresh!!.isFresh)
    assertNull(stillFresh.metadata)

    now += 2 * hour // 7h after fetch -> beyond the 6h default window
    val stale = store.get(query(1L))
    assertFalse(stale!!.isFresh)
  }

  @Test
  fun `get touches the metadata to maintain LRU order`() = runBlocking {
    store.put(query(1L), response(3L))
    val before = dao.cacheRows.values.single().lastValidatedAt

    now += 30_000L
    store.get(query(1L))

    assertEquals(now, dao.cacheRows.values.single().lastValidatedAt)
    assertTrue(dao.cacheRows.values.single().lastValidatedAt > before)
  }

  @Test
  fun `cache below the fifty MiB cap keeps every entry`() = runBlocking {
    store.put(query(1L), response(1L))
    store.put(query(2L), response(1L))
    inflate(store.cacheKey(query(1L)), 20 * mib)
    inflate(store.cacheKey(query(2L)), 20 * mib)

    store.put(query(3L), response(1L))

    assertEquals(3, dao.cacheRows.size)
  }

  @Test
  fun `eviction removes least recently validated entries first and never the fresh put`() = runBlocking {
    store.put(query(1L), response(1L))
    now += 1_000L
    store.put(query(2L), response(1L))
    inflate(store.cacheKey(query(1L)), 30 * mib)
    inflate(store.cacheKey(query(2L)), 25 * mib)

    // Touch entry 2 so entry 1 becomes the least recently validated.
    now += 5_000L
    store.get(query(2L))

    now += 1_000L
    val protectedKey = store.cacheKey(query(3L))
    store.put(query(3L), response(1L))

    assertFalse("oldest entry must be evicted", dao.cacheRows.containsKey(store.cacheKey(query(1L))))
    assertTrue("recently validated entry survives", dao.cacheRows.containsKey(store.cacheKey(query(2L))))
    assertTrue("just-written entry is protected", dao.cacheRows.containsKey(protectedKey))
    assertTrue(
      dao.cacheRows.values.sumOf { it.fileSize } <= DanmakuCacheStore.MAX_TOTAL_BYTES,
    )
  }

  @Test
  fun `offline grace window lasts thirty days`() {
    val response = response(1L)
    val inside = StoredCommentEntry(
      response = response,
      metadata = null,
      fetchedAtEpochMillis = now - 29 * day,
      isFresh = false,
    )
    val outside = inside.copy(fetchedAtEpochMillis = now - 31 * day)

    assertTrue(store.isWithinOfflineGrace(inside))
    assertFalse(store.isWithinOfflineGrace(outside))
  }

  @Test
  fun `clearAll removes metadata and reports freed bytes`() = runBlocking {
    store.put(query(1L), response(2L))
    store.put(query(2L), response(2L))

    val freed = store.clearAll()

    assertTrue(freed > 0L)
    assertTrue(dao.cacheRows.isEmpty())
    assertNull(store.get(query(1L)))
  }

  private fun inflate(cacheKey: String, fileSize: Long) {
    val existing = requireNotNull(dao.cacheRows[cacheKey])
    dao.cacheRows[cacheKey] = existing.copy(fileSize = fileSize)
  }
}
