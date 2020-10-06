package org.ddosolitary.greendragonfly

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class RecordEditorFragment : DialogFragment() {
	companion object {
		const val ARGUMENT_RECORD_ID = "org.ddosolitary.greendragonfly.argument.RECORD_ID"
		const val EXTRA_RECORD_ID = "org.ddosolitary.greendragonfly.extra.RECORD_ID"
		const val RESULT_RECORD_ADDED = 0
		const val RESULT_RECORD_UPDATED = 1
	}

	class RecordEditorViewModel : ViewModel() {
		lateinit var record: Record
		var startEpochDay: Long = 0
		var startHour: Int = 0
		var startMinute: Int = 0
		var startSecond: Int = 0
		var startNano: Int = 0
	}

	private val vm by lazy { ViewModelProvider(this)[RecordEditorViewModel::class.java] }

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val dialog = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.edit_record)
			.setView(createView())
			.setPositiveButton(R.string.ok, null)
			.setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
			.create()
		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener { onOkClicked() }
		}
		return dialog
	}

	private fun createView(): View {
		val view = layoutInflater.inflate(R.layout.fragment_record_editor, null, false).apply {
			findViewById<ImageButton>(R.id.button_edit_date).setOnClickListener { onEditDateClicked(this) }
			findViewById<ImageButton>(R.id.button_edit_time).setOnClickListener { onEditTimeClicked(this) }
			findViewById<CheckBox>(R.id.checkbox_uploaded).setOnCheckedChangeListener { _, isChecked ->
				vm.record.isUploaded = isChecked
			}
			findViewById<Button>(R.id.button_copy_record).setOnClickListener { onCopyRecordClicked() }
		}
		val recordId = requireArguments().getInt(ARGUMENT_RECORD_ID)
		vm.viewModelScope.launch(Dispatchers.Main) {
			val recordEntry = withContext(Dispatchers.IO) {
				Utils.getRecordDao(requireContext()).getRecordById(recordId)
			}
			val record = withContext(Dispatchers.Default) {
				recordEntry.decryptRecord()
			}
			if (record == null) {
				dismiss()
				return@launch
			}
			vm.record = record
			view.findViewById<CheckBox>(R.id.checkbox_uploaded).isChecked = record.isUploaded
			val startDateTime = Utils.millisToTime(record.locations.first().timeStamp)
			vm.startEpochDay = startDateTime.toLocalDate().toEpochDay()
			vm.startHour = startDateTime.hour
			vm.startMinute = startDateTime.minute
			vm.startSecond = startDateTime.second
			vm.startNano = startDateTime.nano
			updateDateView(view)
			updateTimeView(view)
		}
		return view
	}

	private fun onEditDateClicked(rootView: View) {
		MaterialDatePicker.Builder.datePicker()
			.setTitleText(R.string.start_date)
			.setTheme(R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
			.setSelection(vm.startEpochDay * 86400000)
			.build()
			.run {
				addOnPositiveButtonClickListener {
					vm.startEpochDay = it / 86400000
					updateDateView(rootView)
				}
				show(this@RecordEditorFragment.childFragmentManager, toString())
			}
	}

	private fun onEditTimeClicked(rootView: View) {
		MaterialTimePicker.Builder()
			.setTitleText(R.string.start_time)
			.setTimeFormat(TimeFormat.CLOCK_24H)
			.setHour(vm.startHour)
			.setMinute(vm.startMinute)
			.build()
			.run {
				addOnPositiveButtonClickListener {
					vm.startHour = hour
					vm.startMinute = minute
					updateTimeView(rootView)
				}
				show(this@RecordEditorFragment.childFragmentManager, toString())
			}
	}

	private fun onCopyRecordClicked() {
		val newLocations = vm.record.locations.map {
			StampedLocation(
				it.timeStamp + Random.nextLong(-500, 500),
				(it.latitude * 1000000 + Random.nextInt(-10, 10)) / 1000000,
				(it.longitude * 1000000 + Random.nextInt(-10, 10)) / 1000000,
			)
		}
		val newRecord = Record(newLocations, false)
		vm.viewModelScope.launch(Dispatchers.Main) {
			val recordEntry = withContext(Dispatchers.Default) {
				RecordEntry.encryptRecord(newRecord)
			}
			withContext(Dispatchers.IO) {
				Utils.getRecordDao(requireContext()).addRecord(recordEntry)
			}
			targetFragment?.onActivityResult(targetRequestCode, RESULT_RECORD_ADDED, null)
			dismiss()
		}
	}

	private fun onOkClicked() {
		val newDateTime = LocalDate.ofEpochDay(vm.startEpochDay)
				.atTime(vm.startHour, vm.startMinute, vm.startSecond, vm.startNano)
		val newMillis = newDateTime.toInstant(ZoneId.systemDefault().rules.getOffset(newDateTime)).toEpochMilli()
		val offset = newMillis - vm.record.locations.first().timeStamp
		vm.record.locations = vm.record.locations.map {
			it.copy(timeStamp = it.timeStamp + offset)
		}
		vm.viewModelScope.launch(Dispatchers.Main) {
			val recordEntry = withContext(Dispatchers.Default) {
				RecordEntry.encryptRecord(vm.record)
			}
			withContext(Dispatchers.IO) {
				Utils.getRecordDao(requireContext()).updateRecord(recordEntry)
			}
			targetFragment?.onActivityResult(targetRequestCode, RESULT_RECORD_UPDATED, Intent().apply {
				putExtra(EXTRA_RECORD_ID, vm.record.id)
			})
			dismiss()
		}
	}

	private fun updateDateView(rootView: View) {
		rootView.findViewById<TextView>(R.id.text_start_date).text =
			LocalDate.ofEpochDay(vm.startEpochDay)
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
	}

	private fun updateTimeView(rootView: View) {
		rootView.findViewById<TextView>(R.id.text_start_time).text =
			LocalTime.of(vm.startHour, vm.startMinute, vm.startSecond, vm.startNano)
				.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
	}
}
