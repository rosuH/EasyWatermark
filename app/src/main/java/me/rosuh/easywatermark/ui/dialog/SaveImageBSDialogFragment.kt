package me.rosuh.easywatermark.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import androidx.core.view.isInvisible
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.model.Result
import me.rosuh.easywatermark.ui.MainViewModel
import me.rosuh.easywatermark.utils.preCheckStoragePermission

class SaveImageBSDialogFragment : BottomSheetDialogFragment() {

    private val shareViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_save_file, container, false)

        var ivSave: View?
        var ivShare: View?
        var cpbSave: ContentLoadingProgressBar?
        var cpbShare: ContentLoadingProgressBar?
        val isSaving = shareViewModel.saveResult.value?.code == MainViewModel.TYPE_SAVING
        val isSharing = shareViewModel.saveResult.value?.code == MainViewModel.TYPE_SHARING
        with(root) {
            findViewById<View>(R.id.ll_save).apply {
                setOnClickListener {
                    requireActivity().preCheckStoragePermission {
                        shareViewModel.saveImage(requireActivity().contentResolver)
                    }
                }
            }

            ivSave = findViewById<View>(R.id.iv_save).apply {
                if (isSaving && this.animation?.hasStarted() != true) {
                    this.startAnimation(alphaAnimation)
                }
            }

            ivShare = findViewById<View>(R.id.iv_share).apply {
                if (isSharing && this.animation?.hasStarted() != true) {
                    this.startAnimation(alphaAnimation)
                }
            }

            findViewById<View>(R.id.ll_share).apply {
                setOnClickListener {
                    requireActivity().preCheckStoragePermission {
                        shareViewModel.shareImage(requireActivity().contentResolver)
                    }
                }
            }

            cpbSave = findViewById(R.id.cpb_save)

            cpbShare = findViewById(R.id.cpb_share)
        }

        setUpLoadingView(shareViewModel.saveResult.value, cpbSave, cpbShare, ivSave, ivShare)

        shareViewModel.saveResult.observe(
            viewLifecycleOwner,
            Observer {
                setUpLoadingView(it, cpbSave, cpbShare, ivSave, ivShare)
            }
        )

        return root
    }

    private fun setUpLoadingView(
        saveResult: Result<*>?,
        cpbSave: ContentLoadingProgressBar?,
        cpbShare: ContentLoadingProgressBar?,
        ivSave: View?,
        ivShare: View?
    ) {
        when (saveResult?.code) {
            MainViewModel.TYPE_SAVING -> {
                cpbSave?.show()
                cpbShare?.hide()
                ivSave?.isInvisible = true
                ivShare?.isInvisible = false
            }
            MainViewModel.TYPE_SHARING -> {
                cpbSave?.hide()
                cpbShare?.show()
                ivSave?.isInvisible = false
                ivShare?.isInvisible = true
            }
            else -> {
                cpbSave?.hide()
                cpbShare?.hide()
                ivSave?.isInvisible = false
                ivShare?.isInvisible = false
            }
        }
    }

    private val alphaAnimation by lazy {
        AlphaAnimation(1f, 0.30f).apply {
            repeatCount = AlphaAnimation.INFINITE
            repeatMode = AlphaAnimation.REVERSE
            duration = 350
        }
    }

    companion object {

        private const val TAG = "SaveImageBSDialogFragment"

        private fun newInstance(): SaveImageBSDialogFragment {
            return SaveImageBSDialogFragment()
        }

        fun safetyHide(manager: FragmentManager) {
            kotlin.runCatching {
                (manager.findFragmentByTag(TAG) as? SaveImageBSDialogFragment)?.dismissAllowingStateLoss()
            }
        }

        fun safetyShow(manager: FragmentManager) {
            try {
                val f = manager.findFragmentByTag(TAG) as? SaveImageBSDialogFragment
                when {
                    f == null -> {
                        newInstance().show(manager, TAG)
                    }
                    !f.isAdded -> {
                        f.show(manager, TAG)
                    }
                }
            } catch (ie: IllegalStateException) {
                ie.printStackTrace()
            }
        }
    }
}
