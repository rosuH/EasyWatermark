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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import me.rosuh.easywatermark.ui.theme.DesignSliderTrack
import me.rosuh.easywatermark.shared.generated.resources.about_force_dynamic_color
import me.rosuh.easywatermark.shared.generated.resources.about_show_bounds
import me.rosuh.easywatermark.shared.generated.resources.about_title_about
import me.rosuh.easywatermark.shared.generated.resources.about_title_feed_back
import me.rosuh.easywatermark.shared.generated.resources.about_title_info
import me.rosuh.easywatermark.shared.generated.resources.about_title_open_source
import me.rosuh.easywatermark.shared.generated.resources.about_title_privacy_statement
import me.rosuh.easywatermark.shared.generated.resources.about_title_privacy_statement_zh
import me.rosuh.easywatermark.shared.generated.resources.about_title_rating
import me.rosuh.easywatermark.shared.generated.resources.about_title_update_log
import me.rosuh.easywatermark.shared.generated.resources.about_title_version
import me.rosuh.easywatermark.shared.generated.resources.cd_back
import org.jetbrains.compose.resources.stringResource

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
 * Shared product About screen. S-i18n-2: labels from [Res]; hosts supply painters + slots.
 */
@Composable
fun AboutScreen(
    versionName: String,
    showBounds: Boolean,
    dynamicColorOn: Boolean,
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
    val infoTitle = stringResource(Res.string.about_title_info)
    val versionTitle = stringResource(Res.string.about_title_version)
    val ratingTitle = stringResource(Res.string.about_title_rating)
    val feedbackTitle = stringResource(Res.string.about_title_feed_back)
    val aboutTitle = stringResource(Res.string.about_title_about)
    val updateLogTitle = stringResource(Res.string.about_title_update_log)
    val openSourceTitle = stringResource(Res.string.about_title_open_source)
    val privacyZhTitle = stringResource(Res.string.about_title_privacy_statement_zh)
    val privacyEnTitle = stringResource(Res.string.about_title_privacy_statement)
    val dynamicColorLabel = stringResource(Res.string.about_force_dynamic_color)
    val showBoundsLabel = stringResource(Res.string.about_show_bounds)
    val backCd = stringResource(Res.string.cd_back)

    // Full-bleed olive + full-screen radial halo (production bg_gradient_about_page).
    // Halo MUST be drawn on the full-size root — a fixed-height band hard-clips the
    // radial falloff at its bottom edge (user-reported lower half cut off).
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                // Production: centerX=50%p, centerY≈10%p (near logo), radius≈110%p.
                // Full-screen drawRect so the gradient fades into olive with no hard edge.
                val center = Offset(size.width / 2f, size.height * 0.12f)
                val radius = maxOf(size.width, size.height) * 1.10f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFD703).copy(alpha = 0.20f),
                            Color(0xFFFFD703).copy(alpha = 0.07f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // Production: logo near top (marginTop ~36dp), not vertically centered in a tall hero.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AboutHeroHeight)
                    .padding(horizontal = 4.dp),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(
                        painter = icons.back,
                        contentDescription = backCd,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                logo(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = AboutLogoTopPadding)
                        .size(AboutLogoSize),
                )
            }

            SectionHeader(infoTitle)
            AboutRow(icons.version, versionTitle, trailing = versionName, onClick = onVersion)
            AboutRow(icons.rating, ratingTitle, onClick = onRate)
            AboutRow(icons.feedback, feedbackTitle, onClick = onFeedback)

            SectionHeader(aboutTitle)
            AboutRow(icons.updateLog, updateLogTitle, onClick = onUpdateLog)
            AboutRow(icons.openSource, openSourceTitle, onClick = onOpenSource)
            AboutRow(icons.privacyZh, privacyZhTitle, onClick = onPrivacyZh)
            AboutRow(icons.privacyEn, privacyEnTitle, onClick = onPrivacyEn)

            Spacer(Modifier.height(24.dp))
            DevFooter(
                developerCard = developerCard,
                designerCard = designerCard,
                onDeveloper = onDeveloper,
                onDesigner = onDesigner,
            )
            Spacer(Modifier.height(24.dp))

            // Debug toggles — full-row hit target + brand Switch colors (not stock M3).
            SwitchRow(dynamicColorLabel, dynamicColorOn, onToggleDynamicColor)
            SwitchRow(showBoundsLabel, showBounds, onToggleBounds)
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
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DesignEditorBg,
                checkedTrackColor = DesignBrand,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = DesignSliderTrack,
                uncheckedBorderColor = Color.White.copy(alpha = 0.25f),
            ),
        )
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 40.dp),
            pageSpacing = 16.dp,
        ) { page ->
            val (card, onClick) = cards[page]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = card.avatar,
                        contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(28.dp)),
                    )
                    Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = card.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Production about hero logo: xxhdpi 192px → 64.dp (not toolbar 28.dp). */
private val AboutLogoSize = 64.dp

/** Production logo sits high (marginTop ~36dp), not mid-hero. */
private val AboutLogoTopPadding = 8.dp

/** Compact hero: back row + logo (~64) + small bottom gap. */
private val AboutHeroHeight = 100.dp

/** @deprecated Use [AboutScreen]. Temporary alias while hosts migrate. */
@Deprecated("Use AboutScreen", ReplaceWith("AboutScreen(versionName, showBounds, dynamicColorOn, icons, developerCard, designerCard, onBack, onVersion, onRate, onFeedback, onUpdateLog, onOpenSource, onPrivacyZh, onPrivacyEn, onDeveloper, onDesigner, onToggleBounds, onToggleDynamicColor, modifier, logo)"))
@Composable
fun AboutScreenShell(
    versionName: String,
    showBounds: Boolean,
    dynamicColorOn: Boolean,
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
    AboutScreen(
        versionName = versionName,
        showBounds = showBounds,
        dynamicColorOn = dynamicColorOn,
        icons = icons,
        developerCard = developerCard,
        designerCard = designerCard,
        onBack = onBack,
        onVersion = onVersion,
        onRate = onRate,
        onFeedback = onFeedback,
        onUpdateLog = onUpdateLog,
        onOpenSource = onOpenSource,
        onPrivacyZh = onPrivacyZh,
        onPrivacyEn = onPrivacyEn,
        onDeveloper = onDeveloper,
        onDesigner = onDesigner,
        onToggleBounds = onToggleBounds,
        onToggleDynamicColor = onToggleDynamicColor,
        modifier = modifier,
        logo = logo,
    )
}
