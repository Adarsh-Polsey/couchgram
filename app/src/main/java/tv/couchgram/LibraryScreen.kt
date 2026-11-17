package tv.couchgram

import android.app.Application
import androidx.annotation.StringRes
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.couchgram.ui.CaptionText
import tv.couchgram.ui.CenteredPanel
import tv.couchgram.ui.DangerButton
import tv.couchgram.ui.CouchgramTheme
import tv.couchgram.ui.LibraryLayout
import tv.couchgram.ui.MessagePanel
import tv.couchgram.ui.PrimaryButton
import tv.couchgram.ui.Radius
import tv.couchgram.ui.ScreenFrame
import tv.couchgram.ui.SecondaryButton
import tv.couchgram.ui.SkeletonRails
import tv.couchgram.ui.Spacing
import tv.couchgram.ui.StatusPill
import tv.couchgram.ui.TertiaryButton
import tv.couchgram.ui.Wordmark
import tv.couchgram.ui.Tone
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.text.NumberFormat
import java.util.Date

sealed interface LoadFailure {
    data object ChannelMissing : LoadFailure
    data object Timeout : LoadFailure
    data class Telegram(val message: String) : LoadFailure
}

sealed interface LibraryState {
    data object Loading : LibraryState
    data class Failed(val reason: LoadFailure) : LibraryState
    data class Loaded(val items: List<VideoItem>, val complete: Boolean, val refreshing: Boolean = false) : LibraryState
}

sealed interface ReportStatus {
    data class Posted(val atMillis: Long) : ReportStatus
    data class Failed(val reason: String) : ReportStatus
}

/** Storage panel state: numbers, the clear action's progress and result, and the last channel report. */
data class StorageUi(
    val snapshot: StorageSnapshot? = null,
    val clearing: Boolean = false,
    val cleared: Long? = null,
    val report: ReportStatus? = null,
)

/** A channel command for the screens to carry out (see [Commands]); the activity calls [LibraryViewModel.commandDone]. */
sealed interface TvCommand {
    data class Play(val item: VideoItem) : TvCommand
    data object Stop : TvCommand
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val state: StateFlow<LibraryState> = _state

    private val _command = MutableStateFlow<TvCommand?>(null)
    val command: StateFlow<TvCommand?> = _command

    /** The channel's most recently pinned message (may not be a video; the screen checks). */
    private val _pinnedId = MutableStateFlow<Long?>(null)
    val pinnedId: StateFlow<Long?> = _pinnedId

    /** Set by the activity: `/play` and `/stop` only act while Couchgram is on screen. */
    @Volatile
    var foreground = false

    /** Set by the activity: `/clearcache` waits while the player is up, since it would delete what's streaming. */
    @Volatile
    var playing = false

    /** The rail the last video was started from, so Back lands on the same card. */
    var lastRail: String? = null

    private val _storage = MutableStateFlow(StorageUi())
    val storage: StateFlow<StorageUi> = _storage

    /** File keys of the channel videos also in Saved Messages: one of the three ways into Favourites. */
    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    val saved: StateFlow<Set<String>> = _saved

    /** The channel picker while it's open: after the first sign-in, or from Settings. */
    private val _picker = MutableStateFlow<PickerUi?>(null)
    val picker: StateFlow<PickerUi?> = _picker

    /** The chosen chat's title, for the screens' wording ("No videos in …"). */
    private val _channelTitle = MutableStateFlow("")
    val channelTitle: StateFlow<String> = _channelTitle

    /** Phone remote on: commands in the channel are followed and answered (see [Prefs.phoneRemote]). */
    private val _phoneRemote = MutableStateFlow(Prefs.phoneRemote)
    val phoneRemote: StateFlow<Boolean> = _phoneRemote

    /** The open chat, so the picker can mark it; 0 before one is open. */
    val currentChannelId: Long get() = chatId

    private var chatId = 0L
    private var pickerJob: Job? = null
    private var savedChatId = 0L
    private val savedByMessage = HashMap<Long, String>()
    private var job: Job? = null
    private var lastReportAt = 0L
    /** Command messages already handled this session: an open and a live update can both see one. */
    private val answered = HashSet<Long>()

    init {
        viewModelScope.launch {
            Td.auth.collect { auth ->
                when (auth) {
                    is TdApi.AuthorizationStateReady -> if (_state.value == LibraryState.Loading && job?.isActive != true) {
                        viewModelScope.launch { runCatching { Storage.dropTelegramCopies() } }
                        load()
                    }
                    is TdApi.AuthorizationStateLoggingOut -> reset()
                    else -> Unit
                }
            }
        }
        viewModelScope.launch { Td.messages.collect(::onMessageUpdate) }
    }

