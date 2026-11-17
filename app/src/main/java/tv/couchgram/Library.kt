package tv.couchgram

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.drinkless.tdlib.TdApi
import java.util.Locale

/** One playable video in the channel. Width/height are 0 when Telegram doesn't know them (files sent as documents). */
@Immutable
data class VideoItem(
    val messageId: Long,
    /** Parsed from the caption, else the file name; its title is blank when neither has one ("Untitled video"). */
    val release: Release,
    val fileId: Int,
    /** Telegram's permanent id for the video file: the key for its cache and watch progress. */
    val fileKey: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val date: Int,
    /** Pinned in the channel: one of the three ways a video becomes a favourite. */
    val pinned: Boolean = false,
    /** This account reacted to the post (any reaction). */
    val reacted: Boolean = false,
) {
    /** One line for the player and the channel reports: "Monsoon Letters (2026) · HEVC 10-bit". */
    val title: String get() = release.label

    /** Clearly beyond what this TV decodes (see [DeviceVideo]); 0x0 (unknown) never warns. */
    val tooBig: Boolean get() = DeviceVideo.clearlyTooBig(width, height)
}

/** A channel or group the library can show, as the channel picker lists it. */
@Immutable
data class ChannelChoice(
    val id: Long,
    val title: String,
    /** A broadcast channel (else a group). */
    val isChannel: Boolean,
    /** This account owns or administers it: listed first, since that's usually where the videos are. */
    val yours: Boolean,
)

