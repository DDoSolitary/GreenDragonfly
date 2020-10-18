package org.ddosolitary.greendragonfly

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Filter
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

class BindLoginFragment : Fragment() {
	companion object {
		private const val STATE_SELECTED_ITEM = "selectedItem"
	}

	private val vm by lazy { ViewModelProvider(requireActivity())[BindAccountViewModel::class.java] }

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View? {
		return inflater.inflate(R.layout.fragment_bind_login, container, false)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		vm.selectedSchool?.let { outState.putInt(STATE_SELECTED_ITEM, it) }
	}

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)
		vm.selectedSchool = savedInstanceState?.getInt(STATE_SELECTED_ITEM, -1)
			?.let { if (it == -1) null else it }
		requireView().run {
			vm.schools.observe(viewLifecycleOwner) {
				findViewById<AutoCompleteTextView>(R.id.dropdown_schools).apply {
					val adapter = object : ArrayAdapter<BindAccountViewModel.ApiSchoolInfo>(
						context, android.R.layout.simple_spinner_dropdown_item, it
					) {
						override fun getFilter(): Filter = object : Filter() {
							override fun performFiltering(constraint: CharSequence?): FilterResults =
								FilterResults().apply {
									values = it
									count = it.size
								}

							override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
								notifyDataSetChanged()
							}
						}
					}
					setAdapter(adapter)
					inputType = InputType.TYPE_NULL
					setOnItemClickListener { _, _, position, _ -> vm.selectedSchool = position }
				}
			}
			findViewById<EditText>(R.id.input_student_id).apply {
				addTextChangedListener { vm.studentId = it.toString() }
			}
			findViewById<EditText>(R.id.input_password).apply {
				addTextChangedListener { vm.password = it.toString() }
			}
		}
	}
}
