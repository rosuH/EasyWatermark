package me.rosuh.easywatermark.ui.dialog

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.databinding.DialogSaveFileBinding
import me.rosuh.easywatermark.ui.MainActivity
import me.rosuh.easywatermark.ui.MainViewModel
import me.rosuh.easywatermark.ui.adapter.SaveImageListAdapter
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment
import me.rosuh.easywatermark.utils.ktx.preCheckStoragePermission
import android.animation.LayoutTransition




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
            btnSave.apply {
                setOnClickListener {
                    if (shareViewModel.saveResult.value?.code == MainViewModel.TYPE_JOB_FINISH) {
                        // share to other apps
                        openShare()
                    } else {
                        // saving jobs
                        requireActivity().preCheckStoragePermission {
                            shareViewModel.saveImage(
                                requireActivity().contentResolver,
                                (requireContext() as MainActivity).getImageList()
                            )
                        }
                    }
                }
            }

            btnSave.apply {
                if (isSaving && this.animation?.hasStarted() != true) {
                    this.startAnimation(alphaAnimation)
                }
            }

            btnOpenGallery.apply {
                this.isVisible = false
                setOnClickListener {
                    openGallery()
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
                    performanceTransition()
                    slideQuality.isVisible = targetFormat == Bitmap.CompressFormat.JPEG
                    tvQuality.isVisible = targetFormat == Bitmap.CompressFormat.JPEG
                }
            }

            rvResult.apply {
                adapter = SaveImageListAdapter(requireContext()).also {
                    it.submitList(imageList)
                }
                itemAnimator = null
                val spanCount =
                    if (imageList.size > 5) (imageList.size / 2).coerceAtLeast(5) else imageList.size
                layoutManager = GridLayoutManager(requireContext(), spanCount)
            }

            slideQuality.apply {
                value = shareViewModel.compressLevel.toFloat()
                addOnChangeListener { _, value, _ ->
                    shareViewModel.saveOutput(shareViewModel.outputFormat, value.toInt())
                }
            }

            shareViewModel.saveProcess.observe(viewLifecycleOwner) {
                (rvResult.adapter as? SaveImageListAdapter)?.updateJobState(it)
            }

            shareViewModel.saveResult.observe(viewLifecycleOwner) {
                setUpLoadingView(it)
            }
        }
        return root
    }

    private fun performanceTransition() {
        val transition = LayoutTransition()
        transition.setAnimateParentHierarchy(false)
        binding.root.layoutTransition = transition
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpLoadingView(shareViewModel.saveResult.value)
    }

    private fun setUpLoadingView(
        saveResult: Result<*>?
    ) {
        performanceTransition()
        when (saveResult?.code) {
            MainViewModel.TYPE_SAVING -> {
                binding.btnSave.apply {
                    isEnabled = false
                    text = "正在导出"
                }
                binding.atvFormat.isEnabled = false
                binding.slideQuality.isEnabled = false
                binding.menuFormat.isEnabled = false
                binding.btnOpenGallery.isVisible = false
                (dialog as BottomSheetDialog).behavior.isDraggable = false
            }
            MainViewModel.TYPE_SHARING -> {
                binding.btnSave.apply {
                    isEnabled = false
                    text = "正在导出"
                }
                binding.atvFormat.isEnabled = false
                binding.slideQuality.isEnabled = false
                binding.menuFormat.isEnabled = false
                binding.btnOpenGallery.isVisible = false
                (dialog as BottomSheetDialog).behavior.isDraggable = false
            }
            MainViewModel.TYPE_JOB_FINISH -> {
                binding.btnSave.apply {
                    isEnabled = true
                    text = "分享"
                }
                binding.atvFormat.isEnabled = true
                binding.slideQuality.isEnabled = true
                binding.menuFormat.isEnabled = true
                binding.btnOpenGallery.isVisible = true
                (dialog as BottomSheetDialog).behavior.isDraggable = true
            }
            else -> {
                binding.btnSave.apply {
                    isEnabled = true
                    text = "导出到相册"
                }
                binding.atvFormat.isEnabled = true
                binding.slideQuality.isEnabled = true
                binding.menuFormat.isEnabled = true
                binding.btnOpenGallery.isVisible = false
                (dialog as BottomSheetDialog).behavior.isDraggable = true
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

    private fun openGallery() {
        val list = shareViewModel.imageList.value?.first
        if (list.isNullOrEmpty()) return
        val outputUri = list.first().shareUri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(outputUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openShare() {
        val list = shareViewModel.imageList.value?.first
        if (list.isNullOrEmpty()) return
        val intent = Intent().apply {
            type = "image/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (list.size == 1) {
            val outputUri = list.first().shareUri
            intent.apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, outputUri)
            }
        } else {
            val uriList = ArrayList(list.map { it.shareUri })
            intent.apply {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
            }
        }
        startActivity(intent)
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
