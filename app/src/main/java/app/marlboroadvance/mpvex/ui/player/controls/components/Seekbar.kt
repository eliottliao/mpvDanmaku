package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.SeekbarStyle
import app.marlboroadvance.mpvex.ui.player.controls.LocalPlayerButtonsClickEvent
import app.marlboroadvance.mpvex.ui.theme.spacing
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SeekbarWithTimers(
  position: Float,
  duration: Float,
  onSeek: (position: Float, isDragging: Boolean) -> Unit,
  timersInverted: Pair<Boolean, Boolean>,
  positionTimerOnClick: () -> Unit,
  durationTimerOnCLick: () -> Unit,
  chapters: ImmutableList<Segment>,
  paused: Boolean,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Wavy,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  modifier: Modifier = Modifier,
) {
  val clickEvent = LocalPlayerButtonsClickEvent.current
  var isUserInteracting by remember { mutableStateOf(false) }
  var userPosition by remember { mutableFloatStateOf(position) }

  val currentDuration by rememberUpdatedState(duration)
  val currentOnSeek by rememberUpdatedState(onSeek)

  val displayPosition = if (isUserInteracting) userPosition else position

  Row(
    modifier = modifier.height(48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    VideoTimer(
      value = displayPosition,
      isInverted = timersInverted.first,
      onClick = {
        clickEvent()
        positionTimerOnClick()
      },
      modifier = Modifier.width(92.dp),
    )

    Box(
      modifier =
        Modifier
          .weight(1f)
          .height(48.dp)
          .pointerInput(Unit) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              val width = size.width
              if (width <= 0) return@awaitEachGesture

              val dur = currentDuration.takeIf { it.isFinite() && it > 0f } ?: 0f
              val downX = down.position.x
              fun positionForX(x: Float): Float {
                if (dur <= 0f) return 0f
                val widthPx = width.toFloat()
                return ((x.coerceIn(0f, widthPx) / widthPx) * dur).coerceIn(0f, dur)
              }

              val initialPos = positionForX(downX)
              userPosition = initialPos
              isUserInteracting = true

              var isDragging = false
              val touchSlop = viewConfiguration.touchSlop
              val pointerId = down.id

              while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (change.changedToUpIgnoreConsumed()) {
                  change.consume()
                  val finalPos = positionForX(change.position.x)
                  userPosition = finalPos
                  isUserInteracting = false
                  currentOnSeek(finalPos, false)
                  break
                }

                if (!change.pressed) {
                  val finalPos = positionForX(change.position.x)
                  userPosition = finalPos
                  isUserInteracting = false
                  currentOnSeek(if (isDragging) finalPos else initialPos, false)
                  break
                }

                val newX = change.position.x
                if (!isDragging) {
                  if (kotlin.math.abs(newX - downX) > touchSlop) {
                    isDragging = true
                    change.consume()
                    val newPos = positionForX(newX)
                    userPosition = newPos
                    currentOnSeek(newPos, true)
                  }
                } else {
                  change.consume()
                  val newPos = positionForX(newX)
                  userPosition = newPos
                  currentOnSeek(newPos, true)
                }
              }
            }
          },
      contentAlignment = Alignment.Center,
    ) {
      when (seekbarStyle) {
        SeekbarStyle.Standard, SeekbarStyle.Thick -> {
          StandardSeekbar(
            position = displayPosition,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isUserInteracting,
            seekbarStyle = seekbarStyle,
            loopStart = loopStart,
            loopEnd = loopEnd,
          )
        }
        SeekbarStyle.Wavy -> {
          SquigglySeekbar(
            position = displayPosition,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isUserInteracting,
            useWavySeekbar = true,
            seekbarStyle = seekbarStyle,
            loopStart = loopStart,
            loopEnd = loopEnd,
          )
        }
      }
    }

    VideoTimer(
      value = if (timersInverted.second) displayPosition - duration else duration,
      isInverted = timersInverted.second,
      onClick = {
        clickEvent()
        durationTimerOnCLick()
      },
      modifier = Modifier.width(92.dp),
    )
  }
}

