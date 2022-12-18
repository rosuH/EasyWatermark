package me.rosuh.easywatermark.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.FragmentContainerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.tabs.TabLayout
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.widget.utils.BounceEdgeEffectFactory
import me.rosuh.easywatermark.utils.ktx.dp
import me.rosuh.easywatermark.utils.ktx.generateAppearAnimationList
import me.rosuh.easywatermark.utils.ktx.generateDisappearAnimationList
import kotlin.math.abs

/**
 * Custom launch ViewGroup to replace MotionLayout.
 * Using [toLaunchMode] or [toEditorMode] to transform the view layout.
 * Animation was Included.
 * @author hi@rosuh.me
 * @date 2021/8/11
 */
@SuppressLint("ClickableViewAccessibility")
class LaunchView : CustomViewGroup {

    companion object {
        private const val TAG = "LaunchView"
    }

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
            layoutParams = MarginLayoutParams(180.dp, 180.dp)
            setImageResource(R.drawable.ic_log_transparent)
        }
    }

    val ivSelectedPhotoTips: MaterialButton by lazy {
        MaterialButton(context).apply {
            minHeight = 56.dp
            minWidth = 120.dp
            cornerRadius = 56.dp / 3
            textAlignment = TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
            text = context.getString(R.string.tips_pick_image)
            shapeAppearanceModel = ShapeAppearanceModel.Builder().also {
                it.setAllCornerSizes(0f)
            }.build()
        }
    }

    val ivGoAboutPage: ImageView by lazy {
        ImageView(context, null, 0, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                MarginLayoutParams(
                    48.dp,
                    48.dp
                ).also {
                    it.setMargins(0, 0, 0, 16.dp)
                }
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
//            setBackgroundColor(context.colorSurface)
        }
    }

    val ivPhoto: WaterMarkImageView by lazy {
        WaterMarkImageView(context).apply {
            layoutParams =
                MarginLayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
            setPadding(12.dp)
            scaleType = ImageView.ScaleType.MATRIX
        }
    }

    val tabLayout: TabLayout by lazy {
        TabLayout(context).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            isTabIndicatorFullWidth = false
            tabGravity = TabLayout.GRAVITY_FILL
            tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
            setBackgroundColor(Color.TRANSPARENT)
            val contentTab = newTab().also {
                it.text = context.getString(R.string.title_content)
            }
            val styleTab = newTab().also {
                it.text = context.getString(R.string.title_style)
            }
            val layoutTab = newTab().also {
                it.text = context.getString(R.string.title_layout)
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
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    val rvPanel: TouchSensitiveRv by lazy {
        TouchSensitiveRv(context).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            edgeEffectFactory = BounceEdgeEffectFactory(context, this)
        }
    }

    val rvPhotoList: TouchSensitiveRv by lazy {
        TouchSensitiveRv(context).apply {
            layoutParams = MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            this.minimumHeight = 144
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            edgeEffectFactory = BounceEdgeEffectFactory(context, this)
        }
    }
    //endregion

    //region 3 private field
    private val launchViews by lazy {
        listOf(logoView, ivSelectedPhotoTips, ivGoAboutPage)
    }

    private val editorViews by lazy {
        listOf(toolbar, ivPhoto, fcFunctionDetail, tabLayout, rvPanel, rvPhotoList)
    }

    private val launchModeAppearAnimationList by lazy {
        generateAppearAnimationList(launchViews)
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
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        }
    }

    private val dragXAnimation by lazy {
        SpringAnimation(this, SpringAnimation.TRANSLATION_X).apply {
            spring = SpringForce()
                .setFinalPosition(0f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        }
    }
    //endregion

    init {
        clipChildren = false
        clipToPadding = false
//        setBackgroundColor(ContextCompat.getColor(context, R.color.md_theme_dark_background))
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
        rvPanel.let {
            it.setPadding(it.measuredWidth / 2, 0, it.measuredWidth / 2, 0)
        }
        // measure children
        children.forEach {
            if (it != ivPhoto) {
                measureChildWithMargins(it, widthMeasureSpec, 0, heightMeasureSpec, 0)
            }
        }
        val heightUsed = toolbar.measuredHeight
            .plus(tabLayout.measuredHeightWithMargins)
            .plus(rvPanel.measuredHeightWithMargins)
            .plus(fcFunctionDetail.measuredHeightWithMargins)
            .plus(rvPhotoList.measuredHeightWithMargins)

        Log.d(TAG, "${toolbar.measuredHeight}, ${tabLayout.measuredHeightWithMargins}, ${rvPanel.measuredHeightWithMargins},  ${fcFunctionDetail.measuredHeightWithMargins},  ${rvPhotoList.measuredHeightWithMargins}")

        measureChildWithMargins(ivPhoto, widthMeasureSpec, 0, heightMeasureSpec, heightUsed)

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
            it.layoutCenterHorizontal(appendY = (measuredHeight * 0.2f).toInt())
        }
        ivSelectedPhotoTips.let {
            it.layoutCenterHorizontal(appendY = (measuredHeight * 0.6f).toInt())
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
        ivPhoto.let {
            it.layout(0, toolbar.bottom)
        }
        // bottom
        tabLayout.let {
            it.layout(0, measuredHeight - it.measuredHeightWithMargins)
        }
        rvPanel.let {
            it.layout(0, tabLayout.top - it.measuredHeightWithMargins)
        }
        fcFunctionDetail.let {
            it.layout(0, rvPanel.top - it.measuredHeightWithMargins)
        }
        rvPhotoList.let {
            it.layout(0, fcFunctionDetail.top - it.measuredHeightWithMargins)
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
                launchViews.forEach {
                    it.alpha = 0f
                    it.isVisible = false
                }
                editorViews.forEach {
                    it.alpha = 1f
                    it.translationY = 0f
                    it.isVisible = true
                }
            }
            ViewMode.LaunchMode -> {
                editorModeDisappearAnimationList.forEach { it.start() }
                launchModeAppearAnimationList.forEach { it.start() }
            }
        }
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

    fun toEditorMode(): Boolean {
        val animate = mode == ViewMode.LaunchMode
        mode = ViewMode.Editor
        return animate
    }

    fun isEdit(): Boolean = mode == ViewMode.Editor

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
