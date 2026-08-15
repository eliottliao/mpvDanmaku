package app.marlboroadvance.mpvex.ui.player.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import java.util.LinkedHashMap
import kotlin.math.abs
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
  private class RenderEntry(
    val item: DanmakuItem,
    val lane: Int,
    val startMillis: Long,
    val endMillis: Long,
    val textWidth: Float,
    val pixelsPerMillis: Float,
    var renderNode: RenderNode? = null,
  )

  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
  private val textWidthCache = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean =
      size > TEXT_WIDTH_CACHE_SIZE
  }

  private var items: List<DanmakuItem> = emptyList()
  private var renderConfig = DanmakuRenderConfig()
  private var lastKnownPositionMillis = 0L
  private var clockAnchorPositionMillis = 0L
  private var clockAnchorUptimeMillis = 0L
  private var clockSpeed = 1f
  private var clockIsPlaying = false
  private var clockInitialized = false
  private var lastRenderedPositionMillis: Long? = null
  private var nextItemIndex = 0
  private var timelineInitialized = false
  private var timelineNeedsRebuild = true
  private var laneAllocator: LaneAllocator? = null
  private val renderEntries = mutableListOf<RenderEntry>()
  private var timelineCatchUpPending = false

  private var cachedDensity = resources.displayMetrics.density
  private var cachedScaledDensity = resources.displayMetrics.scaledDensity
  private var cachedTextSize = Float.NaN
  private var fontTop = 0f
  private var fontHeight = 0f

  // Cached layout geometry and precomputed per-lane text baselines
  private var geoLeft = 0f
  private var geoRight = 0f
  private var geoTop = 0f
  private var geoWidth = 0f
  private var geoRenderHeight = 0f
  private var geoLaneHeight = 0f
  private var geoLaneCount = 0
  private var laneBaselines = FloatArray(0)
  private var bottomLaneBaselines = FloatArray(0)
  private var hasValidGeometry = false

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
    preparePaints()
  }

  /** Replaces the complete media timeline. Input order is not significant. */
  fun setDanmakuItems(items: List<DanmakuItem>) {
    this.items = items
    resetTimeline(clearMeasurements = false)
    requestRender()
  }

  /** Applies renderer settings immediately and safely handles values from in-progress sliders. */
  fun setRenderConfig(config: DanmakuRenderConfig) {
    val normalized = config.normalized()
    if (normalized == renderConfig) return
    val oldConfig = renderConfig
    val layoutChanged = normalized.enabled != oldConfig.enabled ||
      normalized.showScrolling != oldConfig.showScrolling ||
      normalized.showBottom != oldConfig.showBottom ||
      normalized.showTop != oldConfig.showTop ||
      normalized.fontSizeSp != oldConfig.fontSizeSp ||
      normalized.speed != oldConfig.speed ||
      normalized.density != oldConfig.density ||
      normalized.displayArea != oldConfig.displayArea ||
      normalized.maxOnScreen != oldConfig.maxOnScreen ||
      normalized.fixedDurationMillis != oldConfig.fixedDurationMillis ||
      normalized.laneSpacingDp != oldConfig.laneSpacingDp ||
      normalized.timeOffsetMillis != oldConfig.timeOffsetMillis ||
      normalized.strokeWidthDp != oldConfig.strokeWidthDp ||
      normalized.strokeColorArgb != oldConfig.strokeColorArgb
    renderConfig = normalized
    preparePaints()
    updateGeometry()
    if (layoutChanged) {
      resetTimeline(clearMeasurements = normalized.fontSizeSp != oldConfig.fontSizeSp)
    }
    requestRender()
  }

  /** Supplies a sampled player clock; no mpv calls are made from the draw path. */
  fun updateClock(positionMillis: Long?, isPlaying: Boolean, playbackSpeed: Float = 1f) {
    val now = SystemClock.uptimeMillis()
    val speed = playbackSpeed.coerceIn(0.05f, 8f)
    val projected = if (clockInitialized && clockIsPlaying) {
      clockAnchorPositionMillis + ((now - clockAnchorUptimeMillis) * clockSpeed).toLong()
    } else {
      clockAnchorPositionMillis
    }
    if (positionMillis != null) {
      clockAnchorPositionMillis = positionMillis
      clockAnchorUptimeMillis = now
      clockInitialized = true
    } else if (!clockInitialized || isPlaying != clockIsPlaying) {
      clockAnchorPositionMillis = projected
      clockAnchorUptimeMillis = now
      clockInitialized = true
    }
    clockSpeed = speed
    clockIsPlaying = isPlaying
    lastKnownPositionMillis = if (isPlaying) {
      clockAnchorPositionMillis + ((now - clockAnchorUptimeMillis) * speed).toLong()
    } else {
      clockAnchorPositionMillis
    }
    requestRender()
  }

  /**
   * Supplies a position snapshot and acts as the fallback clock value.
   *
   * A discontinuity is recognized on the next draw and rebuilds the active window, making explicit
   * seek callbacks optional when the position provider already reports the new media time.
   */
  fun updatePosition(positionMillis: Long) {
    updateClock(positionMillis, clockIsPlaying, clockSpeed)
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

    if (!hasValidGeometry && !updateGeometry()) return
    val positionMillis = readPositionMillis()
    val effectivePosition = effectivePositionMillis(positionMillis)
    synchronizeTimeline(effectivePosition)

    var visibleCount = 0
    var index = 0
    val size = renderEntries.size
    while (index < size) {
      val entry = renderEntries[index]
      if (entry.startMillis <= effectivePosition && effectivePosition < entry.endMillis) {
        drawEntry(canvas, entry, effectivePosition)
        visibleCount++
      }
      index++
    }

    lastRenderedPositionMillis = effectivePosition
    if (shouldAnimate()) {
      if (timelineCatchUpPending || visibleCount > 0) scheduleFrame() else scheduleNextWake(positionMillis)
    } else {
      cancelScheduledDraws()
    }
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    updateGeometry()
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

  private fun synchronizeTimeline(effectivePosition: Long) {
    val previousPosition = lastRenderedPositionMillis
    val discontinuity = previousPosition != null &&
      (effectivePosition < previousPosition - BACKWARD_SEEK_TOLERANCE_MS ||
        effectivePosition - previousPosition > FORWARD_SEEK_THRESHOLD_MS)

    if (timelineNeedsRebuild || !timelineInitialized || discontinuity) {
      rebuildTimeline(effectivePosition)
      return
    }

    compactExpiredEntries(effectivePosition)
    timelineCatchUpPending = processItemsThrough(effectivePosition)
  }

  private fun rebuildTimeline(effectivePosition: Long) {
    for (i in renderEntries.indices) {
      discardRenderNode(renderEntries[i])
    }
    renderEntries.clear()
    laneAllocator = LaneAllocator(
      laneCount = geoLaneCount,
      viewportWidth = geoWidth,
      horizontalGap = dp(renderConfig.laneSpacingDp),
    )

    val lookbackMillis = max(
      MAX_SCROLL_DURATION_MS,
      renderConfig.fixedDurationMillis,
    )
    nextItemIndex = lowerBound(effectivePosition - lookbackMillis)
    timelineInitialized = true
    timelineNeedsRebuild = false
    timelineCatchUpPending = processItemsThrough(effectivePosition)
  }

  private fun processItemsThrough(effectivePosition: Long): Boolean {
    val allocator = laneAllocator ?: return false
    var processed = 0
    while (
      nextItemIndex < items.size &&
      items[nextItemIndex].timeMillis <= effectivePosition &&
      processed++ < MAX_ITEMS_PROCESSED_PER_DRAW
    ) {
      val item = items[nextItemIndex++]
      if (!isModeVisible(item.mode)) continue
      if (!passesDensitySample(item)) continue

      val textWidth = measureText(item.text)
      val startMillis = item.timeMillis
      calculateTiming(item, textWidth, geoWidth, startMillis)
      if (timingEndMillis <= effectivePosition && startMillis < effectivePosition) continue
      if (allocator.activeCountAt(startMillis) >= renderConfig.maxOnScreen) continue

      val lane = allocator.allocate(
        startMillis = startMillis,
        endMillis = timingEndMillis,
        textWidth = textWidth,
        pixelsPerMillis = timingPixelsPerMillis,
        mode = item.mode,
      ) ?: continue

      val renderNode = createRenderNodeIfNeeded(item, textWidth)
      renderEntries += RenderEntry(
        item = item,
        lane = lane,
        startMillis = startMillis,
        endMillis = timingEndMillis,
        textWidth = textWidth,
        pixelsPerMillis = timingPixelsPerMillis,
        renderNode = renderNode,
      )
    }
    return nextItemIndex < items.size && items[nextItemIndex].timeMillis <= effectivePosition
  }

  private var timingPixelsPerMillis = 0f
  private var timingEndMillis = 0L

  /** Writes pixels/ms and end time into scratch fields. Fixed comments use zero velocity. */
  private fun calculateTiming(
    item: DanmakuItem,
    textWidth: Float,
    viewportWidth: Float,
    startMillis: Long,
  ) {
    if (item.mode != DanmakuMode.SCROLLING) {
      timingPixelsPerMillis = 0f
      timingEndMillis = saturatedAdd(startMillis, renderConfig.fixedDurationMillis)
      return
    }

    val pixelsPerMillis = dp(BASE_SCROLL_DP_PER_SECOND) * renderConfig.speed / 1_000f
    val unclampedDuration = ((viewportWidth + textWidth) / pixelsPerMillis).toLong()
    val duration = unclampedDuration.coerceIn(MIN_SCROLL_DURATION_MS, MAX_SCROLL_DURATION_MS)
    timingPixelsPerMillis = (viewportWidth + textWidth) / duration
    timingEndMillis = saturatedAdd(startMillis, duration)
  }

  private fun drawEntry(
    canvas: Canvas,
    entry: RenderEntry,
    effectivePosition: Long,
  ) {
    val baseline = if (entry.item.mode == DanmakuMode.BOTTOM) {
      if (entry.lane in bottomLaneBaselines.indices) {
        bottomLaneBaselines[entry.lane]
      } else {
        (height - paddingBottom).toFloat() - (geoLaneCount - entry.lane) * geoLaneHeight - fontTop
      }
    } else {
      if (entry.lane in laneBaselines.indices) {
        laneBaselines[entry.lane]
      } else {
        geoTop + entry.lane * geoLaneHeight - fontTop
      }
    }
    val x = when (entry.item.mode) {
      DanmakuMode.SCROLLING -> geoRight -
        entry.pixelsPerMillis * (effectivePosition - entry.startMillis)
      DanmakuMode.BOTTOM,
      DanmakuMode.TOP,
      -> geoLeft + (geoWidth - entry.textWidth) / 2f
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated && entry.renderNode != null) {
      val node = entry.renderNode!!
      node.alpha = renderConfig.opacity
      node.translationX = x - RENDER_NODE_PADDING
      node.translationY = baseline + fontTop - RENDER_NODE_PADDING
      canvas.drawRenderNode(node)
    } else {
      val itemAlpha = Color.alpha(entry.item.colorArgb)
      fillPaint.color = entry.item.colorArgb
      fillPaint.alpha = (itemAlpha * renderConfig.opacity).roundToInt().coerceIn(0, 255)
      if (strokePaint.strokeWidth > 0f && strokePaint.alpha > 0) {
        canvas.drawText(entry.item.text, x, baseline, strokePaint)
      }
      canvas.drawText(entry.item.text, x, baseline, fillPaint)
    }
  }

  private fun createRenderNodeIfNeeded(item: DanmakuItem, textWidth: Float): RenderNode? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
      val node = RenderNode("Danmaku_${item.id}")
      val padding = RENDER_NODE_PADDING
      val w = ceil(textWidth + padding * 2f).toInt().coerceAtLeast(1)
      val h = ceil(fontHeight + padding * 2f).toInt().coerceAtLeast(1)
      node.setPosition(0, 0, w, h)
      val recordingCanvas = node.beginRecording()
      val drawX = padding.toFloat()
      val drawY = -fontTop + padding.toFloat()
      val itemAlpha = Color.alpha(item.colorArgb)
      val strokeBaseAlpha = Color.alpha(renderConfig.strokeColorArgb)
      fillPaint.color = item.colorArgb
      fillPaint.alpha = itemAlpha
      strokePaint.alpha = strokeBaseAlpha
      if (strokePaint.strokeWidth > 0f && strokeBaseAlpha > 0) {
        recordingCanvas.drawText(item.text, drawX, drawY, strokePaint)
      }
      recordingCanvas.drawText(item.text, drawX, drawY, fillPaint)
      node.endRecording()
      node.alpha = renderConfig.opacity
      strokePaint.alpha = (strokeBaseAlpha * renderConfig.opacity).roundToInt().coerceIn(0, 255)
      node
    }.getOrNull()
  }

  private fun discardRenderNode(entry: RenderEntry) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      entry.renderNode?.discardDisplayList()
      entry.renderNode = null
    }
  }

  private fun preparePaints() {
    val textSize = sp(renderConfig.fontSizeSp)
    val textChanged = textSize != cachedTextSize
    val strokeWidth = dp(renderConfig.strokeWidthDp)
    val strokeChanged = strokePaint.strokeWidth != strokeWidth
    if (textChanged) {
      cachedTextSize = textSize
      fillPaint.textSize = textSize
      strokePaint.textSize = textSize
      fillPaint.fontMetrics.let {
        fontTop = it.top
        fontHeight = it.bottom - it.top
      }
    }
    fillPaint.textSize = textSize
    fillPaint.style = Paint.Style.FILL
    if (strokeChanged) strokePaint.strokeWidth = strokeWidth
    strokePaint.color = renderConfig.strokeColorArgb
    strokePaint.alpha = (Color.alpha(renderConfig.strokeColorArgb) * renderConfig.opacity)
      .roundToInt()
      .coerceIn(0, 255)
  }

  private fun updateGeometry(): Boolean {
    val contentWidth = width - paddingLeft - paddingRight
    val contentHeight = height - paddingTop - paddingBottom
    if (contentWidth <= 0 || contentHeight <= 0) {
      hasValidGeometry = false
      return false
    }

    val laneHeight = ceil(fontHeight + dp(renderConfig.laneSpacingDp))
    val renderHeight = contentHeight * renderConfig.displayArea
    val laneCount = (renderHeight / laneHeight).toInt()
    if (laneCount <= 0) {
      hasValidGeometry = false
      return false
    }

    geoLeft = paddingLeft.toFloat()
    geoRight = (width - paddingRight).toFloat()
    geoTop = paddingTop.toFloat()
    geoWidth = contentWidth.toFloat()
    geoRenderHeight = renderHeight
    geoLaneHeight = laneHeight
    geoLaneCount = laneCount

    if (laneBaselines.size != laneCount) {
      laneBaselines = FloatArray(laneCount)
      bottomLaneBaselines = FloatArray(laneCount)
    }
    val contentBottom = (height - paddingBottom).toFloat()
    var lane = 0
    while (lane < laneCount) {
      laneBaselines[lane] = geoTop + lane * geoLaneHeight - fontTop
      bottomLaneBaselines[lane] = contentBottom - (laneCount - lane) * geoLaneHeight - fontTop
      lane++
    }
    hasValidGeometry = true
    return true
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
    val effectivePosition = effectivePositionMillis(positionMillis)
    val nextTime = items.getOrNull(nextItemIndex)?.timeMillis
    val delay = if (nextTime == null) {
      MAX_WAKE_DELAY_MS
    } else {
      (nextTime - effectivePosition - WAKE_EARLY_MS).coerceIn(MIN_WAKE_DELAY_MS, MAX_WAKE_DELAY_MS)
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
    clockIsPlaying

  private fun readPositionMillis(): Long {
    if (!clockInitialized || !clockIsPlaying) return lastKnownPositionMillis
    val elapsed = SystemClock.uptimeMillis() - clockAnchorUptimeMillis
    return (clockAnchorPositionMillis + (elapsed * clockSpeed).toLong())
      .also { lastKnownPositionMillis = it }
  }

  private fun compactExpiredEntries(effectivePosition: Long) {
    var writeIndex = 0
    var readIndex = 0
    while (readIndex < renderEntries.size) {
      val entry = renderEntries[readIndex]
      if (entry.endMillis > effectivePosition) {
        renderEntries[writeIndex++] = entry
      } else {
        discardRenderNode(entry)
      }
      readIndex++
    }
    while (renderEntries.size > writeIndex) {
      discardRenderNode(renderEntries.removeAt(renderEntries.lastIndex))
    }
  }

  private fun effectivePositionMillis(positionMillis: Long): Long =
    positionMillis - renderConfig.timeOffsetMillis

  private fun resetTimeline(clearMeasurements: Boolean) {
    timelineInitialized = false
    timelineNeedsRebuild = true
    lastRenderedPositionMillis = null
    nextItemIndex = 0
    laneAllocator = null
    for (i in renderEntries.indices) {
      discardRenderNode(renderEntries[i])
    }
    renderEntries.clear()
    if (clearMeasurements) textWidthCache.clear()
  }

  private fun sp(value: Float): Float = value * cachedScaledDensity

  private fun dp(value: Float): Float = value * cachedDensity

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
    timeOffsetMillis = timeOffsetMillis.coerceIn(-600_000L, 600_000L),
  )

  private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

  private fun Long.rotateLeft(bitCount: Int): Long =
    (this shl bitCount) or (this ushr (Long.SIZE_BITS - bitCount))

  private companion object {
    const val RENDER_NODE_PADDING = 4
    const val TEXT_WIDTH_CACHE_SIZE = 2_048
    const val BASE_SCROLL_DP_PER_SECOND = 120f
    const val MIN_SCROLL_DURATION_MS = 2_500L
    const val MAX_SCROLL_DURATION_MS = 30_000L
    const val BACKWARD_SEEK_TOLERANCE_MS = 100L
    const val FORWARD_SEEK_THRESHOLD_MS = 1_500L
    const val WAKE_EARLY_MS = 50L
    const val MIN_WAKE_DELAY_MS = 16L
    const val MAX_WAKE_DELAY_MS = 100L
    const val MAX_ITEMS_PROCESSED_PER_DRAW = 512
    const val CLOCK_REANCHOR_THRESHOLD_MS = 250L
  }
}
