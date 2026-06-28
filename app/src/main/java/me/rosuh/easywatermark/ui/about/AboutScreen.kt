package me.rosuh.easywatermark.ui.about

import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.widget.ColoredImageVIew

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
    Box(modifier = modifier.fillMaxSize()) {
        // D2 (S4d-206): production v2.10.0 shows a soft lavender radial glow at the top behind the centered
        // logo. A fixed decorative layer behind the scrolling content (theme primary → transparent).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            Color.Transparent,
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // D1 (S4d-206): back arrow at start + the brand mark CENTERED and dynamic-colored/animated
            // (ColoredImageVIew, the same widget LaunchScreen uses), matching production — was a static,
            // left-aligned Image(ic_logo_about_page).
            Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                AndroidView(
                    modifier = Modifier.align(Alignment.Center).height(28.dp),
                    factory = { ctx ->
                        ColoredImageVIew(ctx).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageResource(R.drawable.ic_logo_about_page)
                            start()
                        }
                    }
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

        // Parity (S4d-205): production v2.10.0 places the developer footer ABOVE the toggles, with the
        // two switches at the very bottom of the screen. Match that order (footer → switches).
        Spacer(Modifier.height(24.dp))
        DevFooter(onOpenLink = onOpenLink)
        Spacer(Modifier.height(24.dp))

        SwitchRow("Force Open Dynamic Color Support", dynamicColorOn, onToggleDynamicColor)
        SwitchRow("Show Bounds", showBounds, onToggleBounds)
            Spacer(Modifier.height(24.dp))
        }
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

private data class DevCardData(val title: String, val desc: String, val avatar: Int, val url: String)

@Composable
private fun DevFooter(onOpenLink: (String) -> Unit) {
    // D4 (S4d-206): production v2.10.0 shows a horizontal card pager (developer card + designer card, the
    // next one peeking) with a SINGLE centered avatar below that tracks the current page — replacing the
    // simplified one-quote + two-avatar-row layout.
    val cards = listOf(
        DevCardData(
            title = "Developed with ♥ by rosu",
            desc = stringResource(R.string.dev_comment),
            avatar = R.drawable.bg_avatar_dev,
            url = URL_DEV,
        ),
        DevCardData(
            title = "Designed with ♥ by tovi",
            desc = "A Designer.",
            avatar = R.drawable.ic_avatar_tovi,
            url = URL_DESIGNER,
        ),
    )
    val pagerState = rememberPagerState(pageCount = { cards.size })
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val c = cards[page]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = c.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = c.desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        val current = cards[pagerState.currentPage]
        Image(
            painter = painterResource(id = current.avatar),
            contentDescription = current.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .clickable { onOpenLink(current.url) }
        )
    }
}

private const val URL_RELEASES = "https://github.com/rosuH/EasyWatermark/releases/"
private const val URL_MARKET = "market://details?id=me.rosuh.easywatermark"
private const val URL_ISSUES = "https://github.com/rosuH/EasyWatermark/issues/new"
private const val URL_PRIVACY_ZH = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md"
private const val URL_PRIVACY_EN = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md"
private const val URL_DEV = "https://github.com/rosuH"
private const val URL_DESIGNER = "https://tovi.fun/"
