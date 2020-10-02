package org.ddosolitary.greendragonfly

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
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
import org.threeten.bp.format.DateTimeFormatter
import kotlin.math.roundToLong

class RecordsFragment : Fragment() {
	companion object {
		private const val LOG_TAG = "RecordsFragment"
		private const val MILLIS_OF_DAY = 24 * 60 * 60 * 1000L
	}

	private enum class Status { Invalid, Pending, Conflict, Uploaded }
	private data class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

	private inner class RecyclerAdapter :
		RecyclerView.Adapter<ViewHolder>() {

		private var isUploading = false

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			return ViewHolder(
				LayoutInflater.from(parent.context).inflate(
					R.layout.record_item, parent, false
				)
			)
		}

		override fun getItemCount(): Int = vm.records.size

		@SuppressLint("ClickableViewAccessibility")
		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val record = vm.records[position]
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
				val cnt = vm.records.count {
					val targetDate = Utils.millisToTime(it.locations.first().timeStamp).toLocalDate()
					return@count it.isUploaded && targetDate.isEqual(startTime.toLocalDate())
				}
				if (cnt >= plan.maxTimesPerDay) Status.Conflict else Status.Pending
			}
			val gestureDetector = GestureDetectorCompat(context!!, object : GestureDetector.SimpleOnGestureListener() {
				override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
					context!!.startActivity(Intent(context, ShowRecordActivity::class.java).apply {
						putExtra(ShowRecordActivity.EXTRA_RECORD_ID, record.id)
					})
					return true
				}

				override fun onDoubleTap(e: MotionEvent?): Boolean {
					return updateTimeStamps(-MILLIS_OF_DAY)
				}

				override fun onLongPress(e: MotionEvent?) {
					updateTimeStamps(MILLIS_OF_DAY)
				}

				private fun updateTimeStamps(offset: Long): Boolean {
					val debugPref = PreferenceManager.getDefaultSharedPreferences(context!!)
					val allowChange = debugPref.getBoolean(getString(R.string.pref_key_allow_change_date), false)
					if (!isUploading && allowChange) {
						record.locations = record.locations.map { it.copy(timeStamp = it.timeStamp + offset) }
						vm.viewModelScope.launch(Dispatchers.Main) {
							val recordEntry = withContext(Dispatchers.Default) {
								RecordEntry.encryptRecord(record)
							}
							withContext(Dispatchers.IO) {
								Utils.getRecordDao(context!!).updateRecord(recordEntry)
							}
							notifyItemChanged(holder.adapterPosition)
						}
						return true
					}
					return false
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
							setOnClickListener {
								vm.viewModelScope.launch(Dispatchers.Main) {
									withContext(Dispatchers.IO) {
										Utils.getRecordDao(context).deleteRecordById(record.id)
									}
									val p = holder.adapterPosition
									if (p in 0..vm.records.size) {
										vm.records.removeAt(p)
										notifyItemRemoved(p)
									}
								}
							}
							isEnabled = true
						}
						Status.Pending -> {
							setText(R.string.upload)
							setOnClickListener {
								vm.viewModelScope.launch { uploadRecord(record) }
							}
							isEnabled = true
						}
						Status.Uploaded -> {
							setText(R.string.upload)
							isEnabled = false
						}
					}
					if (isUploading) isEnabled = false
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

		private fun setIsWorkingAndUpdate(isWorking: Boolean) {
			view?.findViewById<MaterialProgressBar>(R.id.progress_upload)?.visibility =
				if (isWorking) View.VISIBLE else View.INVISIBLE
			isUploading = isWorking
			notifyDataSetChanged()
		}

		private suspend fun uploadRecord(record: Record) =
			withContext(Dispatchers.Main) {
				setIsWorkingAndUpdate(true)
				try {
					val user = UserInfo.getUser(context!!)!!
					val req = getString(
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
						getString(R.string.api_path_upload, user.apiUrl)
							.httpPost()
							.body(Utils.compressString(req))
							.headerForApi()
							.awaitString()
					)
					check(Utils.checkApiResponse(res))
					val resMsg = Json.parseToJsonElement(res.jsonObject["m"]!!.jsonPrimitive.content)
						.jsonObject["srvresp"]!!.jsonPrimitive.content
					MaterialAlertDialogBuilder(context!!)
						.setTitle(R.string.upload_result)
						.setMessage(resMsg)
						.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
						.show()
					record.isUploaded = true
					val recordEntry = withContext(Dispatchers.Default) {
						RecordEntry.encryptRecord(record)
					}
					withContext(Dispatchers.IO) {
						Utils.getRecordDao(context!!).updateRecord(recordEntry)
					}
				} catch (e: Exception) {
					Log.e(LOG_TAG, Log.getStackTraceString(e))
					Bugsnag.notify(e)
					Snackbar.make(
						view!!.parent as ViewGroup,
						R.string.error_upload,
						Snackbar.LENGTH_LONG
					).useErrorStyle(context!!).show()
				} finally {
					setIsWorkingAndUpdate(false)
				}
			}
	}

	class RecordsViewModel : ViewModel() {
		lateinit var records: MutableList<Record>
	}

	private val vm by lazy { ViewModelProvider(requireActivity())[RecordsViewModel::class.java] }

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		return inflater.inflate(R.layout.fragment_records, container, false)
	}

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)
		vm.viewModelScope.launch(Dispatchers.Main) {
			val recordEntries = withContext(Dispatchers.IO) {
				Utils.getRecordDao(context!!).getRecords()
			}
			// TODO: Warn about decryption failure.
			vm.records = withContext(Dispatchers.Default) {
				recordEntries.mapNotNull { it.decryptRecord() }.toMutableList()
			}
			view!!.findViewById<RecyclerView>(R.id.recycler_records).apply {
				layoutManager = LinearLayoutManager(context)
				adapter = RecyclerAdapter()
				addItemDecoration(
					DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
				)
			}
		}
	}

	fun updateUI() {
		// Assume the only possible change is appending a new item.
		if (activity == null) return
		vm.viewModelScope.launch(Dispatchers.Main) {
			val recordDao = Utils.getRecordDao(context!!)
			val newSize = withContext(Dispatchers.IO) { recordDao.getRecordCount() }
			if (newSize == vm.records.size) return@launch
			check(newSize == vm.records.size + 1)
			val recordEntry = withContext(Dispatchers.IO) { recordDao.getLastRecord() }
			val record = withContext(Dispatchers.Default) {	recordEntry.decryptRecord() }
			// TODO: Warn about decryption failure.
			if (record != null) {
				vm.records.add(record)
				view!!.findViewById<RecyclerView>(R.id.recycler_records)
					.adapter!!.notifyItemInserted(newSize - 1)
			}
		}
	}
}
