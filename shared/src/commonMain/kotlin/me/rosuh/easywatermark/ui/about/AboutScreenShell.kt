package me.rosuh.easywatermark.ui.about

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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

data class AboutScreenStrings(
    val infoTitle: String,
    val versionTitle: String,
    val ratingTitle: String,
    val feedbackTitle: String,
    val aboutTitle: String,
    val updateLogTitle: String,
    val openSourceTitle: String,
    val privacyZhTitle: String,
    val privacyEnTitle: String,
    val dynamicColorLabel: String,
    val showBoundsLabel: String,
)

data class AboutScreenIcons(
    val back: Painter,
    val version: Painter,
    val rating: Painter,
    val feedback: Painter,
    val updateLog: Painter,
    val openSource: Painter,
    val privacyZh: Painter,
    val privacyEn: Painter,
)

data class AboutDevCard(
    val title: String,
    val description: String,
    val avatar: Painter,
)

/**
 * Shared CMP shell for the About screen.
 *
 * Android still provides the animated logo slot, localized resources, painters, link routing, and
 * settings callbacks. This shell owns the screen layout and interaction placement.
 */
@Composable
fun AboutScreenShell(
    versionName: String,
    showBounds: Boolean,
    dynamicColorOn: Boolean,
    strings: AboutScreenStrings,
    icons: AboutScreenIcons,
    developerCard: AboutDevCard,
    designerCard: AboutDevCard,
    onBack: () -> Unit,
    onVersion: () -> Unit,
    onRate: () -> Unit,
    onFeedback: () -> Unit,
    onUpdateLog: () -> Unit,
    onOpenSource: () -> Unit,
    onPrivacyZh: () -> Unit,
    onPrivacyEn: () -> Unit,
    onDeveloper: () -> Unit,
    onDesigner: () -> Unit,
    onToggleBounds: (Boolean) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    logo: @Composable (modifier: Modifier) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Parity: production shows a soft radial glow behind the centered logo.
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
            Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        painter = icons.back,
                        contentDescription = "back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                logo(
                    Modifier
                        .align(Alignment.Center)
                        .height(28.dp)
                )
            }

            SectionHeader(strings.infoTitle)
            AboutRow(icons.version, strings.versionTitle, trailing = versionName, onClick = onVersion)
            AboutRow(icons.rating, strings.ratingTitle, onClick = onRate)
            AboutRow(icons.feedback, strings.feedbackTitle, onClick = onFeedback)

            SectionHeader(strings.aboutTitle)
            AboutRow(icons.updateLog, strings.updateLogTitle, onClick = onUpdateLog)
            AboutRow(icons.openSource, strings.openSourceTitle, onClick = onOpenSource)
            AboutRow(icons.privacyZh, strings.privacyZhTitle, onClick = onPrivacyZh)
            AboutRow(icons.privacyEn, strings.privacyEnTitle, onClick = onPrivacyEn)

            Spacer(Modifier.height(24.dp))
            DevFooter(
                developerCard = developerCard,
                designerCard = designerCard,
                onDeveloper = onDeveloper,
                onDesigner = onDesigner,
            )
            Spacer(Modifier.height(24.dp))

            SwitchRow(strings.dynamicColorLabel, dynamicColorOn, onToggleDynamicColor)
            SwitchRow(strings.showBoundsLabel, showBounds, onToggleBounds)
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
    icon: Painter,
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
            painter = icon,
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
private fun DevFooter(
    developerCard: AboutDevCard,
    designerCard: AboutDevCard,
    onDeveloper: () -> Unit,
    onDesigner: () -> Unit,
) {
    val cards = listOf(developerCard to onDeveloper, designerCard to onDesigner)
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
            val card = cards[page].first
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = card.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        val (current, onClick) = cards[pagerState.currentPage]
        Image(
            painter = current.avatar,
            contentDescription = current.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .clickable { onClick() }
        )
    }
}
