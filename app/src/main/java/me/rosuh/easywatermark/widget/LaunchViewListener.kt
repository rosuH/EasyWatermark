package me.rosuh.easywatermark.widget

interface LaunchViewListener {
    fun onModeChange(oldMode: LaunchView.ViewMode, newMode: LaunchView.ViewMode)
}

private typealias OnModeChange = (oldMode: LaunchView.ViewMode, newMode: LaunchView.ViewMode) -> Unit

class LaunchViewListenerBuilder : LaunchViewListener {

    private var onModeChange: OnModeChange? = null

    override fun onModeChange(oldMode: LaunchView.ViewMode, newMode: LaunchView.ViewMode) {
        this.onModeChange?.invoke(oldMode, newMode)
    }

    fun onModeChange(onModeChange: OnModeChange) {
        this.onModeChange = onModeChange
    }
}