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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.copy
import me.rosuh.easywatermark.shared.generated.resources.recovery_jump_to_store
import me.rosuh.easywatermark.shared.generated.resources.recovery_mode_tips
import me.rosuh.easywatermark.shared.generated.resources.recovery_send_email
import me.rosuh.easywatermark.shared.generated.resources.recovery_send_telegram
import me.rosuh.easywatermark.shared.generated.resources.recovery_title
import me.rosuh.easywatermark.shared.generated.resources.turn_off_recovery_mode
import org.jetbrains.compose.resources.stringResource

/**
 * Compose crash-recovery screen (ADR-0016). S-i18n-2: labels from [Res], not bags.
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
    val title = stringResource(Res.string.recovery_title)
    val tips = stringResource(Res.string.recovery_mode_tips)
    val copy = stringResource(Res.string.copy)
    val sendEmail = stringResource(Res.string.recovery_send_email)
    val sendTelegram = stringResource(Res.string.recovery_send_telegram)
    val jumpToStore = stringResource(Res.string.recovery_jump_to_store)
    val turnOffRecovery = stringResource(Res.string.turn_off_recovery_mode)

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .testTag("sharedComposeRecoveryScreen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = tips,
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
            Text(copy)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onSendEmail) { Text(sendEmail) }
            TextButton(onClick = onTelegram) { Text(sendTelegram) }
            TextButton(onClick = onStore) { Text(jumpToStore) }
        }
        TextButton(
            onClick = onCloseRecovery,
            modifier = Modifier.testTag("sharedComposeRecoveryClose"),
        ) {
            Text(turnOffRecovery)
        }
    }
}
