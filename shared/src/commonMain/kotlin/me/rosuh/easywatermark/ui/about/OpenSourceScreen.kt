package me.rosuh.easywatermark.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.ui.LONG_TEXT_MAX_WIDTH_DP
import me.rosuh.easywatermark.shared.generated.resources.about_title_open_source
import me.rosuh.easywatermark.shared.generated.resources.cd_back
import me.rosuh.easywatermark.shared.generated.resources.open_source_desc_about_lib
import me.rosuh.easywatermark.shared.generated.resources.open_source_desc_material_components
import me.rosuh.easywatermark.shared.generated.resources.open_source_desc_phosphor_icons
import org.jetbrains.compose.resources.stringResource

/**
 * Open-source licenses list. S-i18n-2: labels from [Res], not bags.
 */
@Composable
fun OpenSourceScreen(
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    backIcon: Painter,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val title = stringResource(Res.string.about_title_open_source)
    val backCd = stringResource(Res.string.cd_back)
    val aboutLibDesc = stringResource(Res.string.open_source_desc_about_lib)
    val materialComponentsDesc = stringResource(Res.string.open_source_desc_material_components)
    val phosphorIconsDesc = stringResource(Res.string.open_source_desc_phosphor_icons)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = LONG_TEXT_MAX_WIDTH_DP.dp)
                    .fillMaxWidth()
                    .testTag("openSourceContentMaxWidth"),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = backIcon,
                        contentDescription = backCd,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            OssCard("Coil", aboutLibDesc) {
                onOpenLink("https://github.com/coil-kt/coil")
            }
            OssCard("Material Components for Android", materialComponentsDesc) {
                onOpenLink("https://github.com/material-components/material-components-android")
            }
            OssCard("Phosphor Icons", phosphorIconsDesc) {
                onOpenLink("https://github.com/phosphor-icons/core")
            }

            Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun OssCard(name: String, desc: String, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
