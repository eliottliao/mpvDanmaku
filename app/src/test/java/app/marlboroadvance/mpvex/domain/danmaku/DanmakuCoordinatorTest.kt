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
import kotlinx.coroutines.CompletableDeferred
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
}
