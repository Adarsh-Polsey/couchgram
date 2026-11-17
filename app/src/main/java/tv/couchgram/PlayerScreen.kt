package tv.couchgram

import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.couchgram.ui.CenteredPanel
import tv.couchgram.ui.CouchgramTheme
import tv.couchgram.ui.MessagePanel
import tv.couchgram.ui.Motion
import tv.couchgram.ui.PrimaryButton
import tv.couchgram.ui.ScreenFrame
import tv.couchgram.ui.SecondaryButton
import tv.couchgram.ui.Spacing
import tv.couchgram.ui.StatusPill
import tv.couchgram.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filter

private const val SIZE_WARNING_MS = 8_000L

private fun formatSize(bytes: Long) = "${bytes / 1_000_000} MB"
private const val PROGRESS_EVERY_MS = 5_000L

private enum class FailureKind { Decoder, Format, Network, Other }

/** Checks free space first: streaming needs ~0.7 GB, and failing halfway through a film is worse than asking now. */
@Composable
fun PlayerScreen(item: VideoItem, onExit: () -> Unit, autoResume: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableIntStateOf(0) }
    var freeBytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(check) { freeBytes = withContext(Dispatchers.IO) { Storage.playableBytes(context) } }

    val free = freeBytes
    when {
        free == null -> Box(Modifier.fillMaxSize().background(CouchgramTheme.colors.video))
        free >= Storage.MIN_FREE_TO_PLAY -> ResumeGate(item, onExit, autoResume)
        else -> {
            BackHandler(onBack = onExit)
            LowSpacePanel(
                freeBytes = free,
                clearing = clearing,
                onClearAndPlay = {
                    if (!clearing) {
                        clearing = true
                        scope.launch {
                            runCatching { Storage.clearCache() }
                            clearing = false
                            check++
                        }
                    }
                },
                onExit = onExit,
            )
        }
    }
}

/**
 * An unfinished video asks Resume or Start over first; anything else starts from the top. A `/play`
 * from the phone ([autoResume]) resumes without asking: nobody may be holding the remote. Resuming
 * starts [RESUME_REWIND_MS] early, so the picture picks up from something already seen.
 */
/** Inside the resume island the cache keeps (2 min before the stop), so the rewind is still instant. */
private const val RESUME_REWIND_MS = 10_000L

@Composable
private fun ResumeGate(item: VideoItem, onExit: () -> Unit, autoResume: Boolean) {
    val saved = remember(item.fileKey) { WatchStore[item.fileKey]?.takeIf { it.inProgress } }
    val resumeAt = saved?.let { (it.positionMs - RESUME_REWIND_MS).coerceAtLeast(0L) }
    var startMs by remember(item.fileKey) { mutableStateOf(if (saved == null) 0L else if (autoResume) resumeAt else null) }
    val start = startMs
    if (start != null) {
        StreamingPlayer(item, start, onExit)
    } else if (saved != null) {
        BackHandler(onBack = onExit)
        ResumePrompt(item, saved, onResume = { startMs = resumeAt }, onStartOver = { startMs = 0L })
    }
}

