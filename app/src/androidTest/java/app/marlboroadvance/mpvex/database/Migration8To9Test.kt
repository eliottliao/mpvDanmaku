package app.marlboroadvance.mpvex.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.marlboroadvance.mpvex.di.MIGRATION_8_9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates MIGRATION_8_9: the danmaku tables/indexes are created and existing user
 * data survives. The schemas in `app/schemas` are exposed to this source set as
 * androidTest assets so [MigrationTestHelper] can create v8 databases and validate v9.
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
  private val testDb = "migration-8-9-test"

  @get:Rule
  val helper: MigrationTestHelper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    MpvExDatabase::class.java,
  )

  @Test
  fun migrate8To9CreatesDanmakuTablesAndPreservesData() {
    helper.createDatabase(testDb, 8).apply {
      execSQL(
        """
        INSERT INTO PlaybackStateEntity
          (mediaTitle, lastPosition, playbackSpeed, videoZoom, sid, secondarySid,
           subDelay, subSpeed, aid, audioDelay, timeRemaining, externalSubtitles, hasBeenWatched)
        VALUES ('video.mkv', 1234, 1.0, 0.0, 0, -1, 0, 1.0, 0, 0, 0, '', 0)
        """.trimIndent(),
      )
      execSQL(
        """
        INSERT INTO RecentlyPlayedEntity
          (filePath, fileName, duration, fileSize, width, height, timestamp)
        VALUES ('/storage/video.mkv', 'video.mkv', 120, 1024, 1920, 1080, 1700000000000)
        """.trimIndent(),
      )
      close()
    }

    val db = helper.runMigrationsAndValidate(testDb, 9, true, MIGRATION_8_9)

    // New danmaku tables and their indexes exist.
    val objects = mutableMapOf<String, String>()
    db.query("SELECT type, name FROM sqlite_master WHERE type IN ('table', 'index')").use { cursor ->
      while (cursor.moveToNext()) {
        objects[cursor.getString(1)] = cursor.getString(0)
      }
    }
    assertEquals("table", objects["danmaku_media_bindings"])
    assertEquals("table", objects["danmaku_cache_metadata"])
    assertEquals("index", objects["index_danmaku_media_bindings_episodeId"])
    assertEquals("index", objects["index_danmaku_cache_metadata_episodeId"])

    // Old table data is preserved.
    db.query("SELECT lastPosition FROM PlaybackStateEntity WHERE mediaTitle = 'video.mkv'").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(1234L, cursor.getLong(0))
    }
    db.query("SELECT COUNT(*) FROM RecentlyPlayedEntity").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(1L, cursor.getLong(0))
    }
    db.close()
  }

  @Test
  fun migrate8To9RepairsLegacyPlaybackStateSchema() {
    helper.createDatabase(testDb, 8).apply {
      // Simulate a corrupted v8 database that still carries the v1 playback schema
      // (secondarySubDelay present, externalSubtitles/hasBeenWatched missing).
      execSQL("DROP TABLE PlaybackStateEntity")
      execSQL(
        """
        CREATE TABLE PlaybackStateEntity (
          mediaTitle TEXT NOT NULL PRIMARY KEY,
          lastPosition INTEGER NOT NULL,
          playbackSpeed REAL NOT NULL,
          sid INTEGER NOT NULL,
          secondarySubDelay INTEGER NOT NULL,
          subDelay INTEGER NOT NULL,
          subSpeed REAL NOT NULL,
          aid INTEGER NOT NULL,
          audioDelay INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      execSQL(
        "INSERT INTO PlaybackStateEntity VALUES ('old.mkv', 500, 1.25, 1, 300, 250, 1.0, 1, 100)",
      )
      close()
    }

    val db = helper.runMigrationsAndValidate(testDb, 9, true, MIGRATION_8_9)

    db.query(
      """
      SELECT lastPosition, videoZoom, secondarySid, timeRemaining, externalSubtitles, hasBeenWatched
      FROM PlaybackStateEntity WHERE mediaTitle = 'old.mkv'
      """.trimIndent(),
    ).use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(500L, cursor.getLong(0))
      assertEquals(0.0, cursor.getDouble(1), 0.0)
      assertEquals(-1, cursor.getInt(2))
      assertEquals(0, cursor.getInt(3))
      assertEquals("", cursor.getString(4))
      assertEquals(0, cursor.getInt(5))
    }

    // The danmaku tables are still created on the repair path.
    db.query("SELECT COUNT(*) FROM danmaku_media_bindings").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0L, cursor.getLong(0))
    }
    db.query("SELECT COUNT(*) FROM danmaku_cache_metadata").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0L, cursor.getLong(0))
    }
    db.close()
  }
}
