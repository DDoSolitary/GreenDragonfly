package org.ddosolitary.greendragonfly

import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
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
import java.time.LocalDateTime
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
		const val RESULT_RECORD_DELETED = 2
		private const val STATE_START_DATE = "startDate"
		private const val STATE_START_TIME = "startTime"
	}

	class RecordEditorViewModel(app: Application) : AndroidViewModel(app) {
		data class UpdateResult(val resultCode: Int, val recordId: Int)

		val record = MutableLiveData<Record?>()
		val startDate = MutableLiveData<LocalDate>()
		val startTime = MutableLiveData<LocalTime>()
		var isUploaded: Boolean? = null
		val updateResult = SingleLiveEvent<UpdateResult>()
		private var recordLoaded = false

		fun loadRecord(recordId: Int) {
			if (recordLoaded) return
			recordLoaded = true
			viewModelScope.launch(Dispatchers.Main) {
				val recordEntry = withContext(Dispatchers.IO) {
					Utils.getRecordDao(getApplication<MyApplication>().applicationContext).getRecordById(recordId)
				}
				val record = withContext(Dispatchers.Default) {
					recordEntry.decryptRecord()
				}
				this@RecordEditorViewModel.record.value = record
			}
		}

		fun addRecord(record: Record) {
			viewModelScope.launch(Dispatchers.Main) {
				val recordEntry = withContext(Dispatchers.Default) {
					RecordEntry.encryptRecord(record)
				}
				val newId = withContext(Dispatchers.IO) {
					Utils.getRecordDao(getApplication<MyApplication>().applicationContext).addRecord(recordEntry)
				}
				updateResult.setValue(UpdateResult(RESULT_RECORD_ADDED, newId.toInt()))
			}
		}

		fun updateRecord(record: Record) {
			viewModelScope.launch(Dispatchers.Main) {
				val recordEntry = withContext(Dispatchers.Default) {
					RecordEntry.encryptRecord(record)
				}
				withContext(Dispatchers.IO) {
					Utils.getRecordDao(getApplication<MyApplication>().applicationContext).updateRecord(recordEntry)
				}
				updateResult.setValue(UpdateResult(RESULT_RECORD_UPDATED, record.id))
			}
		}

		fun deleteRecord(record: Record) {
			viewModelScope.launch(Dispatchers.Main) {
				withContext(Dispatchers.IO) {
					Utils.getRecordDao(getApplication<MyApplication>().applicationContext).deleteRecordById(record.id)
				}
				updateResult.setValue(UpdateResult(RESULT_RECORD_DELETED, record.id))
			}
		}
	}

	private val vm by lazy { ViewModelProvider(this)[RecordEditorViewModel::class.java] }

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val dialog = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.edit_record)
			.setView(createView(savedInstanceState))
			.setPositiveButton(R.string.ok, null)
			.setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
			.create()
		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
		}
		return dialog
	}

	private fun createView(savedState: Bundle?): View {
		val view = layoutInflater.inflate(R.layout.fragment_record_editor, null, false)
		view.findViewById<CheckBox>(R.id.checkbox_uploaded)
			.setOnCheckedChangeListener { _, isChecked -> vm.isUploaded = isChecked }
		vm.record.observe(this) {
			if (it == null) {
				dismiss()
				return@observe
			}
			val startDateTime = Utils.millisToTime(it.locations.first().timeStamp)
			vm.startDate.value = (savedState?.getSerializable(STATE_START_DATE) as LocalDate?)
				?: startDateTime.toLocalDate()
			vm.startTime.value = (savedState?.getSerializable(STATE_START_TIME) as LocalTime?)
				?: startDateTime.toLocalTime()
			view.findViewById<ImageButton>(R.id.button_edit_date).setOnClickListener { onEditDateClicked() }
			view.findViewById<ImageButton>(R.id.button_edit_time).setOnClickListener { onEditTimeClicked() }
			view.findViewById<CheckBox>(R.id.checkbox_uploaded).run {
				if (vm.isUploaded == null) {
					isChecked = it.isUploaded
				}
				isEnabled = true
			}
			view.findViewById<Button>(R.id.button_copy_record).run {
				isEnabled = true
				setOnClickListener { onCopyRecordClicked() }
			}
			view.findViewById<Button>(R.id.button_delete_record).run {
				isEnabled = true
				setOnClickListener { _ -> vm.deleteRecord(it) }
			}
			(dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).run {
				isEnabled = true
				setOnClickListener { onOkClicked() }
			}
		}
		vm.startDate.observe(this) {
			view.findViewById<TextView>(R.id.text_start_date).text =
				it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
		}
		vm.startTime.observe(this) {
			view.findViewById<TextView>(R.id.text_start_time).text =
				it.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
		}
		vm.updateResult.observe(this) {
			targetFragment?.onActivityResult(targetRequestCode, it.resultCode, Intent().apply {
				putExtra(EXTRA_RECORD_ID, it.recordId)
			})
			dismiss()
		}
		vm.loadRecord(requireArguments().getInt(ARGUMENT_RECORD_ID))
		return view
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		vm.startDate.value?.let { outState.putSerializable(STATE_START_DATE, it) }
		vm.startTime.value?.let { outState.putSerializable(STATE_START_TIME, it) }
	}

	private fun onEditDateClicked() {
		MaterialDatePicker.Builder.datePicker()
			.setTitleText(R.string.start_date)
			.setTheme(R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
			.setSelection(vm.startDate.value!!.toEpochDay() * 86400000)
			.build()
			.run {
				addOnPositiveButtonClickListener {
					vm.startDate.value = LocalDate.ofEpochDay(it / 86400000)
				}
				show(this@RecordEditorFragment.childFragmentManager, toString())
			}
	}

	private fun onEditTimeClicked() {
		val startTime = vm.startTime.value!!
		MaterialTimePicker.Builder()
			.setTitleText(R.string.start_time)
			.setTimeFormat(TimeFormat.CLOCK_24H)
			.setHour(startTime.hour)
			.setMinute(startTime.minute)
			.build()
			.run {
				addOnPositiveButtonClickListener {
					vm.startTime.value = LocalTime.of(hour, minute, startTime.second, startTime.nano)
				}
				show(this@RecordEditorFragment.childFragmentManager, toString())
			}
	}

	private fun onCopyRecordClicked() {
		val newLocations = vm.record.value!!.locations.map {
			StampedLocation(
				it.timeStamp + Random.nextLong(-500, 500),
				(it.latitude * 1000000 + Random.nextInt(-10, 10)) / 1000000,
				(it.longitude * 1000000 + Random.nextInt(-10, 10)) / 1000000,
			)
		}
		vm.addRecord(Record(newLocations, false))
	}

	private fun onOkClicked() {
		val record = vm.record.value!!
		val newDateTime = LocalDateTime.of(vm.startDate.value!!, vm.startTime.value!!)
		val newMillis = newDateTime.toInstant(ZoneId.systemDefault().rules.getOffset(newDateTime)).toEpochMilli()
		val offset = newMillis - record.locations.first().timeStamp
		record.locations = record.locations.map {
			it.copy(timeStamp = it.timeStamp + offset)
		}
		record.isUploaded = vm.isUploaded ?: record.isUploaded
		vm.updateRecord(record)
	}
}
