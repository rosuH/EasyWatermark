package me.rosuh.easywatermark.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.FragmentContainerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.dp
import me.rosuh.easywatermark.ktx.generateAppearAnimationList
import me.rosuh.easywatermark.ktx.generateDisappearAnimationList
import me.rosuh.easywatermark.widget.utils.BounceEdgeEffectFactory
import kotlin.math.abs


/**
 * Custom launch ViewGroup to replace MotionLayout.
 * Using [toLaunchMode] or [toEditorMode] to transform the view layout.
 * Animation was Included.
 * @author hi@rosuh.me
 * @date 2021/8/11
 */
class LaunchView : CustomViewGroup {

    //region 1 constructor
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
    //endregion

    //region 2 children components
    val logoView: ColoredImageVIew by lazy {
        ColoredImageVIew(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(180.dp, 180.dp)
            setImageResource(R.drawable.ic_log_transparent)
        }
    }

    val tvSelectPhotoTips: TextView by lazy {
        AppCompatTextView(context).apply {
            textAlignment = TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
            text = context.getString(R.string.tips_pick_image)
        }
    }

    val ivSelectedPhotoTips: ImageView by lazy {
        ImageView(context, null, 0, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                MarginLayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 20.dp, 0, 0) }
            setImageResource(R.drawable.ic_picker_image)
        }
    }

    val ivGoAboutPage: ImageView by lazy {
        ImageView(context, null, 0, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                MarginLayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 20.dp, 0, 0) }
            setImageResource(R.drawable.ic_about)
        }
    }

    val toolbar: MaterialToolbar by lazy {
        MaterialToolbar(context).apply {
            layoutParams =
                MarginLayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
                    .also { it.setMargins(0, 20.dp, 0, 0) }
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary))
            elevation = 4.dp.toFloat()
            popupTheme = R.style.ThemeOverlay_AppCompat_Dark_ActionBar
        }
    }

    val ivPhoto: WaterMarkImageView by lazy {
        WaterMarkImageView(context).apply {
            setPadding(12.dp)
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimaryDark))
        }
    }

    val tvDataTips: TextView by lazy {
        AppCompatTextView(context).apply {
            layoutParams =
                ViewGroup.MarginLayoutParams(180.dp, 180.dp).also { it.setMargins(0, 0, 0, 15.dp) }
            setPadding(5.dp)
            textAlignment = TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
            text = context.getString(R.string.tips_pick_image)
        }
    }

    val tabLayout: TabLayout by lazy {
        TabLayout(context).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setTabTextColors(
                ContextCompat.getColor(context, R.color.text_color_main),
                ContextCompat.getColor(context, R.color.selector_tab_color)
            )
            isTabIndicatorFullWidth = false
            tabGravity = TabLayout.GRAVITY_FILL
            isTabIndicatorFullWidth = false
            tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
            setTabIconTintResource(R.color.selector_tab_color)
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimaryDark))
            setSelectedTabIndicatorColor(ContextCompat.getColor(context, R.color.colorAccent))
            val contentTab = newTab().also {
                it.text = context.getString(R.string.title_content)
                it.setIcon(R.drawable.ic_text_title)
            }
            val styleTab = newTab().also {
                it.text = context.getString(R.string.title_style)
                it.setIcon(R.drawable.ic_style_title)
            }
            val layoutTab = newTab().also {
                it.text = context.getString(R.string.title_layout)
                it.setIcon(R.drawable.ic_layout_title)
            }

            addTab(contentTab)
            addTab(styleTab)
            addTab(layoutTab)
        }
    }

    val fcFunctionDetail: FragmentContainerView by lazy {
        FragmentContainerView(context).apply {
            id = generateViewId()
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                56.dp
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorSecondary))
        }
    }

    val rvPanel: TouchSensitiveRv by lazy {
        TouchSensitiveRv(context).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorSecondary))
            clipChildren = false
            clipToPadding = false
            edgeEffectFactory = BounceEdgeEffectFactory(context, this)
        }
    }
    //endregion

    //region 3 private field
    private val launchViews by lazy {
        listOf(logoView, tvSelectPhotoTips, ivSelectedPhotoTips, ivGoAboutPage)
    }

    private val editorViews by lazy {
        listOf(toolbar, ivPhoto, fcFunctionDetail, tabLayout, rvPanel)
    }

    private val launchModeAppearAnimationList by lazy {
        generateAppearAnimationList(launchViews)
    }

    private val launchModeDisappearAnimationList by lazy {
        generateDisappearAnimationList(launchViews)
    }

    private val editorModeAppearAnimationList by lazy {
        generateAppearAnimationList(editorViews)
    }

    private val editorModeDisappearAnimationList by lazy {
        generateDisappearAnimationList(editorViews)
    }

    var mode: ViewMode = ViewMode.LaunchMode
        private set(value) {
            if (field == value) return
            val oldMode = field
            field = value
            transformLayout(oldMode, value)
        }

    private var launchViewListener: LaunchViewListener? = null

    private var startX = 0f

    private var startY = 0f

    private val dragYAnimation by lazy {
        SpringAnimation(this, SpringAnimation.TRANSLATION_Y).apply {
            spring = SpringForce()
                .setFinalPosition(0f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        }
    }

    private val dragXAnimation by lazy {
        SpringAnimation(this, SpringAnimation.TRANSLATION_X).apply {
            spring = SpringForce()
                .setFinalPosition(0f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        }
    }
    //endregion

    init {
        launchViews.forEach {
            it.isVisible = false
            addView(it)
        }
        editorViews.forEach {
            it.isVisible = false
            addView(it)
        }
        post {
            launchModeAppearAnimationList.forEach { it.start() }
        }
    }


    //region 4 override view rendering
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        rvPanel.let {
            it.setPadding(it.measuredWidth / 2, 0, it.measuredWidth / 2, 0)
        }
        // measure children
        children.forEach {
            if (it != ivPhoto) {
                measureChildWithMargins(it, widthMeasureSpec, 0, heightMeasureSpec, 0)
            }
        }
        val heightUsed =
            toolbar.measuredHeight + fcFunctionDetail.measuredHeightWithMargins + tabLayout.measuredHeightWithMargins + rvPanel.measuredHeightWithMargins
        ivPhoto.measure(
            MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(parentHeight - heightUsed, MeasureSpec.EXACTLY)
        )
        // we are in match paren mode so just using parent size.
        setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        when (mode) {
            ViewMode.Editor -> layoutEditor()
            ViewMode.LaunchMode -> layoutLaunch()
        }
    }

    private fun layoutLaunch() {
        logoView.let {
            it.layoutCenterHorizontal(appendY = (measuredHeight * 0.3f).toInt())
        }
        tvSelectPhotoTips.let {
            it.layoutCenterHorizontal(appendY = logoView.bottom)
        }
        ivSelectedPhotoTips.let {
            it.layoutCenterHorizontal(appendY = tvSelectPhotoTips.bottom)
        }
        ivGoAboutPage.let {
            it.layoutCenterHorizontal(appendY = (measuredHeight - it.measuredHeightWithMargins))
        }
    }

    private fun layoutEditor() {
        // top
        toolbar.let {
            it.layout(0, 0)
        }
        // bottom
        tabLayout.let {
            it.layout(0, measuredHeight - it.measuredHeight)
        }
        rvPanel.let {
            it.layout(0, tabLayout.top - it.measuredHeightWithMargins)
        }
        fcFunctionDetail.let {
            it.layout(0, rvPanel.top - it.measuredHeightWithMargins)
        }

        ivPhoto.let {
            it.layout(0, toolbar.bottom)
        }
    }
    //endregion

    //region 5 inside caller
    /**
     * Transform the layout when mode switched.
     * @author hi@rosuh.me
     * @date 2021/8/12
     */
    private fun transformLayout(oldMode: ViewMode, toMode: ViewMode) {
        when (toMode) {
            ViewMode.Editor -> {
                launchModeDisappearAnimationList.forEach { it.start() }
                editorModeAppearAnimationList.forEach { it.start() }
            }
            ViewMode.LaunchMode -> {
                editorModeDisappearAnimationList.forEach { it.start() }
                launchModeAppearAnimationList.forEach { it.start() }
            }
        }
        requestLayout()
        launchViewListener?.onModeChange(oldMode, toMode)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (mode != ViewMode.LaunchMode) {
            return super.onTouchEvent(event)
        }
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragYAnimation.cancel()
                dragXAnimation.cancel()
                startX = event.rawX
                startY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - startX)
                val dy = (event.rawY - startY)

                var percentX = (1 - abs(this.translationX) / measuredWidth).coerceAtMost(1f) / 4
                if (percentX <= 0.2) percentX = 0f
                var percentY = (1 - abs(this.translationY) / measuredHeight).coerceAtMost(1f) / 4
                if (percentY <= 0.2) percentY = 0f
                this.translationX += dx * percentX
                this.translationY += dy * percentY
                startY = event.rawY
                startX = event.rawX
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                dragYAnimation.start()
                dragXAnimation.start()
            }
        }
        return super.onTouchEvent(event)
    }
    //endregion

    //region 6 outside caller
    fun toLaunchMode() {
        mode = ViewMode.LaunchMode
    }

    fun toEditorMode() {
        mode = ViewMode.Editor
    }

    fun setListener(block: LaunchViewListenerBuilder.() -> Unit) {
        launchViewListener = LaunchViewListenerBuilder().also(block)
    }
    //endregion

    /**
     * Sealed class forView showing mode.
     * @author hi@rosuh.me
     * @date 2021/8/12
     */
    sealed class ViewMode {
        object LaunchMode : ViewMode()
        object Editor : ViewMode()
    }
}