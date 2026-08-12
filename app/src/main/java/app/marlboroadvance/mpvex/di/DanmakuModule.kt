package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.database.dao.DanmakuDao
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuBindingRepository
import app.marlboroadvance.mpvex.repository.dandanplay.DanmakuCacheStore
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayRepository
import app.marlboroadvance.mpvex.repository.dandanplay.DandanplayTransport
import app.marlboroadvance.mpvex.repository.dandanplay.DirectSignedTransport
import app.marlboroadvance.mpvex.repository.dandanplay.MediaFingerprintProvider
import app.marlboroadvance.mpvex.repository.dandanplay.RawCommentDiskCache
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val danmakuModule = module {
  single { MediaFingerprintProvider(androidContext()) }
  single { DanmakuBindingRepository(get<DanmakuDao>()) }
  single { RawCommentDiskCache(androidContext().cacheDir) }
  // Registered unconditionally so the settings screen can clear the cache
  // even when no dandanplay credentials are embedded.
  single { DanmakuCacheStore(get<RawCommentDiskCache>(), get<DanmakuDao>()) }

  // The request signer requires non-blank credentials, so the transport and repository
  // beans are only registered when the build actually embeds dandanplay keys.
  val appId = BuildConfig.DANDANPLAY_APP_ID
  val appSecret = BuildConfig.DANDANPLAY_APP_SECRET
  if (appId.isNotBlank() && appSecret.isNotBlank()) {
    single<DandanplayTransport> {
      DirectSignedTransport(
        httpClient = get<OkHttpClient>(),
        appId = appId,
        appSecret = appSecret,
      )
    }
    single { DandanplayRepository(get<DandanplayTransport>(), get<DanmakuCacheStore>()) }
  }
}
