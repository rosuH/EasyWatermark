package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Compose replacement for the legacy `activity_recovery.xml` crash-recovery screen
 * (View→Compose migration, ADR-0016). Shown by [me.rosuh.easywatermark.ui.ComposeMainActivity]
 * when `MyApp.recoveryMode` is true — the crash-loop self-heal surface. Pure UI + callbacks;
 * the host wires clipboard, email, links, and recovery-mode reset.
 *
 * Moved to `:shared/commonMain` in S4d-241. S4d-238 resource strategy: all visible labels are
 * passed as [RecoveryScreenStrings] (the Android caller resolves `stringResource` at the edge,
 * and passes the previously-hardcoded button literals through unchanged). This composable has
 * no `R.string`/`stringResource`/`painterResource` or Android-package dependencies.
 */
@Composable
fun RecoveryScreen(
    crashInfo: String,
    strings: RecoveryScreenStrings,
    onCopy: () -> Unit,
    onSendEmail: () -> Unit,
    onTelegram: () -> Unit,
    onStore: () -> Unit,
    onCloseRecovery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = strings.tips,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = crashInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCopy) {
            Text(strings.copy)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onSendEmail) { Text(strings.sendEmail) }
            TextButton(onClick = onTelegram) { Text(strings.sendTelegram) }
            TextButton(onClick = onStore) { Text(strings.jumpToStore) }
        }
        TextButton(onClick = onCloseRecovery) {
            Text(strings.turnOffRecovery)
        }
    }
}

/**
 * Resolved string values for [RecoveryScreen]. The Android caller constructs this at the edge
 * using `stringResource(R.string.*)` for the localized labels and passes the previously
 * hardcoded button literals (`sendEmail`/`sendTelegram`/`jumpToStore`) through unchanged.
 * Desktop/iOS pass hard-coded English strings. See S4d-238 resource strategy.
 */
data class RecoveryScreenStrings(
    val title: String,
    val tips: String,
    val copy: String,
    val sendEmail: String,
    val sendTelegram: String,
    val jumpToStore: String,
    val turnOffRecovery: String,
)
