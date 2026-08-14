package app.marlboroadvance.mpvex.ui.player.danmaku

import kotlin.math.min

/**
 * Allocates physical text rows and retains only the entries which may still collide.
 *
 * Scrolling comments are allowed to share a lane only when their trajectories cannot intersect.
 * A fixed comment reserves its entire lane for its lifetime. Bottom comments prefer lanes from the
 * bottom up; the other modes prefer lanes from the top down.
 *
 * This implementation uses flat primitive arrays and an unboxed min-heap to eliminate all object
 * allocations from the lane allocation hot path.
 */
internal class LaneAllocator(
  private val laneCount: Int,
  private val viewportWidth: Float,
  private val horizontalGap: Float,
) {
  private val lastStartMillis = LongArray(laneCount)
  private val lastEndMillis = LongArray(laneCount)
  private val lastTextWidth = FloatArray(laneCount)
  private val lastPixelsPerMillis = FloatArray(laneCount)
  private val lastMode = Array<DanmakuMode?>(laneCount) { null }
  private val activeEndHeap = LongMinHeap(maxOf(32, laneCount * 2))

  fun allocate(
    startMillis: Long,
    endMillis: Long,
    textWidth: Float,
    pixelsPerMillis: Float,
    mode: DanmakuMode,
  ): Int? {
    prune(startMillis)

    if (mode == DanmakuMode.BOTTOM) {
      var laneIndex = laneCount - 1
      while (laneIndex >= 0) {
        if (tryAllocate(laneIndex, startMillis, endMillis, textWidth, pixelsPerMillis, mode)) {
          return laneIndex
        }
        laneIndex--
      }
    } else {
      var laneIndex = 0
      while (laneIndex < laneCount) {
        if (tryAllocate(laneIndex, startMillis, endMillis, textWidth, pixelsPerMillis, mode)) {
          return laneIndex
        }
        laneIndex++
      }
    }
    return null
  }

  fun activeCountAt(positionMillis: Long): Int {
    prune(positionMillis)
    return activeEndHeap.size
  }

  private fun prune(positionMillis: Long) {
    while (activeEndHeap.size > 0 && activeEndHeap.peek()!! <= positionMillis) {
      activeEndHeap.pop()
    }
  }

  private fun tryAllocate(
    laneIndex: Int,
    startMillis: Long,
    endMillis: Long,
    textWidth: Float,
    pixelsPerMillis: Float,
    mode: DanmakuMode,
  ): Boolean {
    if (!canCoexist(laneIndex, startMillis, endMillis, textWidth, pixelsPerMillis, mode)) {
      return false
    }
    lastStartMillis[laneIndex] = startMillis
    lastEndMillis[laneIndex] = endMillis
    lastTextWidth[laneIndex] = textWidth
    lastPixelsPerMillis[laneIndex] = pixelsPerMillis
    lastMode[laneIndex] = mode
    activeEndHeap.push(endMillis)
    return true
  }

  private fun canCoexist(
    laneIndex: Int,
    currentStart: Long,
    currentEnd: Long,
    currentTextWidth: Float,
    currentPixelsPerMillis: Float,
    currentMode: DanmakuMode,
  ): Boolean {
    val prevMode = lastMode[laneIndex] ?: return true
    val prevEnd = lastEndMillis[laneIndex]
    val prevStart = lastStartMillis[laneIndex]

    if (prevEnd <= currentStart || currentEnd <= prevStart) {
      return true
    }
    if (prevMode != DanmakuMode.SCROLLING || currentMode != DanmakuMode.SCROLLING) {
      return false
    }

    // Entries are offered in media-time order. Separation is linear while both are alive, so
    // checking the two ends of their shared interval is sufficient to prove no catch-up.
    val overlapStart = if (prevStart > currentStart) prevStart else currentStart
    val overlapEnd = if (prevEnd < currentEnd) prevEnd else currentEnd
    val prevTextWidth = lastTextWidth[laneIndex]
    val prevPixels = lastPixelsPerMillis[laneIndex]

    return horizontalSeparation(prevStart, prevPixels, prevTextWidth, currentStart, currentPixelsPerMillis, overlapStart) >= horizontalGap &&
      horizontalSeparation(prevStart, prevPixels, prevTextWidth, currentStart, currentPixelsPerMillis, overlapEnd) >= horizontalGap
  }

  private fun horizontalSeparation(
    prevStart: Long,
    prevPixels: Float,
    prevTextWidth: Float,
    currentStart: Long,
    currentPixels: Float,
    positionMillis: Long,
  ): Float {
    val previousLeft = viewportWidth - prevPixels * (positionMillis - prevStart)
    val currentLeft = viewportWidth - currentPixels * (positionMillis - currentStart)
    return currentLeft - (previousLeft + prevTextWidth)
  }

  private class LongMinHeap(initialCapacity: Int = 128) {
    private var array = LongArray(initialCapacity)
    var size: Int = 0
      private set

    fun peek(): Long? = if (size == 0) null else array[0]

    fun push(value: Long) {
      if (size == array.size) {
        array = array.copyOf(array.size * 2)
      }
      var i = size++
      while (i > 0) {
        val parent = (i - 1) ushr 1
        val parentVal = array[parent]
        if (value >= parentVal) break
        array[i] = parentVal
        i = parent
      }
      array[i] = value
    }

    fun pop(): Long {
      if (size == 0) throw NoSuchElementException("Heap is empty")
      val result = array[0]
      val last = array[--size]
      if (size > 0) {
        var i = 0
        val half = size ushr 1
        while (i < half) {
          var child = (i shl 1) + 1
          var childVal = array[child]
          val right = child + 1
          if (right < size && array[right] < childVal) {
            child = right
            childVal = array[right]
          }
          if (last <= childVal) break
          array[i] = childVal
          i = child
        }
        array[i] = last
      }
      return result
    }
  }
}
