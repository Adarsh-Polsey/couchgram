package tv.couchgram

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * The smart cache: Media3's on-disk cache of everything played, trimmed by [SmartEvictor].
 *
 * TDLib can only delete a whole file, so its copy is kept tiny ([TdDataSource] rotates it) and this
 * cache holds the parts worth keeping: the stretch around the playhead, a "resume island" around
 * where each unfinished video was left, and each file's header and index (so seeking stays instant).
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private const val FRAGMENT_BYTES = 2L * 1024 * 1024 // eviction granularity

    lateinit var cache: SimpleCache
        private set
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::cache.isInitialized) return
        appContext = context.applicationContext
        // filesDir, not cacheDir: this cache manages its own size, and Android deleting span files
        // behind its back would corrupt the index.
        val dir = if (BuildConfig.STRESS) "media-stress" else "media"
        if (!BuildConfig.STRESS) File(appContext.filesDir, "media-stress").deleteRecursively()
        cache = SimpleCache(File(appContext.filesDir, dir), SmartEvictor(appContext), StandaloneDatabaseProvider(appContext))
        CachePolicy.restoreIslands(WatchStore.states.value)
    }

    fun dataSourceFactory(): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(TdDataSource.Factory())
        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(FRAGMENT_BYTES))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun size(): Long = if (::cache.isInitialized) cache.cacheSpace else 0L

    fun cachedBytes(key: String): Long = cache.getCachedSpans(key).sumOf { it.length }

    /** What of [key] is on disk, as play-time ranges (bytes mapped linearly onto the duration). */
    fun cachedRanges(key: String, sizeBytes: Long, durationMs: Long): List<LongRange> {
        if (sizeBytes <= 0 || durationMs <= 0) return emptyList()
        val merged = mutableListOf<LongRange>()
        for (span in cache.getCachedSpans(key)) {
            val end = span.position + span.length
            val last = merged.lastOrNull()
            if (last != null && span.position <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, end)
            else merged += span.position..end
        }
        return merged.map { (it.first * durationMs / sizeBytes)..(it.last * durationMs / sizeBytes) }
    }

    fun remove(key: String) {
        runCatching { cache.removeResource(key) }
    }

    fun clearAll() {
        if (!::cache.isInitialized) return
        cache.keys.toList().forEach(::remove)
    }
}

/**
 * What the evictor may and may not drop. Updated by the player every few seconds; read on
 * the cache's loader threads, hence the concurrent collections.
 */
object CachePolicy {
    private const val MB = 1024L * 1024
    const val HEADROOM = 500 * MB // never fill the device past this
    private val MIN_CAP = if (BuildConfig.STRESS) 16 * MB else 200 * MB
    private val MAX_CAP = if (BuildConfig.STRESS) 48 * MB else 2048 * MB
    private const val CAP_SHARE = 0.4 // of the space the cache could use
    private const val HEAD_BYTES = 4 * MB // container header
    private const val TAIL_BYTES = 16 * MB // MP4 moov / MKV cues often live at the end
    private const val AHEAD_PROTECT = 256 * MB
    val BACK_MS = if (BuildConfig.STRESS) 30_000L else 5 * 60_000L // instant rewind window behind the playhead
    private val ISLAND_BEFORE_MS = if (BuildConfig.STRESS) 20_000L else 2 * 60_000L
    private val ISLAND_AFTER_MS = if (BuildConfig.STRESS) 30_000L else 3 * 60_000L

    private data class Range(val start: Long, val end: Long) {
        fun overlaps(span: CacheSpan) = span.position < end && span.position + span.length > start
    }

    private val protectedRanges = ConcurrentHashMap<String, List<Range>>()
    private val islandAge = ConcurrentHashMap<String, Long>()
    private val watched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var playingKey: String? = null
        private set

    @Volatile
    private var playingByte = 0L

    @Volatile
    private var backBytes = 0L

    /** Cache size limit: 40% of what it could use, 200 MB–2 GB, and always leaving [HEADROOM] free. */
    fun capBytes(context: Context, cacheSize: Long): Long {
        val free = StatFs(context.filesDir.path).availableBytes
        val share = ((free + cacheSize) * CAP_SHARE).toLong().coerceIn(MIN_CAP, MAX_CAP)
        return minOf(share, cacheSize + free - HEADROOM).coerceAtLeast(0L)
    }

