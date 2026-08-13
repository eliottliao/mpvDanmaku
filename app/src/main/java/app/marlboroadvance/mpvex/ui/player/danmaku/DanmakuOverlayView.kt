package app.marlboroadvance.mpvex.ui.player.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Non-interactive Canvas overlay for renderer-ready danmaku comments.
 *
 * The view never owns playback time. Its position and playback-state providers should point at the
 * same mpv instance as the video. This keeps pause, seek and playback-rate changes synchronized
 * without introducing a second clock.
 */
class DanmakuOverlayView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
  private data class RenderEntry(
    val item: DanmakuItem,
    val lane: Int,
    val startMillis: Long,
    val endMillis: Long,
    val textWidth: Float,
    val pixelsPerMillis: Float,
  )

  private data class Geometry(
    val left: Float,
    val right: Float,
    val top: Float,
    val renderHeight: Float,
    val laneHeight: Float,
    val laneCount: Int,
  ) {
    val width: Float get() = right - left
  }

  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
  private val textWidthCache = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean =
      size > TEXT_WIDTH_CACHE_SIZE
  }

  private var items: List<DanmakuItem> = emptyList()
  private var renderConfig = DanmakuRenderConfig()
  private var positionMillisProvider: () -> Long? = { lastKnownPositionMillis }
  private var isPlayingProvider: () -> Boolean = { false }
  private var lastKnownPositionMillis = 0L
  private var lastRenderedPositionMillis: Long? = null
  private var nextItemIndex = 0
  private var timelineInitialized = false
  private var timelineNeedsRebuild = true
  private var laneAllocator: LaneAllocator? = null
  private val renderEntries = mutableListOf<RenderEntry>()

  private var framePosted = false
  private var delayedWakePosted = false
  private val frameCallback = Choreographer.FrameCallback {
    framePosted = false
    if (shouldAnimate()) invalidate()
  }
  private val delayedWake = Runnable {
    delayedWakePosted = false
    if (shouldAnimate()) invalidate()
  }

  init {
    setWillNotDraw(false)
    isClickable = false
    isLongClickable = false
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    fillPaint.typeface = Typeface.DEFAULT_BOLD
    strokePaint.typeface = Typeface.DEFAULT_BOLD
    strokePaint.style = Paint.Style.STROKE
  }

  /** Replaces the complete media timeline. Input order is not significant. */
  fun setDanmakuItems(items: List<DanmakuItem>) {
    this.items = items
      .asSequence()
      .filter { it.text.isNotBlank() }
      .sortedWith(compareBy<DanmakuItem> { it.timeMillis }.thenBy { it.id })
      .toList()
    resetTimeline(clearMeasurements = false)
    requestRender()
  }

  /** Applies renderer settings immediately and safely handles values from in-progress sliders. */
  fun setRenderConfig(config: DanmakuRenderConfig) {
    val normalized = config.normalized()
    if (normalized == renderConfig) return
    val typographyChanged = normalized.fontSizeSp != renderConfig.fontSizeSp
    renderConfig = normalized
    resetTimeline(clearMeasurements = typographyChanged)
    requestRender()
  }

  /**
   * Installs the mpv-backed media position and playback-state providers.
   *
   * The position provider may return null while the player has no usable time, e.g. across a file
   * or track transition. Null keeps the last known position instead of being read as a seek.
   */
  fun setClockProviders(
    positionMillisProvider: () -> Long?,
    isPlayingProvider: () -> Boolean,
  ) {
    this.positionMillisProvider = positionMillisProvider
    this.isPlayingProvider = isPlayingProvider
    timelineNeedsRebuild = true
    notifyPlaybackStateChanged()
  }

  /**
   * Supplies a position snapshot and acts as the fallback clock value.
   *
   * A discontinuity is recognized on the next draw and rebuilds the active window, making explicit
   * seek callbacks optional when the position provider already reports the new media time.
   */
  fun updatePosition(positionMillis: Long) {
    lastKnownPositionMillis = positionMillis
    requestRender()
  }

  /** Re-evaluates frame scheduling after pause, resume, playback end, or player replacement. */
  fun notifyPlaybackStateChanged() {
    cancelScheduledDraws()
    requestRender()
  }

  /** Drops all comments and active lane state. Clock providers and render settings are retained. */
  fun clear() {
    items = emptyList()
    resetTimeline(clearMeasurements = true)
    cancelScheduledDraws()
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (!renderConfig.enabled || items.isEmpty()) {
      cancelScheduledDraws()
      return
    }

    val geometry = calculateGeometry() ?: return
    preparePaints()
    val positionMillis = readPositionMillis()
    synchronizeTimeline(positionMillis, geometry)

    var visibleCount = 0
    renderEntries.forEach { entry ->
      if (entry.startMillis <= positionMillis && positionMillis < entry.endMillis) {
        drawEntry(canvas, entry, positionMillis, geometry)
        visibleCount++
      }
    }

    lastRenderedPositionMillis = positionMillis
    if (shouldAnimate()) {
      if (visibleCount > 0) scheduleFrame() else scheduleNextWake(positionMillis)
    } else {
      cancelScheduledDraws()
    }
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    if (width != oldWidth || height != oldHeight) resetTimeline(clearMeasurements = false)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestRender()
  }

  override fun onDetachedFromWindow() {
    cancelScheduledDraws()
    super.onDetachedFromWindow()
  }

  override fun onVisibilityChanged(changedView: View, visibility: Int) {
    super.onVisibilityChanged(changedView, visibility)
    if (visibility == VISIBLE) requestRender() else cancelScheduledDraws()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    if (visibility == VISIBLE) requestRender() else cancelScheduledDraws()
  }

  private fun synchronizeTimeline(positionMillis: Long, geometry: Geometry) {
    val previousPosition = lastRenderedPositionMillis
    val discontinuity = previousPosition != null &&
      (positionMillis < previousPosition - BACKWARD_SEEK_TOLERANCE_MS ||
        positionMillis - previousPosition > FORWARD_SEEK_THRESHOLD_MS)

    if (timelineNeedsRebuild || !timelineInitialized || discontinuity) {
      rebuildTimeline(positionMillis, geometry)
      return
    }

    renderEntries.removeAll { it.endMillis <= positionMillis }
    processItemsThrough(positionMillis, geometry)
  }

  private fun rebuildTimeline(positionMillis: Long, geometry: Geometry) {
    renderEntries.clear()
    laneAllocator = LaneAllocator(
      laneCount = geometry.laneCount,
      viewportWidth = geometry.width,
      horizontalGap = dp(renderConfig.laneSpacingDp),
    )

    val lookbackMillis = max(
      MAX_SCROLL_DURATION_MS,
      renderConfig.fixedDurationMillis,
    )
    nextItemIndex = lowerBound(positionMillis - lookbackMillis)
    timelineInitialized = true
    timelineNeedsRebuild = false
    processItemsThrough(positionMillis, geometry)
  }

  private fun processItemsThrough(positionMillis: Long, geometry: Geometry) {
    val allocator = laneAllocator ?: return
    while (nextItemIndex < items.size && items[nextItemIndex].timeMillis <= positionMillis) {
      val item = items[nextItemIndex++]
      if (!isModeVisible(item.mode) || !passesDensitySample(item)) continue

      val textWidth = measureText(item.text)
      val timing = calculateTiming(item, textWidth, geometry.width)
      if (timing.second <= positionMillis && item.timeMillis < positionMillis) continue
      if (allocator.activeCountAt(item.timeMillis) >= renderConfig.maxOnScreen) continue

      val allocationEntry = LaneAllocator.Entry(
        startMillis = item.timeMillis,
        endMillis = timing.second,
        textWidth = textWidth,
        pixelsPerMillis = timing.first,
        mode = item.mode,
      )
      val lane = allocator.allocate(allocationEntry) ?: continue
      renderEntries += RenderEntry(
        item = item,
        lane = lane,
        startMillis = item.timeMillis,
        endMillis = timing.second,
        textWidth = textWidth,
        pixelsPerMillis = timing.first,
      )
    }
  }

  /** Returns pixels/ms and end time. Fixed comments use zero velocity. */
  private fun calculateTiming(
    item: DanmakuItem,
    textWidth: Float,
    viewportWidth: Float,
  ): Pair<Float, Long> {
    if (item.mode != DanmakuMode.SCROLLING) {
      return 0f to saturatedAdd(item.timeMillis, renderConfig.fixedDurationMillis)
    }

    val pixelsPerMillis = dp(BASE_SCROLL_DP_PER_SECOND) * renderConfig.speed / 1_000f
    val unclampedDuration = ((viewportWidth + textWidth) / pixelsPerMillis).toLong()
    val duration = unclampedDuration.coerceIn(MIN_SCROLL_DURATION_MS, MAX_SCROLL_DURATION_MS)
    val actualPixelsPerMillis = (viewportWidth + textWidth) / duration
    return actualPixelsPerMillis to saturatedAdd(item.timeMillis, duration)
  }

  private fun drawEntry(
    canvas: Canvas,
    entry: RenderEntry,
    positionMillis: Long,
    geometry: Geometry,
  ) {
    val baseline = geometry.top + entry.lane * geometry.laneHeight - fillPaint.fontMetrics.top
    val x = when (entry.item.mode) {
      DanmakuMode.SCROLLING -> geometry.right -
        entry.pixelsPerMillis * (positionMillis - entry.startMillis)
      DanmakuMode.BOTTOM,
      DanmakuMode.TOP,
      -> geometry.left + (geometry.width - entry.textWidth) / 2f
    }

    val itemAlpha = Color.alpha(entry.item.colorArgb)
    fillPaint.color = entry.item.colorArgb
    fillPaint.alpha = (itemAlpha * renderConfig.opacity).roundToInt().coerceIn(0, 255)
    strokePaint.color = renderConfig.strokeColorArgb
    strokePaint.alpha = (Color.alpha(renderConfig.strokeColorArgb) * renderConfig.opacity)
      .roundToInt()
      .coerceIn(0, 255)
    if (strokePaint.strokeWidth > 0f && strokePaint.alpha > 0) {
      canvas.drawText(entry.item.text, x, baseline, strokePaint)
    }
    canvas.drawText(entry.item.text, x, baseline, fillPaint)
  }

  private fun preparePaints() {
    val textSize = sp(renderConfig.fontSizeSp)
    fillPaint.textSize = textSize
    fillPaint.style = Paint.Style.FILL
    strokePaint.textSize = textSize
    strokePaint.strokeWidth = dp(renderConfig.strokeWidthDp)
  }

  private fun calculateGeometry(): Geometry? {
    val contentWidth = width - paddingLeft - paddingRight
    val contentHeight = height - paddingTop - paddingBottom
    if (contentWidth <= 0 || contentHeight <= 0) return null

    preparePaints()
    val fontHeight = fillPaint.fontMetrics.run { bottom - top }
    val laneHeight = ceil(fontHeight + dp(renderConfig.laneSpacingDp))
    val renderHeight = contentHeight * renderConfig.displayArea
    val laneCount = (renderHeight / laneHeight).toInt()
    if (laneCount <= 0) return null
    return Geometry(
      left = paddingLeft.toFloat(),
      right = (width - paddingRight).toFloat(),
      top = paddingTop.toFloat(),
      renderHeight = renderHeight,
      laneHeight = laneHeight,
      laneCount = laneCount,
    )
  }

  private fun measureText(text: String): Float = textWidthCache[text]
    ?: fillPaint.measureText(text).also { textWidthCache[text] = it }

  private fun isModeVisible(mode: DanmakuMode): Boolean = when (mode) {
    DanmakuMode.SCROLLING -> renderConfig.showScrolling
    DanmakuMode.BOTTOM -> renderConfig.showBottom
    DanmakuMode.TOP -> renderConfig.showTop
  }

  private fun passesDensitySample(item: DanmakuItem): Boolean {
    if (renderConfig.density >= 1f) return true
    if (renderConfig.density <= 0f) return false
    var hash = item.id xor item.timeMillis.rotateLeft(21) xor item.text.hashCode().toLong()
    hash = (hash xor (hash ushr 30)) * -4658895280553007687L
    hash = (hash xor (hash ushr 27)) * -7723592293110705685L
    hash = hash xor (hash ushr 31)
    val unit = (hash ushr 11).toDouble() / (1L shl 53).toDouble()
    return unit < renderConfig.density
  }

  private fun lowerBound(timeMillis: Long): Int {
    var low = 0
    var high = items.size
    while (low < high) {
      val middle = (low + high) ushr 1
      if (items[middle].timeMillis < timeMillis) low = middle + 1 else high = middle
    }
    return low
  }

  private fun scheduleNextWake(positionMillis: Long) {
    if (delayedWakePosted || framePosted || !shouldAnimate()) return
    val nextTime = items.getOrNull(nextItemIndex)?.timeMillis
    // Past the last comment there is nothing to wait for, but the clock still has to be polled: the
    // position provider is the only seek signal this view gets, so stopping here would leave the
    // overlay blank for the rest of playback after a backward seek.
    val delay = if (nextTime == null) {
      MAX_WAKE_DELAY_MS
    } else {
      (nextTime - positionMillis - WAKE_EARLY_MS).coerceIn(MIN_WAKE_DELAY_MS, MAX_WAKE_DELAY_MS)
    }
    delayedWakePosted = true
    postDelayed(delayedWake, delay)
  }

  private fun requestRender() {
    if (isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE) {
      invalidate()
      if (shouldAnimate()) scheduleFrame()
    }
  }

  private fun scheduleFrame() {
    if (framePosted || !isAttachedToWindow) return
    removeCallbacks(delayedWake)
    delayedWakePosted = false
    framePosted = true
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun cancelScheduledDraws() {
    if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback)
    framePosted = false
    removeCallbacks(delayedWake)
    delayedWakePosted = false
  }

  private fun shouldAnimate(): Boolean = renderConfig.enabled &&
    items.isNotEmpty() &&
    isAttachedToWindow &&
    visibility == VISIBLE &&
    windowVisibility == VISIBLE &&
    runCatching(isPlayingProvider).getOrDefault(false)

  private fun readPositionMillis(): Long =
    (runCatching(positionMillisProvider).getOrNull() ?: lastKnownPositionMillis)
      .also { lastKnownPositionMillis = it }

  private fun resetTimeline(clearMeasurements: Boolean) {
    timelineInitialized = false
    timelineNeedsRebuild = true
    lastRenderedPositionMillis = null
    nextItemIndex = 0
    laneAllocator = null
    renderEntries.clear()
    if (clearMeasurements) textWidthCache.clear()
  }

  private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

  private fun dp(value: Float): Float = value * resources.displayMetrics.density

  private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

  private fun DanmakuRenderConfig.normalized(): DanmakuRenderConfig = copy(
    opacity = opacity.finiteOr(0.85f).coerceIn(0f, 1f),
    fontSizeSp = fontSizeSp.finiteOr(28f).coerceIn(8f, 96f),
    speed = speed.finiteOr(1f).coerceIn(0.25f, 4f),
    density = density.finiteOr(1f).coerceIn(0f, 1f),
    displayArea = displayArea.finiteOr(0.75f).coerceIn(0.1f, 1f),
    maxOnScreen = maxOnScreen.coerceIn(1, 200),
    fixedDurationMillis = fixedDurationMillis.coerceIn(500L, 30_000L),
    strokeWidthDp = strokeWidthDp.finiteOr(2f).coerceIn(0f, 8f),
    laneSpacingDp = laneSpacingDp.finiteOr(4f).coerceIn(0f, 24f),
  )

  private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

  private fun Long.rotateLeft(bitCount: Int): Long =
    (this shl bitCount) or (this ushr (Long.SIZE_BITS - bitCount))

  private companion object {
    const val TEXT_WIDTH_CACHE_SIZE = 2_048
    const val BASE_SCROLL_DP_PER_SECOND = 120f
    const val MIN_SCROLL_DURATION_MS = 2_500L
    const val MAX_SCROLL_DURATION_MS = 30_000L
    const val BACKWARD_SEEK_TOLERANCE_MS = 100L
    const val FORWARD_SEEK_THRESHOLD_MS = 1_500L
    const val WAKE_EARLY_MS = 50L
    const val MIN_WAKE_DELAY_MS = 16L
    const val MAX_WAKE_DELAY_MS = 100L
  }
}