@Composable
private fun SquigglySeekbar(
  position: Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean,
  isScrubbing: Boolean,
  useWavySeekbar: Boolean,
  seekbarStyle: SeekbarStyle,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

  var phaseOffset by remember { mutableFloatStateOf(0f) }
  var heightFraction by remember { mutableFloatStateOf(1f) }

  val scope = rememberCoroutineScope()

  val waveLength = 80f
  val lineAmplitude = if (useWavySeekbar) 6f else 0f
  val phaseSpeed = 10f
  val transitionPeriods = 1.5f
  val transitionEnabled = true

  LaunchedEffect(isPaused, isScrubbing, useWavySeekbar) {
    if (!useWavySeekbar) {
      heightFraction = 0f
      return@LaunchedEffect
    }

    scope.launch {
      val shouldFlatten = isPaused || isScrubbing
      val targetHeight = if (shouldFlatten) 0f else 1f
      val animationDuration = if (shouldFlatten) 550 else 800
      val startDelay = if (shouldFlatten) 0L else 60L

      delay(startDelay)

      val animator = Animatable(heightFraction)
      animator.animateTo(
        targetValue = targetHeight,
        animationSpec = tween(durationMillis = animationDuration, easing = LinearEasing),
      ) {
        heightFraction = value
      }
    }
  }

  LaunchedEffect(isPaused, useWavySeekbar) {
    if (isPaused || !useWavySeekbar) return@LaunchedEffect

    var lastFrameTime = withFrameMillis { it }
    while (isActive) {
      withFrameMillis { frameTimeMillis ->
        val deltaTime = (frameTimeMillis - lastFrameTime) / 1000f
        phaseOffset += deltaTime * phaseSpeed
        phaseOffset %= waveLength
        lastFrameTime = frameTimeMillis
      }
    }
  }

  Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
    val strokeWidth = 5.dp.toPx()
    val progress = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
    val totalWidth = size.width
    val totalProgressPx = totalWidth * progress
    val centerY = size.height / 2f
    val clipTop = lineAmplitude + strokeWidth

    val path = Path()
    val waveStart = -phaseOffset - waveLength / 2f
    path.moveTo(waveStart, centerY)

    var currentX = waveStart
    var waveSign = 1f
    val dist = waveLength / 2f

    while (currentX < totalWidth + waveLength) {
      waveSign = -waveSign
      val nextX = currentX + dist
      val midX = currentX + dist / 2f
      val amp = waveSign * heightFraction * lineAmplitude
      path.cubicTo(midX, centerY + amp, midX, centerY + amp, nextX, centerY + amp)
      currentX = nextX
    }

    fun drawPathWithGaps(startX: Float, endX: Float, color: Color) {
      if (endX <= startX) return
      clipRect(left = startX, top = centerY - clipTop, right = endX, bottom = centerY + clipTop) {
        drawPath(path = path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
      }
    }

    drawPathWithGaps(0f, totalProgressPx, primaryColor)
    drawPathWithGaps(totalProgressPx, totalWidth, primaryColor.copy(alpha = 0.3f))

    val barWidth = 5.dp.toPx()
    val barHalfHeight = (lineAmplitude + strokeWidth)
    if (barHalfHeight > 0.5f) {
      drawLine(
        color = primaryColor,
        start = Offset(totalProgressPx, centerY - barHalfHeight),
        end = Offset(totalProgressPx, centerY + barHalfHeight),
        strokeWidth = barWidth,
        cap = StrokeCap.Round,
      )
    }

    if (loopStart != null || loopEnd != null) {
      val loopColor = Color(0xFFFFB300)
      if (loopStart != null && duration > 0f) {
        val startPx = (loopStart / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(startPx, centerY - barHalfHeight), Offset(startPx, centerY + barHalfHeight), 2.dp.toPx())
      }
      if (loopEnd != null && duration > 0f) {
        val endPx = (loopEnd / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(endPx, centerY - barHalfHeight), Offset(endPx, centerY + barHalfHeight), 2.dp.toPx())
      }
    }
  }
}