object Library {
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "3gp")
    private const val PAGES_PER_FILTER = 10 // 100 messages per page
    private const val SAVED_PAGES_PER_FILTER = 3
    private const val CHATS_MAX = 1000

    /**
     * The chosen chat, or null if this account can no longer see it (left, deleted, or another
     * account signed in). Matched by id, so renaming the channel changes nothing.
     */
    suspend fun chat(id: Long): TdApi.Chat? {
        val chat = runCatching { Td.send(TdApi.GetChat(id)) }.getOrNull() ?: run {
            // Right after sign-in TDLib knows no chats yet; loading the lists fills them in.
            loadChatList(TdApi.ChatListMain())
            loadChatList(TdApi.ChatListArchive())
            runCatching { Td.send(TdApi.GetChat(id)) }.getOrNull()
        } ?: return null
        return chat.takeIf { choiceOf(it) != null }
    }

    /** Every channel and group this account is in: its own first, then channels before groups, then by recent activity. */
    suspend fun choices(): List<ChannelChoice> {
        val ids = LinkedHashSet<Long>()
        for (list in listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
            loadChatList(list)
            ids += Td.send(TdApi.GetChats(list, CHATS_MAX)).chatIds.toList()
        }
        return ids.mapNotNull { id -> runCatching { Td.send(TdApi.GetChat(id)) }.getOrNull()?.let { choiceOf(it) } }
            .sortedWith(compareByDescending<ChannelChoice> { it.yours }.thenByDescending { it.isChannel })
    }

    /** Null for private chats and bots, and for groups this account has left or was removed from. */
    private suspend fun choiceOf(chat: TdApi.Chat): ChannelChoice? {
        val (isChannel, status) = when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup ->
                type.isChannel to runCatching { Td.send(TdApi.GetSupergroup(type.supergroupId)).status }.getOrNull()
            is TdApi.ChatTypeBasicGroup ->
                false to runCatching { Td.send(TdApi.GetBasicGroup(type.basicGroupId)).status }.getOrNull()
            else -> return null
        }
        if (status == null || status is TdApi.ChatMemberStatusLeft || status is TdApi.ChatMemberStatusBanned) return null
        val yours = status is TdApi.ChatMemberStatusCreator || status is TdApi.ChatMemberStatusAdministrator
        return ChannelChoice(chat.id, chat.title.trim(), isChannel, yours)
    }

    /** The channel's newest message. TDLib may answer an empty first page while it syncs, hence the retry. */
    suspend fun lastMessage(chatId: Long): TdApi.Message? {
        repeat(2) {
            Td.send(TdApi.GetChatHistory(chatId, 0, 0, 1, false)).messages.firstOrNull()?.let { return it }
        }
        return null
    }

    /** Loads a chat list until TDLib reports the end (404), capped at ~1000 chats. */
    private suspend fun loadChatList(list: TdApi.ChatList) {
        repeat(10) {
            try {
                Td.send(TdApi.LoadChats(list, 100))
            } catch (e: TdException) {
                if (e.error.code == 404) return
                throw e
            }
        }
    }

    /**
     * Newest first, emitted again after every page so the grid fills while the rest loads.
     * Videos uploaded "as files" are documents, so both filters are searched.
     */
    fun videos(chatId: Long): Flow<List<VideoItem>> = flow {
        val items = LinkedHashMap<Long, VideoItem>()
        for (filter in listOf(TdApi.SearchMessagesFilterDocument(), TdApi.SearchMessagesFilterVideo())) {
            var fromMessageId = 0L
            for (page in 0 until PAGES_PER_FILTER) {
                val found = Td.send(TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, 0, 100, filter))
                if (BuildConfig.DEBUG) {
                    val kinds = found.messages.groupingBy { it.content.javaClass.simpleName }.eachCount()
                    android.util.Log.i("Library", "${filter.javaClass.simpleName} page $page: total=${found.totalCount} got=${found.messages.size} $kinds")
                }
                found.messages.forEach { message -> toItem(message)?.let { items[it.messageId] = it } }
                emit(items.values.sortedByDescending { it.date })
                if (found.nextFromMessageId == 0L || found.messages.isEmpty()) break
                fromMessageId = found.nextFromMessageId
            }
        }
    }

    fun toItem(message: TdApi.Message): VideoItem? = when (val content = message.content) {
        is TdApi.MessageDocument -> {
            val doc = content.document
            val ext = doc.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (doc.mimeType.startsWith("video/") || ext in VIDEO_EXTENSIONS) {
                val release = release(content.caption.text, doc.fileName)
                VideoItem(
                    messageId = message.id,
                    release = release,
                    fileId = doc.document.id,
                    fileKey = keyOf(doc.document),
                    size = fileSize(doc.document),
                    width = 0,
                    height = 0,
                    // Files sent as documents carry no duration; the caption's ⏳ line does.
                    durationSec = release.runtimeSec,
                    date = message.date,
                    pinned = message.isPinned,
                    reacted = reactedByMe(message.interactionInfo),
                )
            } else {
                null
            }
        }
        is TdApi.MessageVideo -> {
            val video = content.video
            val release = release(content.caption.text, video.fileName)
            VideoItem(
                messageId = message.id,
                release = release,
                fileId = video.video.id,
                fileKey = keyOf(video.video),
                size = fileSize(video.video),
                width = video.width,
                height = video.height,
                durationSec = video.duration.takeIf { it > 0 } ?: release.runtimeSec,
                date = message.date,
                pinned = message.isPinned,
                reacted = reactedByMe(message.interactionInfo),
            )
        }
        else -> null
    }

    private fun release(caption: String, fileName: String): Release = Release.parse(caption, fileName).also {
        // One line per video, so a new caption format can be checked with `adb logcat -s Release:I`.
        if (BuildConfig.DEBUG) android.util.Log.i("Release", "${caption.lineSequence().firstOrNull().orEmpty().take(90)} → $it")
    }

    /** The video file's permanent key, for any message that carries one (a channel post or its copy in Saved Messages). */
    fun fileKeyOf(message: TdApi.Message): String? = when (val content = message.content) {
        is TdApi.MessageDocument -> keyOf(content.document.document)
        is TdApi.MessageVideo -> keyOf(content.video.video)
        else -> null
    }

    fun reactedByMe(info: TdApi.MessageInteractionInfo?): Boolean =
        info?.reactions?.reactions?.any { it.isChosen } == true

    /**
     * Saved Messages, as message id → file key. A video saved (forwarded) there from the channel
     * keeps the same Telegram file, so its key matches the channel post's.
     */
    suspend fun savedFiles(savedChatId: Long): Map<Long, String> {
        val found = HashMap<Long, String>()
        for (filter in listOf(TdApi.SearchMessagesFilterDocument(), TdApi.SearchMessagesFilterVideo())) {
            var fromMessageId = 0L
            for (page in 0 until SAVED_PAGES_PER_FILTER) {
                val result = Td.send(TdApi.SearchChatMessages(savedChatId, null, "", null, fromMessageId, 0, 100, filter))
                result.messages.forEach { m -> fileKeyOf(m)?.let { found[m.id] = it } }
                if (result.nextFromMessageId == 0L || result.messages.isEmpty()) break
                fromMessageId = result.nextFromMessageId
            }
        }
        return found
    }

    private fun keyOf(file: TdApi.File) = file.remote.uniqueId.ifEmpty { "file-${file.id}" }

    private fun fileSize(file: TdApi.File) = if (file.size > 0) file.size else file.expectedSize
}
