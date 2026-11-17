package tv.couchgram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Small non-secret app state. The Telegram session itself lives in TDLib's database. */
object Prefs {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.getSharedPreferences("couchgram", Context.MODE_PRIVATE)
    }

    /** True once a sign-in has completed, so a cold start can show the library's skeleton straight away. */
    var wasSignedIn: Boolean
        get() = prefs.getBoolean("signed_in", false)
        set(value) = prefs.edit { putBoolean("signed_in", value) }

    /** The chat the library shows, picked on first launch (0 = not picked yet). */
    var channelId: Long
        get() = prefs.getLong("channel_id", 0L)
        set(value) = prefs.edit { putLong("channel_id", value) }

    /**
     * Phone remote: the TV follows `/play`, `/stop`, `/storage` and `/clearcache` posted in the channel,
     * replies there, and posts crash lines there. Off by default: it writes to the user's channel.
     */
    var phoneRemote: Boolean
        get() = prefs.getBoolean("phone_remote", false)
        set(value) = prefs.edit { putBoolean("phone_remote", value) }

    /** A crash line saved by the crash handler, posted on the next launch (phone remote only). */
    var pendingCrash: String?
        get() = prefs.getString("pending_crash", null)
        set(value) = prefs.edit(commit = true) { putString("pending_crash", value) }

    /** Player picture mode (Fit / Fill / Stretch), kept across videos. */
    var pictureMode: PictureMode
        get() = runCatching { PictureMode.valueOf(prefs.getString("picture", null) ?: "") }.getOrDefault(PictureMode.Fit)
        set(value) = prefs.edit { putString("picture", value.name) }

    /** Subtitle language last chosen ("und" if the track had none); null = subtitles off. */
    var subtitleLanguage: String?
        get() = prefs.getString("subtitles", null)
        set(value) = prefs.edit { putString("subtitles", value) }

    fun clearSession() = prefs.edit { clear() }
}
