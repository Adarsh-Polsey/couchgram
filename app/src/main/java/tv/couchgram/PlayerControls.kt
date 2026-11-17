package tv.couchgram

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.couchgram.ui.CouchgramTheme
import tv.couchgram.ui.Motion
import tv.couchgram.ui.Radius
import tv.couchgram.ui.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val COMMIT_AFTER_MS = 700L // seek lands this long after the last press
private const val STREAK_WINDOW_MS = 600L // presses closer than this count as one run
private const val REPEAT_EVERY_MS = 150L // while held, one step per this interval
private const val HIDE_AFTER_MS = 4_000L
private const val FLASH_MS = 600L

enum class Flash { Play, Pause }

/**
 * Remote-first playback controls. Left/Right (and ⏪/⏩) seek straight away, getting bigger the
 * longer they're held (10 s → 30 s → 1 min → 5 min), and the seek lands 0.7 s after the last press
 * so held keys don't hammer the network. OK plays/pauses; Up/Down just show the overlay.
 */
@Stable
class RemoteControls(private val player: Player, private val scope: CoroutineScope) {
    var overlayVisible by mutableStateOf(true)
    var lastInteraction by mutableLongStateOf(0L)
    var seekTargetMs by mutableStateOf<Long?>(null)
        private set
    var seekDeltaMs by mutableLongStateOf(0L)
        private set
    var flash by mutableStateOf<Flash?>(null)
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var cachedMs by mutableStateOf<List<LongRange>>(emptyList())
    var optionsOpen by mutableStateOf(false)

    /** Set by the player screen to tell stall counting a jump caused the next buffering. */
    var onSeek: () -> Unit = {}

    private var streak = 0
    private var lastSeekKeyAt = 0L
    private var lastStepAt = 0L
    private var seekOrigin = 0L
    private var commitJob: Job? = null

    fun poke() {
        overlayVisible = true
        lastInteraction = SystemClock.uptimeMillis()
    }

    fun tick() {
        positionMs = player.currentPosition
        durationMs = player.duration.coerceAtLeast(0L)
        bufferedMs = player.bufferedPosition
        playing = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
    }

    fun nudge(direction: Int, held: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (held && now - lastStepAt < REPEAT_EVERY_MS) return
        streak = if (held || now - lastSeekKeyAt < STREAK_WINDOW_MS) streak + 1 else 0
        lastSeekKeyAt = now
        lastStepAt = now
        if (seekTargetMs == null) seekOrigin = player.currentPosition
        val end = player.duration.takeIf { it > 0 }?.minus(1_000) ?: Long.MAX_VALUE
        val target = ((seekTargetMs ?: seekOrigin) + direction * stepFor(streak)).coerceIn(0L, end)
        seekTargetMs = target
        seekDeltaMs = target - seekOrigin
        poke()
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(COMMIT_AFTER_MS)
            commit()
        }
    }

    /** OK during a seek jumps now instead of waiting. */
    fun commit() {
        val target = seekTargetMs ?: return
        commitJob?.cancel()
        onSeek()
        player.seekTo(target)
        positionMs = target
        seekTargetMs = null
        streak = 0
        poke()
    }

    fun okPressed() = if (seekTargetMs != null) commit() else togglePlay()

    fun togglePlay() {
        if (player.playWhenReady) player.pause() else player.play()
        flash = if (player.playWhenReady) Flash.Play else Flash.Pause
        poke()
    }

    fun play() {
        player.play()
        flash = Flash.Play
        poke()
    }

    fun pause() {
        player.pause()
        flash = Flash.Pause
        poke()
    }

    fun openOptions() {
        optionsOpen = true
        poke()
    }

    fun closeOptions() {
        optionsOpen = false
        poke()
    }

    /** Back cancels a pending seek, then hides the overlay; returns false when the player should close. */
    fun back(): Boolean = when {
        seekTargetMs != null -> {
            commitJob?.cancel()
            seekTargetMs = null
            streak = 0
            true
        }
        overlayVisible -> {
            overlayVisible = false
            true
        }
        else -> false
    }

    /** Would the current seek target play without downloading? */
    fun targetIsReady(): Boolean {
        val t = seekTargetMs ?: return true
        return t in positionMs..bufferedMs || cachedMs.any { t in it }
    }

    private fun stepFor(streak: Int): Long = when {
        streak < 3 -> 10_000L
        streak < 8 -> 30_000L
        streak < 14 -> 60_000L
        else -> 5 * 60_000L
    }
}

