package app.marlboroadvance.mpvex.repository.dandanplay

import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayCommentQuery
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CachedCommentResponse(
  val response: DandanplayCommentResponseDto,
  val fetchedAtEpochMillis: Long,
  val isFresh: Boolean,
)

/** Gzip cache for raw comment responses. Old entries remain usable as offline fallback. */
class RawCommentDiskCache(
  cacheDir: File,
  private val json: Json = defaultDandanplayJson(),
  private val freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
  private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
  private val directory = File(cacheDir, "danmaku/comments")
  private val mutex = Mutex()

  init {
    require(freshnessMillis >= 0L) { "freshnessMillis must not be negative" }
  }

  suspend fun get(query: DandanplayCommentQuery): CachedCommentResponse? {
    if (query.fromCommentId != 0L) return null
    return mutex.withLock {
      withContext(Dispatchers.IO) {
        val file = cacheFile(query)
        if (!file.isFile) return@withContext null
        val envelope = runCatching {
          GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString(CommentCacheEnvelope.serializer(), it.readText())
          }
        }.getOrNull() ?: return@withContext null

        val age = currentTimeMillis() - envelope.fetchedAtEpochMillis
        CachedCommentResponse(
          response = envelope.response,
          fetchedAtEpochMillis = envelope.fetchedAtEpochMillis,
          isFresh = age in 0L..freshnessMillis,
        )
      }
    }
  }

  suspend fun put(
    query: DandanplayCommentQuery,
    response: DandanplayCommentResponseDto,
    fetchedAtEpochMillis: Long = currentTimeMillis(),
  ) {
    if (query.fromCommentId != 0L) return
    mutex.withLock {
      withContext(Dispatchers.IO) {
        check(directory.exists() || directory.mkdirs()) {
          "Could not create dandanplay cache directory"
        }

        val target = cacheFile(query)
        val temporary = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        val bytes = json.encodeToString(
          CommentCacheEnvelope(
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            response = response,
          ),
        ).toByteArray(Charsets.UTF_8)

        try {
          FileOutputStream(temporary).use { fileOutput ->
            val gzip = GZIPOutputStream(fileOutput)
            gzip.write(bytes)
            gzip.finish()
            gzip.flush()
            fileOutput.fd.sync()
          }
          replaceAtomically(temporary, target)
        } finally {
          if (temporary.exists()) temporary.delete()
        }
      }
    }
  }

  suspend fun remove(query: DandanplayCommentQuery): Boolean =
    mutex.withLock {
      withContext(Dispatchers.IO) { !cacheFile(query).exists() || cacheFile(query).delete() }
    }

  /** Cache key / file name for a query, shared with the metadata bookkeeping layer. */
  fun cacheKey(query: DandanplayCommentQuery): String = cacheFile(query).name

  /** Deletes a cached file by its stored name. Returns true when nothing remains. */
  suspend fun deleteFile(fileName: String): Boolean {
    require(fileName.isNotEmpty() && !fileName.contains('/') && !fileName.contains('\\')) {
      "Invalid cache file name"
    }
    return mutex.withLock {
      withContext(Dispatchers.IO) {
        val file = File(directory, fileName)
        !file.exists() || file.delete()
      }
    }
  }

  /** Removes every cached file and returns the total number of bytes freed. */
  suspend fun clearAll(): Long =
    mutex.withLock {
      withContext(Dispatchers.IO) {
        var freed = 0L
        directory.listFiles()?.forEach { file ->
          if (file.isFile) {
            freed += file.length()
            file.delete()
          }
        }
        freed
      }
    }

  /** Disk size of the cache entry for a query, or 0 when the file is missing. */
  fun sizeOf(query: DandanplayCommentQuery): Long = cacheFile(query).takeIf { it.isFile }?.length() ?: 0L

  internal fun cacheFile(query: DandanplayCommentQuery): File {
    val related = if (query.withRelated) 1 else 0
    val conversion = query.chineseConversion.apiValue
    return File(directory, "${query.episodeId}-$related-$conversion.json.gz")
  }

  private fun replaceAtomically(
    source: File,
    target: File,
  ) {
    try {
      Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: Exception) {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  companion object {
    const val DEFAULT_FRESHNESS_MILLIS: Long = 6L * 60L * 60L * 1_000L
  }
}

@Serializable
private data class CommentCacheEnvelope(
  val version: Int = 1,
  val fetchedAtEpochMillis: Long,
  val response: DandanplayCommentResponseDto,
)

