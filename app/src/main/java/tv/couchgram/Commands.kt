package tv.couchgram

import org.drinkless.tdlib.TdApi
import java.util.Locale

/** What the phone can tell the TV by posting in the channel. */
sealed interface Command {
    /** `/play` sent as a reply to a video: play that one. */
    data class Play(val messageId: Long) : Command

    /** `/play` pointing at nothing; the TV answers with how to use it. */
    data object PlayHint : Command

    /** `/stop` or `/back`: leave the player. */
    data object Stop : Command

    /** `/storage`: post the storage line. */
    data object Storage : Command

    /** `/clearcache` or `/clear`: what the Storage panel's Clear cache does. */
    data object ClearCache : Command

    /** Answered when they're the channel's last message at app open too (sent while the TV was off). */
    val answersWhenLast: Boolean get() = this == Storage || this == ClearCache
}

object Commands {
    /** Commands older than this are stale: TDLib replays messages the TV missed while offline. */
    const val MAX_AGE_S = 120

    private val COMMAND = Regex("""^/([A-Za-z]+)(?:@\w+)?(?=\s|$)""")

    fun parse(text: String, replyToMessageId: Long?): Command? =
        when (COMMAND.find(text.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)) {
            "play" -> replyToMessageId?.let(Command::Play) ?: Command.PlayHint
            "stop", "back" -> Command.Stop
            "storage" -> Command.Storage
            "clearcache", "clear" -> Command.ClearCache
            else -> null
        }

    fun of(message: TdApi.Message): Command? {
        val text = (message.content as? TdApi.MessageText)?.text?.text ?: return null
        // chatId is 0 when TDLib doesn't know the replied chat; in a channel that's this one.
        val reply = (message.replyTo as? TdApi.MessageReplyToMessage)
            ?.takeIf { it.chatId == message.chatId || it.chatId == 0L }?.messageId
        return parse(text, reply)
    }

    fun isStale(message: TdApi.Message, nowMillis: Long = System.currentTimeMillis()) =
        nowMillis / 1000 - message.date > MAX_AGE_S
}
