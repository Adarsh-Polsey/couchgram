package tv.couchgram

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.couchgram.ui.CouchgramTheme
import tv.couchgram.ui.Radius
import tv.couchgram.ui.Spacing
import java.util.Locale

/** Picture modes for the TV: letterboxed, filled (crops edges), or stretched. Remembered across videos. */
@OptIn(UnstableApi::class)
enum class PictureMode(val resizeMode: Int, val label: Int) {
    Fit(AspectRatioFrameLayout.RESIZE_MODE_FIT, R.string.picture_fit),
    Fill(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, R.string.picture_fill),
    Stretch(AspectRatioFrameLayout.RESIZE_MODE_FILL, R.string.picture_stretch),
}

/** One selectable audio or subtitle track. */
data class TrackChoice(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

fun trackChoices(player: Player, type: Int, describe: (Format) -> String): List<TrackChoice> =
    player.currentTracks.groups
        .filter { it.type == type }
        .flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { i ->
                TrackChoice(group, i, describe(group.getTrackFormat(i)), group.isTrackSelected(i))
            }
        }

fun selectTrack(player: Player, choice: TrackChoice) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(choice.group.type, false)
        .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
        .build()
}

fun subtitlesOff(player: Player) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
}

/**
 * Options panel (Up or ☰ while playing): Picture, Audio and Subtitles rows of chips. Up/Down moves
 * between rows, Left/Right between choices, OK picks, Back closes. Focus starts on the current
 * picture mode.
 */
// FilterChip is still marked experimental in tv-material 1.1; it's the TV chip with focus and selection built in.
@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerOptionsPanel(
    player: Player,
    picture: PictureMode,
    onPicture: (PictureMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    tracksVersion: Int,
) {
    val context = LocalContext.current
    val colors = CouchgramTheme.colors
    val title = stringResource(R.string.options_title)
    val first = remember { FocusRequester() }
    val audio = remember(tracksVersion) { trackChoices(player, C.TRACK_TYPE_AUDIO) { audioLabel(context, it) } }
    val subtitles = remember(tracksVersion) { trackChoices(player, C.TRACK_TYPE_TEXT) { subtitleLabel(context, it) } }
    val subtitlesDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || subtitles.none { it.selected }

    BackHandler(onBack = onClose)

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.scrim, RoundedCornerShape(Radius.large))
            .padding(Spacing.xl)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OptionRow(stringResource(R.string.options_picture)) {
            items(PictureMode.entries) { mode ->
                FilterChip(
                    selected = mode == picture,
                    onClick = { onPicture(mode) },
                    modifier = if (mode == picture) Modifier.focusRequester(first) else Modifier,
                ) { Text(stringResource(mode.label)) }
            }
        }
        if (audio.size > 1) {
            OptionRow(stringResource(R.string.options_audio)) {
                items(audio) { track ->
                    FilterChip(selected = track.selected, onClick = { selectTrack(player, track) }) { Text(track.label) }
                }
            }
        }
        if (subtitles.isNotEmpty()) {
            OptionRow(stringResource(R.string.options_subtitles)) {
                item {
                    FilterChip(
                        selected = subtitlesDisabled,
                        onClick = {
                            subtitlesOff(player)
                            Prefs.subtitleLanguage = null
                        },
                    ) { Text(stringResource(R.string.subtitles_off)) }
                }
                items(subtitles) { track ->
                    FilterChip(
                        selected = !subtitlesDisabled && track.selected,
                        onClick = {
                            selectTrack(player, track)
                            // Remembered, so the next video starts with the same subtitles.
                            Prefs.subtitleLanguage = track.group.getTrackFormat(track.index).language ?: C.LANGUAGE_UNDETERMINED
                        },
                    ) { Text(track.label) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun OptionRow(label: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = CouchgramTheme.colors.textSecondary,
            modifier = Modifier.width(110.dp).semantics { heading() },
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
    }
}

private fun language(format: Format): String? =
    format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }

/**
 * "Malayalam · 5.1 · Dolby Digital Plus". Language first: the track's own label is often a
 * watermark or site name, so it's only used when the file gives no language.
 */
private fun audioLabel(context: android.content.Context, f: Format): String = listOfNotNull(
    language(f) ?: f.label,
    when (f.channelCount) {
        1 -> context.getString(R.string.audio_mono)
        2 -> context.getString(R.string.audio_stereo)
        6 -> "5.1"
        8 -> "7.1"
        Format.NO_VALUE -> null
        else -> context.getString(R.string.audio_channels, f.channelCount)
    },
    when (f.sampleMimeType) {
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> null
    },
).joinToString(" · ").ifBlank { context.getString(R.string.track_unnamed) }

private fun subtitleLabel(context: android.content.Context, f: Format): String = listOfNotNull(
    language(f) ?: f.label,
    if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) context.getString(R.string.subtitles_forced) else null,
).joinToString(" · ").ifBlank { context.getString(R.string.track_unnamed) }
