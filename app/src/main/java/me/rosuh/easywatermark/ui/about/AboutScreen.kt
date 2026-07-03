package me.rosuh.easywatermark.ui.about

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.widget.ColoredImageVIew

/**
 * Android wrapper for the shared About screen shell.
 *
 * Android keeps resource lookup, URL routing, and the legacy animated logo view edge.
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
    AboutScreenShell(
        versionName = versionName,
        showBounds = showBounds,
        dynamicColorOn = dynamicColorOn,
        strings = AboutScreenStrings(
            infoTitle = stringResource(R.string.about_title_info),
            versionTitle = stringResource(R.string.about_title_version),
            ratingTitle = stringResource(R.string.about_title_rating),
            feedbackTitle = stringResource(R.string.about_title_feed_back),
            aboutTitle = stringResource(R.string.about_title_about),
            updateLogTitle = stringResource(R.string.about_title_update_log),
            openSourceTitle = stringResource(R.string.about_title_open_source),
            privacyZhTitle = stringResource(R.string.about_title_privacy_statement_zh),
            privacyEnTitle = stringResource(R.string.about_title_privacy_statement),
            dynamicColorLabel = "Force Open Dynamic Color Support",
            showBoundsLabel = "Show Bounds",
        ),
        icons = AboutScreenIcons(
            back = painterResource(R.drawable.ic_back),
            version = painterResource(R.drawable.ic_version),
            rating = painterResource(R.drawable.ic_rate),
            feedback = painterResource(R.drawable.ic_bug_report),
            updateLog = painterResource(R.drawable.ic_update_log),
            openSource = painterResource(R.drawable.ic_open_source),
            privacyZh = painterResource(R.drawable.ic_privacy_cn),
            privacyEn = painterResource(R.drawable.ic_privacy_en),
        ),
        developerCard = AboutDevCard(
            title = "Developed with ♥ by rosu",
            description = stringResource(R.string.dev_comment),
            avatar = painterResource(R.drawable.bg_avatar_dev),
        ),
        designerCard = AboutDevCard(
            title = "Designed with ♥ by tovi",
            description = "A Designer.",
            avatar = painterResource(R.drawable.ic_avatar_tovi),
        ),
        onBack = onBack,
        onVersion = { onOpenLink(URL_RELEASES) },
        onRate = { onOpenLink(URL_MARKET) },
        onFeedback = { onOpenLink(URL_ISSUES) },
        onUpdateLog = { onOpenLink(URL_RELEASES) },
        onOpenSource = onOpenSource,
        onPrivacyZh = { onOpenLink(URL_PRIVACY_ZH) },
        onPrivacyEn = { onOpenLink(URL_PRIVACY_EN) },
        onDeveloper = { onOpenLink(URL_DEV) },
        onDesigner = { onOpenLink(URL_DESIGNER) },
        onToggleBounds = onToggleBounds,
        onToggleDynamicColor = onToggleDynamicColor,
        modifier = modifier,
        logo = { logoModifier ->
            AndroidView(
                modifier = logoModifier,
                factory = { ctx ->
                    ColoredImageVIew(ctx).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageResource(R.drawable.ic_logo_about_page)
                        start()
                    }
                }
            )
        },
    )
}

private const val URL_RELEASES = "https://github.com/rosuH/EasyWatermark/releases/"
private const val URL_MARKET = "market://details?id=me.rosuh.easywatermark"
private const val URL_ISSUES = "https://github.com/rosuH/EasyWatermark/issues/new"
private const val URL_PRIVACY_ZH = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md"
private const val URL_PRIVACY_EN = "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md"
private const val URL_DEV = "https://github.com/rosuH"
private const val URL_DESIGNER = "https://tovi.fun/"
