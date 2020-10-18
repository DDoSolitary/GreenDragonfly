package org.ddosolitary.greendragonfly

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baidu.mapapi.model.LatLng
import com.bugsnag.android.Bugsnag
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpPost
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

class RecordsFragment : Fragment() {
	companion object {
		private const val REQUEST_EDIT_RECORD = 0
		private const val LOG_TAG = "RecordsFragment"
	}

	private enum class Status { Invalid, Pending, Conflict, Uploaded }
	private data class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

	private inner class RecyclerAdapter(val records: MutableList<Record>) :
		RecyclerView.Adapter<ViewHolder>() {

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			return ViewHolder(
				LayoutInflater.from(parent.context).inflate(
					R.layout.record_item, parent, false
				)
			)
		}

		override fun getItemCount(): Int = records.size

		@SuppressLint("ClickableViewAccessibility")
		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val record = records[position]
			val startTime = Utils.millisToTime(record.locations.first().timeStamp)
			val speed = StampedLocation.getAverageSpeed(record.locations)
			val distance = StampedLocation.getDistance(record.locations)
			val plan = UserInfo.getUser(holder.view.context)!!.plan!!
			val speedInRange = speed in plan.minSpeed..plan.maxSpeed
			val distanceInRange = distance >= plan.minDistance
			val status = if (record.isUploaded) {
				Status.Uploaded
			} else if (!speedInRange || !distanceInRange) {
				Status.Invalid
			} else {
				val cnt = records.count {
					val targetDate = Utils.millisToTime(it.locations.first().timeStamp).toLocalDate()
					return@count it.isUploaded && targetDate.isEqual(startTime.toLocalDate())
				}
				if (cnt >= plan.maxTimesPerDay) Status.Conflict else Status.Pending
			}
			val gestureDetector = GestureDetectorCompat(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
				override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
					if (vm.isUploading.value != true) {
						requireContext().startActivity(Intent(context, ShowRecordActivity::class.java).apply {
							putExtra(ShowRecordActivity.EXTRA_RECORD_ID, record.id)
						})
					}
					return true
				}

