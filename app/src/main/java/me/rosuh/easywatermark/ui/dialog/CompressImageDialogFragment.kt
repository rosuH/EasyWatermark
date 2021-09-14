package me.rosuh.easywatermark.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.model.Result
import me.rosuh.easywatermark.ui.MainViewModel

class CompressImageDialogFragment : DialogFragment() {

    private var btnCompress: MaterialButton? = null
    private var btnCancel: MaterialButton? = null
    private var cpbCompress: ContentLoadingProgressBar? = null
    private var tvCompressTips: TextView? = null
    private val shareViewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            isCancelable = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_compress_img, container, false)
        with(root) {
            cpbCompress = findViewById(R.id.cpb_compress)
            tvCompressTips = findViewById(R.id.tv_compress_tips)
            btnCancel = findViewById<MaterialButton>(R.id.btn_cancel).apply {
                setOnClickListener {
                    shareViewModel.cancelCompressJob()
                    dismissAllowingStateLoss()
                }
            }
            btnCompress = findViewById(R.id.btn_compress)
        }
        setTupState()
        shareViewModel.compressedResult.observe(viewLifecycleOwner, Observer {
            setTupState(it)
        })

        return root
    }

    private fun setTupState(result: Result<*>? = shareViewModel.compressedResult.value) {
        when (result?.code) {
            MainViewModel.TYPE_COMPRESSING -> {
                cpbCompress?.apply {
                    isVisible = true
                    show()
                }
                tvCompressTips?.text = getString(R.string.tips_compressing)
                btnCompress?.apply {
                    isEnabled = false
                    text = context.getString(R.string.tips_compress_images)
                }
            }
            MainViewModel.TYPE_COMPRESS_OK -> {
                cpbCompress?.apply {
                    isVisible = false
                    hide()
                }
                tvCompressTips?.text = getString(R.string.tips_compress_ok)
                btnCompress?.apply {
                    text = context.getString(R.string.tips_ok)
                    isEnabled = true
                    setOnClickListener {
                        dismissAllowingStateLoss()
                    }
                }
            }
            MainViewModel.TYPE_COMPRESS_ERROR -> {
                tvCompressTips?.text =
                    getString(R.string.tips_compress_create_uri_failed, result.message)
                cpbCompress?.apply {
                    isVisible = false
                    hide()
                }
                btnCompress?.apply {
                    isEnabled = true
                    text = context.getString(R.string.tips_compress_images)
                    setOnClickListener {
                        shareViewModel.compressImg(requireActivity())
                    }
                }
            }
            else -> {
                cpbCompress?.apply {
                    isVisible = false
                    hide()
                }
                tvCompressTips?.setText(R.string.tips_need_compress_img)
                btnCompress?.apply {
                    isEnabled = true
                    text = context.getString(R.string.tips_compress_images)
                    setOnClickListener {
                        shareViewModel.compressImg(requireActivity())
                    }
                }
            }
        }
    }

    companion object {

        private const val TAG = "CompressImageDialogFragment"

        private fun newInstance(): CompressImageDialogFragment {
            return CompressImageDialogFragment()
        }

        fun safetyShow(manager: FragmentManager) {
            try {
                val f = manager.findFragmentByTag(TAG) as? CompressImageDialogFragment
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