@Composable
fun StandardSeekbar(
  position: Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean = false,
  isScrubbing: Boolean = false,
  useWavySeekbar: Boolean = false,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Standard,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  var heightFraction by remember { mutableFloatStateOf(1f) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(isPaused, isScrubbing) {
    scope.launch {
      val shouldFlatten = isPaused || isScrubbing
      val targetHeight = if (shouldFlatten) 0.7f else 1f
      val animationDuration = if (shouldFlatten) 550 else 800
      val startDelay = if (shouldFlatten) 0L else 60L
      delay(startDelay)
      val animator = Animatable(heightFraction)
      animator.animateTo(targetValue = targetHeight, animationSpec = tween(durationMillis = animationDuration, easing = LinearEasing)) {
        heightFraction = value
      }
    }
  }

  val isThick = seekbarStyle == SeekbarStyle.Thick
  val baseTrackHeight = if (isThick) 16.dp else 8.dp
  val trackHeightDp = baseTrackHeight * heightFraction
  val thumbWidth = 6.dp
  val thumbHeight = if (isThick) 16.dp else 24.dp

  Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
    val totalWidth = size.width
    val totalHeight = size.height
    val playedFraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
    val playedPx = totalWidth * playedFraction

    val trackHeight = trackHeightDp.toPx()
    val trackTop = (totalHeight - trackHeight) / 2f
    val trackBottom = trackTop + trackHeight
    val outerRadius = trackHeight / 2f
    val innerRadius = if (isThick) outerRadius else 2.dp.toPx()

    val thumbTrackGapSize = 14.dp.toPx()
    val gapHalf = thumbTrackGapSize / 2f
    val thumbGapStart = (playedPx - gapHalf).coerceIn(0f, totalWidth)
    val thumbGapEnd = (playedPx + gapHalf).coerceIn(0f, totalWidth)

    fun drawSegment(startX: Float, endX: Float, color: Color) {
      if (endX - startX < 0.5f) return
      val path = Path()
      val isOuterLeft = startX <= 0.5f
      val isInnerLeft = kotlin.math.abs(startX - thumbGapEnd) < 0.5f
      val cornerRadiusLeft = when {
        isOuterLeft -> CornerRadius(outerRadius)
        isInnerLeft -> CornerRadius(innerRadius)
        else -> CornerRadius.Zero
      }
      val isOuterRight = endX >= totalWidth - 0.5f
      val isInnerRight = kotlin.math.abs(endX - thumbGapStart) < 0.5f
      val cornerRadiusRight = when {
        isOuterRight -> CornerRadius(outerRadius)
        isInnerRight -> CornerRadius(innerRadius)
        else -> CornerRadius.Zero
      }
      path.addRoundRect(RoundRect(startX, trackTop, endX, trackBottom, cornerRadiusLeft, cornerRadiusLeft, cornerRadiusRight, cornerRadiusRight))
      drawPath(path, color)
    }

    if (thumbGapEnd < totalWidth) drawSegment(thumbGapEnd, totalWidth, primaryColor.copy(alpha = 0.3f))
    if (thumbGapStart > 0f) drawSegment(0f, thumbGapStart, primaryColor)

    if (loopStart != null || loopEnd != null) {
      val loopColor = Color(0xFFFFB300)
      if (loopStart != null && duration > 0f) {
        val startPx = (loopStart / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(startPx, trackTop), Offset(startPx, trackBottom), 2.dp.toPx())
      }
      if (loopEnd != null && duration > 0f) {
        val endPx = (loopEnd / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(endPx, trackTop), Offset(endPx, trackBottom), 2.dp.toPx())
      }
    }

    val thumbWidthPx = thumbWidth.toPx()
    val thumbHeightPx = thumbHeight.toPx()
    val thumbLeft = (playedPx - thumbWidthPx / 2f).coerceIn(0f, totalWidth - thumbWidthPx)
    val thumbTop = (totalHeight - thumbHeightPx) / 2f
    drawPath(Path().apply { addRoundRect(RoundRect(thumbLeft, thumbTop, thumbLeft + thumbWidthPx, thumbTop + thumbHeightPx, CornerRadius(thumbWidthPx / 2f))) }, primaryColor)
  }
}

@Composable
fun VideoTimer(
  value: Float,
  isInverted: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  val interactionSource = remember { MutableInteractionSource() }
  Text(
    modifier = modifier.fillMaxHeight().clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick).wrapContentHeight(Alignment.CenterVertically),
    text = Utils.prettyTime(value.toInt(), isInverted),
    color = Color.White,
    textAlign = TextAlign.Center,
  )
}

@Preview
@Composable
private fun PreviewSeekBar() {
  SeekbarWithTimers(
    position = 30f,
    duration = 180f,
    onSeek = { _, _ -> },
    timersInverted = Pair(false, true),
    positionTimerOnClick = {},
    durationTimerOnCLick = {},
    chapters = persistentListOf(),
    paused = false,
  )
}
