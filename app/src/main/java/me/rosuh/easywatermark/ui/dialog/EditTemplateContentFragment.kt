package me.rosuh.easywatermark.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.databinding.DialogEditTemplateBinding
import me.rosuh.easywatermark.ui.base.BaseBindBSDFragment
import java.util.Date

class EditTemplateContentFragment : BaseBindBSDFragment<DialogEditTemplateBinding>() {

    private var template: Template? = null

    private var isEdit = false

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): DialogEditTemplateBinding {
        return DialogEditTemplateBinding.inflate(layoutInflater, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        template = arguments?.getParcelable("template") as? Template
        isEdit = template != null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvTitle.text = if (isEdit) "编辑模板" else "添加模板"
        binding.etWaterText.apply {
            setText(template?.content)
            post {
                setSelection(text?.length ?: 0)
                requestFocus()
            }
        }
        binding.btnConfirm.apply {
            setOnClickListener {
                val msg = binding.etWaterText.text.toString().trim()
                if (msg.isBlank()) {
                    Toast.makeText(requireContext(), "不能为空", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (isEdit) {
                    val t = template?.copy(content = msg, lastModifiedDate = Date()) ?: kotlin.run {
                        Toast.makeText(requireContext(), R.string.tips_error, Toast.LENGTH_LONG).show()
                        dismissAllowingStateLoss()
                        return@setOnClickListener
                    }
                    shareViewModel.updateTemplate(t)
                } else {
                    shareViewModel.addTemplate(msg)
                }
                dismissAllowingStateLoss()
            }
        }
    }


    companion object {
        const val TAG = "EditTemplateContentFragment"

        fun safetyShow(manager: FragmentManager, template: Template? = null) {
            try {
                val f = (manager.findFragmentByTag(TAG) as? SaveImageBSDialogFragment)
                    ?: EditTemplateContentFragment()
                f.apply {
                    arguments = Bundle().also {
                        it.putParcelable("template", template)
                    }
                }
                if (!f.isAdded) {
                    f.show(manager, TAG)
                }
            } catch (ie: IllegalStateException) {
                ie.printStackTrace()
            }
        }
    }
}