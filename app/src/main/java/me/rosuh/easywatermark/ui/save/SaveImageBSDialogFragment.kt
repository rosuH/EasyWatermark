package me.rosuh.easywatermark.ui.save

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
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

        var ivSave: View?
        var ivShare: View?
        val curState = shareViewModel.saveState.value ?: MainViewModel.State.Ready
        with(root) {
            findViewById<View>(R.id.ll_save).apply {
                setOnClickListener {
                    shareViewModel.saveImage(requireActivity())
                }
            }

            ivSave = findViewById<View>(R.id.iv_save).apply {
                if (curState == MainViewModel.State.Saving && this.animation?.hasStarted() != true) {
                    this.startAnimation(alphaAnimation)
                }
            }

            ivShare = findViewById<View>(R.id.iv_share).apply {
                if (curState == MainViewModel.State.Sharing && this.animation?.hasStarted() != true) {
                    this.startAnimation(alphaAnimation)
                }
            }

            findViewById<View>(R.id.ll_share).apply {
                setOnClickListener {
                    shareViewModel.shareImage(requireActivity())
                }
            }
        }

        shareViewModel.saveState.observe(viewLifecycleOwner, Observer {
            when (it) {
                MainViewModel.State.Ready, MainViewModel.State.Error -> {
                    ivSave?.clearAnimation()
                    ivShare?.clearAnimation()
                }
                MainViewModel.State.Saving -> {
                    if (ivSave?.animation?.hasStarted() != true) {
                        ivSave?.startAnimation(alphaAnimation)
                    }
                }
                MainViewModel.State.Sharing -> {
                    if (ivShare?.animation?.hasStarted() != true) {
                        ivShare?.startAnimation(alphaAnimation)
                    }
                }
                MainViewModel.State.SaveOk -> {
                    ivSave?.clearAnimation()
                }
                MainViewModel.State.ShareOk -> {
                    ivShare?.clearAnimation()
                }
            }
        })

        return root
    }

    private val alphaAnimation by lazy {
        AlphaAnimation(1f, 0.30f).apply {
            repeatCount = AlphaAnimation.INFINITE
            repeatMode = AlphaAnimation.REVERSE
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {

                }

                override fun onAnimationEnd(animation: Animation?) {

                }

                override fun onAnimationStart(animation: Animation?) {

                }

            })
            duration = 550
        }
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