    /**
     * First load fills the grid page by page; a refresh keeps the current grid until the new one is ready.
     * With no channel chosen yet (first sign-in), the picker opens instead.
     */
    fun load() {
        val id = Prefs.channelId
        if (id == 0L) return openPicker(canCancel = false)
        job?.cancel()
        val previous = _state.value as? LibraryState.Loaded
        _state.value = previous?.copy(refreshing = true) ?: LibraryState.Loading
        job = viewModelScope.launch {
            try {
                withTimeout(LOAD_TIMEOUT_MS) {
                    val chat = Library.chat(id)
                    if (chat == null) {
                        _state.value = LibraryState.Failed(LoadFailure.ChannelMissing)
                        return@withTimeout
                    }
                    _channelTitle.value = chat.title.trim()
                    openChat(chat.id)
                    var latest = emptyList<VideoItem>()
                    Library.videos(id).collect { items ->
                        latest = items
                        if (previous == null) _state.value = LibraryState.Loaded(items, complete = false)
                    }
                    _state.value = LibraryState.Loaded(latest, complete = true)
                    refreshStorage()
                    loadSaved()
                    loadPinned()
                    answerLastCommand()
                }
            } catch (e: TimeoutCancellationException) {
                _state.value = settle() ?: LibraryState.Failed(LoadFailure.Timeout)
            } catch (e: TdException) {
                _state.value = settle() ?: LibraryState.Failed(LoadFailure.Telegram(e.error.message))
            }
        }
    }

    fun signOut() = Td.signOut()

    /** Lists the account's channels and groups. [canCancel]: Back returns to what was on screen. */
    fun openPicker(canCancel: Boolean) {
        pickerJob?.cancel()
        _picker.value = PickerUi(canCancel = canCancel)
        pickerJob = viewModelScope.launch {
            _picker.value = try {
                PickerUi(choices = withTimeout(LOAD_TIMEOUT_MS) { Library.choices() }, canCancel = canCancel)
            } catch (e: TimeoutCancellationException) {
                PickerUi(failure = LoadFailure.Timeout, canCancel = canCancel)
            } catch (e: TdException) {
                PickerUi(failure = LoadFailure.Telegram(e.error.message), canCancel = canCancel)
            }
        }
    }

    fun cancelPicker() {
        if (_picker.value?.canCancel != true) return
        pickerJob?.cancel()
        _picker.value = null
    }

    /** Switches the library to [choice]. Watch progress and the cache are keyed by file, so they carry over. */
    fun choose(choice: ChannelChoice) {
        pickerJob?.cancel()
        _picker.value = null
        if (choice.id == chatId && _state.value is LibraryState.Loaded) return
        leaveChannel()
        Prefs.channelId = choice.id
        _channelTitle.value = choice.title
        load()
    }

    fun setPhoneRemote(on: Boolean) {
        Prefs.phoneRemote = on
        _phoneRemote.value = on
        if (!on) {
            Prefs.pendingCrash = null
            _storage.value = _storage.value.copy(report = null)
        }
    }

    /** Called every time the app comes to the front. The first load answers commands itself. */
    fun onAppOpened() {
        refreshStorage()
        if (chatId != 0L) answerLastCommand()
    }

    fun commandDone() {
        _command.value = null
    }

    /**
     * With the phone remote on, opening the Storage panel posts the storage line; at most every 30 s,
     * so a quick reopen doesn't repeat it.
     */
    fun openStorage() = viewModelScope.launch {
        val snapshot = Storage.snapshot(getApplication())
        _storage.value = _storage.value.copy(snapshot = snapshot)
        if (!_phoneRemote.value) return@launch
        val now = System.currentTimeMillis()
        if (now - lastReportAt < REPORT_MIN_GAP_MS) return@launch
        lastReportAt = now
        val status = try {
            Reporter.sendNow(Reporter.storage(snapshot))
            ReportStatus.Posted(now)
        } catch (e: TdException) {
            ReportStatus.Failed(e.error.message)
        } catch (e: IllegalStateException) {
            ReportStatus.Failed(e.message ?: "channel not ready")
        }
        _storage.value = _storage.value.copy(report = status)
    }

