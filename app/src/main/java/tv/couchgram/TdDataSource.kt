package tv.couchgram

import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Streams a Telegram file into ExoPlayer (through [MediaCache]) with a bounded footprint, so a
 * multi-GB file plays on a TV stick with little free space:
 * - TDLib downloads only a window past the read position (5% of free space, 32-256 MB), topped up
 *   as it drains, and never past the range the player asked for.
 * - When the window drains and TDLib's copy has passed [ROTATE_AT], the copy is deleted and the
 *   download restarts at the read position. Everything already read is in [MediaCache] by then, so
 *   at most [LOW_WATER] bytes are fetched twice.
 *
 * A seek reopens at a new position, which moves the window there. ExoPlayer calls this on its
 * loader thread, so blocking here is expected.
 *
 * Telegram's CDN: a download that starts at byte 0 may be redirected to it (common for films
 * forwarded from big public channels). TDLib then hash-checks the file in order and ignores any new
 * offset or limit ("Ignore streaming_offset … need_check_ = true" in the log): a jump to an MKV index
 * at the end would wait for the whole file. So a download is restarted, never re-aimed, when it moves
 * (a restart at a non-zero offset is served directly, not by the CDN), and one that runs a full window
 * past the playhead is stopped.
 */
@OptIn(UnstableApi::class)
class TdDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource()
    }

    private var uri: Uri? = null
    private var fileId = 0
    private var position = 0L
    private var bytesRemaining = 0L
    private var rangeEnd = 0L
    private var ahead = MIN_AHEAD
    /** Bytes known to be on disk from [position] on, capped at [RECHECK] so the window is checked often. */
    private var readyAhead = 0L
    private var localFile: RandomAccessFile? = null
    private var opened = false
    /** When the current wait for bytes at [position] began; a long wait restarts the download. */
    private var waitingSince = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = fileIdOf(dataSpec.uri)
        transferInitializing(dataSpec)
        val file = call(TdApi.GetFile(fileId))
        val size = if (file.size > 0) file.size else file.expectedSize
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        rangeEnd = position + bytesRemaining
        ahead = (StatFs(Environment.getDataDirectory().path).availableBytes / 20).coerceIn(MIN_AHEAD, MAX_AHEAD)
        readyAhead = 0
        // The cache layer re-opens at every gap it needs filled, so the copy is checked here as well as
        // on top-ups; anything already read is in MediaCache, so dropping the copy loses nothing.
        if (BuildConfig.DEBUG) Log.i("SmartCache", "open at ${position / MB} MB, Telegram copy ${file.local.downloadedSize / MB} MB")
        if (file.local.downloadedSize > ROTATE_AT) dropLocalCopy()
        // Start small: a fresh open is often a probe (container header, or an index at the file's end)
        // that needs a few MB fast. Steady reading grows it to the full window via top-ups.
        download(position, START_WINDOW)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (readyAhead == 0L) readyAhead = awaitBytes()
        val toRead = minOf(length.toLong(), readyAhead, bytesRemaining).toInt()
        val file = localFile ?: openLocal()
        file.seek(position)
        val read = file.read(buffer, offset, toRead)
        if (read < 0) throw IOException("Telegram file ended early at byte $position")
        position += read
        bytesRemaining -= read
        readyAhead -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        localFile?.close()
        localFile = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Blocks until at least one byte at [position] is on disk; returns how many are. Keeps the window topped up. */
    private fun awaitBytes(): Long {
        waitingSince = SystemClock.elapsedRealtime()
        while (true) {
            if (Thread.interrupted()) throw InterruptedIOException()
            val local = call(TdApi.GetFile(fileId)).local
            val ready = call(TdApi.GetFileDownloadedPrefixSize(fileId, position)).size
            // A CDN download ignores the window's limit and would fetch the whole file: stop it a full
            // window ahead. The next top-up restarts it (directly, and rotating the copy if it's big).
            if (local.isDownloadingActive && ready >= ahead + LOW_WATER) {
                if (BuildConfig.DEBUG) Log.i("SmartCache", "download ran ${ready / MB} MB ahead (CDN?), stopping it")
                call(TdApi.CancelDownloadFile(fileId, false))
            }
            // Top up before the window runs dry, so playback never waits on the next request.
            if (ready < LOW_WATER && position + ready < rangeEnd && !local.isDownloadingActive && !local.isDownloadingCompleted) {
                if (!local.canBeDownloaded) throw IOException("Telegram won't let this file be downloaded")
                if (BuildConfig.DEBUG) Log.i("SmartCache", "top-up at ${position / MB} MB, Telegram copy ${local.downloadedSize / MB} MB")
                if (local.downloadedSize > ROTATE_AT) {
                    rotate()
                    continue
                }
                download(position + ready)
            }
            if (ready > 0) return minOf(ready, RECHECK)
            val waited = SystemClock.elapsedRealtime() - waitingSince
            if (local.isDownloadingActive && (local.downloadOffset > position || waited > STALL_MS)) {
                // Downloading, but not what's needed here (or it ignored our offset): restart at the playhead.
                if (BuildConfig.DEBUG) Log.i("SmartCache", "nothing at ${position / MB} MB after ${waited / 1000} s, restarting the download there")
                download(position, START_WINDOW)
                waitingSince = SystemClock.elapsedRealtime()
            }
            // Wake on the next progress update for this file, or re-check after a short wait.
            blocking { withTimeoutOrNull(WAIT_MS) { Td.files.first { it.id == fileId } } }
        }
    }

    /** Drops the local copy (everything already played) and restarts the window at [position]. */
    private fun rotate() {
        dropLocalCopy()
        download(position, START_WINDOW)
    }

    private fun dropLocalCopy() {
        if (BuildConfig.DEBUG) Log.i("SmartCache", "rotating Telegram copy at ${position / MB} MB")
        localFile?.close()
        localFile = null
        call(TdApi.CancelDownloadFile(fileId, false))
        call(TdApi.DeleteFile(fileId))
    }

    /** Starts the window at [from]. A running download is stopped first: re-aiming it may be ignored (see the class doc). */
    private fun download(from: Long, window: Long = ahead) {
        val limit = minOf(window, rangeEnd - from).coerceAtLeast(1L)
        if (from > 0 && call(TdApi.GetFile(fileId)).local.isDownloadingActive) call(TdApi.CancelDownloadFile(fileId, false))
        call(TdApi.DownloadFile(fileId, PRIORITY, from, limit, false))
    }

    private fun openLocal(): RandomAccessFile {
        val path = call(TdApi.GetFile(fileId)).local.path
        if (path.isEmpty()) throw IOException("Telegram hasn't created the local file yet")
        return RandomAccessFile(path, "r").also { localFile = it }
    }

    private fun <T : TdApi.Object> call(function: TdApi.Function<T>): T = blocking { Td.send(function) }

    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: InterruptedException) {
        throw InterruptedIOException().apply { initCause(e) }
    } catch (e: TdException) {
        throw IOException(e.message, e)
    }

    companion object {
        private const val PRIORITY = 32 // TDLib's highest
        private const val WAIT_MS = 500L
        private const val MB = 1024L * 1024
        private val START_WINDOW = 4 * MB
        private val MIN_AHEAD = if (BuildConfig.STRESS) 8 * MB else 32 * MB
        private val MAX_AHEAD = if (BuildConfig.STRESS) 16 * MB else 256 * MB
        private val LOW_WATER = if (BuildConfig.STRESS) 4 * MB else 8 * MB
        private val ROTATE_AT = if (BuildConfig.STRESS) 24 * MB else 128 * MB
        private const val RECHECK = 4L * 1024 * 1024
        /** No bytes at the playhead this long while TDLib says it's downloading: restart there. */
        private const val STALL_MS = 6_000L

        fun uriFor(fileId: Int): Uri = Uri.parse("tg://file/$fileId")

        private fun fileIdOf(uri: Uri): Int =
            uri.lastPathSegment?.toIntOrNull() ?: throw IOException("Not a Telegram file uri: $uri")
    }
}
