package me.rosuh.easywatermark.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.R

/**
 * Compose replacement for the legacy [AboutActivity] (View→Compose migration).
 * Pure UI + callbacks; the hosting NavHost entry wires links (LocalUriHandler),
 * the open-source screen, and the two AboutViewModel toggles.
 */
@Composable
fun AboutScreen(
    versionName: String,
    showBounds: Boolean,
    dynamicColorOn: Boolean,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenSource: () -> Unit,
    onToggleBounds: (Boolean) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
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
                    painter = painterResource(id = R.drawable.ic_back),
                    contentDescription = "back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Image(
                painter = painterResource(id = R.drawable.ic_logo_about_page),
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).height(28.dp)
            )
        }

        SectionHeader(stringResource(R.string.about_title_info))
        AboutRow(R.drawable.ic_version, stringResource(R.string.about_title_version), trailing = versionName) {
            onOpenLink(URL_RELEASES)
        }
        AboutRow(R.drawable.ic_rate, stringResource(R.string.about_title_rating)) {
            onOpenLink(URL_MARKET)
        }
        AboutRow(R.drawable.ic_bug_report, stringResource(R.string.about_title_feed_back)) {
            onOpenLink(URL_ISSUES)
        }

        SectionHeader(stringResource(R.string.about_title_about))
        AboutRow(R.drawable.ic_update_log, stringResource(R.string.about_title_update_log)) {
            onOpenLink(URL_RELEASES)
        }
        AboutRow(R.drawable.ic_open_source, stringResource(R.string.about_title_open_source)) {
            onOpenSource()
        }
        AboutRow(R.drawable.ic_privacy_cn, stringResource(R.string.about_title_privacy_statement_zh)) {
            onOpenLink(URL_PRIVACY_ZH)
        }
        AboutRow(R.drawable.ic_privacy_en, stringResource(R.string.about_title_privacy_statement)) {
            onOpenLink(URL_PRIVACY_EN)
        }

        SwitchRow("Force Open Dynamic Color Support", dynamicColorOn, onToggleDynamicColor)
        SwitchRow("Show Bounds", showBounds, onToggleBounds)

        Spacer(Modifier.height(24.dp))
        DevFooter(onOpenLink = onOpenLink)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun AboutRow(
    iconRes: Int,
    title: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp).weight(1f)
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DevFooter(onOpenLink: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.dev_comment),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Image(
                painter = painterResource(id = R.drawable.bg_avatar_dev),
                contentDescription = "developer",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable { onOpenLink(URL_DEV) }
            )
            Image(
                painter = painterResource(id = R.drawable.ic_avatar_tovi),
                contentDescription = "designer",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable { onOpenLink(URL_DESIGNER) }
            )
        }
    }
}

private const val URL_RELEASES = "https://github.com/rosuH/EasyWatermark/releases/"
private const val URL_MARKET = "market://details?id=me.rosuh.easywatermark"
private const val URL_ISSUES = "https://github.com/rosuH/EasyWatermark/issues/new"
private const val URL_PRIVACY_ZH = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md"
private const val URL_PRIVACY_EN = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md"
private const val URL_DEV = "https://github.com/rosuH"
private const val URL_DESIGNER = "https://tovi.fun/"
