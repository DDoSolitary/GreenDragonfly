package org.ddosolitary.greendragonfly

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baidu.mapapi.model.LatLng
import com.crashlytics.android.Crashlytics
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpPost
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.content
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import org.threeten.bp.format.DateTimeFormatter
import kotlin.math.roundToLong

class RecordsFragment : Fragment() {
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

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val recordEntry = vm.records[position]
			val locations = recordEntry.getLocations()
			val startTime = Utils.millisToTime(locations.first().timeStamp)
			val speed = StampedLocation.getAverageSpeed(locations)
			val distance = StampedLocation.getDistance(locations)
			val plan = UserInfo.getUser(holder.view.context)!!.plan!!
			val speedInRange = speed in plan.minSpeed..plan.maxSpeed
			val distanceInRange = distance >= plan.minDistance
			val status = if (recordEntry.isUploaded) {
				Status.Uploaded
			} else if (!speedInRange || !distanceInRange) {
				Status.Invalid
			} else {
				val cnt = vm.records.count {
					val targetDate = Utils.millisToTime(
						it.getLocations().first().timeStamp
					).toLocalDate()
					return@count it.isUploaded && targetDate.isEqual(startTime.toLocalDate())
				}
				if (cnt >= plan.maxTimesPerDay) Status.Conflict else Status.Pending
			}
			holder.view.apply {
				setOnClickListener {
					context.startActivity(Intent(context, ShowRecordActivity::class.java).apply {
						putExtra(EXTRA_RECORD, StampedLocation.listToJson(locations))
					})
				}
				findViewById<TextView>(R.id.text_start_time).text =
					startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
				findViewById<TextView>(R.id.text_duration).text =
					Utils.formatSeconds(StampedLocation.getDuration(locations))
				findViewById<TextView>(R.id.text_speed).apply {
					text = context.getString(R.string.speed_template, speed)
					setTextColor(context.getColor(if (speedInRange) R.color.text else R.color.textError))
				}
				findViewById<TextView>(R.id.text_distance).apply {
					text = context.getString(R.string.distance_template, distance)
					setTextColor(context.getColor(if (distanceInRange) R.color.text else R.color.textError))
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
					setTextColor(
						context.getColor(
							if (status == Status.Invalid || status == Status.Conflict) {
								R.color.textError
							} else {
								R.color.text
							}
						)
					)
				}
				findViewById<Button>(R.id.button_record_acton).apply {
					when (status) {
						Status.Conflict, Status.Invalid -> {
							setText(R.string.delete)
							setOnClickListener {
								vm.viewModelScope.launch(Dispatchers.Main) {
									withContext(Dispatchers.IO) {
										Utils.getRecordDao(context).deleteRecord(recordEntry)
									}
									val p = holder.adapterPosition
									if (p in 0..vm.records.size) {
										vm.records.removeAt(p)
										notifyItemRemoved(p)
									}
								}
							}
						}
						Status.Pending -> {
							setText(R.string.upload)
							setOnClickListener {
								vm.viewModelScope.launch {
									uploadRecord(recordEntry, locations)
								}
							}
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

		private fun setIsWorkingAndUpdate(isWorking: Boolean) {
			view?.findViewById<MaterialProgressBar>(R.id.progress_upload)?.visibility =
				if (isWorking) View.VISIBLE else View.INVISIBLE
			isUploading = isWorking
			notifyDataSetChanged()
		}

		private suspend fun uploadRecord(record: RecordEntry, locations: List<StampedLocation>) =
			withContext(Dispatchers.Main) {
				setIsWorkingAndUpdate(true)
				try {
					val user = UserInfo.getUser(context!!)!!
					val req = getString(
						R.string.api_upload_template,
						locations.first().timeStamp / 1000,
						locations.last().timeStamp / 1000,
						user.token,
						user.schoolId,
						StampedLocation.getDistance(locations),
						StampedLocation.getAverageSpeed(locations),
						user.studentId,
						user.plan!!.attType,
						user.plan.eventId,
						locations.joinToString {
							LatLng(it.latitude, it.longitude).toString() + ';'
						},
						StampedLocation.getDuration(locations).roundToLong()
					)
					val res = Json(JsonConfiguration.Stable).parseJson(
						getString(R.string.api_path_upload, user.apiUrl)
							.httpPost()
							.body(Utils.compressString(req))
							.headerForApi()
							.awaitString()
					)
					check(Utils.checkApiResponse(res))
					withContext(Dispatchers.IO) {
						Utils.getRecordDao(context!!)
							.updateRecord(record.apply { isUploaded = true })
					}
					MaterialAlertDialogBuilder(context)
						.setTitle(R.string.upload_result)
						.setMessage(res.jsonObject["m"]!!.jsonObject["srvresp"]!!.content)
						.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
						.show()
				} catch (e: Exception) {
					Log.e(null, Log.getStackTraceString(e))
					Crashlytics.logException(e)
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
		lateinit var records: MutableList<RecordEntry>
	}

	private val vm by lazy { ViewModelProviders.of(activity!!)[RecordsViewModel::class.java] }

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		return inflater.inflate(R.layout.fragment_records, container, false)
	}

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)
		vm.viewModelScope.launch(Dispatchers.Main) {
			vm.records = withContext(Dispatchers.IO) {
				Utils.getRecordDao(context!!).getRecords()
			}.toMutableList()
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
			vm.records.add(withContext(Dispatchers.IO) { recordDao.getLastRecord() })
			view!!.findViewById<RecyclerView>(R.id.recycler_records)
				.adapter!!.notifyItemInserted(newSize - 1)
		}
	}
}
