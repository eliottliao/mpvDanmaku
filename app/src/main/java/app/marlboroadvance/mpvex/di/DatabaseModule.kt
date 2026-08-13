package app.marlboroadvance.mpvex.di

import androidx.room.Room
import androidx.room.RoomDatabase
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.database.repository.PlaybackStateRepositoryImpl
import app.marlboroadvance.mpvex.database.repository.PlaylistRepository
import app.marlboroadvance.mpvex.database.repository.RecentlyPlayedRepositoryImpl
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.domain.recentlyplayed.repository.RecentlyPlayedRepository
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailRepository
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

const val DATABASE_NAME = "mpvDanmaku.db"

/** Database wiring for mpvDanmaku's independent schema history. */
val DatabaseModule =
  module {
    single<Json> {
      Json {
        isLenient = true
        ignoreUnknownKeys = true
      }
    }

    single<MpvExDatabase> {
      Room
        .databaseBuilder(androidContext(), MpvExDatabase::class.java, DATABASE_NAME)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
    }

    singleOf(::PlaybackStateRepositoryImpl).bind(PlaybackStateRepository::class)

    single<RecentlyPlayedRepository> {
      RecentlyPlayedRepositoryImpl(get<MpvExDatabase>().recentlyPlayedDao())
    }

    single { ThumbnailRepository(androidContext()) }

    single {
      app.marlboroadvance.mpvex.database.repository.VideoMetadataCacheRepository(
        context = androidContext(),
        dao = get<MpvExDatabase>().videoMetadataDao(),
      )
    }

    single { get<MpvExDatabase>().networkConnectionDao() }

    single {
      app.marlboroadvance.mpvex.repository.NetworkRepository(
        dao = get(),
      )
    }

    single {
      PlaylistRepository(
        playlistDao = get<MpvExDatabase>().playlistDao(),
      )
    }

    single { get<MpvExDatabase>().danmakuDao() }
  }
