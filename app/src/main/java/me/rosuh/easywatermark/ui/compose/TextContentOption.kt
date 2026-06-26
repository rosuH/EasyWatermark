package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.WaterMark

@Preview
@Composable
private fun TextContentOptionPreview() {
    TextContentOption(
        item = FuncTitleModel(
            FuncType.Text,
            R.string.water_mark_mode_text,
            R.drawable.ic_func_text
        ),
        waterMark = WaterMark.default,
        onTextChange = {},
        onGoTemplateList = {}
    )
}

@Composable
fun TextContentOption(
    item: FuncTitleModel,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    onTextChange: (String) -> Unit,
    onGoTemplateList: () -> Unit,
) {
    var showEditSheet by remember { mutableStateOf(false) }
    // Parity (ADR-0011 / ADR-0015 item B): production opens a modal "Edit watermark"
    // sheet when the text row is tapped, instead of an inline always-editable field.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = waterMark.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { showEditSheet = true }
                .padding(vertical = 16.dp)
        )
        Icon(
            painter = painterResource(id = R.drawable.ic_go_template_list),
            contentDescription = stringResource(id = R.string.dialog_title_template_title),
            modifier = Modifier
                .clickable { onGoTemplateList() }
                .padding(start = 16.dp)
        )
    }

    if (showEditSheet) {
        WatermarkTextEditSheet(
            initialText = waterMark.text,
            onConfirm = {
                onTextChange(it)
                showEditSheet = false
            },
            onDismiss = { showEditSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatermarkTextEditSheet(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialText) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = stringResource(id = R.string.dialog_title_edit_watermark),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RectangleShape,
            )
            Button(
                onClick = { onConfirm(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                shape = RectangleShape,
            ) {
                Text(text = stringResource(id = R.string.tips_confirm_dialog))
            }
        }
    }
}
