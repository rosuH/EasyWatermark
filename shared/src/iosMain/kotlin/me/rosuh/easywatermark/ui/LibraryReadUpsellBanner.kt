package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_allow_all_photos
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_denied
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_dismiss
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_limited
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_settings
import org.jetbrains.compose.resources.stringResource

internal enum class LibraryReadBannerKind {
    Limited,
    Denied,
    Restricted,
}

/**
 * ADR-0029 Q11=B: information strip only. Does not gate pick / edit / export.
 * Limited CTA is “allow all photos” via Settings — never the limited-library picker.
 */
@Composable
internal fun LibraryReadUpsellBanner(
    kind: LibraryReadBannerKind,
    onCta: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = when (kind) {
        LibraryReadBannerKind.Limited ->
            stringResource(Res.string.tips_ios_library_read_upsell_limited)
        LibraryReadBannerKind.Denied,
        LibraryReadBannerKind.Restricted,
        -> stringResource(Res.string.tips_ios_library_read_upsell_denied)
    }
    val cta = when (kind) {
        LibraryReadBannerKind.Limited ->
            stringResource(Res.string.tips_ios_library_read_upsell_allow_all_photos)
        LibraryReadBannerKind.Denied,
        LibraryReadBannerKind.Restricted,
        -> stringResource(Res.string.tips_ios_library_read_upsell_settings)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("iosLibraryReadUpsellBanner"),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            TextButton(onClick = onCta) {
                Text(cta)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.tips_ios_library_read_upsell_dismiss))
            }
        }
    }
}
