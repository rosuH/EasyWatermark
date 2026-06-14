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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.R

/**
 * Compose replacement for the legacy `activity_recovery.xml` crash-recovery screen
 * (View→Compose migration, ADR-0016). Shown by [ComposeMainActivity] when
 * `MyApp.recoveryMode` is true — the crash-loop self-heal surface. Pure UI + callbacks;
 * the host wires clipboard, email, links, and recovery-mode reset.
 */
@Composable
fun RecoveryScreen(
    crashInfo: String,
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
            text = stringResource(R.string.recovery_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = stringResource(R.string.recovery_mode_tips),
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
            Text(stringResource(R.string.copy))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onSendEmail) { Text("Send email") }
            TextButton(onClick = onTelegram) { Text("Send Telegram") }
            TextButton(onClick = onStore) { Text("Jump to Store") }
        }
        TextButton(onClick = onCloseRecovery) {
            Text(stringResource(R.string.turn_off_recovery_mode))
        }
    }
}
