package tv.couchgram

import android.content.Context
import android.os.StatFs
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

@Immutable
data class StorageSnapshot(
    val freeBytes: Long,
    val totalBytes: Long,
    val cacheBytes: Long,
    /** Unfinished videos with a resume island on disk, and its size. */
    val resumeVideos: Int,
    val resumeBytes: Long,
)

object Storage {
    /** Telegram's window plus a working cache; counted with what the cache can give back. */
    const val MIN_FREE_TO_PLAY = 500L * 1000 * 1000

    suspend fun snapshot(context: Context): StorageSnapshot {
        val (free, total) = withContext(Dispatchers.IO) {
            StatFs(context.filesDir.path).let { it.availableBytes to it.totalBytes }
        }
        val telegram = runCatching { Td.send(TdApi.GetStorageStatisticsFast()).filesSize }.getOrDefault(0L)
        val resume = withContext(Dispatchers.IO) {
            WatchStore.states.value.filterValues { it.inProgress }.keys
                .map { MediaCache.cachedBytes(it) }.filter { it > 0 }
        }
        return StorageSnapshot(free, total, telegram + MediaCache.size(), resume.size, resume.sum())
    }

    /** Free space plus what the media cache would give back if a new video needs room. */
    fun playableBytes(context: Context): Long = StatFs(context.filesDir.path).availableBytes + MediaCache.size()

    /**
     * Deletes everything TDLib has downloaded. Those are only temporary copies (the media cache keeps
     * what's worth keeping), so this also runs at start-up: a crash or force-stop mid-film can't
     * leave a large copy behind.
     */
    suspend fun dropTelegramCopies() {
        Td.send(
            TdApi.OptimizeStorage().apply {
                size = 0
                ttl = 0
                count = 0
                immunityDelay = 0
                returnDeletedFileStatistics = false
                chatLimit = 0
            },
        )
    }

    /** Deletes Telegram's files and the media cache (resume islands too). Returns the bytes freed. */
    suspend fun clearCache(): Long {
        val before = Td.send(TdApi.GetStorageStatisticsFast()).filesSize + MediaCache.size()
        dropTelegramCopies()
        withContext(Dispatchers.IO) { MediaCache.clearAll() }
        val after = Td.send(TdApi.GetStorageStatisticsFast()).filesSize + MediaCache.size()
        return (before - after).coerceAtLeast(0L)
    }
}
