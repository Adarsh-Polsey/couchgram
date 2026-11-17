package tv.couchgram

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Where a video was left and whether it has been watched to the end once. Keyed by Telegram's permanent file id. */
@Immutable
data class WatchState(
    val positionMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val watched: Boolean,
    val updatedAt: Long,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Worth resuming: started (past the first 10 s) and not at the credits yet. */
    val inProgress: Boolean get() = positionMs > MIN_RESUME_MS && progress < WatchStore.WATCHED_AT

    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)

    private companion object {
        const val MIN_RESUME_MS = 10_000L
    }
}

object WatchStore {
    /** Past 95% the video counts as watched: the rest is usually credits. */
    val WATCHED_AT = if (BuildConfig.STRESS) 0.01f else 0.95f

    private lateinit var prefs: SharedPreferences
    private val _states = MutableStateFlow<Map<String, WatchState>>(emptyMap())
    val states: StateFlow<Map<String, WatchState>> = _states

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(if (BuildConfig.STRESS) "watch-stress" else "watch", Context.MODE_PRIVATE)
        _states.value = prefs.all.mapNotNull { (key, value) ->
            runCatching {
                val o = JSONObject(value as String)
                key to WatchState(o.getLong("p"), o.getLong("d"), o.getLong("s"), o.getBoolean("w"), o.getLong("u"))
            }.getOrNull()
        }.toMap()
    }

    operator fun get(key: String): WatchState? = _states.value[key]

    /** Records the play position. Returns true the first time the video crosses [WATCHED_AT]. */
    fun save(key: String, positionMs: Long, durationMs: Long, sizeBytes: Long): Boolean {
        if (durationMs <= 0) return false
        val old = _states.value[key]
        val finished = positionMs >= durationMs * WATCHED_AT
        // Finishing resets the resume point, so the next play starts from the top.
        val state = WatchState(
            positionMs = if (finished) 0L else positionMs,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            watched = finished || old?.watched == true,
            updatedAt = System.currentTimeMillis(),
        )
        _states.value = _states.value + (key to state)
        prefs.edit {
            putString(
                key,
                JSONObject()
                    .put("p", state.positionMs).put("d", state.durationMs).put("s", state.sizeBytes)
                    .put("w", state.watched).put("u", state.updatedAt)
                    .toString(),
            )
        }
        return finished && old?.watched != true
    }

    fun clear() {
        _states.value = emptyMap()
        if (::prefs.isInitialized) prefs.edit { clear() }
    }
}
