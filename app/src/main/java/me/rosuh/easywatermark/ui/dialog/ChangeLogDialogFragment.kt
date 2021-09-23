package me.rosuh.easywatermark.ui.dialog

import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.utils.ktx.openLink
import javax.inject.Inject

/**
 * @author hi@rosuh.me
 * @date 2020/9/3
 * A dialog showing change log, which using [BuildConfig.VERSION_CODE]
 * and [R.string.dialog_change_log_content] to generate md5 To decide whether to display
 */
@AndroidEntryPoint
class ChangeLogDialogFragment : BottomSheetDialogFragment() {

    @Inject
    lateinit var repo: UserConfigRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_change_log, container, false)
        with(root) {
            findViewById<TextView>(R.id.tv_content).apply {
                movementMethod = LinkMovementMethod()
                val text: String = requireActivity().getString(R.string.dialog_change_log_content)
                val styledText: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
                } else {
                    Html.fromHtml(text)
                }
                setText(styledText)
            }
            findViewById<View>(R.id.tv_go_github).setOnClickListener {
                requireActivity().openLink("https://github.com/rosuH/EasyWatermark/releases/")
            }
        }
        return root
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        lifecycleScope.launch {
            repo.saveVersionCode("${BuildConfig.VERSION_CODE}")
        }
    }

    companion object {

        private const val TAG = "ChangeLogDialogFragment"

        private fun newInstance(): ChangeLogDialogFragment {
            return ChangeLogDialogFragment()
        }

        fun safetyShow(manager: FragmentManager, force: Boolean = false) {
            kotlin.runCatching {
                if (!force) {
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