    fun refreshStorage() = viewModelScope.launch {
        _storage.value = _storage.value.copy(snapshot = Storage.snapshot(getApplication()))
    }

    fun clearCache() {
        viewModelScope.launch { clearCacheNow() }
    }

    /** Clears and refreshes the panel's numbers; null if a clear was already running. */
    private suspend fun clearCacheNow(): Pair<Long, StorageSnapshot>? {
        if (_storage.value.clearing) return null
        _storage.value = _storage.value.copy(clearing = true, cleared = null)
        val freed = runCatching { Storage.clearCache() }.getOrDefault(0L)
        val snapshot = Storage.snapshot(getApplication())
        _storage.value = _storage.value.copy(snapshot = snapshot, clearing = false, cleared = freed)
        return freed to snapshot
    }

    /** On a failed refresh or a slow tail, keep whatever is already on screen. */
    private fun settle(): LibraryState? =
        (_state.value as? LibraryState.Loaded)?.takeIf { it.items.isNotEmpty() }?.copy(complete = true, refreshing = false)

    private fun loadPinned() = viewModelScope.launch {
        _pinnedId.value = runCatching { Td.send(TdApi.GetChatPinnedMessage(chatId)).id }.getOrNull()
    }

    /**
     * `/storage` or `/clearcache` left as the channel's last message (say, sent while the TV was off)
     * is answered when the app opens. Only the very last message counts, so an old one never runs twice:
     * once answered, the reply is the last message.
     */
    private fun answerLastCommand() {
        if (!_phoneRemote.value) return
        viewModelScope.launch {
            val last = runCatching { Library.lastMessage(chatId) }.getOrNull() ?: return@launch
            if (Commands.of(last)?.answersWhenLast == true) handle(last)
        }
    }

    /** Carries out a command and replies under it, so the phone sees the TV heard it. */
    private fun handle(message: TdApi.Message) {
        if (!_phoneRemote.value) return
        val command = Commands.of(message) ?: return
        if (!answered.add(message.id)) return
        when (command) {
            Command.Storage -> viewModelScope.launch {
                val snapshot = Storage.snapshot(getApplication())
                _storage.value = _storage.value.copy(snapshot = snapshot)
                Reporter.post(Reporter.storage(snapshot), replyTo = message.id)
            }
            Command.ClearCache -> {
                if (playing) return Reporter.post(Reporter.clearWhilePlaying(), replyTo = message.id)
                viewModelScope.launch {
                    val (freed, snapshot) = clearCacheNow() ?: return@launch
                    Reporter.post(Reporter.cleared(freed, snapshot), replyTo = message.id)
                }
            }
            is Command.Play -> {
                if (!foreground) return Reporter.post(Reporter.notOpen(), replyTo = message.id)
                viewModelScope.launch {
                    val loaded = (_state.value as? LibraryState.Loaded)?.items.orEmpty()
                    val item = loaded.firstOrNull { it.messageId == command.messageId }
                        ?: runCatching { Td.send(TdApi.GetMessage(chatId, command.messageId)) }.getOrNull()?.let(Library::toItem)
                    if (item == null) return@launch Reporter.post(Reporter.playHint(), replyTo = message.id)
                    // An old video the library hasn't paged in yet still has to be found by the player route.
                    if (loaded.none { it.messageId == item.messageId }) updateItems { it + item }
                    lastRail = null
                    _command.value = TvCommand.Play(item)
                    Reporter.post(Reporter.playing(item.title), replyTo = message.id)
                }
            }
            Command.PlayHint -> Reporter.post(Reporter.playHint(), replyTo = message.id)
            Command.Stop -> if (foreground) {
                _command.value = TvCommand.Stop
                Reporter.post(Reporter.stopped(), replyTo = message.id)
            }
        }
    }

    /** Saved Messages is the account's own chat; a failed search just leaves Favourites to pins and reactions. */
    private fun loadSaved() = viewModelScope.launch {
        runCatching {
            val me = Td.send(TdApi.GetMe())
            val chat = Td.send(TdApi.CreatePrivateChat(me.id, true))
            savedChatId = chat.id
            val found = Library.savedFiles(chat.id)
            savedByMessage.clear()
            savedByMessage.putAll(found)
            _saved.value = savedByMessage.values.toSet()
        }
    }

    /** Opening the chat makes TDLib deliver its new messages live. */
    private fun openChat(id: Long) {
        if (id == chatId) return
        if (chatId != 0L) Td.post(TdApi.CloseChat(chatId))
        chatId = id
        Td.post(TdApi.OpenChat(id))
        Reporter.channelReady(id)
    }

