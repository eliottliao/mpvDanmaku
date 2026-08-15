package app.marlboroadvance.mpvex.domain.danmaku

import android.net.Uri
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchMode
import app.marlboroadvance.mpvex.domain.danmaku.model.MediaFingerprint
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayCommentDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayCommentResponseDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayMatchResponseDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayMatchResultDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayRepository
import app.marlboroadvance.mpvex.repository.dandanplay.DanmakuCacheStore
import app.marlboroadvance.mpvex.repository.dandanplay.MediaFingerprintProvider
import app.marlboroadvance.mpvex.repository.dandanplay.RawCommentDiskCache
import app.marlboroadvance.mpvex.testing.FakeDanmakuDao
import app.marlboroadvance.mpvex.testing.FakeDandanplayTransport
import app.marlboroadvance.mpvex.testing.InMemoryPreferenceStore
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuUiStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Session lifecycle tests for [DanmakuCoordinator].
 *
 * Android-only collaborators are kept at arm's length: [Uri] and the final
 * [MediaFingerprintProvider] are Mockito mocks, while preferences run on an
 * in-memory store so no Context/DataStore is required on the JVM.
 */
class DanmakuCoordinatorTest {
  private val credentialsMissingMessage =
    "This build has no dandanplay AppId/AppSecret configured"

  private fun enabledPreferences(): DanmakuPreferences {
    val preferences = DanmakuPreferences(InMemoryPreferenceStore())
    preferences.enabled.set(true)
    preferences.privacyAccepted.set(true)
    return preferences
  }

  private fun fingerprint(mediaKey: String, fileName: String) = MediaFingerprint(
    mediaKey = mediaKey,
    fileName = fileName,
    fileHash = "0123456789abcdef0123456789abcdef",
    fileSize = 1_000L,
    durationSeconds = 60,
    suggestedMatchMode = DandanplayMatchMode.HASH_AND_FILE_NAME,
  )

  private class GateDispatcher : CoroutineDispatcher(), AutoCloseable {
    data class Gate(
      val entered: CompletableDeferred<Unit> = CompletableDeferred(),
      val release: CompletableDeferred<Unit> = CompletableDeferred(),
      val completed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var nextGate: Gate? = null

    fun gateNextDispatch(): Gate = Gate().also { nextGate = it }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      executor.execute {
        nextGate?.also { gate ->
          nextGate = null
          gate.entered.complete(Unit)
          runBlocking { gate.release.await() }
          block.run()
          gate.completed.complete(Unit)
        } ?: block.run()
      }
    }

    override fun close() {
      executor.shutdownNow()
    }
  }

  @Test
  fun `missing credentials enter the error state without any request`() = runBlocking {
    val fingerprintProvider = mock<MediaFingerprintProvider>()
    val dao = FakeDanmakuDao()
    val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    val coordinator = DanmakuCoordinator(
      repository = null,
      fingerprintProvider = fingerprintProvider,
      bindingRepository = DanmakuBindingRepository(dao),
      preferences = enabledPreferences(),
      scope = scope,
    )

    coordinator.openMedia(mock<Uri>(), "Show.S01E01.mkv", 1400.0)

    val state = coordinator.state.value
    assertEquals(DanmakuUiStatus.Error, state.status)
    assertEquals(credentialsMissingMessage, state.errorMessage)
    assertEquals(0, state.commentCount)
    assertTrue(coordinator.items.value.isEmpty())
    verifyNoInteractions(fingerprintProvider)
    assertTrue("no request may touch the database", dao.bindings.isEmpty())
    scope.cancel()
  }

