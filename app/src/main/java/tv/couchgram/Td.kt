package tv.couchgram

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdException(val error: TdApi.Error) : Exception("Telegram error ${error.code}: ${error.message}")

/**
 * The app's single TDLib client. TDLib calls back on its own thread; results are bridged to
 * coroutines by [send], and the update streams the app cares about are exposed as flows.
 */
object Td {
    private const val TAG = "Td"

    @Volatile
    private var client: Client? = null
    private lateinit var appContext: Context

    private val _auth = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val auth: StateFlow<TdApi.AuthorizationState?> = _auth

    private val _connection = MutableStateFlow<TdApi.ConnectionState?>(null)
    val connection: StateFlow<TdApi.ConnectionState?> = _connection

    // Download progress for every file; readers filter by file id. Dropping old progress is fine,
    // a newer update for the same file always follows.
    private val _files = MutableSharedFlow<TdApi.File>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val files: SharedFlow<TdApi.File> = _files

    /** New and deleted messages, pins and reactions, so an open library follows the phone without a refresh. */
    private val _messages = MutableSharedFlow<TdApi.Update>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<TdApi.Update> = _messages

    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        Prefs.init(appContext)
        Reporter.init(appContext)
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create({ onUpdate(it) }, null, null)
    }

    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            requireNotNull(client).send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(TdException(result))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }

    /** Fire-and-forget, for calls nobody waits on (closing a chat, cleanup). */
    fun post(function: TdApi.Function<*>) {
        client?.send(function, ::logError)
    }

    /** Stop downloading a file and delete what's on disk; TV sticks have little storage. */
    fun forget(fileId: Int) {
        client?.send(TdApi.CancelDownloadFile(fileId, false)) { post(TdApi.DeleteFile(fileId)) }
    }

    /** Ask for a fresh sign-in code, e.g. after the network came back. */
    fun requestQrCode() = post(TdApi.RequestQrCodeAuthentication(LongArray(0)))

    /** Ends this TV's Telegram session; TDLib closes and a new client starts sign-in again. */
    fun signOut() = post(TdApi.LogOut())

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                val state = update.authorizationState
                when (state) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
                    // A TV has no convenient keyboard: always sign in by QR code from the phone.
                    is TdApi.AuthorizationStateWaitPhoneNumber -> requestQrCode()
                    is TdApi.AuthorizationStateReady -> Prefs.wasSignedIn = true
                    is TdApi.AuthorizationStateLoggingOut -> {
                        // Another account may sign in next: drop this one's progress and cached video.
                        Prefs.clearSession()
                        WatchStore.clear()
                        MediaCache.clearAll()
                    }
                    else -> Unit
                }
                _auth.value = state
                // After a sign-out TDLib closes this client for good; a fresh one starts sign-in.
                // Created after publishing Closed, so its first update can't be overwritten by it.
                if (state is TdApi.AuthorizationStateClosed) {
                    client = Client.create({ onUpdate(it) }, null, null)
                }
            }
            is TdApi.UpdateConnectionState -> _connection.value = update.state
            is TdApi.UpdateFile -> _files.tryEmit(update.file)
            is TdApi.UpdateNewMessage, is TdApi.UpdateDeleteMessages,
            is TdApi.UpdateMessageIsPinned, is TdApi.UpdateMessageInteractionInfo -> _messages.tryEmit(update as TdApi.Update)
        }
    }

    private fun sendParameters() {
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(appContext.filesDir, "tdlib").absolutePath
            // Streamed video chunks go to the cache dir, so Android may also reclaim them.
            filesDirectory = File(appContext.cacheDir, "tdlib-files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = "en"
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        post(params)
    }

    private fun logError(result: TdApi.Object) {
        if (BuildConfig.DEBUG && result is TdApi.Error) Log.w(TAG, "TDLib error ${result.code}: ${result.message}")
    }
}
