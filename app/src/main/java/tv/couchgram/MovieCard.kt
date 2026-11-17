package tv.couchgram

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.couchgram.ui.CardGradients
import tv.couchgram.ui.CouchgramTheme
import tv.couchgram.ui.LibraryLayout
import tv.couchgram.ui.Radius
import tv.couchgram.ui.Spacing

/** Pinned in the channel, reacted to, or saved to Saved Messages ([saved] holds those file keys). */
fun VideoItem.isFavourite(saved: Set<String>) = pinned || reacted || fileKey in saved

/** Uploaded in the last three days and never opened. */
fun VideoItem.isNew(watch: WatchState?, nowMillis: Long) = watch == null && nowMillis - date * 1000L < NEW_FOR_MS

private const val NEW_FOR_MS = 3 * 24 * 60 * 60 * 1000L

/** Card text runs tight (slightly negative tracking) so a full title fits in two lines. */
private val CardTitle = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
private val CardMeta = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)

/**
 * A film as a gradient card, all cards the same size: title (two lines), year and version, then a
 * state chip (time left, watched, new, too big for the TV) or the language, and the file size. Watching
 * cards get a progress bar along the bottom edge; favourites get a ★.
 */
@Composable
fun MovieCard(
    item: VideoItem,
    watch: WatchState?,
    favourite: Boolean,
    isNew: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CouchgramTheme.colors
    val context = LocalContext.current
    val r = item.release
    val shape = RoundedCornerShape(Radius.large)
    val (from, to) = remember(item.fileKey) { CardGradients.forKey(item.fileKey) }
    // The year-and-version line gets a second line when the title needs only one.
    var titleLines by remember { mutableIntStateOf(1) }
    val meta = remember(r) { listOfNotNull(r.year?.toString(), r.version.ifBlank { null }).joinToString(" · ") }
    val inProgress = watch?.inProgress == true

    Card(
        onClick = onClick,
        modifier = modifier
            .size(LibraryLayout.cardWidth, LibraryLayout.cardHeight)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.onCard), shape = shape)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(from, to)))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row {
                    Text(
                        r.title.ifBlank { stringResource(R.string.video_untitled) },
                        style = CardTitle,
                        color = colors.onCard,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { titleLines = it.lineCount },
                        modifier = Modifier.weight(1f),
                    )
                    if (favourite) {
                        Box(Modifier.padding(start = Spacing.xs).size(18.dp).background(colors.cardChip, CircleShape), contentAlignment = Alignment.Center) {
                            Text("★", style = CardMeta, color = colors.favourite)
                        }
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(meta, style = CardMeta, color = colors.onCardSecondary, maxLines = if (titleLines > 1) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        when {
                            inProgress -> StateChip(timeLeft(watch!!), colors.watching)
                            watch?.watched == true -> StateChip(stringResource(R.string.library_watched), colors.watched)
                            item.tooBig -> StateChip(stringResource(R.string.library_too_big, DeviceVideo.label), colors.warning)
                            isNew -> StateChip(stringResource(R.string.card_new), colors.fresh)
                            r.languages.isNotEmpty() -> Text(
                                if (r.languages.size == 1) r.languages[0]
                                else stringResource(R.string.card_more_languages, r.languages[0], r.languages.size - 1),
                                style = CardMeta,
                                color = colors.onCardSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    if (item.size > 0) {
                        Text(Formatter.formatShortFileSize(context, item.size), style = CardMeta, color = colors.onCardSecondary, maxLines = 1)
                    }
                }
            }
            if (inProgress) {
                // Along the bottom edge; at least a sliver, so "just started" still shows.
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(colors.cardChip)) {
                    Box(Modifier.fillMaxWidth(watch!!.progress.coerceAtLeast(0.03f)).fillMaxHeight().background(colors.watching))
                }
            }
        }
    }
}

/**
 * The channel's last pinned video, one wide card across the top: a "Pinned" chip and its state,
 * then the title large, then languages, version, audio and length. Same gradient as its small card.
 */