@Composable
private fun ResumePrompt(item: VideoItem, saved: WatchState, onResume: () -> Unit, onStartOver: () -> Unit) {
    val focus = remember { FocusRequester() }
    val at = DateUtils.formatElapsedTime(saved.positionMs / 1000)
    ScreenFrame {
        CenteredPanel {
            MessagePanel(
                title = item.title.ifBlank { stringResource(R.string.video_untitled) },
                body = stringResource(R.string.resume_body, at, DateUtils.formatElapsedTime(saved.durationMs / 1000)),
            ) {
                PrimaryButton(stringResource(R.string.resume_action, at), onResume, Modifier.focusRequester(focus))
                SecondaryButton(stringResource(R.string.resume_start_over), onStartOver)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun LowSpacePanel(freeBytes: Long, clearing: Boolean, onClearAndPlay: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    ScreenFrame {
        CenteredPanel {
            MessagePanel(
                title = stringResource(R.string.player_space_title),
                body = stringResource(
                    R.string.player_space_body,
                    Formatter.formatShortFileSize(context, freeBytes),
                    Formatter.formatShortFileSize(context, Storage.MIN_FREE_TO_PLAY),
                ),
            ) {
                PrimaryButton(
                    stringResource(if (clearing) R.string.storage_clearing else R.string.action_clear_and_play),
                    onClick = onClearAndPlay,
                    modifier = Modifier.focusRequester(focus),
                )
                SecondaryButton(stringResource(R.string.action_back_to_library), onExit)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** What the debug compatibility log needs; not UI state, so changing it never recomposes. */
private class PlaybackStats {
    val createdAt = SystemClock.elapsedRealtime()
    var started = false
    var audioMime: String? = null
    var audioDecoder: String? = null
    var videoMime: String? = null
    var videoDecoder: String? = null
    var container: String? = null
}

@OptIn(UnstableApi::class)
@Composable
private fun StreamingPlayer(item: VideoItem, startMs: Long, onExit: () -> Unit) {
    val context = LocalContext.current
    val colors = CouchgramTheme.colors
    var failure by remember { mutableStateOf<PlaybackException?>(null) }
    var videoSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var firstFrame by remember { mutableStateOf(false) }
    var sizeWarning by remember { mutableStateOf(false) }
    val title = item.title.ifBlank { stringResource(R.string.video_untitled) }
    val downloaded by produceState(0L, item.fileId) {
        Td.files.filter { it.id == item.fileId }.collect { value = it.local.downloadedSize }
    }

    val player = remember(item.fileId) {
        // Extension renderers after the platform ones: FFmpeg only steps in for audio the device can't
        // decode itself (Dolby AC-3 / E-AC-3), and its PCM is downmixed for the TV's stereo input.
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MediaCache.dataSourceFactory(), PatchedExtractorsFactory()))
            // Smaller buffers than the phone defaults: a Fire TV Stick has ~1 GB of RAM.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 40_000, 2_500, 5_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .build()
            .apply {
                // Cache key = Telegram's permanent file id, so cached parts survive restarts.
                val media = MediaItem.Builder().setUri(TdDataSource.uriFor(item.fileId)).setCustomCacheKey(item.fileKey).build()
                setMediaItem(media, startMs)
                // Subtitles start the way they were last left.
                Prefs.subtitleLanguage?.let { lang ->
                    trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredTextLanguage(lang).build()
                }
                prepare()
                playWhenReady = true
            }
    }

    val stats = remember(item.fileId) { PlaybackStats() }
    val controls = rememberRemoteControls(player, item.fileKey, item.size)
    val keys = remember { FocusRequester() }
    var picture by remember { mutableStateOf(Prefs.pictureMode) }
    var tracksVersion by remember { mutableIntStateOf(0) }

    // Every 5 s: tell the cache where the playhead is, and save the resume point.
    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_EVERY_MS)
            val duration = player.duration
            if (duration <= 0) continue
            CachePolicy.playing(item.fileKey, player.currentPosition, duration, item.size)
            // Watched-to-the-end cleanup waits for exit: dropping the cache now would stall the credits.
            if (stats.started) WatchStore.save(item.fileKey, player.currentPosition, duration, item.size)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = error
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("Compat", "FAIL ${item.title.take(50)} | ${formatSize(item.size)} | ${classify(error)} ${error.errorCodeName} | ${error.cause?.message?.take(120)}")
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying || stats.started) return
                // First moment of actual playback: both decoders are up, so the log can say which ones.
                stats.started = true
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "Compat",
                        "PLAY ${item.title.take(50)} | ${formatSize(item.size)} | ${stats.container} | video ${stats.videoMime} ${videoSize?.let { "${it.first}x${it.second}" }} " +
                            "via ${stats.videoDecoder} | audio ${stats.audioMime} via ${stats.audioDecoder} | ready ${SystemClock.elapsedRealtime() - stats.createdAt} ms",
                    )
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0) ?: return
                videoSize = video.width to video.height
                if (DeviceVideo.tooBig(video.width, video.height)) sizeWarning = true
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                stats.audioMime = format.sampleMimeType
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                stats.audioDecoder = decoderName
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                stats.videoMime = format.sampleMimeType
                stats.container = format.containerMimeType
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                stats.videoDecoder = decoderName
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            val position = player.currentPosition
            val duration = player.duration
            if (stats.started && duration > 0) {
                WatchStore.save(item.fileKey, position, duration, item.size)
                if (position >= duration * WatchStore.WATCHED_AT) {
                    // Watched to the end: nothing left worth keeping.
                    if (BuildConfig.DEBUG) android.util.Log.i("SmartCache", "watched to the end, dropping ${MediaCache.cachedBytes(item.fileKey) / 1024 / 1024} MB")
                    CachePolicy.watchedOnce(item.fileKey)
                    MediaCache.remove(item.fileKey)
                } else {
                    CachePolicy.stopped(item.fileKey, position, duration, item.size)
                }
            }
            player.removeAnalyticsListener(analytics)
            player.removeListener(listener)
            player.release()
            Td.forget(item.fileId)
        }
    }

    // Home mid-video must not keep the sound playing behind the launcher.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    LaunchedEffect(sizeWarning) {
        if (sizeWarning) {
            delay(SIZE_WARNING_MS)
            sizeWarning = false
        }
    }

    // Back: cancel a pending jump, then hide the overlay, then leave.
    BackHandler { if (failure != null || !controls.back()) onExit() }

    // The remote talks to this box (see PlayerControls.kt); focus returns here after an error is retried.
    LaunchedEffect(failure) { if (failure == null) runCatching { keys.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.video)
            .focusRequester(keys)
            .remoteKeys(controls, enabled = failure == null && !controls.optionsOpen)
            .focusable(),
    ) {
        // Media3's view only draws the picture and its buffering spinner; its touch-style controller is off.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = {
                it.resizeMode = picture.resizeMode
                // Lift subtitles above the seek bar while the overlay is up.
                it.subtitleView?.setBottomPaddingFraction(if (controls.overlayVisible) 0.24f else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (firstFrame && failure == null) PlayerOverlay(controls, title)
        if (controls.optionsOpen && failure == null) {
            PlayerOptionsPanel(
                player = player,
                picture = picture,
                onPicture = {
                    picture = it
                    Prefs.pictureMode = it
                },
                onClose = {
                    controls.closeOptions()
                    runCatching { keys.requestFocus() }
                },
                tracksVersion = tracksVersion,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = Spacing.safeHorizontal, end = Spacing.safeHorizontal, bottom = 150.dp),
            )
        }

        // Before the first frame: what is happening and how far along, below the buffering spinner.
        if (!firstFrame && failure == null) {
            Column(
                Modifier.align(Alignment.Center).padding(top = 160.dp).widthIn(max = 640.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(
                    if (downloaded > 0) {
                        stringResource(R.string.player_loading_progress, Formatter.formatShortFileSize(context, downloaded))
                    } else {
                        stringResource(R.string.player_loading)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        AnimatedVisibility(
            visible = sizeWarning && failure == null,
            enter = fadeIn(tween(Motion.STANDARD)),
            exit = fadeOut(tween(Motion.STANDARD)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = Spacing.xxxl * 2),
        ) {
            videoSize?.let { (w, h) -> StatusPill(stringResource(R.string.player_too_big, w, h, DeviceVideo.label), Tone.Warning) }
        }

        failure?.let { error ->
            Box(Modifier.fillMaxSize().background(colors.scrim)) {
                CenteredPanel {
                    PlaybackFailure(
                        kind = classify(error),
                        onRetry = {
                            failure = null
                            player.prepare()
                            player.play()
                        },
                        onExit = onExit,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackFailure(kind: FailureKind, onRetry: () -> Unit, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    val (title, body) = when (kind) {
        FailureKind.Decoder -> stringResource(R.string.player_decoder_title) to stringResource(R.string.player_decoder_body, DeviceVideo.label)
        FailureKind.Format -> stringResource(R.string.player_format_title) to stringResource(R.string.player_format_body)
        FailureKind.Network -> stringResource(R.string.player_network_title) to stringResource(R.string.player_network_body)
        FailureKind.Other -> stringResource(R.string.player_other_title) to stringResource(R.string.player_other_body)
    }
    // Retrying can't fix a file the TV can't decode, so those only offer the way back.
    val canRetry = kind == FailureKind.Network || kind == FailureKind.Other
    MessagePanel(title, body) {
        if (canRetry) {
            PrimaryButton(stringResource(R.string.action_try_again), onRetry, Modifier.focusRequester(focus))
            SecondaryButton(stringResource(R.string.action_back_to_library), onExit)
        } else {
            PrimaryButton(stringResource(R.string.action_back_to_library), onExit, Modifier.focusRequester(focus))
        }
    }
    LaunchedEffect(kind) { runCatching { focus.requestFocus() } }
}

private fun classify(error: PlaybackException): FailureKind = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> FailureKind.Decoder
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> FailureKind.Format
    in 2000 until 3000 -> FailureKind.Network // Media3's IO error range
    else -> FailureKind.Other
}
