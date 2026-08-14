package app.marlboroadvance.mpvex.ui.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LaneAllocatorTest {
  @Test
  fun `non-overlapping scrolling comments share the first lane`() {
    val allocator = LaneAllocator(laneCount = 5, viewportWidth = 1000f, horizontalGap = 20f)
    val lane1 = allocator.allocate(
      startMillis = 0L,
      endMillis = 5000L,
      textWidth = 100f,
      pixelsPerMillis = 0.22f,
      mode = DanmakuMode.SCROLLING,
    )
    val lane2 = allocator.allocate(
      startMillis = 6000L,
      endMillis = 11000L,
      textWidth = 100f,
      pixelsPerMillis = 0.22f,
      mode = DanmakuMode.SCROLLING,
    )
    assertEquals(0, lane1)
    assertEquals(0, lane2)
  }

  @Test
  fun `overlapping comments that would collide take the next lane`() {
    val allocator = LaneAllocator(laneCount = 5, viewportWidth = 1000f, horizontalGap = 20f)
    val lane1 = allocator.allocate(
      startMillis = 0L,
      endMillis = 5000L,
      textWidth = 200f,
      pixelsPerMillis = 0.24f,
      mode = DanmakuMode.SCROLLING,
    )
    // Starts shortly after lane1 with same speed -> would collide if placed in lane 0
    val lane2 = allocator.allocate(
      startMillis = 100L,
      endMillis = 5100L,
      textWidth = 200f,
      pixelsPerMillis = 0.24f,
      mode = DanmakuMode.SCROLLING,
    )
    assertEquals(0, lane1)
    assertEquals(1, lane2)
  }

  @Test
  fun `bottom comments allocate from the bottom lane upwards`() {
    val allocator = LaneAllocator(laneCount = 4, viewportWidth = 1000f, horizontalGap = 20f)
    val lane1 = allocator.allocate(
      startMillis = 0L,
      endMillis = 4000L,
      textWidth = 300f,
      pixelsPerMillis = 0f,
      mode = DanmakuMode.BOTTOM,
    )
    val lane2 = allocator.allocate(
      startMillis = 500L,
      endMillis = 4500L,
      textWidth = 300f,
      pixelsPerMillis = 0f,
      mode = DanmakuMode.BOTTOM,
    )
    assertEquals(3, lane1)
    assertEquals(2, lane2)
  }

  @Test
  fun `top comments allocate from top lane downwards`() {
    val allocator = LaneAllocator(laneCount = 4, viewportWidth = 1000f, horizontalGap = 20f)
    val lane1 = allocator.allocate(
      startMillis = 0L,
      endMillis = 4000L,
      textWidth = 300f,
      pixelsPerMillis = 0f,
      mode = DanmakuMode.TOP,
    )
    val lane2 = allocator.allocate(
      startMillis = 500L,
      endMillis = 4500L,
      textWidth = 300f,
      pixelsPerMillis = 0f,
      mode = DanmakuMode.TOP,
    )
    assertEquals(0, lane1)
    assertEquals(1, lane2)
  }

  @Test
  fun `returns null when all lanes are full and colliding`() {
    val allocator = LaneAllocator(laneCount = 2, viewportWidth = 1000f, horizontalGap = 20f)
    val lane1 = allocator.allocate(0L, 4000L, 200f, 0f, DanmakuMode.TOP)
    val lane2 = allocator.allocate(0L, 4000L, 200f, 0f, DanmakuMode.TOP)
    val lane3 = allocator.allocate(0L, 4000L, 200f, 0f, DanmakuMode.TOP)

    assertEquals(0, lane1)
    assertEquals(1, lane2)
    assertNull(lane3)
  }

  @Test
  fun `active count reflects pruned expired comments`() {
    val allocator = LaneAllocator(laneCount = 5, viewportWidth = 1000f, horizontalGap = 20f)
    allocator.allocate(0L, 2000L, 100f, 0.22f, DanmakuMode.SCROLLING)
    allocator.allocate(500L, 3000L, 100f, 0.22f, DanmakuMode.SCROLLING)
    allocator.allocate(1000L, 4000L, 100f, 0.22f, DanmakuMode.SCROLLING)

    assertEquals(3, allocator.activeCountAt(1500L))
    // At 2500, the first comment (end=2000) is pruned
    assertEquals(2, allocator.activeCountAt(2500L))
    // At 3500, the second comment (end=3000) is pruned
    assertEquals(1, allocator.activeCountAt(3500L))
    // At 4500, all are pruned
    assertEquals(0, allocator.activeCountAt(4500L))
  }
}
