package app.marlboroadvance.mpvex.testing

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayEpisodeSearchQuery
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayCommentResponseDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayMatchRequestDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayMatchResponseDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplaySearchEpisodesResponseDto
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * Scriptable in-memory [DandanplayTransport].
 *
 * [matchGate]/[commentsGate] block the corresponding call until completed, which lets
 * tests observe in-flight state; [onCommentsFetchStart] signals when a comment fetch
 * actually begins so concurrent-merge tests stay deterministic.
 */
class FakeDandanplayTransport : DandanplayTransport {
  var matchResponse: DandanplayMatchResponseDto = DandanplayMatchResponseDto()
  var matchError: Throwable? = null
  var matchGate: CompletableDeferred<Unit>? = null
  var onMatchStart: (() -> Unit)? = null

  var searchResponse: DandanplaySearchEpisodesResponseDto = DandanplaySearchEpisodesResponseDto()

  var commentsResponse: DandanplayCommentResponseDto = DandanplayCommentResponseDto()
  var commentsError: Throwable? = null
  var commentsGate: CompletableDeferred<Unit>? = null
  var onCommentsFetchStart: (() -> Unit)? = null

  val matchCalls = AtomicInteger(0)
  val searchCalls = AtomicInteger(0)
  val commentsCalls = AtomicInteger(0)

  override suspend fun match(request: DandanplayMatchRequestDto): DandanplayMatchResponseDto {
    matchCalls.incrementAndGet()
    onMatchStart?.invoke()
    matchGate?.await()
    matchError?.let { throw it }
    return matchResponse
  }

  override suspend fun searchEpisodes(
    query: DandanplayEpisodeSearchQuery,
  ): DandanplaySearchEpisodesResponseDto {
    searchCalls.incrementAndGet()
    return searchResponse
  }

  override suspend fun getComments(query: DandanplayCommentQuery): DandanplayCommentResponseDto {
    commentsCalls.incrementAndGet()
    onCommentsFetchStart?.invoke()
    commentsGate?.await()
    commentsError?.let { throw it }
    return commentsResponse
  }
}