  @Test
  fun `a superseded session cannot pollute the current state`() {
    val tempDir = Files.createTempDirectory("danmaku-coordinator-test").toFile()
    try {
      runBlocking {
        val dao = FakeDanmakuDao()
        val transport = FakeDandanplayTransport()
        transport.matchGate = CompletableDeferred()
        val firstMatchStarted = CompletableDeferred<Unit>()
        transport.onMatchStart = {
          if (!firstMatchStarted.isCompleted) firstMatchStarted.complete(Unit)
        }
        transport.matchResponse = DandanplayMatchResponseDto(
          success = true,
          isMatched = true,
          matches = listOf(
            DandanplayMatchResultDto(
              episodeId = 42L,
              animeId = 7L,
              animeTitle = "Test Anime",
              episodeTitle = "EP01",
            ),
          ),
        )
        transport.commentsResponse = DandanplayCommentResponseDto(
          count = 1,
          comments = listOf(DandanplayCommentDto(cid = 101L, p = "5.0,1,16777215,1", m = "danmaku")),
        )
        val repository = DandanplayRepository(
          transport = transport,
          commentCache = DanmakuCacheStore(
            diskCache = RawCommentDiskCache(tempDir),
            dao = dao,
          ),
        )

        val fingerprintProvider = mock<MediaFingerprintProvider>()
        val firstFingerprint = fingerprint("media-key-1", "First")
        val secondFingerprint = fingerprint("media-key-2", "Second")
        whenever(
          fingerprintProvider.fingerprint(
            uri = any<Uri>(),
            fallbackFileName = anyOrNull<String>(),
            durationSeconds = any<Double>(),
            allowContentHash = any<Boolean>(),
          ),
        ).thenReturn(firstFingerprint, secondFingerprint)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val coordinator = DanmakuCoordinator(
          repository = repository,
          fingerprintProvider = fingerprintProvider,
          bindingRepository = DanmakuBindingRepository(dao),
          preferences = enabledPreferences(),
          scope = scope,
        )

        // Session 1 starts matching and blocks on the transport gate.
        coordinator.openMedia(mock<Uri>(), "First.mkv", 60.0)
        withTimeout(10_000) {
          firstMatchStarted.await()
          coordinator.state.first { it.status == DanmakuUiStatus.Matching }
        }

        // Session 2 supersedes session 1 while session 1 is still suspended in match().
        coordinator.openMedia(mock<Uri>(), "Second.mkv", 60.0)

        // Both matches now return; only the current generation may proceed.
        transport.matchGate!!.complete(Unit)

        withTimeout(10_000) {
          coordinator.state.first { it.status == DanmakuUiStatus.Ready }
        }

        val state = coordinator.state.value
        assertNull("the cancelled session must not surface an error", state.errorMessage)
        assertEquals(42L, state.selectedEpisodeId)
        assertEquals(1, state.commentCount)
        assertEquals(
          "only the surviving session may persist a binding",
          setOf("media-key-2"),
          dao.bindings.keys,
        )
        assertEquals(listOf(101L), coordinator.items.value.map { it.id })
        scope.cancel()
      }
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `multiple exact candidates require user selection`() {
    val tempDir = Files.createTempDirectory("danmaku-coordinator-test").toFile()
    try {
      runBlocking {
        val dao = FakeDanmakuDao()
        val transport = FakeDandanplayTransport().apply {
          matchResponse = DandanplayMatchResponseDto(
            success = true,
            isMatched = true,
            matches = listOf(
              DandanplayMatchResultDto(42L, 7L, "Test Anime", "EP01"),
              DandanplayMatchResultDto(43L, 7L, "Test Anime", "EP02"),
            ),
          )
        }
        val repository = DandanplayRepository(
          transport = transport,
          commentCache = DanmakuCacheStore(RawCommentDiskCache(tempDir), dao),
        )
        val fingerprintProvider = mock<MediaFingerprintProvider>()
        whenever(
          fingerprintProvider.fingerprint(
            uri = any<Uri>(),
            fallbackFileName = anyOrNull<String>(),
            durationSeconds = any<Double>(),
            allowContentHash = any<Boolean>(),
          ),
        ).thenReturn(fingerprint("media-key", "Show"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val coordinator = DanmakuCoordinator(
          repository,
          fingerprintProvider,
          DanmakuBindingRepository(dao),
          enabledPreferences(),
          scope,
        )

        coordinator.openMedia(mock<Uri>(), "Show.mkv", 60.0)
        val state = withTimeout(10_000) {
          coordinator.state.first { it.status == DanmakuUiStatus.NoMatch }
        }

        assertEquals(listOf(42L, 43L), state.candidates.map { it.episodeId })
        assertTrue(dao.bindings.isEmpty())
        assertEquals(0, transport.commentsCalls.get())
        scope.cancel()
      }
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `clearing media cancels an in-flight item rebuild`() {
    val tempDir = Files.createTempDirectory("danmaku-coordinator-test").toFile()
    GateDispatcher().use { itemDispatcher ->
      try {
        runBlocking {
          val dao = FakeDanmakuDao()
          val transport = FakeDandanplayTransport().apply {
            matchResponse = DandanplayMatchResponseDto(
              success = true,
              isMatched = true,
              matches = listOf(DandanplayMatchResultDto(42L, 7L, "Test Anime", "EP01")),
            )
            commentsResponse = DandanplayCommentResponseDto(
              count = 1,
              comments = listOf(DandanplayCommentDto(101L, "5.0,1,16777215,1", "danmaku")),
            )
          }
          val repository = DandanplayRepository(
            transport = transport,
            commentCache = DanmakuCacheStore(RawCommentDiskCache(tempDir), dao),
          )
          val fingerprintProvider = mock<MediaFingerprintProvider>()
          whenever(
            fingerprintProvider.fingerprint(
              uri = any<Uri>(),
              fallbackFileName = anyOrNull<String>(),
              durationSeconds = any<Double>(),
              allowContentHash = any<Boolean>(),
            ),
          ).thenReturn(fingerprint("media-key", "Show"))
          val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
          val coordinator = DanmakuCoordinator(
            repository,
            fingerprintProvider,
            DanmakuBindingRepository(dao),
            enabledPreferences(),
            scope,
            itemDispatcher,
          )

          coordinator.openMedia(mock<Uri>(), "Show.mkv", 60.0)
          withTimeout(10_000) { coordinator.state.first { it.status == DanmakuUiStatus.Ready } }
          assertEquals(listOf(101L), coordinator.items.value.map { it.id })

          val gate = itemDispatcher.gateNextDispatch()
          coordinator.refresh()
          withTimeout(10_000) { gate.entered.await() }
          coordinator.clearMedia()
          gate.release.complete(Unit)
          withTimeout(10_000) { gate.completed.await() }

          assertTrue(coordinator.items.value.isEmpty())
          scope.cancel()
        }
      } finally {
        tempDir.deleteRecursively()
      }
    }
  }

  @Test
  fun `openMedia clears fingerprint immediately preventing mutation of previous binding`() = runBlocking {
    val tempDir = Files.createTempDirectory("danmaku-fingerprint-test").toFile()
    try {
      val dao = FakeDanmakuDao()
      val transport = FakeDandanplayTransport()
      val repository = DandanplayRepository(
        transport = transport,
        commentCache = DanmakuCacheStore(RawCommentDiskCache(tempDir), dao),
      )
      val fingerprintStarted = CompletableDeferred<Unit>()
      val fingerprintGate = CompletableDeferred<Unit>()
      val fingerprintProvider = mock<MediaFingerprintProvider>()
      whenever(
        fingerprintProvider.fingerprint(
          uri = any<Uri>(),
          fallbackFileName = anyOrNull<String>(),
          durationSeconds = any<Double>(),
          allowContentHash = any<Boolean>(),
        ),
      ).thenAnswer {
        fingerprintStarted.complete(Unit)
        runBlocking { fingerprintGate.await() }
        fingerprint("key-ep2", "Ep2")
      }
      val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      val bindingRepo = DanmakuBindingRepository(dao)
      val coordinator = DanmakuCoordinator(
        repository,
        fingerprintProvider,
        bindingRepo,
        enabledPreferences(),
        scope,
      )

      // Save an existing binding for Ep1
      bindingRepo.save(
        mediaKey = "key-ep1",
        episodeId = 1L,
        animeId = 1L,
        animeTitle = "Anime",
        episodeTitle = "01",
        matchSource = DanmakuMatchSource.MANUAL,
        serverShiftSeconds = 0.0,
        fileHash = null,
        fileSize = 100L,
      )

      // Open new media Ep2 (whose fingerprint calculation is paused on fingerprintGate)
      coordinator.openMedia(mock<Uri>(), "Ep2.mkv", 60.0)
      withTimeout(10_000) {
        fingerprintStarted.await()
      }

      // Adjust offset while Ep2 fingerprint is still computing
      coordinator.setOffset(5000L)

      // Verify Ep1 userOffset was NOT mutated to 5.0
      val ep1Binding = dao.getBinding("key-ep1")
      assertEquals(0.0, ep1Binding!!.userOffsetSeconds, 0.001)

      fingerprintGate.complete(Unit)
      scope.cancel()
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `comments matching blocked words are filtered during loading`() = runBlocking {
    val tempDir = Files.createTempDirectory("danmaku-filter-load-test").toFile()
    try {
      val dao = FakeDanmakuDao()
      val transport = FakeDandanplayTransport().apply {
        matchResponse = DandanplayMatchResponseDto(
          success = true,
          isMatched = true,
          matches = listOf(DandanplayMatchResultDto(42L, 7L, "Test Anime", "EP01")),
        )
        commentsResponse = DandanplayCommentResponseDto(
          count = 2,
          comments = listOf(
            DandanplayCommentDto(101L, "5.0,1,16777215,1", "A SPOILER appears"),
            DandanplayCommentDto(102L, "6.0,1,16777215,1", "safe comment"),
          ),
        )
      }
      val repository = DandanplayRepository(
        transport = transport,
        commentCache = DanmakuCacheStore(RawCommentDiskCache(tempDir), dao),
      )
      val fingerprintProvider = mock<MediaFingerprintProvider>()
      whenever(
        fingerprintProvider.fingerprint(
          uri = any<Uri>(),
          fallbackFileName = anyOrNull<String>(),
          durationSeconds = any<Double>(),
          allowContentHash = any<Boolean>(),
        ),
      ).thenReturn(fingerprint("filter-load", "Show"))
      val preferences = enabledPreferences().apply {
        blockedKeywords.set(setOf("spoiler"))
      }
      val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      val coordinator = DanmakuCoordinator(
        repository,
        fingerprintProvider,
        DanmakuBindingRepository(dao),
        preferences,
        scope,
      )

      coordinator.openMedia(mock<Uri>(), "Show.mkv", 60.0)
      withTimeout(10_000) { coordinator.state.first { it.status == DanmakuUiStatus.Ready } }

      assertEquals(listOf(102L), coordinator.items.value.map { it.id })
      assertEquals(setOf("spoiler"), coordinator.state.value.blockedKeywords)
      scope.cancel()
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `blocked word and regex preference changes refilter loaded comments without fetching`() = runBlocking {
    val tempDir = Files.createTempDirectory("danmaku-filter-reactive-test").toFile()
    try {
      val dao = FakeDanmakuDao()
      val transport = FakeDandanplayTransport().apply {
        matchResponse = DandanplayMatchResponseDto(
          success = true,
          isMatched = true,
          matches = listOf(DandanplayMatchResultDto(42L, 7L, "Test Anime", "EP01")),
        )
        commentsResponse = DandanplayCommentResponseDto(
          count = 2,
          comments = listOf(
            DandanplayCommentDto(101L, "5.0,1,16777215,1", "spoiler99"),
            DandanplayCommentDto(102L, "6.0,1,16777215,1", "safe comment"),
          ),
        )
      }
      val repository = DandanplayRepository(
        transport = transport,
        commentCache = DanmakuCacheStore(RawCommentDiskCache(tempDir), dao),
      )
      val fingerprintProvider = mock<MediaFingerprintProvider>()
      whenever(
        fingerprintProvider.fingerprint(
          uri = any<Uri>(),
          fallbackFileName = anyOrNull<String>(),
          durationSeconds = any<Double>(),
          allowContentHash = any<Boolean>(),
        ),
      ).thenReturn(fingerprint("filter-reactive", "Show"))
      val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      val coordinator = DanmakuCoordinator(
        repository,
        fingerprintProvider,
        DanmakuBindingRepository(dao),
        enabledPreferences(),
        scope,
      )

      coordinator.openMedia(mock<Uri>(), "Show.mkv", 60.0)
      withTimeout(10_000) { coordinator.state.first { it.status == DanmakuUiStatus.Ready } }
      assertEquals(listOf(101L, 102L), coordinator.items.value.map { it.id })

      coordinator.addBlockedKeyword("spoiler")
      withTimeout(10_000) { coordinator.items.first { it.map { item -> item.id } == listOf(102L) } }

      coordinator.removeBlockedKeyword("spoiler")
      withTimeout(10_000) { coordinator.items.first { it.size == 2 } }

      coordinator.addBlockedKeyword("^spoiler\\d+$")
      withTimeout(10_000) {
        coordinator.state.first { "^spoiler\\d+$" in it.blockedKeywords }
      }
      coordinator.setKeywordRegexEnabled(true)
      withTimeout(10_000) { coordinator.items.first { it.map { item -> item.id } == listOf(102L) } }

      assertTrue(coordinator.state.value.keywordRegexEnabled)
      assertEquals(1, transport.commentsCalls.get())
      scope.cancel()
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
