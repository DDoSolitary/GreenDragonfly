package org.ddosolitary.greendragonfly

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

class BindLoginFragment : Fragment() {
	companion object {
		private const val STATE_SELECTED_ITEM = "SELECTED_ITEM"
	}

	private val vm by lazy { ViewModelProvider(requireActivity())[BindAccountViewModel::class.java] }

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		return inflater.inflate(R.layout.fragment_bind_login, container, false)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		vm.selectedSchool?.let { outState.putInt(STATE_SELECTED_ITEM, it) }
	}

	override fun onViewStateRestored(savedInstanceState: Bundle?) {
		super.onViewStateRestored(savedInstanceState)
		savedInstanceState?.getInt(STATE_SELECTED_ITEM, -1)?.let {
			if (it != -1) vm.selectedSchool = it
		}
		view!!.run {
			vm.schools.run {
				value?.let { updateSchoolList(it) }
				observe(viewLifecycleOwner, { updateSchoolList(it) })
			}
			findViewById<AutoCompleteTextView>(R.id.dropdown_schools).apply {
				setOnItemClickListener { _, _, position, _ -> vm.selectedSchool = position }
			}
			findViewById<EditText>(R.id.input_student_id).apply {
				vm.studentId = text.toString()
				addTextChangedListener { vm.studentId = it.toString() }
			}
			findViewById<EditText>(R.id.input_password).apply {
				vm.password = text.toString()
				addTextChangedListener { vm.password = it.toString() }
			}
		}
	}

	private fun updateSchoolList(schools: List<BindAccountViewModel.ApiSchoolInfo>) {
		view!!.findViewById<AutoCompleteTextView>(R.id.dropdown_schools).apply {
			setAdapter(
				ArrayAdapter(
					context,
					android.R.layout.simple_spinner_dropdown_item,
					schools.toTypedArray()
				)
			)
			inputType = InputType.TYPE_NULL
		}
	}
}
