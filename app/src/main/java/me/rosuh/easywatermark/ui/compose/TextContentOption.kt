package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.WaterMark

@Preview
@Composable
private fun TextContentOptionPreview() {
    TextContentOption(
        item = FuncTitleModel(
            FuncTitleModel.FuncType.Text,
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
    val configuration = LocalWindowInfo.current.containerSize
    val screenHeight = configuration.height.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.wrapContentHeight(),
        verticalArrangement = Arrangement.Center
    ) {
        TextField(
            value = waterMark.text,
            onValueChange = onTextChange,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_go_template_list),
                contentDescription = stringResource(
                    id = R.string.dialog_title_template_title
                ),
                Modifier.clickable { onGoTemplateList() }
            )
        }
    }

}