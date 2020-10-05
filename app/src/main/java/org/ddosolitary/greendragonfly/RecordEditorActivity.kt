package org.ddosolitary.greendragonfly

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecordEditorActivity : AppCompatActivity() {
	companion object {
		const val EXTRA_RECORD_ID = "org.ddosolitary.greendragonfly.extra.RECORD_ID"
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

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_record_editor)
		val recordId = intent.getIntExtra(EXTRA_RECORD_ID, -1)
		vm.viewModelScope.launch(Dispatchers.Main) {
			val recordEntry = withContext(Dispatchers.IO) {
				Utils.getRecordDao(this@RecordEditorActivity).getRecordById(recordId)
			}
			val record = withContext(Dispatchers.Default) {
				recordEntry.decryptRecord()
			}
			if (record == null) {
				setResult(Activity.RESULT_CANCELED)
				finish()
				return@launch
			}
			vm.record = record
			findViewById<CheckBox>(R.id.checkbox_uploaded).isChecked = record.isUploaded
			val startDateTime = Utils.millisToTime(record.locations.first().timeStamp)
			vm.startEpochDay = startDateTime.toLocalDate().toEpochDay()
			vm.startHour = startDateTime.hour
			vm.startMinute = startDateTime.minute
			vm.startSecond = startDateTime.second
			vm.startNano = startDateTime.nano
			updateDateView()
			updateTimeView()
		}
	}

	fun onDateEditClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		MaterialDatePicker.Builder.datePicker()
			.setTitleText(R.string.start_date)
			.setTheme(R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
			.setSelection(vm.startEpochDay * 86400000)
			.build()
			.run {
				addOnPositiveButtonClickListener {
					vm.startEpochDay = it / 86400000
					updateDateView()
				}
				show(supportFragmentManager, toString())
			}
	}

	fun onTimeEditClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		val dialog = MaterialTimePicker.Builder()
			.setTitleText(R.string.start_time)
			.setTimeFormat(TimeFormat.CLOCK_24H)
			.setHour(vm.startHour)
			.setMinute(vm.startMinute)
			.build()
			.run {
				addOnPositiveButtonClickListener {
					vm.startHour = hour
					vm.startMinute = minute
					updateTimeView()
				}
				show(supportFragmentManager, toString())
			}
	}

	fun onUploadedClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		vm.record.isUploaded = (view as CheckBox).isChecked
	}

	fun onCancelClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		setResult(Activity.RESULT_CANCELED)
		finish()
	}

	fun onOkClicked(@Suppress("UNUSED_PARAMETER") view: View) {
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
				Utils.getRecordDao(this@RecordEditorActivity).updateRecord(recordEntry)
			}
			setResult(Activity.RESULT_OK, Intent().apply {
				putExtra(EXTRA_RECORD_ID, vm.record.id)
			})
			finish()
		}
	}

	private fun updateDateView() {
		findViewById<TextView>(R.id.text_start_date).text =
			LocalDate.ofEpochDay(vm.startEpochDay)
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
	}

	private fun updateTimeView() {
		findViewById<TextView>(R.id.text_start_time).text =
			LocalTime.of(vm.startHour, vm.startMinute, vm.startSecond, vm.startNano)
				.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
	}
}
