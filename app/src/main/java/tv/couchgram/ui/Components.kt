package tv.couchgram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import tv.couchgram.R

/**
 * Full-screen frame that keeps content inside the TV's overscan-safe area. [horizontal] is 0 for
 * screens whose rails scroll to the screen edge and pad their own content.
 */
@Composable
fun ScreenFrame(modifier: Modifier = Modifier, horizontal: Dp = Spacing.safeHorizontal, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .background(CouchgramTheme.colors.background)
            .padding(horizontal = horizontal, vertical = Spacing.safeVertical),
        content = content,
    )
}

/** The Couchgram wordmark: "Couch" in text colour, "gram" in the rose-to-violet gradient. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    val colors = CouchgramTheme.colors
    val name = stringResource(R.string.app_name)
    val text = remember(colors) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.textPrimary)) { append("Couch") }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, brush = Brush.linearGradient(listOf(colors.logoStart, colors.logoEnd)))) { append("gram") }
        }
    }
    Text(
        text,
        style = style.copy(letterSpacing = (-0.5).sp),
        modifier = modifier.semantics {
            heading()
            contentDescription = name
        },
    )
}

/** Empty, error and offline states: what happened, what to do, and the action that does it. */
@Composable
fun MessagePanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier.widthIn(max = 640.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyLarge, color = CouchgramTheme.colors.textSecondary)
        if (actions != null) {
            Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md), content = actions)
        }
    }
}

enum class Tone { Neutral, Active, Warning }

/** Small live status next to a header: a coloured dot plus words, never colour alone. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val colors = CouchgramTheme.colors
    val dot = when (tone) {
        Tone.Neutral -> colors.textSecondary
        Tone.Active -> colors.accent
        Tone.Warning -> colors.warning
    }
    Row(
        modifier
            .background(colors.surfaceRaised, RoundedCornerShape(Radius.medium))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Button roles: [Button] is primary, [OutlinedButton] secondary, these two cover the rest. */
@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        // Disabled stays transparent: a grey pill would read as focus on a TV.
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = CouchgramTheme.colors.textSecondary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = CouchgramTheme.colors.textSecondary.copy(alpha = 0.4f),
        ),
    ) { Text(text) }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CouchgramTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = colors.dangerContainer,
            contentColor = colors.onDangerContainer,
            focusedContainerColor = colors.dangerContainer,
            focusedContentColor = colors.onDangerContainer,
        ),
    ) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }
}

/** Library sizes shared by the rails and their skeleton, so nothing shifts when data lands. */
object LibraryLayout {
    /** Five cards fit across the safe area (5 x 160 + 4 x 16 = 864dp) and a sixth peeks in. */
    val cardWidth = 160.dp
    val cardHeight = 92.dp
    val cardGap = Spacing.lg

    /** The pinned video's card: as wide as a full row of five. */
    val wideWidth = cardWidth * 5 + cardGap * 4
    val wideHeight = 100.dp
    val detailsHeight = 52.dp
}

/** Loading placeholder shaped like the library: details band, then rails of cards. Static, no shimmer loop. */
@Composable
fun SkeletonRails(modifier: Modifier = Modifier, rails: Int = 2, cards: Int = 6) {
    val colors = CouchgramTheme.colors
    Column(
        modifier.fillMaxSize().padding(top = Spacing.md).semantics { hideFromAccessibility() },
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(Modifier.height(LibraryLayout.detailsHeight).padding(horizontal = Spacing.safeHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.size(240.dp, 22.dp).background(colors.surfaceRaised, RoundedCornerShape(Radius.small)))
            Box(Modifier.size(420.dp, 14.dp).background(colors.surface, RoundedCornerShape(Radius.small)))
        }
        repeat(rails) {
            Column(Modifier.padding(start = Spacing.safeHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(110.dp, 16.dp).background(colors.surfaceRaised, RoundedCornerShape(Radius.small)))
                Row(horizontalArrangement = Arrangement.spacedBy(LibraryLayout.cardGap)) {
                    repeat(cards) {
                        Box(
                            Modifier
                                .size(LibraryLayout.cardWidth, LibraryLayout.cardHeight)
                                .background(colors.surface, RoundedCornerShape(Radius.large)),
                        )
                    }
                }
            }
        }
    }
}

/** Centres a panel in the remaining space of a screen. */
@Composable
fun CenteredPanel(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun VSpace(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))

@Composable
fun CaptionText(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = CouchgramTheme.colors.textSecondary, textAlign = align)
}
