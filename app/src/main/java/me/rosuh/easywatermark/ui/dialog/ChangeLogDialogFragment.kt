package me.rosuh.easywatermark.ui.dialog

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.toMD5
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.model.WaterMarkConfig.Companion.SP_KEY_CHANGE_LOG

/**
 * @author rosuh@qq.com
 * @date 2020/9/3
 * A dialog showing change log, which using [BuildConfig.VERSION_CODE]
 * and [R.string.dialog_change_log_content] to generate md5 To decide whether to display
 */
class ChangeLogDialogFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_change_log, container, false)
        with(root) {
            findViewById<TextView>(R.id.tv_content).apply {
                setText(R.string.dialog_change_log_content)
            }
        }
        return root
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        MyApp.instance.getSharedPreferences(WaterMarkConfig.SP_NAME, Context.MODE_PRIVATE).edit {
            val curMD5 =
                "${BuildConfig.VERSION_CODE}${this@ChangeLogDialogFragment.getString(R.string.dialog_change_log_content)}".toMD5()
            putString(SP_KEY_CHANGE_LOG, curMD5)
        }
    }

    companion object {

        private const val TAG = "ChangeLogDialogFragment"

        private fun newInstance(): ChangeLogDialogFragment {
            return ChangeLogDialogFragment()
        }

        private fun checkHasUpgraded(): Boolean {
            with(
                MyApp.instance.getSharedPreferences(
                    WaterMarkConfig.SP_NAME,
                    Context.MODE_PRIVATE
                )
            ) {
                val savedMD5 = this.getString(SP_KEY_CHANGE_LOG, "")
                val curMD5 =
                    "${BuildConfig.VERSION_CODE}${MyApp.instance.getString(R.string.dialog_change_log_content)}".toMD5()
                return savedMD5 != curMD5
            }
        }

        fun safetyHide(manager: FragmentManager) {
            kotlin.runCatching {
                (manager.findFragmentByTag(TAG) as? ChangeLogDialogFragment)?.dismissAllowingStateLoss()
            }
        }

        fun safetyShow(manager: FragmentManager) {
            kotlin.runCatching {
                if (!checkHasUpgraded()) {
                    return
                }
                val f = manager.findFragmentByTag(TAG) as? ChangeLogDialogFragment
                when {
                    f == null -> {
                        newInstance().show(manager, TAG)
                    }
                    !f.isAdded -> {
                        f.show(manager, TAG)
                    }
                }
            }
        }
    }
}