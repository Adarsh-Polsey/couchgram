package tv.couchgram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.couchgram.ui.CouchgramTheme
import tv.couchgram.ui.Motion
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Td.start(this)
        WatchStore.init(this)
        MediaCache.init(this)
        setContent {
            CouchgramTheme { CouchgramApp() }
        }
    }
}

private sealed interface Route {
    data object SignIn : Route
    data object Library : Route
    data class Player(val item: VideoItem) : Route
}

@Composable
private fun CouchgramApp(library: LibraryViewModel = viewModel()) {
    val auth by Td.auth.collectAsStateWithLifecycle()
    val libraryState by library.state.collectAsStateWithLifecycle()
    // Saveable, so the player reopens on the same video if Android kills the app in the background.
    var playingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastPlayedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var remoteStartedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val wasSignedIn = remember { Prefs.wasSignedIn }
    val command by library.command.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        library.foreground = true
        library.onAppOpened()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { library.foreground = false }
    // `/play` (as a reply to a video) and `/stop` posted in the channel steer the TV.
    LaunchedEffect(command) {
        when (val c = command) {
            is TvCommand.Play -> {
                lastPlayedId = c.item.messageId
                playingId = c.item.messageId
                remoteStartedId = c.item.messageId
            }
            TvCommand.Stop -> playingId = null
            null -> return@LaunchedEffect
        }
        library.commandDone()
    }

    val ready = auth is TdApi.AuthorizationStateReady
    val starting = auth == null || auth is TdApi.AuthorizationStateWaitTdlibParameters
    val playing = playingId?.let { id -> (libraryState as? LibraryState.Loaded)?.items?.firstOrNull { it.messageId == id } }

    val route = when {
        ready && playing != null -> Route.Player(playing)
        // A returning user goes straight to the library's skeleton while TDLib opens its database.
        ready || (starting && wasSignedIn) -> Route.Library
        else -> Route.SignIn
    }
    SideEffect { library.playing = route is Route.Player }

    AnimatedContent(
        targetState = route,
        transitionSpec = { fadeIn(tween(Motion.STANDARD)) togetherWith fadeOut(tween(Motion.STANDARD)) },
        contentKey = { it::class },
        label = "route",
    ) { target ->
        when (target) {
            Route.SignIn -> LoginScreen(auth)
            Route.Library -> LibraryScreen(
                vm = library,
                ready = ready,
                focusId = lastPlayedId,
                onPlay = {
                    lastPlayedId = it.messageId
                    playingId = it.messageId
                    remoteStartedId = null
                },
            )
            // Keyed, so a `/play` for another video while one is playing starts a fresh player.
            is Route.Player -> key(target.item.messageId) {
                PlayerScreen(target.item, onExit = { playingId = null }, autoResume = remoteStartedId == target.item.messageId)
            }
        }
    }
}
