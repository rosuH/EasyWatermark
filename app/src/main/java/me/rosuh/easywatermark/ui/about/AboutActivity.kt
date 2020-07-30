package me.rosuh.easywatermark.ui.about

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import com.danielstone.materialaboutlibrary.MaterialAboutActivity
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.items.MaterialAboutTitleItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.openLink


class AboutActivity : MaterialAboutActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply {
            setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this@AboutActivity, R.color.colorPrimary)))
        }
    }

    override fun getActivityTitle(): CharSequence? {
        return getString(R.string.app_name)
    }

    override fun getMaterialAboutList(context: Context): MaterialAboutList {
        val aboutBuilder = MaterialAboutCard.Builder()
            .title(R.string.about_title_about)
            .cardColor(ContextCompat.getColor(this, R.color.colorPrimaryDark))
            .addItem(
                MaterialAboutActionItem.Builder()
                    .text(R.string.about_title_version)
                    .subText(BuildConfig.VERSION_NAME)
                    .icon(R.drawable.ic_version)
                    .setOnClickAction {
                        openLink("https://github.com/rosuH/EasyWatermark/releases/")

                    }.build()
            )
            .addItem(
                MaterialAboutActionItem.Builder()
                    .text(R.string.about_title_rating)
                    .icon(R.drawable.ic_rate)
                    .setOnClickAction {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=me.rosuh.easywatermark")
                            )
                        )
                    }.build()
            )
            .addItem(
                MaterialAboutActionItem.Builder()
                    .text(R.string.about_title_feed_back)
                    .icon(R.drawable.ic_bug_report)
                    .setOnClickAction {
                        openLink("https://github.com/rosuH/EasyWatermark/issues/new")
                    }.build()
            )

        val infoBuilder = MaterialAboutCard.Builder()
            .cardColor(ContextCompat.getColor(this, R.color.colorPrimaryDark))
            .title(R.string.about_title_info)
            .addItem(
                MaterialAboutActionItem.Builder()
                    .text(R.string.about_title_update_log)
                    .icon(R.drawable.ic_update_log)
                    .setOnClickAction {
                        openLink("https://github.com/rosuH/EasyWatermark/releases/")
                    }.build()
            )
            .addItem(
                MaterialAboutActionItem.Builder()
                    .text(R.string.about_title_open_source)
                    .icon(R.drawable.ic_open_source)
                    .setOnClickAction {
                        startActivity(Intent(this@AboutActivity, OpenSourceActivity::class.java))
                    }.build()
            )


        val authorBuilder = MaterialAboutCard.Builder()
            .cardColor(ContextCompat.getColor(this, R.color.colorPrimaryDark))
            .title(R.string.about_title_author)
            .addItem(
                MaterialAboutActionItem.Builder()
                    .text("rosuH")
                    .icon(R.drawable.ic_author)
                    .setOnClickAction {
                        openLink("https://github.com/rosuH")
                    }.build()
            )
            .addItem(
                MaterialAboutActionItem.Builder()
                    .text(R.string.about_title_repo_link)
                    .icon(R.drawable.ic_github)
                    .setOnClickAction {
                        openLink("https://github.com/rosuH/EasyWatermark")
                    }.build()
            )


        return MaterialAboutList.Builder()
            .addCard(aboutBuilder.build())
            .addCard(infoBuilder.build())
            .addCard(authorBuilder.build())
            .build()
    }
}