    /** Called while playing: keep from 5 min behind to well ahead, plus header and index. */
    fun playing(key: String, positionMs: Long, durationMs: Long, sizeBytes: Long) {
        watched.remove(key)
        if (durationMs <= 0 || sizeBytes <= 0) return
        val bytesPerMs = sizeBytes.toDouble() / durationMs
        val pos = (positionMs * bytesPerMs).toLong()
        playingKey = key
        playingByte = pos
        backBytes = (BACK_MS * bytesPerMs).toLong()
        protectedRanges[key] = indexRanges(sizeBytes) + Range(pos - backBytes, pos + AHEAD_PROTECT)
    }

    /** Called when playback stops: shrink protection to an island around where it stopped. */
    fun stopped(key: String, positionMs: Long, durationMs: Long, sizeBytes: Long) {
        if (playingKey == key) playingKey = null
        island(key, positionMs, durationMs, sizeBytes, System.currentTimeMillis())
    }

    fun watchedOnce(key: String) {
        watched.add(key)
        protectedRanges.remove(key)
        islandAge.remove(key)
    }

    fun restoreIslands(states: Map<String, WatchState>) {
        states.forEach { (key, s) ->
            if (s.inProgress) island(key, s.positionMs, s.durationMs, s.sizeBytes, s.updatedAt)
        }
    }

    private fun island(key: String, positionMs: Long, durationMs: Long, sizeBytes: Long, age: Long) {
        if (durationMs <= 0 || sizeBytes <= 0) return
        val bytesPerMs = sizeBytes.toDouble() / durationMs
        val pos = (positionMs * bytesPerMs).toLong()
        protectedRanges[key] = indexRanges(sizeBytes) +
            Range(pos - (ISLAND_BEFORE_MS * bytesPerMs).toLong(), pos + (ISLAND_AFTER_MS * bytesPerMs).toLong())
        islandAge[key] = age
    }

    private fun indexRanges(size: Long) = listOf(Range(0, HEAD_BYTES), Range(size - TAIL_BYTES, size))

    /** Lower goes first: finished videos, then old footage behind the playhead, then other videos. */
    fun priority(span: CacheSpan): Int = when {
        span.key in watched -> 0
        span.key == playingKey && span.position + span.length < playingByte - backBytes -> 1
        span.key != playingKey -> 2
        else -> 3
    }

    /** [strict] = respect resume islands; the second eviction pass gives up the oldest islands too. */
    fun isProtected(span: CacheSpan, strict: Boolean): Boolean {
        if (span.key in watched) return false
        if (!strict && span.key != playingKey) return false
        return protectedRanges[span.key]?.any { it.overlaps(span) } == true
    }

    fun islandAge(key: String): Long = islandAge[key] ?: 0L
}

/** LRU eviction that respects [CachePolicy]: the cache only shrinks when it nears its cap. */
@OptIn(UnstableApi::class)
private class SmartEvictor(private val context: Context) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
    private var size = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, required: Long) {
        val cap = CachePolicy.capBytes(context, size)
        if (size + required <= cap) return
        val before = size
        // Pass 1 keeps every resume island; pass 2 gives up the oldest islands, never the playing video's.
        trim(cache, required, cap)
        if (BuildConfig.DEBUG) Log.i("SmartCache", "trimmed ${(before - size) / MB_} MB, now ${size / MB_} of ${cap / MB_} MB")
    }

    private fun trim(cache: Cache, required: Long, cap: Long) {
        for (strict in listOf(true, false)) {
            val order = spans.sortedWith(
                compareBy<CacheSpan>({ CachePolicy.priority(it) }, { CachePolicy.islandAge(it.key) }, { it.lastTouchTimestamp }),
            )
            for (span in order) {
                if (size + required <= cap) return
                if (CachePolicy.isProtected(span, strict)) continue
                runCatching { cache.removeSpan(span) }
            }
        }
    }

    private companion object {
        const val MB_ = 1024L * 1024
    }
}
