package me.rosuh.easywatermark.ui.save

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.MainViewModel

class SaveImageBSDialogFragment : BottomSheetDialogFragment() {

    private val shareViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_save_file, null)
        with(root) {
            findViewById<View>(R.id.ll_save).apply {
                setOnClickListener {
                    shareViewModel.saveImage(requireActivity())
                }
            }

            findViewById<View>(R.id.ll_share).apply {
                setOnClickListener {
                    shareViewModel.shareImage(requireActivity())
                }
            }
        }
        return root
    }

    companion object {

        const val TAG = "SaveImageBSDialogFragment"

        private fun newInstance(): SaveImageBSDialogFragment {
            return SaveImageBSDialogFragment()
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