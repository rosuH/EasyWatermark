package me.rosuh.easywatermark.ui.dialog

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.ArrayAdapter
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.databinding.DialogSaveFileBinding
import me.rosuh.easywatermark.ui.MainActivity
import me.rosuh.easywatermark.ui.MainViewModel
import me.rosuh.easywatermark.ui.adapter.SaveImageListAdapter
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment
import me.rosuh.easywatermark.ui.widget.ScalebleGridLayoutManager
import me.rosuh.easywatermark.utils.ktx.dp
import me.rosuh.easywatermark.utils.ktx.preCheckStoragePermission

class SaveImageBSDialogFragment : BaseBindBSDFragment<DialogSaveFileBinding>() {
    private val imageList: List<ImageInfo>
        get() = (requireContext() as MainActivity).getImageList()

    private val popArray = arrayOf("JPEG", "PNG")

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): DialogSaveFileBinding {
        val root = DialogSaveFileBinding.inflate(layoutInflater, container, false)
        val isSaving = shareViewModel.saveResult.value?.code == MainViewModel.TYPE_SAVING
        with(root) {
            ivSave.apply {
                setOnClickListener {
                    requireActivity().preCheckStoragePermission {
                        shareViewModel.saveImage(
                            requireActivity().contentResolver,
                            (requireContext() as MainActivity).getImageList()
                        )
                    }
                }
            }

            ivSave.apply {
                if (isSaving && this.animation?.hasStarted() != true) {
                    this.startAnimation(alphaAnimation)
                }
            }

            atvFormat.also {
                it.setAdapter(
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        popArray
                    )
                )
                it.setText(
                    if (shareViewModel.outputFormat == Bitmap.CompressFormat.JPEG) "JPEG" else "PNG",
                    false
                )
                it.setOnItemClickListener { _, _, index, _ ->
                    val targetFormat =
                        if (index == 0) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    shareViewModel.saveOutput(targetFormat, slideQuality.value.toInt())
                }
            }

            rvResult.apply {
                adapter = SaveImageListAdapter(requireContext()).also {
                    it.submitList(imageList)
                }
                itemAnimator = null
                val spanCount = if (imageList.size > 5) (imageList.size / 2).coerceAtLeast(5) else imageList.size
                layoutManager = GridLayoutManager(requireContext(), spanCount)
            }

            slideQuality.apply {
                value = shareViewModel.compressLevel.toFloat()
                addOnChangeListener { _, value, _ ->
                    shareViewModel.saveOutput(shareViewModel.outputFormat, value.toInt())
                }
            }

            setUpLoadingView(shareViewModel.saveResult.value, ivSave)

            shareViewModel.saveProcess.observe(viewLifecycleOwner) {
                (rvResult.adapter as? SaveImageListAdapter)?.updateJobState(it)
            }

            shareViewModel.saveResult.observe(viewLifecycleOwner) {
                setUpLoadingView(it, ivSave)
            }
        }
        return root
    }

    private fun setUpLoadingView(
        saveResult: Result<*>?,
        ivSave: View?,
    ) {
        when (saveResult?.code) {
            MainViewModel.TYPE_SAVING -> {
                ivSave?.isEnabled = false
            }
            MainViewModel.TYPE_SHARING -> {
                ivSave?.isEnabled = false
            }
            else -> {
                ivSave?.isEnabled = true
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
