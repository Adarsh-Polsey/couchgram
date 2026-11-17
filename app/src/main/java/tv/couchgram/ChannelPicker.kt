package tv.couchgram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.couchgram.ui.CaptionText
import tv.couchgram.ui.CenteredPanel
import tv.couchgram.ui.MessagePanel
import tv.couchgram.ui.PrimaryButton
import tv.couchgram.ui.ScreenFrame
import tv.couchgram.ui.SecondaryButton
import tv.couchgram.ui.Spacing
import tv.couchgram.ui.VSpace
import tv.couchgram.ui.Wordmark

/** The channel picker: [choices] is null while loading; [failure] if Telegram didn't answer. */
data class PickerUi(
    val choices: List<ChannelChoice>? = null,
    val failure: LoadFailure? = null,
    /** False on first launch: there's no library to go back to, so Back leaves the app. */
    val canCancel: Boolean = false,
)

/**
 * Which channel or group the library shows. Opens after the first sign-in and from Settings →
 * Change channel. The account's own channels come first; [currentId] is marked and focused.
 */
@Composable
fun ChannelPicker(
    ui: PickerUi,
    currentId: Long,
    onChoose: (ChannelChoice) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    BackHandler(enabled = ui.canCancel, onBack = onCancel)
    ScreenFrame {
        Wordmark()
        VSpace(Spacing.lg)
        val choices = ui.choices
        when {
            ui.failure != null -> CenteredPanel { PickerFailure(ui.failure, onRetry, onSignOut) }
            choices == null -> {
                PickerHeading()
                CaptionText(stringResource(R.string.picker_loading))
            }
            choices.isEmpty() -> CenteredPanel { NoChannels(onRetry, onSignOut) }
            else -> {
                PickerHeading()
                ChoiceList(choices, currentId, onChoose)
            }
        }
    }
}

@Composable
private fun PickerHeading() {
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        CaptionText(stringResource(R.string.picker_body))
    }
    VSpace(Spacing.md)
}

@Composable
private fun ChoiceList(choices: List<ChannelChoice>, currentId: Long, onChoose: (ChannelChoice) -> Unit) {
    val focus = remember { FocusRequester() }
    val start = choices.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    // Starts scrolled to the focused row, so it's composed when focus is requested.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    LazyColumn(
        state = listState,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxHeight(),
        contentPadding = PaddingValues(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        itemsIndexed(choices, key = { _, choice -> choice.id }) { index, choice ->
            ListItem(
                selected = choice.id == currentId,
                onClick = { onChoose(choice) },
                headlineContent = {
                    Text(choice.title.ifBlank { stringResource(R.string.picker_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { Text(stringResource(kindOf(choice))) },
                modifier = if (index == start) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}

private fun kindOf(choice: ChannelChoice) = when {
    choice.isChannel && choice.yours -> R.string.picker_your_channel
    choice.isChannel -> R.string.picker_channel
    choice.yours -> R.string.picker_your_group
    else -> R.string.picker_group
}

@Composable
private fun NoChannels(onRefresh: () -> Unit, onSignOut: () -> Unit) {
    val focus = remember { FocusRequester() }
    MessagePanel(stringResource(R.string.picker_empty_title), stringResource(R.string.picker_empty_body)) {
        PrimaryButton(stringResource(R.string.library_refresh), onRefresh, Modifier.focusRequester(focus))
        SecondaryButton(stringResource(R.string.library_sign_out), onSignOut)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun PickerFailure(reason: LoadFailure, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val focus = remember { FocusRequester() }
    val (title, body) = when (reason) {
        is LoadFailure.Telegram ->
            stringResource(R.string.picker_failed_title) to stringResource(R.string.picker_failed_body, reason.message)
        else -> stringResource(R.string.library_timeout_title) to stringResource(R.string.library_timeout_body)
    }
    MessagePanel(title, body) {
        PrimaryButton(stringResource(R.string.action_try_again), onRetry, Modifier.focusRequester(focus))
        SecondaryButton(stringResource(R.string.library_sign_out), onSignOut)
    }
    LaunchedEffect(reason) { runCatching { focus.requestFocus() } }
}