    private fun onMessageUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewMessage -> {
                val message = update.message
                if (savedChatId != 0L && message.chatId == savedChatId) {
                    Library.fileKeyOf(message)?.let { savedByMessage[message.id] = it }
                    _saved.value = savedByMessage.values.toSet()
                    return
                }
                if (message.chatId != chatId) return
                Commands.of(message)?.let { command ->
                    if (!_phoneRemote.value) return
                    when {
                        // Only if it's still the last message once everything missed has arrived.
                        command.answersWhenLast -> answerLastCommand()
                        // TDLib also replays messages missed while offline; an old /play must not start a film.
                        !Commands.isStale(message) -> handle(message)
                    }
                    return
                }
                val item = Library.toItem(message) ?: return
                updateItems { items -> if (items.any { it.messageId == item.messageId }) items else listOf(item) + items }
            }
            is TdApi.UpdateDeleteMessages -> {
                if (!update.isPermanent) return
                if (savedChatId != 0L && update.chatId == savedChatId) {
                    update.messageIds.forEach { savedByMessage.remove(it) }
                    _saved.value = savedByMessage.values.toSet()
                    return
                }
                if (update.chatId != chatId) return
                val gone = update.messageIds.toSet()
                updateItems { items -> items.filterNot { it.messageId in gone } }
            }
            is TdApi.UpdateMessageIsPinned -> if (update.chatId == chatId) {
                updateItems { items -> items.map { if (it.messageId == update.messageId) it.copy(pinned = update.isPinned) else it } }
                // A new pin goes to the top; unpinning the top one falls back to the next newest pin.
                if (update.isPinned) _pinnedId.value = update.messageId
                else if (_pinnedId.value == update.messageId) loadPinned()
            }
            is TdApi.UpdateMessageInteractionInfo -> if (update.chatId == chatId) {
                val reacted = Library.reactedByMe(update.interactionInfo)
                updateItems { items -> items.map { if (it.messageId == update.messageId && it.reacted != reacted) it.copy(reacted = reacted) else it } }
            }
            else -> Unit
        }
    }

    private inline fun updateItems(change: (List<VideoItem>) -> List<VideoItem>) {
        val loaded = _state.value as? LibraryState.Loaded ?: return
        _state.value = loaded.copy(items = change(loaded.items))
    }

    /** Forgets everything tied to the open chat, before switching channels or signing out. */
    private fun leaveChannel() {
        job?.cancel()
        if (chatId != 0L) Td.post(TdApi.CloseChat(chatId))
        chatId = 0L
        _pinnedId.value = null
        _command.value = null
        answered.clear()
        lastRail = null
        Reporter.channelReady(0L)
        _state.value = LibraryState.Loading
    }

    private fun reset() {
        leaveChannel()
        pickerJob?.cancel()
        _picker.value = null
        _channelTitle.value = ""
        _phoneRemote.value = false // Prefs were cleared on sign-out
        savedChatId = 0L
        savedByMessage.clear()
        _saved.value = emptySet()
        _storage.value = StorageUi()
    }

    override fun onCleared() {
        if (chatId != 0L) Td.post(TdApi.CloseChat(chatId))
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 45_000L
        const val REPORT_MIN_GAP_MS = 30_000L
    }
}

/**
 * [ready]: TDLib is signed in; before that the screen shows its skeleton (cold start).
 * [focusId]: the video to put focus on, so coming back from the player lands where you left.
 */
