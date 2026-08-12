package app.marlboroadvance.mpvex.ui.player.danmaku

import kotlin.math.min

/**
 * Allocates physical text rows and retains only the entries which may still collide.
 *
 * Scrolling comments are allowed to share a lane only when their trajectories cannot intersect.
 * A fixed comment reserves its entire lane for its lifetime. Bottom comments prefer lanes from the
 * bottom up; the other modes prefer lanes from the top down.
 */
internal class LaneAllocator(
  private val laneCount: Int,
  private val viewportWidth: Float,
  private val horizontalGap: Float,
) {
  internal data class Entry(
    val startMillis: Long,
    val endMillis: Long,
    val textWidth: Float,
    val pixelsPerMillis: Float,
    val mode: DanmakuMode,
  )

  private val lanes: List<MutableList<Entry>> = List(laneCount) { mutableListOf() }

  fun allocate(entry: Entry): Int? {
    prune(entry.startMillis)

    val indices = when (entry.mode) {
      DanmakuMode.BOTTOM -> (laneCount - 1 downTo 0)
      DanmakuMode.SCROLLING,
      DanmakuMode.TOP,
      -> (0 until laneCount)
    }

    for (laneIndex in indices) {
      if (lanes[laneIndex].all { canCoexist(it, entry) }) {
        lanes[laneIndex].add(entry)
        return laneIndex
      }
    }
    return null
  }

  fun activeCountAt(positionMillis: Long): Int = lanes.sumOf { lane ->
    lane.count { it.startMillis <= positionMillis && positionMillis < it.endMillis }
  }

  private fun prune(positionMillis: Long) {
    lanes.forEach { lane -> lane.removeAll { it.endMillis <= positionMillis } }
  }

  private fun canCoexist(previous: Entry, current: Entry): Boolean {
    if (previous.endMillis <= current.startMillis || current.endMillis <= previous.startMillis) {
      return true
    }
    if (previous.mode != DanmakuMode.SCROLLING || current.mode != DanmakuMode.SCROLLING) {
      return false
    }

    // Entries are offered in media-time order. Separation is linear while both are alive, so
    // checking the two ends of their shared interval is sufficient to prove no catch-up.
    val overlapStart = maxOf(previous.startMillis, current.startMillis)
    val overlapEnd = min(previous.endMillis, current.endMillis)
    return horizontalSeparation(previous, current, overlapStart) >= horizontalGap &&
      horizontalSeparation(previous, current, overlapEnd) >= horizontalGap
  }

  private fun horizontalSeparation(
    previous: Entry,
    current: Entry,
    positionMillis: Long,
  ): Float {
    val previousLeft = viewportWidth -
      previous.pixelsPerMillis * (positionMillis - previous.startMillis)
    val currentLeft = viewportWidth -
      current.pixelsPerMillis * (positionMillis - current.startMillis)
    return currentLeft - (previousLeft + previous.textWidth)
  }
}

