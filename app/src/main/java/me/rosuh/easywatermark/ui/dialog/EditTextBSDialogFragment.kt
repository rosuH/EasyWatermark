package me.rosuh.easywatermark.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.dp
import me.rosuh.easywatermark.ui.MainViewModel

class EditTextBSDialogFragment : BottomSheetDialogFragment() {

    private var et: TextInputEditText? = null
    private val shareViewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            (this as BottomSheetDialog).behavior.apply {
                peekHeight = 165.dp.toInt()
                isDraggable = false
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_edit_text, container, false)
        with(root) {
            et = findViewById<TextInputEditText>(R.id.et_water_text).apply {
                setText(shareViewModel.config.value?.text.toString())
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        shareViewModel.updateText(s?.toString() ?: "")
                    }

                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {
                    }

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                    }
                })
            }
            findViewById<Button>(R.id.btn_confirm).apply {
                setOnClickListener {
                    shareViewModel.updateText(et?.text?.toString() ?: "")
                    dismiss()
                }
            }
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        et?.requestFocus()
    }

    companion object {

        const val TAG = "EditTextBSDialogFragment"

        private fun newInstance(): EditTextBSDialogFragment {
            return EditTextBSDialogFragment()
        }

        fun safetyShow(manager: FragmentManager) {
            try {
                val f = manager.findFragmentByTag(TAG) as? EditTextBSDialogFragment
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