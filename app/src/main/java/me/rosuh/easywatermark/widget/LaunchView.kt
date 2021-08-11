package me.rosuh.easywatermark.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.FragmentContainerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.dp

/**
 * Custom launch ViewGroup to replace MotionLayout
 * @author rosuh@qq.com
 * @date 2021/8/11
*/
class LaunchView : ViewGroup {

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    private val logoView: ColoredImageVIew by lazy {
        ColoredImageVIew(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(180.dp, 180.dp)
            setImageResource(R.drawable.ic_log_transparent)
        }
    }

    private val tvSelectPhotoTips: TextView by lazy {
        AppCompatTextView(context).apply {
            textAlignment = TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
            text = context.getString(R.string.tips_pick_image)
        }
    }

    private val ivSelectedPhotoTips: ImageView by lazy {
        AppCompatImageView(context, null, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                ViewGroup.MarginLayoutParams(180.dp, 180.dp).also { it.setMargins(0, 20.dp, 0, 0) }
            setImageResource(R.drawable.ic_picker_image)
        }
    }

    private val ivGoAboutPage: ImageView by lazy {
        AppCompatImageView(context, null, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                ViewGroup.MarginLayoutParams(180.dp, 180.dp).also { it.setMargins(0, 20.dp, 0, 0) }
            setImageResource(R.drawable.ic_log_transparent)
        }
    }

    private val toolbar: MaterialToolbar by lazy {
        MaterialToolbar(context).apply {
            layoutParams =
                ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    android.R.attr.actionBarSize
                )
                    .also { it.setMargins(0, 20.dp, 0, 0) }
            setBackgroundColor(android.R.attr.colorPrimary)
            elevation = 4.dp.toFloat()
            isVisible = false
            popupTheme = R.style.ThemeOverlay_AppCompat_Dark_ActionBar
        }
    }

    private val waterImageView: WaterMarkImageView by lazy {
        WaterMarkImageView(context).apply {
            setPadding(12.dp)
            isVisible = false
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimaryDark))
        }
    }

    private val tvDataTips: TextView by lazy {
        AppCompatTextView(context).apply {
            layoutParams =
                ViewGroup.MarginLayoutParams(180.dp, 180.dp).also { it.setMargins(0, 0, 0, 15.dp) }
            setPadding(5.dp)
            textAlignment = TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
            text = context.getString(R.string.tips_pick_image)
        }
    }

    private val tabLayout: TabLayout by lazy {
        TabLayout(context, null, R.style.Widget_App_TabLayout).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimaryDark))
            isVisible = false
            tabGravity = TabLayout.GRAVITY_FILL
            setSelectedTabIndicatorColor(ContextCompat.getColor(context, R.color.colorAccent))
            isTabIndicatorFullWidth = true
            val contentTab = TabLayout.Tab().also {
                it.text = context.getString(R.string.title_content)
                it.setIcon(R.drawable.ic_text_title)
            }
            val styleTab = TabLayout.Tab().also {
                it.text = context.getString(R.string.title_style)
                it.setIcon(R.drawable.ic_style_title)
            }
            val layoutTab = TabLayout.Tab().also {
                it.text = context.getString(R.string.title_layout)
                it.setIcon(R.drawable.ic_layout_title)
            }

            addTab(contentTab)
            addTab(styleTab)
            addTab(layoutTab)
        }
    }

    private val fcFunctionDetail: FragmentContainerView by lazy {
        FragmentContainerView(context).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                56.dp
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorSecondary))
            isInvisible = true
        }
    }

    private val rvPanel: TouchSensitiveRv by lazy {
        TouchSensitiveRv(context).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
            isInvisible = true
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

    }


    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}