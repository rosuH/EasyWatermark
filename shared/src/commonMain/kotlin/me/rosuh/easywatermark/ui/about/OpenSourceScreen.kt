package me.rosuh.easywatermark.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/**
 * Compose replacement for the legacy [OpenSourceActivity] (View→Compose migration).
 * A simple scrollable list of open-source library cards; each opens its repo link.
 *
 * S4d-238 resource strategy: all text is passed as [OpenSourceScreenStrings] (the Android
 * caller resolves `stringResource` at the edge); the back icon is passed as a [Painter]
 * (the Android caller resolves `painterResource` at the edge). This composable has no
 * `R.string`/`R.drawable`/`stringResource`/`painterResource` dependencies.
 */
@Composable
fun OpenSourceScreen(
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    backIcon: Painter,
    strings: OpenSourceScreenStrings,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = backIcon,
                    contentDescription = "back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = strings.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        OssCard("Coil", strings.aboutLibDesc) {
            onOpenLink("https://github.com/coil-kt/coil")
        }
        OssCard("Material Components for Android", strings.materialComponentsDesc) {
            onOpenLink("https://github.com/material-components/material-components-android")
        }
        OssCard("Compressor", strings.compressorDesc) {
            onOpenLink("https://github.com/zetbaitsu/Compressor/")
        }

        Spacer(Modifier.height(24.dp))
    }
}

data class OpenSourceScreenStrings(
    val title: String,
    val aboutLibDesc: String,
    val materialComponentsDesc: String,
    val compressorDesc: String,
)

@Composable
private fun OssCard(name: String, desc: String, onClick: () -> Unit) {
    // Parity (S4d-206, D5/D6): production v2.10.0 uses OUTLINED cards (border, transparent fill) with a
    // LARGE title, not a filled secondaryContainer card with a medium title.
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
