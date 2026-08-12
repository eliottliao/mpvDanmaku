package app.marlboroadvance.mpvex.domain.danmaku

import android.content.ContentResolver
import android.net.Uri
import app.marlboroadvance.mpvex.database.entities.DanmakuMediaBindingEntity
import app.marlboroadvance.mpvex.domain.danmaku.model.ChineseConversion
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentSource
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayEpisodeSearchQuery
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchCandidate
import app.marlboroadvance.mpvex.domain.danmaku.model.MediaFingerprint
import app.marlboroadvance.mpvex.domain.danmaku.model.RemoteDanmakuComment
import app.marlboroadvance.mpvex.domain.danmaku.model.RemoteDanmakuType
import app.marlboroadvance.mpvex.preferences.DanmakuChineseConversion
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayRepository
import app.marlboroadvance.mpvex.repository.dandanplay.MediaFingerprintProvider
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuItem
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuMatchCandidate as UiMatchCandidate
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuMode
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuRenderConfig
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuUiState
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuUiStatus
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** Owns one player's dandanplay matching session and renderer-ready state. */
class DanmakuCoordinator(
  private val repository: DandanplayRepository?,
  private val fingerprintProvider: MediaFingerprintProvider,
  private val bindingRepository: DanmakuBindingRepository,
  private val preferences: DanmakuPreferences,
  private val scope: CoroutineScope,
) : AutoCloseable {
  private data class ActiveMedia(
    val uri: Uri,
    val fileName: String,
    val durationSeconds: Double,
  )

  private data class Selection(
    val episodeId: Long,
    val animeId: Long?,
    val animeTitle: String,
    val episodeTitle: String,
    val serverShiftSeconds: Double,
    val source: DanmakuMatchSource,
  )

  private val _state = MutableStateFlow(initialState())
  val state: StateFlow<DanmakuUiState> = _state.asStateFlow()

  private val _items = MutableStateFlow<List<DanmakuItem>>(emptyList())
  val items: StateFlow<List<DanmakuItem>> = _items.asStateFlow()

  private val _renderConfig = MutableStateFlow(renderConfig())
  val renderConfig: StateFlow<DanmakuRenderConfig> = _renderConfig.asStateFlow()

  private val generation = AtomicLong(0L)
  private var sessionJob: Job? = null
  private var activeMedia: ActiveMedia? = null
  private var fingerprint: MediaFingerprint? = null
  private var selection: Selection? = null
  private var rawComments: List<RemoteDanmakuComment> = emptyList()
  private var selectableEpisodes: Map<Long, Selection> = emptyMap()

  /** anime title + episode of the last search request that actually succeeded. */
  private var lastSearchParams: Pair<String, String?>? = null

  fun openMedia(
    uri: Uri,
    fileName: String,
    durationSeconds: Double,
  ) {
    activeMedia = ActiveMedia(uri, fileName, durationSeconds)
    refreshPreferenceState()
    if (!preferences.enabled.get()) {
      disableForCurrentSession()
      return
    }
    if (!preferences.privacyAccepted.get()) {
      failWithoutRequest(PRIVACY_REQUIRED_MESSAGE)
      return
    }
    val api = repository
    if (api == null) {
      failWithoutRequest(CREDENTIALS_MISSING_MESSAGE)
      return
    }

    launchSession { token ->
      _state.update {
        it.copy(
          enabled = true,
          status = DanmakuUiStatus.Matching,
          candidates = emptyList(),
          commentCount = 0,
          errorMessage = null,
        )
      }
      _items.value = emptyList()
      rawComments = emptyList()
      selection = null
      selectableEpisodes = emptyMap()

      val computed = fingerprintProvider.fingerprint(
        uri = uri,
        fallbackFileName = fileName,
        durationSeconds = durationSeconds,
        allowContentHash = uri.scheme == ContentResolver.SCHEME_CONTENT ||
          uri.scheme == ContentResolver.SCHEME_FILE,
      )
      ensureCurrent(token)
      fingerprint = computed

      val saved = bindingRepository.get(computed.mediaKey)
      ensureCurrent(token)
      if (saved != null) {
        val restored = saved.toSelection()
        selection = restored
        loadComments(api, computed, restored, forceRefresh = false, token = token)
        return@launchSession
      }

      if (!preferences.autoMatch.get()) {
        _state.update { it.copy(status = DanmakuUiStatus.Idle) }
        return@launchSession
      }
      matchCurrentMedia(api, computed, token)
    }
  }

  fun setEnabled(enabled: Boolean) {
    if (!enabled) {
      preferences.enabled.set(false)
      disableForCurrentSession()
      return
    }
    if (!preferences.privacyAccepted.get()) {
      failWithoutRequest(PRIVACY_REQUIRED_MESSAGE)
      return
    }
    preferences.enabled.set(true)
    val media = activeMedia
    if (media == null) {
      refreshPreferenceState()
      _state.update { it.copy(enabled = true, status = DanmakuUiStatus.Idle) }
    } else {
      openMedia(media.uri, media.fileName, media.durationSeconds)
    }
  }

  fun updateSearchQuery(query: String) {
    _state.update { it.copy(searchQuery = query, errorMessage = null) }
  }

  fun search() {
    val api = repository ?: run {
      failWithoutRequest(CREDENTIALS_MISSING_MESSAGE)
      return
    }
    if (!canRequest()) return
    val query = _state.value.searchQuery.trim()
    if (query.length < 2) {
      _state.update { it.copy(errorMessage = "Enter at least two characters") }
      return
    }
    val params = buildSearchParams(query)
    if (params == lastSearchParams && _state.value.candidates.isNotEmpty()) {
      return
    }

    launchSession { token ->
      _state.update { it.copy(isSearching = true, errorMessage = null) }
      val result = api.searchEpisodes(
        DandanplayEpisodeSearchQuery(anime = params.first, episode = params.second),
      )
      ensureCurrent(token)
      lastSearchParams = params
      val selections = LinkedHashMap<Long, Selection>()
      val candidates = buildList {
        for (anime in result.animes) {
          for (episode in anime.episodes) {
            val candidate = Selection(
              episodeId = episode.episodeId,
              animeId = anime.animeId,
              animeTitle = anime.animeTitle,
              episodeTitle = episode.episodeTitle,
              serverShiftSeconds = 0.0,
              source = DanmakuMatchSource.MANUAL,
            )
            selections[candidate.episodeId] = candidate
            add(candidate.toUiCandidate())
          }
        }
      }
      selectableEpisodes = selections
      _state.update {
        it.copy(
          status = if (candidates.isEmpty()) DanmakuUiStatus.NoMatch else DanmakuUiStatus.Idle,
          candidates = candidates,
          isSearching = false,
        )
      }
    }
  }

  /**
   * Derives (anime title, episode) for a manual search query. The title always prefers
   * the user input; the episode falls back to the current file name when the query
   * itself carries no episode information.
   */
  private fun buildSearchParams(query: String): Pair<String, String?> {
    val info = runCatching { MediaInfoParser.parse(query) }.getOrNull()
    val queryEpisode = info?.takeIf { looksLikeMediaFileName(query) }?.episode
    val anime = info?.title?.takeIf { it.isNotBlank() }?.trim() ?: query
    var episode = queryEpisode
    if (episode == null) {
      val fileName = activeMedia?.fileName
      if (!fileName.isNullOrBlank()) {
        episode = runCatching { MediaInfoParser.parse(fileName) }.getOrNull()?.episode
      }
    }
    return anime to episode?.takeIf { it > 0 }?.toString()
  }

  /** Only trust parser-extracted episodes when the query carries episode markers or media noise. */
  private fun looksLikeMediaFileName(query: String): Boolean =
    Regex("""[Ss]\d{1,2}[Ee]\d{1,4}|\b\d{1,2}[xX]\d{1,4}\b|[Ee][Pp]\d+|[Ee]pisode\s*\d+|\.\w{2,4}$""")
      .containsMatchIn(query)

  fun selectCandidate(candidate: UiMatchCandidate) {
    val api = repository ?: run {
      failWithoutRequest(CREDENTIALS_MISSING_MESSAGE)
      return
    }
    if (!canRequest()) return
    val computed = fingerprint ?: run {
      _state.update { it.copy(errorMessage = "The current media has not been fingerprinted") }
      return
    }
    val selected = selectableEpisodes[candidate.episodeId] ?: Selection(
      episodeId = candidate.episodeId,
      animeId = null,
      animeTitle = candidate.animeTitle,
      episodeTitle = candidate.episodeTitle,
      serverShiftSeconds = 0.0,
      source = DanmakuMatchSource.MANUAL,
    )

    launchSession { token ->
      persistAndLoad(api, computed, selected, forceRefresh = false, token = token)
    }
    lastSearchParams = null
  }

  fun refresh() {
    val api = repository ?: run {
      failWithoutRequest(CREDENTIALS_MISSING_MESSAGE)
      return
    }
    if (!canRequest()) return
    val computed = fingerprint ?: return
    val selected = selection ?: return
    launchSession { token ->
      _state.update { it.copy(isRefreshing = true, errorMessage = null) }
      loadComments(api, computed, selected, forceRefresh = true, token = token)
    }
  }

  fun rematch() {
    val api = repository ?: run {
      failWithoutRequest(CREDENTIALS_MISSING_MESSAGE)
      return
    }
    if (!canRequest()) return
    val computed = fingerprint ?: return
    launchSession { token ->
      bindingRepository.remove(computed.mediaKey)
      ensureCurrent(token)
      selection = null
      rawComments = emptyList()
      _items.value = emptyList()
      matchCurrentMedia(api, computed, token)
    }
  }

  fun setOffset(offsetMillis: Long) {
    val clamped = offsetMillis.coerceIn(-600_000L, 600_000L)
    _state.update { it.copy(offsetMillis = clamped) }
    scope.launch { rebuildItems() }
    val mediaKey = fingerprint?.mediaKey ?: return
    scope.launch { bindingRepository.updateOffset(mediaKey, clamped / 1_000.0) }
  }

  fun setOpacity(value: Float) {
    val clamped = value.coerceIn(0.1f, 1f)
    preferences.opacity.set(clamped)
    _state.update { it.copy(opacity = clamped) }
    updateRenderConfig()
  }

  fun setFontSize(value: Float) {
    val clamped = value.coerceIn(12f, 64f)
    preferences.fontSize.set(clamped)
    _state.update { it.copy(fontSize = clamped) }
    updateRenderConfig()
  }

  fun setDensity(value: Float) {
    val clamped = value.coerceIn(0.05f, 1f)
    preferences.density.set(clamped)
    _state.update { it.copy(density = clamped) }
    scope.launch { rebuildItems() }
    updateRenderConfig()
  }

  fun setDisplayArea(value: Float) {
    val clamped = value.coerceIn(0.2f, 1f)
    preferences.displayArea.set(clamped)
    _state.update { it.copy(displayArea = clamped) }
    updateRenderConfig()
  }

  fun clearMedia() {
    activeMedia = null
    fingerprint = null
    selection = null
    rawComments = emptyList()
    selectableEpisodes = emptyMap()
    lastSearchParams = null
    generation.incrementAndGet()
    sessionJob?.cancel()
    sessionJob = null
    _items.value = emptyList()
    _state.value = initialState()
    updateRenderConfig()
  }

  override fun close() {
    clearMedia()
  }

  private suspend fun matchCurrentMedia(
    api: DandanplayRepository,
    computed: MediaFingerprint,
    token: Long,
  ) {
    _state.update {
      it.copy(
        status = DanmakuUiStatus.Matching,
        candidates = emptyList(),
        errorMessage = null,
      )
    }
    val result = api.match(computed.toMatchQuery())
    ensureCurrent(token)
    val selections = result.candidates.associate { candidate ->
      candidate.episodeId to candidate.toSelection(
        if (result.isMatched) exactMatchSource(computed) else DanmakuMatchSource.MANUAL,
      )
    }
    selectableEpisodes = selections
    val candidates = result.candidates.map { candidate ->
      candidate.toUiCandidate(confidence = if (result.isMatched) 1f else null)
    }
    if (result.isMatched && result.candidates.isNotEmpty()) {
      persistAndLoad(api, computed, selections.getValue(result.candidates.first().episodeId), false, token)
    } else {
      _state.update {
        it.copy(
          status = DanmakuUiStatus.NoMatch,
          candidates = candidates,
          isSearching = false,
          isRefreshing = false,
        )
      }
    }
  }

  private suspend fun persistAndLoad(
    api: DandanplayRepository,
    computed: MediaFingerprint,
    selected: Selection,
    forceRefresh: Boolean,
    token: Long,
  ) {
    val saved = bindingRepository.save(
      mediaKey = computed.mediaKey,
      episodeId = selected.episodeId,
      animeId = selected.animeId,
      animeTitle = selected.animeTitle,
      episodeTitle = selected.episodeTitle,
      matchSource = selected.source,
      serverShiftSeconds = selected.serverShiftSeconds,
      fileHash = computed.fileHash,
      fileSize = computed.fileSize,
    )
    ensureCurrent(token)
    selection = selected
    _state.update { it.copy(offsetMillis = (saved.userOffsetSeconds * 1_000.0).roundToLong()) }
    loadComments(api, computed, selected, forceRefresh, token)
  }

  private suspend fun loadComments(
    api: DandanplayRepository,
    computed: MediaFingerprint,
    selected: Selection,
    forceRefresh: Boolean,
    token: Long,
  ) {
    selection = selected
    _state.update {
      it.copy(
        status = DanmakuUiStatus.Loading,
        currentAnimeTitle = selected.animeTitle,
        currentEpisodeTitle = selected.episodeTitle,
        selectedEpisodeId = selected.episodeId,
        isSearching = false,
        errorMessage = null,
      )
    }
    val result = api.getComments(
      query = DandanplayCommentQuery(
        episodeId = selected.episodeId,
        withRelated = preferences.showRelated.get(),
        chineseConversion = preferences.chConvert.get().toDomain(),
      ),
      forceRefresh = forceRefresh,
    )
    ensureCurrent(token)
    rawComments = result.comments
    rebuildItems()
    _state.update {
      it.copy(
        status = if (result.source == DandanplayCommentSource.CACHE && result.isStale) {
          DanmakuUiStatus.OfflineCache
        } else {
          DanmakuUiStatus.Ready
        },
        commentCount = result.comments.size,
        lastUpdatedAtMillis = System.currentTimeMillis(),
        isRefreshing = false,
        errorMessage = null,
      )
    }
  }

  private suspend fun rebuildItems() {
    val selected = selection ?: run {
      _items.value = emptyList()
      return
    }
    val offsetSeconds = _state.value.offsetMillis / 1_000.0
    val density = _state.value.density.coerceIn(0.05f, 1f)
    val blocked = keywordFilters()
    val comments = rawComments
    val rebuilt = withContext(Dispatchers.Default) {
      comments.asSequence()
        .filter { density >= 1f || stableSample(it.id) < density }
        .filterNot { comment -> blocked.any { it.matches(comment.text) } }
        .mapNotNull { comment ->
          val timeMillis = ((comment.timeSeconds + selected.serverShiftSeconds + offsetSeconds) * 1_000.0)
            .roundToLong()
          if (timeMillis < 0L) return@mapNotNull null
          DanmakuItem(
            id = comment.id,
            timeMillis = timeMillis,
            text = comment.text,
            mode = when (comment.type) {
              RemoteDanmakuType.SCROLLING -> DanmakuMode.SCROLLING
              RemoteDanmakuType.BOTTOM -> DanmakuMode.BOTTOM
              RemoteDanmakuType.TOP -> DanmakuMode.TOP
            },
            colorArgb = comment.colorArgb,
          )
        }
        .sortedWith(compareBy<DanmakuItem> { it.timeMillis }.thenBy { it.id })
        .toList()
    }
    _items.value = rebuilt
  }

  private fun keywordFilters(): List<KeywordFilter> {
    val regex = preferences.keywordRegexEnabled.get()
    return preferences.blockedKeywords.get().mapNotNull { raw ->
      val value = raw.trim()
      if (value.isEmpty()) return@mapNotNull null
      if (regex) {
        runCatching { KeywordFilter.RegexFilter(Regex(value, RegexOption.IGNORE_CASE)) }.getOrNull()
      } else {
        KeywordFilter.Text(value.lowercase(Locale.ROOT))
      }
    }
  }

  private sealed interface KeywordFilter {
    fun matches(value: String): Boolean

    data class Text(val value: String) : KeywordFilter {
      override fun matches(value: String): Boolean = value.lowercase(Locale.ROOT).contains(this.value)
    }

    data class RegexFilter(val regex: Regex) : KeywordFilter {
      override fun matches(value: String): Boolean = regex.containsMatchIn(value)
    }
  }

  private fun stableSample(id: Long): Float {
    var value = id
    value = (value xor (value ushr 33)) * -49064778989728563L
    value = (value xor (value ushr 33)) * -4265267296055464877L
    value = value xor (value ushr 33)
    return ((value ushr 40) and 0xFFFFFF).toFloat() / 0x1000000.toFloat()
  }

  private fun launchSession(block: suspend (Long) -> Unit) {
    val token = generation.incrementAndGet()
    sessionJob?.cancel()
    sessionJob = scope.launch {
      try {
        block(token)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (generation.get() != token) return@launch
        _state.update {
          it.copy(
            status = DanmakuUiStatus.Error,
            isSearching = false,
            isRefreshing = false,
            errorMessage = error.message ?: "Unable to load danmaku",
          )
        }
      }
    }
  }

  private suspend fun ensureCurrent(token: Long) {
    currentCoroutineContext().ensureActive()
    if (generation.get() != token) throw CancellationException("Superseded danmaku session")
  }

  private fun canRequest(): Boolean {
    if (!preferences.enabled.get()) {
      disableForCurrentSession()
      return false
    }
    if (!preferences.privacyAccepted.get()) {
      failWithoutRequest(PRIVACY_REQUIRED_MESSAGE)
      return false
    }
    return true
  }

  private fun disableForCurrentSession() {
    generation.incrementAndGet()
    sessionJob?.cancel()
    sessionJob = null
    _items.value = emptyList()
    rawComments = emptyList()
    _state.update {
      it.copy(
        enabled = false,
        status = DanmakuUiStatus.Disabled,
        candidates = emptyList(),
        commentCount = 0,
        isSearching = false,
        isRefreshing = false,
        errorMessage = null,
      )
    }
    updateRenderConfig()
  }

  private fun failWithoutRequest(message: String) {
    generation.incrementAndGet()
    sessionJob?.cancel()
    sessionJob = null
    _items.value = emptyList()
    _state.update {
      it.copy(
        enabled = preferences.enabled.get(),
        status = DanmakuUiStatus.Error,
        isSearching = false,
        isRefreshing = false,
        errorMessage = message,
      )
    }
    updateRenderConfig()
  }

  private fun refreshPreferenceState() {
    _state.update {
      it.copy(
        enabled = preferences.enabled.get(),
        showScrolling = preferences.showScrolling.get(),
        showTop = preferences.showTop.get(),
        showBottom = preferences.showBottom.get(),
        opacity = preferences.opacity.get().coerceIn(0.1f, 1f),
        fontSize = preferences.fontSize.get().coerceIn(12f, 64f),
        speed = preferences.speed.get().coerceIn(0.25f, 3f),
        density = preferences.density.get().coerceIn(0.05f, 1f),
        displayArea = preferences.displayArea.get().coerceIn(0.2f, 1f),
      )
    }
    updateRenderConfig()
  }

  private fun initialState(): DanmakuUiState = DanmakuUiState(
    enabled = preferences.enabled.get(),
    status = if (preferences.enabled.get()) DanmakuUiStatus.Idle else DanmakuUiStatus.Disabled,
    showScrolling = preferences.showScrolling.get(),
    showTop = preferences.showTop.get(),
    showBottom = preferences.showBottom.get(),
    opacity = preferences.opacity.get(),
    fontSize = preferences.fontSize.get(),
    speed = preferences.speed.get(),
    density = preferences.density.get(),
    displayArea = preferences.displayArea.get(),
  )

  private fun renderConfig(): DanmakuRenderConfig = DanmakuRenderConfig(
    enabled = _state.value.enabled,
    showScrolling = _state.value.showScrolling,
    showBottom = _state.value.showBottom,
    showTop = _state.value.showTop,
    opacity = _state.value.opacity,
    fontSizeSp = _state.value.fontSize,
    speed = _state.value.speed,
    density = 1f,
    displayArea = _state.value.displayArea,
    maxOnScreen = preferences.maxOnScreen.get(),
    fixedDurationMillis = (preferences.fixedDurationSeconds.get() * 1_000f).roundToLong(),
    strokeWidthDp = preferences.outlineWidth.get(),
  )

  private fun updateRenderConfig() {
    _renderConfig.value = renderConfig()
  }

  private fun DanmakuMediaBindingEntity.toSelection(): Selection = Selection(
    episodeId = episodeId,
    animeId = animeId,
    animeTitle = animeTitle,
    episodeTitle = episodeTitle,
    serverShiftSeconds = serverShiftSeconds,
    source = runCatching { DanmakuMatchSource.valueOf(matchSource) }.getOrDefault(DanmakuMatchSource.MANUAL),
  ).also {
    _state.update { state -> state.copy(offsetMillis = (userOffsetSeconds * 1_000.0).roundToLong()) }
  }

  private fun DandanplayMatchCandidate.toSelection(source: DanmakuMatchSource): Selection = Selection(
    episodeId = episodeId,
    animeId = animeId,
    animeTitle = animeTitle,
    episodeTitle = episodeTitle,
    serverShiftSeconds = shiftSeconds,
    source = source,
  )

  private fun Selection.toUiCandidate(): UiMatchCandidate = UiMatchCandidate(
    episodeId = episodeId,
    animeTitle = animeTitle,
    episodeTitle = episodeTitle,
  )

  private fun DandanplayMatchCandidate.toUiCandidate(confidence: Float?): UiMatchCandidate =
    UiMatchCandidate(
      episodeId = episodeId,
      animeTitle = animeTitle,
      episodeTitle = episodeTitle,
      confidence = confidence,
    )

  private fun exactMatchSource(computed: MediaFingerprint): DanmakuMatchSource =
    if (computed.fileHash != null) DanmakuMatchSource.HASH_EXACT else DanmakuMatchSource.FILE_NAME

  private fun DanmakuChineseConversion.toDomain(): ChineseConversion = when (this) {
    DanmakuChineseConversion.None -> ChineseConversion.NONE
    DanmakuChineseConversion.Simplified -> ChineseConversion.SIMPLIFIED
    DanmakuChineseConversion.Traditional -> ChineseConversion.TRADITIONAL
  }

  companion object {
    private const val PRIVACY_REQUIRED_MESSAGE =
      "Accept the dandanplay privacy notice in Settings before enabling danmaku"
    private const val CREDENTIALS_MISSING_MESSAGE =
      "This build has no dandanplay AppId/AppSecret configured"
  }
}
