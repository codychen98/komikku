package exh.ui.metadata

import android.os.Build
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Renders metadata key/value rows for every [exh.metadata.metadata.RaisedSearchMetadata] source
 * (E-Hentai, MangaDex, NHentai, etc.).
 */
@Composable
fun MetadataInfoList(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(scrollState),
    ) {
        items.forEach { (title, text) ->
            MetadataInfoRow(
                title = title,
                text = text,
            )
        }
    }
}

@Composable
private fun MetadataInfoRow(
    title: String,
    text: String,
) {
    val context = LocalContext.current
    val copyValue = {
        context.copyMetadataField(title, text)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = copyValue,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .width(140.dp)
                .padding(start = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.7F),
            )
        }
        IconButton(
            onClick = copyValue,
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
            )
        }
    }
}

private fun android.content.Context.copyMetadataField(label: String, value: String) {
    if (value.isBlank()) return
    copyToClipboard(label, value)
    // copyToClipboard skips the in-app toast on Android 13+ (system UI only).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        toast(MR.strings.copied_to_clipboard_plain)
    }
}