@Composable
fun rememberRemoteControls(player: Player, fileKey: String, sizeBytes: Long): RemoteControls {
    val scope = rememberCoroutineScope()
    val controls = remember(player) { RemoteControls(player, scope) }
    // Fast ticks while the overlay is up (moving numbers), slow ones otherwise.
    LaunchedEffect(controls) {
        while (true) {
            controls.tick()
            delay(if (controls.overlayVisible || controls.seekTargetMs != null) 250 else 1_000)
        }
    }
    LaunchedEffect(controls.lastInteraction, controls.playing, controls.seekTargetMs) {
        if (controls.playing && controls.seekTargetMs == null && controls.overlayVisible && !controls.optionsOpen) {
            delay(HIDE_AFTER_MS)
            controls.overlayVisible = false
        }
    }
    LaunchedEffect(controls.flash) {
        if (controls.flash != null) {
            delay(FLASH_MS)
            controls.flash = null
        }
    }
    // What's already on the TV, so the bar can show where a jump would be instant.
    LaunchedEffect(controls.overlayVisible, controls.durationMs > 0) {
        while (controls.overlayVisible && controls.durationMs > 0) {
            controls.cachedMs = withContext(Dispatchers.IO) { MediaCache.cachedRanges(fileKey, sizeBytes, controls.durationMs) }
            delay(1_000)
        }
    }
    return controls
}

/** Routes remote keys to [controls]. Disabled while an error panel owns focus. */
fun Modifier.remoteKeys(controls: RemoteControls, enabled: Boolean): Modifier = onPreviewKeyEvent { event ->
    if (!enabled) return@onPreviewKeyEvent false
    val down = event.type == KeyEventType.KeyDown
    val held = event.nativeKeyEvent.repeatCount > 0
    when (event.key) {
        Key.DirectionLeft, Key.MediaRewind -> {
            if (down) controls.nudge(-1, held)
            true
        }
        Key.DirectionRight, Key.MediaFastForward -> {
            if (down) controls.nudge(+1, held)
            true
        }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
            if (down && !held) controls.okPressed()
            true
        }
        Key.MediaPlay -> {
            if (down) controls.play()
            true
        }
        Key.MediaPause -> {
            if (down) controls.pause()
            true
        }
        Key.DirectionUp, Key.Menu -> {
            if (down && !held) controls.openOptions()
            true
        }
        Key.DirectionDown -> {
            if (down) controls.poke()
            true
        }
        else -> false
    }
}

