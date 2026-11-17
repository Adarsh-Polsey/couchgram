package tv.couchgram

import android.content.Context
import android.text.format.Formatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * The TV's few posts to the channel, all silent and one line: the storage line (when the Storage
 * panel opens), replies to the phone's commands (`/storage`, `/clearcache`, `/play`, `/stop`), and a
 * crash saved on the way down and posted on the next launch. The TV library ignores them (it lists only videos).
 * Nothing is posted unless the phone remote is on ([Prefs.phoneRemote]).
 */
object Reporter {
    private const val TITLE_MAX = 60
    private const val QUEUE_MAX = 10

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<String>()

    @Volatile
    private var chatId = 0L

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (Prefs.phoneRemote) Prefs.pendingCrash = crashLine(error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The channel to post to; 0 after sign-out. Flushes a saved crash and anything queued. */
    fun channelReady(id: Long) {
        chatId = id
        if (id == 0L) return
        if (!Prefs.phoneRemote) {
            Prefs.pendingCrash = null
            synchronized(queue) { queue.clear() }
            return
        }
        Prefs.pendingCrash?.let {
            Prefs.pendingCrash = null
            post(it)
        }
        val queued = synchronized(queue) { queue.toList().also { queue.clear() } }
        queued.forEach(::post)
    }

    /** Fire-and-forget; queued if the channel isn't known yet. [replyTo] threads it under a command. */
    fun post(line: String, replyTo: Long = 0L) {
        if (!Prefs.phoneRemote) return
        val id = chatId
        if (id == 0L) {
            synchronized(queue) { if (queue.size < QUEUE_MAX) queue.addLast(line) }
            return
        }
        scope.launch { runCatching { send(id, line, replyTo) } }
    }

    /** For callers that show the outcome (the storage panel). Throws [TdException]. */
    suspend fun sendNow(line: String) {
        check(Prefs.phoneRemote) { "Phone remote is off" }
        val id = chatId
        check(id != 0L) { "Channel not known yet" }
        send(id, line, 0L)
    }

    private suspend fun send(id: Long, line: String, replyTo: Long) {
        Td.send(
            TdApi.SendMessage().apply {
                chatId = id
                if (replyTo != 0L) this.replyTo = TdApi.InputMessageReplyToMessage(replyTo, null, 0, "")
                options = TdApi.MessageSendOptions().apply {
                    disableNotification = true
                    fromBackground = true
                }
                inputMessageContent = TdApi.InputMessageText().apply {
                    text = TdApi.FormattedText(line, emptyArray())
                }
            },
        )
    }

    // --- Lines ---

    fun storage(s: StorageSnapshot) =
        app.getString(R.string.report_storage, size(s.freeBytes), size(s.totalBytes), size(s.cacheBytes))

    fun playing(title: String) = app.getString(R.string.report_playing, short(title))

    fun playHint() = app.getString(R.string.report_play_hint)

    fun notOpen() = app.getString(R.string.report_not_open)

    fun cleared(freed: Long, s: StorageSnapshot) =
        if (freed > 0) app.getString(R.string.report_cleared, size(freed), size(s.freeBytes))
        else app.getString(R.string.report_already_clear, size(s.freeBytes))

    fun clearWhilePlaying() = app.getString(R.string.report_clear_while_playing)

    fun stopped() = app.getString(R.string.report_stopped)

    private fun crashLine(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val frame = root.stackTrace.firstOrNull { it.className.startsWith("tv.couchgram") } ?: root.stackTrace.firstOrNull()
        val where = frame?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
        val what = listOfNotNull(root.javaClass.simpleName, root.message?.take(80)).joinToString(": ")
        return app.getString(R.string.report_crash, BuildConfig.VERSION_NAME, what, where)
    }

    private fun short(title: String) = when {
        title.isBlank() -> app.getString(R.string.video_untitled)
        title.length <= TITLE_MAX -> title
        else -> title.take(TITLE_MAX - 1).trimEnd() + "…"
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(app, bytes)
}
