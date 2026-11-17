package tv.couchgram

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.couchgram.ui.CaptionText
import tv.couchgram.ui.CenteredPanel
import tv.couchgram.ui.CouchgramTheme
import tv.couchgram.ui.MessagePanel
import tv.couchgram.ui.Motion
import tv.couchgram.ui.PrimaryButton
import tv.couchgram.ui.Radius
import tv.couchgram.ui.ScreenFrame
import tv.couchgram.ui.Wordmark
import tv.couchgram.ui.SecondaryButton
import tv.couchgram.ui.Spacing
import tv.couchgram.ui.StatusPill
import tv.couchgram.ui.Tone
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.NumberFormat

private const val SLOW_AFTER_MS = 15_000L

@Composable
fun LoginScreen(state: TdApi.AuthorizationState?) {
    val connection by Td.connection.collectAsStateWithLifecycle()
    val offline = connection is TdApi.ConnectionStateWaitingForNetwork
    ScreenFrame {
        CenteredPanel {
            when (state) {
                is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> QrLogin(state.link, offline)
                is TdApi.AuthorizationStateWaitPassword -> PasswordLogin(state.passwordHint)
                null,
                is TdApi.AuthorizationStateWaitTdlibParameters,
                is TdApi.AuthorizationStateWaitPhoneNumber -> if (offline) OfflinePanel() else Connecting()
                is TdApi.AuthorizationStateLoggingOut,
                is TdApi.AuthorizationStateClosing,
                is TdApi.AuthorizationStateClosed -> StatusPill(stringResource(R.string.login_signing_out), Tone.Active)
                // Shown only while this screen fades out after sign-in succeeds.
                is TdApi.AuthorizationStateReady -> Unit
                else -> UnsupportedStep()
            }
        }
    }
}

/** Nothing waits forever: after 15s the user gets the reason and a way to ask again. */
@Composable
private fun Connecting() {
    var attempt by remember { mutableIntStateOf(0) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        slow = false
        delay(SLOW_AFTER_MS)
        slow = true
    }
    if (!slow) {
        StatusPill(stringResource(R.string.status_connecting), Tone.Active)
    } else {
        val focus = remember { FocusRequester() }
        MessagePanel(stringResource(R.string.login_slow_title), stringResource(R.string.login_slow_body)) {
            PrimaryButton(
                stringResource(R.string.login_new_code),
                onClick = {
                    Td.requestQrCode()
                    attempt++
                },
                modifier = Modifier.focusRequester(focus),
            )
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

@Composable
private fun OfflinePanel() {
    MessagePanel(stringResource(R.string.login_offline_title), stringResource(R.string.login_offline_body))
}

@Composable
private fun UnsupportedStep() {
    val focus = remember { FocusRequester() }
    MessagePanel(stringResource(R.string.login_unsupported_title), stringResource(R.string.login_unsupported_body)) {
        PrimaryButton(stringResource(R.string.login_start_over), onClick = Td::signOut, modifier = Modifier.focusRequester(focus))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun QrLogin(link: String, offline: Boolean) {
    val colors = CouchgramTheme.colors
    val steps = listOf(
        stringResource(R.string.login_step_open),
        stringResource(R.string.login_step_link),
        stringResource(R.string.login_step_scan),
    )
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxxl), verticalAlignment = Alignment.CenterVertically) {
        // TDLib refreshes the link every ~30 seconds; the new code fades in rather than jumping.
        Crossfade(targetState = link, animationSpec = tween(Motion.STANDARD), label = "qr") { current ->
            val qr = remember(current) { qrBitmap(current, 600, colors.qrDark, colors.qrLight) }
            Image(qr, contentDescription = stringResource(R.string.login_qr_description), modifier = Modifier.size(320.dp))
        }
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Wordmark(style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(numbers.format(index + 1), style = MaterialTheme.typography.titleMedium, color = colors.accent, modifier = Modifier.width(Spacing.xl))
                    Text(step, style = MaterialTheme.typography.titleMedium)
                }
            }
            CaptionText(stringResource(R.string.login_code_refreshes))
            if (offline) StatusPill(stringResource(R.string.status_waiting_network), Tone.Warning)
        }
    }
}

@Composable
private fun PasswordLogin(hint: String) {
    val colors = CouchgramTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    // Not saveable on purpose: a password doesn't belong in saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(R.string.password_label)

    val submit: () -> Unit = {
        when {
            busy -> Unit
            password.isEmpty() -> error = context.getString(R.string.password_empty)
            else -> {
                busy = true
                error = null
                scope.launch {
                    try {
                        Td.send(TdApi.CheckAuthenticationPassword(password))
                    } catch (e: TdException) {
                        error = passwordError(context, e.error)
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.password_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.password_body), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        if (hint.isNotBlank()) CaptionText(stringResource(R.string.password_hint, hint))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary, modifier = Modifier.padding(top = Spacing.sm))
        BasicTextField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentType = ContentType.Password
                    contentDescription = label
                    error?.let { error(it) }
                }
                .background(colors.surfaceRaised, RoundedCornerShape(Radius.medium))
                .border(
                    width = 2.dp,
                    color = when {
                        error != null -> colors.danger
                        focused -> colors.accent
                        else -> colors.border
                    },
                    shape = RoundedCornerShape(Radius.medium),
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PrimaryButton(
                stringResource(if (busy) R.string.password_checking else R.string.password_submit),
                onClick = submit,
                enabled = !busy,
            )
            SecondaryButton(
                stringResource(if (visible) R.string.password_hide else R.string.password_show),
                onClick = { visible = !visible },
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
}

private fun passwordError(context: Context, error: TdApi.Error): String = when {
    error.message == "PASSWORD_HASH_INVALID" -> context.getString(R.string.password_wrong)
    error.code == 429 || error.message.startsWith("FLOOD_WAIT") -> context.getString(R.string.password_too_many)
    else -> context.getString(R.string.password_failed, error.message)
}

private fun qrBitmap(text: String, sizePx: Int, dark: Color, light: Color): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 2))
    val w = matrix.width
    val h = matrix.height
    val on = dark.toArgb()
    val off = light.toArgb()
    val pixels = IntArray(w * h) { i -> if (matrix[i % w, i / w]) on else off }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565).asImageBitmap()
}