				override fun onLongPress(e: MotionEvent?) {
					val debugPref = PreferenceManager.getDefaultSharedPreferences(requireContext())
					val allowChange = debugPref.getBoolean(getString(R.string.pref_key_allow_record_editing), false)
					if (vm.isUploading.value != true && allowChange) {
						RecordEditorFragment().run {
							setTargetFragment(this@RecordsFragment, REQUEST_EDIT_RECORD)
							arguments = Bundle().apply { putInt(RecordEditorFragment.ARGUMENT_RECORD_ID, record.id) }
							show(this@RecordsFragment.parentFragmentManager, toString())
						}
					}
				}
			})
			holder.view.apply {
				setOnTouchListener { _, event ->
					gestureDetector.onTouchEvent(event)
				}
				findViewById<TextView>(R.id.text_start_time).text =
					startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
				findViewById<TextView>(R.id.text_duration).text =
					Utils.formatSeconds(StampedLocation.getDuration(record.locations))
				findViewById<TextView>(R.id.text_speed).apply {
					text = context.getString(R.string.speed_template, speed)
					setTextViewColor(this, !speedInRange)
				}
				findViewById<TextView>(R.id.text_distance).apply {
					text = context.getString(R.string.distance_template, distance)
					setTextViewColor(this, !distanceInRange)
				}
				findViewById<TextView>(R.id.text_status).apply {
					setText(
						when (status) {
							Status.Invalid -> R.string.status_invalid
							Status.Pending -> R.string.status_pending
							Status.Conflict -> R.string.status_conflict
							Status.Uploaded -> R.string.status_uploaded
						}
					)
					setTextViewColor(this, status == Status.Invalid || status == Status.Conflict)
				}
				findViewById<Button>(R.id.button_record_acton).apply {
					when (status) {
						Status.Conflict, Status.Invalid -> {
							setText(R.string.delete)
							setOnClickListener { vm.deleteRecord(record.id) }
							isEnabled = true
						}
						Status.Pending -> {
							setText(R.string.upload)
							setOnClickListener {
								vm.viewModelScope.launch { vm.uploadRecord(record) }
							}
							isEnabled = true
						}
						Status.Uploaded -> {
							setText(R.string.upload)
							isEnabled = false
						}
					}
					if (vm.isUploading.value == true) isEnabled = false
				}
			}
		}

		private fun setTextViewColor(view: TextView, isErr: Boolean) {
			view.setTextColor(
				if (isErr) {
					view.context.getColor(R.color.textError)
				} else {
					Utils.resolveColorAttr(view.context, android.R.attr.textColorPrimary)
				}
			)
		}
	}

	class RecordsViewModel(app: Application) : AndroidViewModel(app) {
		val records = MutableLiveData<MutableList<Record>>()
		val addedRecord = SingleLiveEvent<Record>()
		val updatedRecord = SingleLiveEvent<Record>()
		val deletedRecordId = SingleLiveEvent<Int>()
		val uploadResult = SingleLiveEvent<String>()
		val uploadException = SingleLiveEvent<Exception>()
		val isUploading = MutableLiveData<Boolean>()

		init { loadRecordList() }

		private fun loadRecordList() {
			viewModelScope.launch(Dispatchers.Main) {
				val recordEntries = withContext(Dispatchers.IO) {
					Utils.getRecordDao(getApplication<MyApplication>().applicationContext).getRecords()
				}
				// TODO: Warn about decryption failure.
				records.value = withContext(Dispatchers.Default) {
					recordEntries.mapNotNull { it.decryptRecord() }.toMutableList()
				}
			}
		}

		private suspend fun loadRecord(id: Int): Record? = withContext(Dispatchers.IO) {
			val recordEntry = Utils.getRecordDao(getApplication<MyApplication>().applicationContext)
				.getRecordById(id)
			withContext(Dispatchers.Default) {
				recordEntry.decryptRecord()
			}
		}

		fun loadAddedRecord(id: Int) {
			viewModelScope.launch(Dispatchers.Main) {
				addedRecord.setValue(loadRecord(id) ?: return@launch)
			}
		}

		fun loadUpdatedRecord(id: Int) {
			viewModelScope.launch(Dispatchers.Main) {
				updatedRecord.setValue(loadRecord(id) ?: return@launch)
			}
		}

		fun deleteRecord(id: Int) {
			viewModelScope.launch(Dispatchers.Main) {
				withContext(Dispatchers.IO) {
					Utils.getRecordDao(getApplication<MyApplication>().applicationContext)
						.deleteRecordById(id)
				}
				deletedRecordId.setValue(id)
			}
		}

		fun uploadRecord(record: Record) {
			viewModelScope.launch(Dispatchers.Main) {
				isUploading.value = true
				val context = getApplication<MyApplication>().applicationContext
				try {
					val user = UserInfo.getUser(context)!!
					val req = context.getString(
						R.string.api_upload_template,
						record.locations.first().timeStamp / 1000,
						record.locations.last().timeStamp / 1000,
						user.token,
						user.schoolId,
						StampedLocation.getDistance(record.locations),
						StampedLocation.getAverageSpeed(record.locations),
						user.studentId,
						user.plan!!.attType,
						user.plan.eventId,
						record.locations.joinToString("") { LatLng(it.latitude, it.longitude).toString() + ';' },
						StampedLocation.getDuration(record.locations).roundToLong()
					)
					val res = Json.parseToJsonElement(
						context.getString(R.string.api_path_upload, user.apiUrl)
							.httpPost()
							.body(Utils.compressString(req))
							.headerForApi()
							.awaitString()
					)
					check(Utils.checkApiResponse(res))
					val resMsg = Json.parseToJsonElement(res.jsonObject["m"]!!.jsonPrimitive.content)
						.jsonObject["srvresp"]!!.jsonPrimitive.content
					uploadResult.setValue(resMsg)
					record.isUploaded = true
					val recordEntry = withContext(Dispatchers.Default) {
						RecordEntry.encryptRecord(record)
					}
					withContext(Dispatchers.IO) {
						Utils.getRecordDao(context).updateRecord(recordEntry)
					}
				} catch (e: Exception) {
					Log.e(LOG_TAG, Log.getStackTraceString(e))
					Bugsnag.notify(e)
					uploadException.setValue(e)
				} finally {
					isUploading.value = false
				}
			}
		}
	}

	private val vm by lazy { ViewModelProvider(this)[RecordsViewModel::class.java] }

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View? {
		return inflater.inflate(R.layout.fragment_records, container, false)
	}

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)
		vm.records.observe(viewLifecycleOwner) { records ->
			val adapter = RecyclerAdapter(records)
			requireView().findViewById<RecyclerView>(R.id.recycler_records).apply {
				layoutManager = LinearLayoutManager(context)
				this.adapter = adapter
				addItemDecoration(
					DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
				)
			}
			vm.addedRecord.observe(viewLifecycleOwner) { record ->
				if (records.none { it.id == record.id }) {
					records.add(record)
					adapter.notifyItemInserted(records.size - 1)
					adapter.notifyItemRangeChanged(0, records.size - 1)
				}
			}
			vm.updatedRecord.observe(viewLifecycleOwner) { record ->
				val position = records.indexOfFirst { it.id == record.id }
				if (position != -1) {
					records[position] = record
					adapter.notifyItemRangeChanged(0, records.size)
				}
			}
			vm.deletedRecordId.observe(viewLifecycleOwner) { recordId ->
				val position = records.indexOfFirst { it.id == recordId }
				if (position != -1) {
					records.removeAt(position)
					adapter.notifyItemRemoved(position)
					adapter.notifyItemRangeChanged(0, records.size)
				}
			}
			vm.uploadResult.observe(viewLifecycleOwner) {
				MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.upload_result)
					.setMessage(it)
					.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
					.show()
			}
			vm.uploadException.observe(viewLifecycleOwner) {
				Snackbar.make(
					requireView().parent as ViewGroup,
					getString(R.string.error_upload, it.localizedMessage),
					Snackbar.LENGTH_LONG
				).useErrorStyle(requireContext()).show()
			}
			vm.isUploading.observe(viewLifecycleOwner) {
				requireView().findViewById<MaterialProgressBar>(R.id.progress_upload).visibility =
					if (it) View.VISIBLE else View.INVISIBLE
				adapter.notifyDataSetChanged()
			}
		}
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		when (requestCode) {
			REQUEST_EDIT_RECORD -> {
				val recordId = data!!.getIntExtra(RecordEditorFragment.EXTRA_RECORD_ID, -1)
				when (resultCode) {
					RecordEditorFragment.RESULT_RECORD_ADDED -> vm.loadAddedRecord(recordId)
					RecordEditorFragment.RESULT_RECORD_UPDATED -> vm.loadUpdatedRecord(recordId)
				}
			}
		}
	}

	fun notifyRecordAdded(recordId: Int) {
		vm.loadAddedRecord(recordId)
	}
}