@Composable
fun PinnedCard(
    item: VideoItem,
    watch: WatchState?,
    favourite: Boolean,
    isNew: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CouchgramTheme.colors
    val context = LocalContext.current
    val r = item.release
    val shape = RoundedCornerShape(Radius.large)
    val (from, to) = remember(item.fileKey) { CardGradients.forKey(item.fileKey) }
    val inProgress = watch?.inProgress == true
    val runtime = runtimeText(item.durationSec)
    val meta = remember(r, runtime) {
        listOfNotNull(
            r.languages.joinToString(", ").ifBlank { null },
            r.version.ifBlank { null },
            r.audio.joinToString(" + ").ifBlank { null },
            runtime,
        ).joinToString(" · ")
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .size(LibraryLayout.wideWidth, LibraryLayout.wideHeight)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        // Wide, so a smaller scale reads the same as the small cards' 1.1.
        scale = CardDefaults.scale(focusedScale = 1.03f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.onCard), shape = shape)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(from, to)))) {
            Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StateChip(stringResource(R.string.card_pinned), colors.onCard)
                    when {
                        inProgress -> StateChip(timeLeft(watch!!), colors.watching)
                        watch?.watched == true -> StateChip(stringResource(R.string.library_watched), colors.watched)
                        item.tooBig -> StateChip(stringResource(R.string.library_too_big, DeviceVideo.label), colors.warning)
                        isNew -> StateChip(stringResource(R.string.card_new), colors.fresh)
                    }
                    Spacer(Modifier.weight(1f))
                    if (favourite) Text("★", style = CardMeta, color = colors.favourite)
                    if (item.size > 0) Text(Formatter.formatShortFileSize(context, item.size), style = CardMeta, color = colors.onCardSecondary)
                }
                Text(
                    r.titleWithYear.ifBlank { stringResource(R.string.video_untitled) },
                    style = CardTitle.copy(fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),
                    color = colors.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) Text(meta, style = CardMeta, color = colors.onCardSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (inProgress) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(colors.cardChip)) {
                    Box(Modifier.fillMaxWidth(watch!!.progress.coerceAtLeast(0.01f)).fillMaxHeight().background(colors.watching))
                }
            }
        }
    }
}

/** A state on a card: tinted words on a dark chip, readable on every gradient. */
@Composable
private fun StateChip(text: String, tint: Color) {
    Text(
        text,
        style = CardMeta,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .background(CouchgramTheme.colors.cardChip, RoundedCornerShape(Radius.small))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun timeLeft(watch: WatchState): String {
    val minutes = ((watch.remainingMs + 59_999) / 60_000).toInt()
    return if (minutes >= 60) stringResource(R.string.library_hours_left, minutes / 60, minutes % 60)
    else pluralStringResource(R.plurals.library_minutes_left, minutes, minutes)
}

/**
 * Everything about the focused film, above the rails, so the cards can stay small: title and year,
 * why it's a favourite, then languages, picture, source, audio, subtitles, length, size and upload day.
 * Fixed height, so moving focus never shifts the rails.
 */
@Composable
fun VideoDetails(item: VideoItem?, saved: Set<String>, modifier: Modifier = Modifier) {
    val colors = CouchgramTheme.colors
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().height(LibraryLayout.detailsHeight), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (item == null) return@Column
        val r = item.release
        val reasons = listOfNotNull(
            stringResource(R.string.favourite_pinned).takeIf { item.pinned },
            stringResource(R.string.favourite_reacted).takeIf { item.reacted },
            stringResource(R.string.favourite_saved).takeIf { item.fileKey in saved },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Text(
                r.titleWithYear.ifBlank { stringResource(R.string.video_untitled) },
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (reasons.isNotEmpty()) {
                Text("★ " + reasons.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = colors.favourite, maxLines = 1)
            }
        }
        val facts = detailFacts(context, item)
        Text(facts, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Hindi, Malayalam · 1080p HEVC 10-bit · DS4K · Netflix WEBRip · Dolby 5.1 + AAC · Subtitles · 1 h 59 min · 2.6 GB · Added 2 days ago". */
@Composable
private fun detailFacts(context: Context, item: VideoItem): String {
    val r = item.release
    val subtitles = stringResource(R.string.details_subtitles)
    val runtime = runtimeText(item.durationSec)
    val added = stringResource(
        R.string.details_added,
        DateUtils.getRelativeTimeSpanString(item.date * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercaseFirst(),
    )
    return listOfNotNull(
        r.languages.joinToString(", ").ifBlank { null },
        listOfNotNull(r.resolution, r.codec, "10-bit".takeIf { r.tenBit }).joinToString(" ").ifBlank { null },
        "DS4K".takeIf { r.fromFourK },
        listOfNotNull(r.service, r.source).joinToString(" ").ifBlank { null },
        r.audio.joinToString(" + ").ifBlank { null },
        subtitles.takeIf { r.subtitles },
        runtime,
        item.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
        added,
    ).joinToString(" · ")
}

/** "1 h 59 min" for 1:59:39 (rounded down, as the caption says); null when unknown. */
@Composable
private fun runtimeText(seconds: Int): String? {
    if (seconds <= 0) return null
    val minutes = seconds / 60
    return if (minutes >= 60) stringResource(R.string.duration_hours, minutes / 60, minutes % 60)
    else stringResource(R.string.duration_minutes, minutes)
}

/** "2 days ago" stays as is; "Yesterday" becomes "yesterday" after "Added". */
private fun String.lowercaseFirst() = replaceFirstChar { it.lowercase() }