@Composable
fun LibraryScreen(vm: LibraryViewModel, ready: Boolean, focusId: Long?, onPlay: (VideoItem) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val connection by Td.connection.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val watch by WatchStore.states.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val pinnedId by vm.pinnedId.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    val channel by vm.channelTitle.collectAsStateWithLifecycle()
    val phoneRemote by vm.phoneRemote.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    picker?.let { ui ->
        ChannelPicker(
            ui = ui,
            currentId = vm.currentChannelId,
            onChoose = vm::choose,
            onRetry = { vm.openPicker(ui.canCancel) },
            onCancel = vm::cancelPicker,
            onSignOut = { confirmSignOut = true },
        )
        if (confirmSignOut) SignOutDialog(onConfirm = { confirmSignOut = false; vm.signOut() }, onDismiss = { confirmSignOut = false })
        return
    }

    // No side padding: rails scroll to the screen edge and pad themselves; everything else is padded below.
    ScreenFrame(horizontal = 0.dp) {
        LibraryHeader(
            modifier = Modifier.padding(horizontal = Spacing.safeHorizontal),
            // The empty state already says "No videos"; a "0 videos" header would repeat it.
            count = (state as? LibraryState.Loaded)?.items?.size?.takeIf { it > 0 },
            freeBytes = storage.snapshot?.freeBytes,
            status = status(state, connection),
            actionsEnabled = ready && state != LibraryState.Loading,
            onRefresh = vm::load,
            onStorage = {
                vm.openStorage()
                showStorage = true
            },
            onSettings = { showSettings = true },
        )
        when (val s = state) {
            LibraryState.Loading -> SkeletonRails()
            is LibraryState.Failed -> CenteredPanel {
                FailurePanel(
                    reason = s.reason,
                    onRetry = vm::load,
                    onChooseChannel = { vm.openPicker(canCancel = true) },
                    onSignOut = { confirmSignOut = true },
                )
            }
            is LibraryState.Loaded -> when {
                s.items.isNotEmpty() -> LibraryRails(
                    items = s.items,
                    complete = s.complete,
                    watch = watch,
                    saved = saved,
                    pinnedId = pinnedId,
                    focusId = focusId,
                    focusRail = vm.lastRail,
                    onPlay = { item, rail ->
                        vm.lastRail = rail.name
                        onPlay(item)
                    },
                )
                !s.complete -> SkeletonRails()
                else -> CenteredPanel { EmptyPanel(channel, onRefresh = vm::load, onChangeChannel = { vm.openPicker(canCancel = true) }) }
            }
        }
    }

    if (showStorage) {
        StorageDialog(storage, channel, onClear = vm::clearCache, onClose = { showStorage = false })
    }

    if (showSettings) {
        SettingsDialog(
            channel = channel,
            phoneRemote = phoneRemote,
            onChangeChannel = {
                showSettings = false
                vm.openPicker(canCancel = true)
            },
            onPhoneRemote = vm::setPhoneRemote,
            onSignOut = {
                showSettings = false
                confirmSignOut = true
            },
            onClose = { showSettings = false },
        )
    }

    if (confirmSignOut) {
        SignOutDialog(
            onConfirm = {
                confirmSignOut = false
                vm.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun status(state: LibraryState, connection: TdApi.ConnectionState?): Pair<String, Tone>? = when {
    connection is TdApi.ConnectionStateWaitingForNetwork -> stringResource(R.string.status_waiting_network) to Tone.Warning
    state is LibraryState.Loaded && state.refreshing -> stringResource(R.string.status_updating) to Tone.Active
    state is LibraryState.Loaded && !state.complete -> stringResource(R.string.status_loading_more) to Tone.Active
    else -> null
}

@Composable
private fun LibraryHeader(
    modifier: Modifier,
    count: Int?,
    freeBytes: Long?,
    status: Pair<String, Tone>?,
    actionsEnabled: Boolean,
    onRefresh: () -> Unit,
    onStorage: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f)) {
            Wordmark()
            val subtitle = listOfNotNull(
                count?.let { pluralStringResource(R.plurals.library_count, it, it) },
                freeBytes?.let { stringResource(R.string.storage_free_short, Formatter.formatShortFileSize(context, it)) },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CouchgramTheme.colors.textSecondary)
            }
        }
        if (status != null) StatusPill(status.first, status.second)
        SecondaryButton(stringResource(R.string.library_refresh), onRefresh, enabled = actionsEnabled)
        SecondaryButton(stringResource(R.string.storage_button), onStorage, enabled = actionsEnabled)
        // Channel, phone remote and sign-out live in Settings.
        SecondaryButton(stringResource(R.string.settings_button), onSettings, enabled = actionsEnabled)
    }
}

private enum class Rail(@StringRes val title: Int) {
    Pinned(R.string.rail_pinned),
    Watching(R.string.rail_watching),
    Favourites(R.string.rail_favourites),
    Latest(R.string.rail_latest),
}

/**
 * Top to bottom, under a details band for the focused film: the channel's last pinned video as one
 * wide card, Watching (unfinished, most recent first), Favourites (pinned, reacted to or saved in
 * Telegram) and Latest (newest uploads not being watched). Pinned and Watching hide when empty;
 * Favourites stays and says how to fill it.
 */
