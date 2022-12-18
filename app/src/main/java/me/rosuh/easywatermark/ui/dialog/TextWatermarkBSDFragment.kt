package me.rosuh.easywatermark.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.transition.ChangeBounds
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.databinding.DialogEditTextContainerBinding
import me.rosuh.easywatermark.ui.UiState
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment
import me.rosuh.easywatermark.utils.ktx.getScreenHeight

class TextWatermarkBSDFragment : BaseBindBSDFragment<DialogEditTextContainerBinding>() {

    private var savedTemplateListHeight: Int = 0
    private var savedEditHeight: Int = 0
    private var bottomSheet: View? = null
    private var et: TextInputEditText? = null

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): DialogEditTextContainerBinding {
        return DialogEditTextContainerBinding.inflate(layoutInflater, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            shareViewModel.uiStateFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    when (it) {
                        UiState.None -> {}
                        is UiState.UseTemplate -> {
                            if (childFragmentManager.fragments.lastOrNull() is TextContentTemplateListFragment) {
                                collapseToEdit()
                            } else {
                                dialog?.onBackPressed()
                            }
                        }
                        UiState.GoEdit -> {
                            if (childFragmentManager.fragments.lastOrNull() is TextContentTemplateListFragment) {
                                collapseToEdit()
                            } else {
                                dialog?.onBackPressed()
                            }
                        }
                        UiState.GoTemplate -> {
                            expandToTemplateList()
                        }
                        else -> {}
                    }
                }
        }
    }

    private fun expandToTemplateList() {
        val sheetContainer = binding.fragmentContainerView
        val transition = TransitionSet().apply {
            addTransition(ChangeBounds())
            addTransition(MaterialFadeThrough())
            addListener(object : Transition.TransitionListener {
                override fun onTransitionStart(transition: Transition) {
                    binding.fragmentContainerView.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .start()
                    childFragmentManager
                        .beginTransaction()
                        .replace(
                            R.id.fragment_container_view,
                            TextContentTemplateListFragment()
                        )
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }

                override fun onTransitionEnd(transition: Transition) {
                    binding.fragmentContainerView.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }

                override fun onTransitionCancel(transition: Transition) {
                }

                override fun onTransitionPause(transition: Transition) {
                }

                override fun onTransitionResume(transition: Transition) {
                }

            })
        }
        savedEditHeight = sheetContainer.height
        TransitionManager.beginDelayedTransition(sheetContainer.parent as ViewGroup, transition)
        val height = (activity?.getScreenHeight() ?: ViewGroup.LayoutParams.MATCH_PARENT)
        sheetContainer.layoutParams.height = height
        (dialog as? BottomSheetDialog)?.behavior?.also {
            it.peekHeight = height
            it.isDraggable = false
        }
        sheetContainer.requestLayout()
    }

    private fun collapseToEdit() {
        val sheetContainer = binding.fragmentContainerView
        val transition = TransitionSet().apply {
            addTransition(ChangeBounds())
            addTransition(MaterialFadeThrough())
            duration = 400
            addListener(object : Transition.TransitionListener {
                override fun onTransitionStart(transition: Transition) {
                    childFragmentManager.popBackStack()
                }

                override fun onTransitionEnd(transition: Transition) {
                }

                override fun onTransitionCancel(transition: Transition) {
                }

                override fun onTransitionPause(transition: Transition) {
                }

                override fun onTransitionResume(transition: Transition) {
                }

            })
        }
        savedTemplateListHeight = sheetContainer.height
        TransitionManager.beginDelayedTransition(sheetContainer.parent as ViewGroup, transition)
        val height =
            if (savedEditHeight == 0) ViewGroup.LayoutParams.WRAP_CONTENT else savedEditHeight
        sheetContainer.layoutParams.height = height
        (dialog as? BottomSheetDialog)?.behavior?.also { behavior ->
            behavior.peekHeight = height
            behavior.isDraggable = true
        }
        sheetContainer.requestLayout()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return object : BottomSheetDialog(requireContext()) {
            override fun onBackPressed() {
                if (shareViewModel.uiStateFlow.value is UiState.GoEdit) {
                    super.onBackPressed()
                } else {
                    shareViewModel.goTemplateEdit()
                }
            }
        }.apply {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            window?.clearFlags(FLAG_DIM_BEHIND)
            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    this@TextWatermarkBSDFragment.bottomSheet = bottomSheet
                }

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                }
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_enter_in_slide,
                R.anim.fragment_exit_slide,
                R.anim.fragment_readd_in_slide,
                R.anim.fragment_close_out_slide
            )
            .add(
                R.id.fragment_container_view,
                EditTextContentFragment()
            )
            .addToBackStack(EditTextContentFragment.TAG)
            .commit()
        et?.requestFocus()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        shareViewModel.resetEditDialog()
    }

    companion object {

        const val TAG = "EditTextBSDialogFragment"

        private fun newInstance(): TextWatermarkBSDFragment {
            return TextWatermarkBSDFragment()
        }

        fun safetyShow(manager: FragmentManager) {
            try {
                val f = manager.findFragmentByTag(TAG) as? TextWatermarkBSDFragment
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
