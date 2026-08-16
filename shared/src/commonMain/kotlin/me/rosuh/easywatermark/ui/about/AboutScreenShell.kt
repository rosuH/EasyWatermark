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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.ui.ABOUT_CONTENT_MAX_WIDTH_DP
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import me.rosuh.easywatermark.ui.theme.DesignSliderTrack
import me.rosuh.easywatermark.shared.generated.resources.about_follow_photo
import me.rosuh.easywatermark.shared.generated.resources.about_follow_wallpaper
import me.rosuh.easywatermark.shared.generated.resources.about_prefer_in_app_gallery
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
    modifier: Modifier = Modifier,
    /**
     * ADR-0027: Android-only wallpaper Material You preference.
     * When false, hide the Follow wallpaper switch (iOS/Desktop).
     */
    showFollowWallpaperSwitch: Boolean = false,
    followWallpaperOn: Boolean = false,
    onToggleFollowWallpaper: (Boolean) -> Unit = {},
    /** Content editor theme from current photo (all platforms). Default on. */
    followPhotoOn: Boolean = true,
    onToggleFollowPhoto: (Boolean) -> Unit = {},
    /** Android-only: show preference for in-app MediaStore gallery vs system Photo Picker. */
    showPreferInAppGallerySwitch: Boolean = false,
    preferInAppGallery: Boolean = false,
    onTogglePreferInAppGallery: (Boolean) -> Unit = {},
    /**
     * ≥800 dual-pane / Desktop: limit content width and show Dev+Designer side-by-side (H5).
     * Compact/Medium keep full-bleed scroll + pager cards.
     */
    useLargeLayout: Boolean = false,
    /**
     * Extra inset for **content** (back button / scroll) after [safeDrawingPadding].
     * Desktop macOS uses this for the traffic-light band so the olive + halo stay
     * full-bleed under a transparent title bar.
     */
    contentPadding: PaddingValues = PaddingValues(),
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
    val followWallpaperLabel = stringResource(Res.string.about_follow_wallpaper)
    val followPhotoLabel = stringResource(Res.string.about_follow_photo)
    val showBoundsLabel = stringResource(Res.string.about_show_bounds)
    val preferInAppGalleryLabel = stringResource(Res.string.about_prefer_in_app_gallery)
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
        // Back at top-start (production About / Material convention). Launch keeps its
        // info entry at BottomCenter; About does not mirror that thumb target.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(contentPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Clear the floating back IconButton at top (48.dp).
                    .padding(top = 48.dp)
                    .testTag(if (useLargeLayout) "aboutLargeLayout" else "aboutCompactLayout"),
                horizontalAlignment = if (useLargeLayout) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (useLargeLayout) {
                                Modifier.widthIn(max = ABOUT_CONTENT_MAX_WIDTH_DP.dp)
                            } else {
                                Modifier
                            },
                        )
                        .fillMaxWidth(),
                ) {
                    // Production: logo near top (marginTop ~36dp), not vertically centered in a tall hero.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AboutHeroHeight)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        logo(
                            Modifier
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
                        sideBySide = useLargeLayout,
                    )
                    Spacer(Modifier.height(24.dp))

                    // Prefs toggles — full-row hit target + brand Switch colors (not stock M3).
                    if (showFollowWallpaperSwitch) {
                        SwitchRow(followWallpaperLabel, followWallpaperOn, onToggleFollowWallpaper)
                    }
                    SwitchRow(followPhotoLabel, followPhotoOn, onToggleFollowPhoto)
                    SwitchRow(showBoundsLabel, showBounds, onToggleBounds)
                    if (showPreferInAppGallerySwitch) {
                        SwitchRow(
                            preferInAppGalleryLabel,
                            preferInAppGallery,
                            onTogglePreferInAppGallery,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .testTag("aboutBack"),
            ) {
                Icon(
                    painter = icons.back,
                    contentDescription = backCd,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
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
    // I2: single toggleable row (name = label text, Role.Switch, checked state).
    // Switch is visual only so TalkBack does not see two separate focus targets.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag("aboutSwitchRow"),
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
            onCheckedChange = null,
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
    sideBySide: Boolean = false,
) {
    val cards = listOf(developerCard to onDeveloper, designerCard to onDesigner)
    val cardHeight = AboutDevCardHeight
    if (sideBySide) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("aboutDevCardsSideBySide"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            cards.forEach { (card, onClick) ->
                DevPersonCard(
                    card = card,
                    onClick = onClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(cardHeight),
                )
            }
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { cards.size })
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 36.dp),
            pageSpacing = 12.dp,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight + 8.dp)
                .testTag("aboutDevCardsPager"),
        ) { page ->
            val (card, onClick) = cards[page]
            DevPersonCard(
                card = card,
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .graphicsLayer {
                        val pageOffset = (
                            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                            ).absoluteValue
                        val focus = (1f - pageOffset.coerceIn(0f, 1f))
                        val scale = AboutDevCardSideScale +
                            (1f - AboutDevCardSideScale) * focus
                        scaleX = scale
                        scaleY = scale
                        alpha = AboutDevCardSideAlpha +
                            (1f - AboutDevCardSideAlpha) * focus
                    },
            )
        }
    }
}

@Composable
private fun DevPersonCard(
    card: AboutDevCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = card.avatar,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AboutDevAvatarSize)
                    .clip(RoundedCornerShape(AboutDevAvatarSize / 2)),
            )
            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier.height(4.dp))
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Production about hero logo: xxhdpi 192px → 64.dp (not toolbar 28.dp). */
private val AboutLogoSize = 64.dp

/** Production logo sits high (marginTop ~36dp), not mid-hero. */
private val AboutLogoTopPadding = 8.dp

/** Compact hero: logo only (~64) + small vertical gap (back is bottom chrome like Launch). */
private val AboutHeroHeight = 88.dp

/** Equal-size developer / designer cards (avatar 56 + vertical padding). */
private val AboutDevCardHeight = 88.dp
private val AboutDevAvatarSize = 56.dp

/** Side (unfocused) page scale / alpha; focused page is 1f. */
private const val AboutDevCardSideScale = 0.88f
private const val AboutDevCardSideAlpha = 0.72f

/** @deprecated Use [AboutScreen]. Temporary alias while hosts migrate. */
@Deprecated("Use AboutScreen")
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
        showFollowWallpaperSwitch = true,
        followWallpaperOn = dynamicColorOn,
        onToggleFollowWallpaper = onToggleDynamicColor,
        modifier = modifier,
        logo = logo,
    )
}