@Composable
fun BoxScope.PlayerOverlay(controls: RemoteControls, title: String) {
    val colors = CouchgramTheme.colors
    val seeking = controls.seekTargetMs != null

    AnimatedVisibility(
        visible = controls.overlayVisible,
        enter = fadeIn(tween(Motion.FAST)),
        exit = fadeOut(tween(Motion.STANDARD)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.scrim, Color.Transparent)))
                    .padding(start = Spacing.safeHorizontal, end = Spacing.safeHorizontal, top = Spacing.safeVertical, bottom = Spacing.xxxl),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, colors.scrim)))
                    .padding(start = Spacing.safeHorizontal, end = Spacing.safeHorizontal, bottom = Spacing.safeVertical, top = Spacing.xxxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val times = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeText(controls.seekTargetMs ?: controls.positionMs, times, if (seeking) colors.accent else colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    TimeText(controls.durationMs, times, colors.textSecondary)
                }
                SeekBar(controls)
                Text(
                    statusLine(controls),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    // The jump size, big and centred, while seeking.
    if (seeking) {
        val delta = controls.seekDeltaMs
        val label = stringResource(
            if (delta >= 0) R.string.player_seek_ahead else R.string.player_seek_back,
            DateUtils.formatElapsedTime(abs(delta) / 1000),
        )
        Text(
            label,
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            color = colors.textPrimary,
            modifier = Modifier
                .align(Alignment.Center)
                .background(colors.scrim, RoundedCornerShape(Radius.large))
                .padding(horizontal = Spacing.xxl, vertical = Spacing.lg)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    // Play / pause acknowledgement.
    AnimatedVisibility(
        visible = controls.flash != null && !seeking,
        enter = fadeIn(tween(Motion.FAST)),
        exit = fadeOut(tween(Motion.STANDARD)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        TransportIcon(controls.flash ?: Flash.Play)
    }
}

@Composable
private fun statusLine(controls: RemoteControls): String = when {
    controls.seekTargetMs != null -> stringResource(if (controls.targetIsReady()) R.string.player_seek_instant else R.string.player_seek_load)
    controls.buffering -> stringResource(R.string.player_loading)
    !controls.playing -> stringResource(R.string.player_paused)
    else -> stringResource(R.string.player_hint)
}

@Composable
private fun TimeText(ms: Long, style: TextStyle, color: Color) {
    Text(DateUtils.formatElapsedTime(ms / 1000), style = style, color = color)
}

/**
 * Track · on-the-TV ranges (instant to jump to) · buffered · played · jump preview. Shapes and
 * the status line carry the meaning too, not colour alone.
 */
@Composable
private fun SeekBar(controls: RemoteControls) {
    val colors = CouchgramTheme.colors
    val duration = controls.durationMs.coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(20.dp).semantics { hideFromAccessibility() }) {
        val trackH = 6.dp.toPx()
        val top = (size.height - trackH) / 2
        val radius = CornerRadius(trackH / 2, trackH / 2)
        fun x(ms: Long) = size.width * (ms.coerceIn(0L, duration).toFloat() / duration)
        fun bar(fromMs: Long, toMs: Long, color: Color) {
            val l = x(fromMs)
            val r = x(toMs)
            if (r > l) drawRoundRect(color, Offset(l, top), Size(r - l, trackH), radius)
        }
        bar(0, duration, colors.border)
        controls.cachedMs.forEach { bar(it.first, it.last, colors.textSecondary.copy(alpha = 0.35f)) }
        bar(controls.positionMs, controls.bufferedMs, colors.textSecondary.copy(alpha = 0.6f))
        bar(0, controls.positionMs, colors.accent)
        val target = controls.seekTargetMs
        if (target != null) {
            bar(minOf(target, controls.positionMs), maxOf(target, controls.positionMs), colors.accent.copy(alpha = 0.45f))
            drawCircle(colors.textPrimary, radius = 9.dp.toPx(), center = Offset(x(target), size.height / 2))
        } else {
            drawCircle(colors.accent, radius = 7.dp.toPx(), center = Offset(x(controls.positionMs), size.height / 2))
        }
    }
}

/** Play triangle or pause bars, drawn in code. */
@Composable
private fun TransportIcon(kind: Flash) {
    val colors = CouchgramTheme.colors
    Box(
        Modifier.size(96.dp).background(colors.scrim, RoundedCornerShape(48.dp)).semantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            when (kind) {
                Flash.Play -> drawPath(
                    Path().apply {
                        moveTo(size.width * 0.2f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.2f, size.height)
                        close()
                    },
                    colors.textPrimary,
                )
                Flash.Pause -> {
                    val w = size.width * 0.3f
                    drawRect(colors.textPrimary, Offset(size.width * 0.1f, 0f), Size(w, size.height))
                    drawRect(colors.textPrimary, Offset(size.width * 0.6f, 0f), Size(w, size.height))
                }
            }
        }
    }
}