// LocalBringIntoViewSpec is still experimental in foundation; it's the supported way to change focus scrolling.
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRails(
    items: List<VideoItem>,
    complete: Boolean,
    watch: Map<String, WatchState>,
    saved: Set<String>,
    pinnedId: Long?,
    focusId: Long?,
    focusRail: String?,
    onPlay: (VideoItem, Rail) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val rails = remember(items, watch, saved, pinnedId) {
        // The newest pin, if it's a video; else the newest pinned video.
        val pinned = items.firstOrNull { it.messageId == pinnedId && it.pinned }
            ?: items.filter { it.pinned }.maxByOrNull { it.messageId }
        val watching = items.filter { watch[it.fileKey]?.inProgress == true }
            .sortedByDescending { watch[it.fileKey]?.updatedAt ?: 0L }
        val latest = items.filterNot { watch[it.fileKey]?.inProgress == true }
        listOfNotNull(
            pinned?.let { Rail.Pinned to listOf(it) },
            (Rail.Watching to watching).takeIf { watching.isNotEmpty() },
            Rail.Favourites to items.filter { it.isFavourite(saved) },
            (Rail.Latest to latest).takeIf { latest.isNotEmpty() },
        )
    }
    val rowStates = remember { Rail.entries.associateWith { LazyListState() } }
    val columnState = rememberLazyListState()
    val requester = remember { FocusRequester() }
    // Coming back from the player lands on the video just played, in the rail it was started from
    // when it's still there. Opening the app lands on Watching (resume is the usual next step).
    val target = remember(rails) {
        fun find(rail: Rail, list: List<VideoItem>) = list.indexOfFirst { it.messageId == focusId }.takeIf { it >= 0 }?.let { rail to it }
        rails.firstOrNull { it.first.name == focusRail }?.let { (rail, list) -> find(rail, list) }
            ?: rails.firstNotNullOfOrNull { (rail, list) -> find(rail, list) }
            ?: rails.firstOrNull { it.first == Rail.Watching }?.let { it.first to 0 }
            // Favourites can be empty (just its hint), so the first rail with a card.
            ?: (rails.first { it.second.isNotEmpty() }.first to 0)
    }
    var focusedId by remember { mutableStateOf(focusId) }
    val focused = items.firstOrNull { it.messageId == focusedId } ?: rails.firstNotNullOfOrNull { it.second.firstOrNull() }

    VideoDetails(focused, saved, Modifier.padding(start = Spacing.safeHorizontal, end = Spacing.safeHorizontal, top = Spacing.md))
    // Compose on TV scrolls a focused item to ~30% down the screen, which would tuck the pinned card
    // under the details band when Watching gets focus. Up and down scroll only as far as needed;
    // the rows keep the TV behaviour.
    val rowSpec = LocalBringIntoViewSpec.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides OnlyAsFarAsNeeded) {
    LazyColumn(
        state = columnState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.safeVertical),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(rails, key = { it.first.name }) { (rail, list) ->
            if (rail == Rail.Pinned) {
                val item = list.single()
                val state = watch[item.fileKey]
                PinnedCard(
                    item = item,
                    watch = state,
                    favourite = item.isFavourite(saved),
                    isNew = item.isNew(state, now),
                    onClick = { onPlay(item, Rail.Pinned) },
                    onFocused = { focusedId = item.messageId },
                    modifier = Modifier
                        .padding(horizontal = Spacing.safeHorizontal, vertical = Spacing.sm)
                        .then(if (target.first == Rail.Pinned) Modifier.focusRequester(requester) else Modifier),
                )
                return@items
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
            VideoRail(
                rail = rail,
                items = list,
                rowState = rowStates.getValue(rail),
                watch = watch,
                saved = saved,
                now = now,
                focusIndex = target.second.takeIf { target.first == rail },
                requester = requester,
                onFocused = { focusedId = it.messageId },
                onPlay = onPlay,
            )
            }
        }
    }
    }
    // Placed once the whole list is in: an early page may not hold the video being watched yet.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(complete) {
        if (!complete || placed) return@LaunchedEffect
        placed = true
        val (rail, index) = target
        val railIndex = rails.indexOfFirst { it.first == rail }
        if (columnState.layoutInfo.visibleItemsInfo.none { it.index == railIndex }) columnState.scrollToItem(railIndex)
        rowStates.getValue(rail).scrollToItem(index)
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** Scrolls just enough to show the requested item, and not at all if it's already in view. */
@kotlin.OptIn(ExperimentalFoundationApi::class)
private val OnlyAsFarAsNeeded = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            offset < 0f && end > containerSize -> 0f // bigger than the viewport: leave it
            offset < 0f -> offset
            else -> end - containerSize
        }
    }
}

