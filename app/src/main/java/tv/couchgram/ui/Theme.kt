package tv.couchgram.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Couchgram's design tokens. Screens use these roles, never raw colours or sizes.
 *
 * Platform policy: a TV app is dark-only (10-foot UI convention, and video looks best on dark), uses
 * tv-material components as-is for focus, scale and glow, and keeps content inside the
 * overscan-safe area. Input is the D-pad remote, so "pressed" feedback is the focus state.
 */
@Immutable
data class CouchgramColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val warning: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val scrim: Color,
    /**
     * Library states: soft tints that stay easy on the eyes in a dark room. Always paired with
     * words or a mark (✓, ★, a progress bar), never colour alone.
     */
    val watching: Color,
    val watched: Color,
    val fresh: Color,
    val favourite: Color,
    /** The "gram" half of the wordmark, rose to violet. */
    val logoStart: Color,
    val logoEnd: Color,
    /** Text and chips on the gradient cards. */
    val onCard: Color,
    val onCardSecondary: Color,
    val cardChip: Color,
    val video: Color,
    val qrDark: Color,
    val qrLight: Color,
)

private val Dark = CouchgramColors(
    background = Color(0xFF101114),
    surface = Color(0xFF1A1C21),
    surfaceRaised = Color(0xFF24272E),
    border = Color(0xFF3A3E47),
    textPrimary = Color(0xFFF0F1F3),
    textSecondary = Color(0xFFB4B8C0),
    accent = Color(0xFF2AABEE),
    warning = Color(0xFFF5B841),
    danger = Color(0xFFFF8A80),
    dangerContainer = Color(0xFFC62828),
    onDangerContainer = Color(0xFFFFFFFF),
    scrim = Color(0xCC000000),
    watching = Color(0xFF8CCFF5), // soft sky
    watched = Color(0xFF9AD9B0), // soft mint
    fresh = Color(0xFFC3B5F5), // soft lavender
    favourite = Color(0xFFF4A8BF), // soft rose
    logoStart = Color(0xFFFF7A9C),
    logoEnd = Color(0xFF9B8CFF),
    onCard = Color(0xFFFFFFFF),
    onCardSecondary = Color(0xD9FFFFFF),
    cardChip = Color(0x66000000),
    video = Color(0xFF000000),
    qrDark = Color(0xFF000000),
    qrLight = Color(0xFFFFFFFF),
)

val LocalCouchgramColors = staticCompositionLocalOf { Dark }

/**
 * Card backgrounds: soft two-tone diagonal gradients standing in for thumbnails (none are
 * downloaded). Mid-tone on purpose: white text measures 4.5:1 or better on every stop, and nothing
 * glares in a dark room. Picked from the file's key, so a video keeps its colour in every rail.
 */
object CardGradients {
    private val pairs = listOf(
        Color(0xFFA25776) to Color(0xFF73477D), // rose
        Color(0xFF157EA4) to Color(0xFF3F5893), // ocean
        Color(0xFF48834B) to Color(0xFF006C62), // fern
        Color(0xFF7866AA) to Color(0xFF7C4371), // violet
        Color(0xFFA85950) to Color(0xFF85415E), // coral
        Color(0xFF00837E) to Color(0xFF006585), // teal
        Color(0xFF5571B1) to Color(0xFF644C8A), // indigo
        Color(0xFF148669) to Color(0xFF13618D), // lagoon
    )

    fun forKey(key: String): Pair<Color, Color> = pairs[key.lowercase().hashCode().mod(pairs.size)]
}

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** TVs can crop the outer ~5% of the picture: 48dp x 27dp on a 960x540dp screen. */
    val safeHorizontal = 48.dp
    val safeVertical = 27.dp
}

/** Small for badges, medium for inputs and pills, large for cards and dialogs. */
object Radius {
    val small = 4.dp
    val medium = 8.dp
    val large = 12.dp
}

/** One easing family (tween's default FastOutSlowIn), three durations. */
object Motion {
    const val FAST = 150
    const val STANDARD = 250
    const val EMPHASISED = 400
}

object CouchgramTheme {
    val colors: CouchgramColors @Composable get() = LocalCouchgramColors.current
}

@Composable
fun CouchgramTheme(content: @Composable () -> Unit) {
    val c = Dark
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = c.accent,
            background = c.background,
            onBackground = c.textPrimary,
            surface = c.surface,
            onSurface = c.textPrimary,
            surfaceVariant = c.surfaceRaised,
            onSurfaceVariant = c.textSecondary,
            border = c.border,
            error = c.danger,
        ),
    ) {
        CompositionLocalProvider(
            LocalCouchgramColors provides c,
            LocalContentColor provides c.textPrimary,
            content = content,
        )
    }
}
