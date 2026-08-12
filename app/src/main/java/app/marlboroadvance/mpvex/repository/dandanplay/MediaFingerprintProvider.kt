package app.marlboroadvance.mpvex.repository.dandanplay

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayInvalidQueryException
import app.marlboroadvance.mpvex.domain.danmaku.model.DandanplayMatchMode
import app.marlboroadvance.mpvex.domain.danmaku.model.MediaFingerprint
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Computes the first-16-MiB matching hash without delaying media playback. */
class MediaFingerprintProvider(
  context: Context,
) {
  private val contentResolver = context.applicationContext.contentResolver

  suspend fun fingerprint(
    uri: Uri,
    fallbackFileName: String? = null,
    durationSeconds: Double = 0.0,
    allowContentHash: Boolean = true,
  ): MediaFingerprint = withContext(Dispatchers.IO) {
    val metadata = resolveMetadata(uri)
    val displayName = metadata.fileName
      ?.takeIf { it.isNotBlank() }
      ?: fallbackFileName
      ?: uri.lastPathSegment
      ?: throw DandanplayInvalidQueryException("The media file name is unavailable")
    val size = metadata.fileSize?.takeIf { it >= 0L }
      ?: resolveDescriptorSize(uri)
      ?: 0L
    createFingerprint(
      mediaIdentity = uri.toString(),
      displayName = displayName,
      fileSize = size,
      durationSeconds = normalizeDuration(durationSeconds),
      inputStreamProvider = if (allowContentHash) {
        { contentResolver.openInputStream(uri) }
      } else {
        null
      },
    )
  }

  suspend fun fingerprint(
    file: File,
    durationSeconds: Double = 0.0,
  ): MediaFingerprint = withContext(Dispatchers.IO) {
    createFingerprint(
      mediaIdentity = file.absolutePath,
      displayName = file.name,
      fileSize = file.length().coerceAtLeast(0L),
      durationSeconds = normalizeDuration(durationSeconds),
      inputStreamProvider = if (file.isFile) ({ file.inputStream() }) else null,
    )
  }

  /**
   * Generic entry point for non-Android media providers. Pass no stream to deliberately use
   * file-name-only matching for network media.
   */
  suspend fun fingerprint(
    mediaIdentity: String,
    fileName: String,
    fileSize: Long,
    durationSeconds: Int,
    inputStreamProvider: (() -> InputStream?)? = null,
  ): MediaFingerprint = withContext(Dispatchers.IO) {
    createFingerprint(
      mediaIdentity = mediaIdentity,
      displayName = fileName,
      fileSize = fileSize.coerceAtLeast(0L),
      durationSeconds = durationSeconds.coerceAtLeast(0),
      inputStreamProvider = inputStreamProvider,
    )
  }

  private suspend fun createFingerprint(
    mediaIdentity: String,
    displayName: String,
    fileSize: Long,
    durationSeconds: Int,
    inputStreamProvider: (() -> InputStream?)?,
  ): MediaFingerprint {
    val apiFileName = withoutExtension(File(displayName).name)
    if (apiFileName.isBlank()) {
      throw DandanplayInvalidQueryException("The media file name must not be blank")
    }

    val firstChunkMd5 = inputStreamProvider?.let { provider ->
      runCatching { provider()?.use { md5FirstChunk(it) } }.getOrNull()
    }
    val localIdentity = if (firstChunkMd5 != null) {
      "local\u0000$firstChunkMd5\u0000$fileSize"
    } else {
      "media\u0000$mediaIdentity"
    }

    return MediaFingerprint(
      mediaKey = sha256(localIdentity),
      fileName = apiFileName,
      fileHash = firstChunkMd5,
      fileSize = fileSize,
      durationSeconds = durationSeconds,
      suggestedMatchMode = if (firstChunkMd5 != null) {
        DandanplayMatchMode.HASH_AND_FILE_NAME
      } else {
        DandanplayMatchMode.FILE_NAME_ONLY
      },
    )
  }

  private fun resolveMetadata(uri: Uri): ResolverMetadata {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
      val file = uri.path?.let(::File)
      return ResolverMetadata(file?.name, file?.takeIf { it.exists() }?.length())
    }
    return runCatching {
      contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
      )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use ResolverMetadata(null, null)
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        ResolverMetadata(
          fileName = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
            ?.let(cursor::getString),
          fileSize = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
            ?.let(cursor::getLong),
        )
      }
    }.getOrNull() ?: ResolverMetadata(null, null)
  }

  private fun resolveDescriptorSize(uri: Uri): Long? =
    runCatching {
      contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.statSize.takeIf { it >= 0L }
      }
    }.getOrNull()

  private suspend fun md5FirstChunk(input: InputStream): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = HASH_BYTE_LIMIT
    var bytesSinceCancellationCheck = 0L
    while (remaining > 0L) {
      val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) break
      if (read == 0) {
        val byte = input.read()
        if (byte < 0) break
        digest.update(byte.toByte())
        remaining -= 1L
        bytesSinceCancellationCheck += 1L
      } else {
        digest.update(buffer, 0, read)
        remaining -= read.toLong()
        bytesSinceCancellationCheck += read.toLong()
      }
      // Keep the hash interruptible so episode switches can cancel promptly.
      if (bytesSinceCancellationCheck >= CANCELLATION_CHECK_BYTE_INTERVAL) {
        bytesSinceCancellationCheck = 0L
        currentCoroutineContext().ensureActive()
      }
    }
    return digest.digest().toLowerHex()
  }

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .toLowerHex()

  private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
      val value = byte.toInt() and 0xFF
      append(HEX[value ushr 4])
      append(HEX[value and 0x0F])
    }
  }

  private fun withoutExtension(fileName: String): String {
    val extensionStart = fileName.lastIndexOf('.')
    return if (extensionStart > 0) fileName.substring(0, extensionStart) else fileName
  }

  private fun normalizeDuration(durationSeconds: Double): Int {
    if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return 0
    return durationSeconds.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  }

  private data class ResolverMetadata(
    val fileName: String?,
    val fileSize: Long?,
  )

  companion object {
    const val HASH_BYTE_LIMIT: Long = 16L * 1024L * 1024L
    private const val CANCELLATION_CHECK_BYTE_INTERVAL: Long = 1024L * 1024L
    private const val HEX = "0123456789abcdef"
  }
}