@Composable
private fun VideoRail(
    rail: Rail,
    items: List<VideoItem>,
    rowState: LazyListState,
    watch: Map<String, WatchState>,
    saved: Set<String>,
    now: Long,
    focusIndex: Int?,
    requester: FocusRequester,
    onFocused: (VideoItem) -> Unit,
    onPlay: (VideoItem, Rail) -> Unit,
) {
    val colors = CouchgramTheme.colors
    val scope = rememberCoroutineScope()
    val wholeRail = remember { BringIntoViewRequester() }
    Column(
        Modifier
            .bringIntoViewRequester(wholeRail)
            // Scrolling to a card alone could hide its rail's title above it; bring the whole rail in.
            .onFocusChanged { if (it.hasFocus) scope.launch { wholeRail.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.safeHorizontal).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            when (rail) {
                Rail.Watching -> Box(Modifier.size(8.dp).background(colors.watching, CircleShape))
                Rail.Favourites -> Text("★", style = MaterialTheme.typography.titleSmall, color = colors.favourite)
                Rail.Latest, Rail.Pinned -> Unit
            }
            Text(stringResource(rail.title), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            if (items.isNotEmpty()) {
                Text(NumberFormat.getInstance().format(items.size), style = MaterialTheme.typography.titleSmall, color = colors.textSecondary)
            }
        }
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.favourites_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.safeHorizontal, vertical = Spacing.sm),
            )
            return@Column
        }
        LazyRow(
            state = rowState,
            // Re-entering a rail returns to the card last focused in it, as on other TV apps.
            modifier = Modifier.focusRestorer(),
            // Vertical room for the focused card's 1.1x scale and border.
            contentPadding = PaddingValues(horizontal = Spacing.safeHorizontal, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(LibraryLayout.cardGap),
        ) {
            itemsIndexed(items, key = { _, item -> item.messageId }, contentType = { _, _ -> "card" }) { index, item ->
                val state = watch[item.fileKey]
                MovieCard(
                    item = item,
                    watch = state,
                    favourite = item.isFavourite(saved),
                    isNew = item.isNew(state, now),
                    onClick = { onPlay(item, rail) },
                    onFocused = { onFocused(item) },
                    modifier = Modifier
                        .animateItem()
                        .then(if (index == focusIndex) Modifier.focusRequester(requester) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun EmptyPanel(channel: String, onRefresh: () -> Unit, onChangeChannel: () -> Unit) {
    val focus = remember { FocusRequester() }
    MessagePanel(
        title = stringResource(R.string.library_empty_title, channel),
        body = stringResource(R.string.library_empty_body),
    ) {
        PrimaryButton(stringResource(R.string.library_refresh), onRefresh, Modifier.focusRequester(focus))
        SecondaryButton(stringResource(R.string.settings_change_channel), onChangeChannel)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun FailurePanel(reason: LoadFailure, onRetry: () -> Unit, onChooseChannel: () -> Unit, onSignOut: () -> Unit) {
    val focus = remember { FocusRequester() }
    val (title, body) = when (reason) {
        LoadFailure.ChannelMissing ->
            stringResource(R.string.library_missing_title) to stringResource(R.string.library_missing_body)
        LoadFailure.Timeout ->
            stringResource(R.string.library_timeout_title) to stringResource(R.string.library_timeout_body)
        is LoadFailure.Telegram ->
            stringResource(R.string.library_failed_title) to stringResource(R.string.library_failed_body, reason.message)
    }
    MessagePanel(title, body) {
        if (reason == LoadFailure.ChannelMissing) {
            PrimaryButton(stringResource(R.string.action_choose_channel), onChooseChannel, Modifier.focusRequester(focus))
            SecondaryButton(stringResource(R.string.library_sign_out), onSignOut)
        } else {
            PrimaryButton(stringResource(R.string.action_try_again), onRetry, Modifier.focusRequester(focus))
        }
    }
    LaunchedEffect(reason) { runCatching { focus.requestFocus() } }
}

/** Which channel, the phone remote, and sign-out: the things set once and rarely touched. */
@Composable
private fun SettingsDialog(
    channel: String,
    phoneRemote: Boolean,
    onChangeChannel: () -> Unit,
    onPhoneRemote: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.settings_title)
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .background(CouchgramTheme.colors.surfaceRaised, RoundedCornerShape(Radius.large))
                // Scrolls on short screens, so the remote can always reach Close and Sign out.
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xxl)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            SettingRow(stringResource(R.string.settings_channel), channel) {
                SecondaryButton(stringResource(R.string.settings_change_channel), onChangeChannel, Modifier.focusRequester(focus))
            }
            SettingRow(
                stringResource(R.string.settings_phone_remote),
                stringResource(if (phoneRemote) R.string.settings_on else R.string.settings_off),
            ) {
                SecondaryButton(
                    stringResource(if (phoneRemote) R.string.settings_turn_off else R.string.settings_turn_on),
                    onClick = { onPhoneRemote(!phoneRemote) },
                )
            }
            CaptionText(stringResource(R.string.settings_phone_remote_body))
            Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PrimaryButton(stringResource(R.string.action_close), onClose)
                // Destructive, so last in the row and styled lowest.
                TertiaryButton(stringResource(R.string.library_sign_out), onSignOut)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun SettingRow(label: String, value: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f)) {
            CaptionText(label)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        action()
    }
}

@Composable
private fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.sign_out_title)
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .background(CouchgramTheme.colors.surfaceRaised, RoundedCornerShape(Radius.large))
                .padding(Spacing.xxl)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.sign_out_body), style = MaterialTheme.typography.bodyLarge, color = CouchgramTheme.colors.textSecondary)
            Spacer(Modifier.size(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                // Safe choice holds focus, so a stray OK press can't sign out.
                SecondaryButton(stringResource(R.string.action_cancel), onDismiss, Modifier.focusRequester(cancelFocus))
                DangerButton(stringResource(R.string.library_sign_out), onConfirm)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

@Composable
private fun StorageDialog(ui: StorageUi, channel: String, onClear: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = CouchgramTheme.colors
    val title = stringResource(R.string.storage_title)
    val clearFocus = remember { FocusRequester() }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(colors.surfaceRaised, RoundedCornerShape(Radius.large))
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xxl)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            val s = ui.snapshot
            if (s == null) {
                CaptionText(stringResource(R.string.storage_loading))
            } else {
                val used = (s.totalBytes - s.freeBytes).coerceAtLeast(0L)
                val low = s.freeBytes < Storage.MIN_FREE_TO_PLAY
                Text(stringResource(R.string.storage_free_line, size(s.freeBytes), size(s.totalBytes)), style = MaterialTheme.typography.titleMedium)
                // Used share of the TV's storage; warning colour (plus the sentence below) when too little is left to stream.
                Box(Modifier.fillMaxWidth().height(8.dp).background(colors.surface, RoundedCornerShape(Radius.small))) {
                    Box(
                        Modifier
                            .fillMaxWidth(used.toFloat() / s.totalBytes.coerceAtLeast(1L))
                            .fillMaxHeight()
                            .background(if (low) colors.warning else colors.accent, RoundedCornerShape(Radius.small)),
                    )
                }
                if (low) Text(stringResource(R.string.storage_low, size(Storage.MIN_FREE_TO_PLAY)), style = MaterialTheme.typography.bodyMedium, color = colors.warning)
                Text(stringResource(R.string.storage_cache_line, size(s.cacheBytes)), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                if (s.resumeVideos > 0) {
                    CaptionText(pluralStringResource(R.plurals.storage_resume, s.resumeVideos, s.resumeVideos, size(s.resumeBytes)))
                }
            }
            when (val r = ui.report) {
                is ReportStatus.Posted -> CaptionText(
                    stringResource(R.string.report_posted, channel, DateFormat.getTimeFormat(context).format(Date(r.atMillis))),
                )
                is ReportStatus.Failed -> Text(
                    stringResource(R.string.report_failed, channel, r.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.danger,
                )
                null -> Unit
            }
            ui.cleared?.let { freed ->
                Text(
                    if (freed > 0) stringResource(R.string.storage_cleared, size(freed)) else stringResource(R.string.storage_already_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accent,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                // Stays enabled (so focus doesn't jump) while the view model ignores repeat presses.
                PrimaryButton(
                    stringResource(if (ui.clearing) R.string.storage_clearing else R.string.storage_clear),
                    onClick = onClear,
                    modifier = Modifier.focusRequester(clearFocus),
                )
                SecondaryButton(stringResource(R.string.action_close), onClose)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { clearFocus.requestFocus() } }
}
