package org.ddosolitary.greendragonfly

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

class UserInfoFragment : Fragment() {
	private data class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)
	private inner class RecyclerAdapter :
		RecyclerView.Adapter<ViewHolder>() {

		init {
			vm.user.observe(this@UserInfoFragment, { notifyDataSetChanged() })
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			return ViewHolder(
				LayoutInflater.from(parent.context).inflate(
					R.layout.user_info_item, parent, false
				)
			)
		}

		override fun getItemCount(): Int =
			if (vm.user.value == null) 0 else if (vm.user.value!!.plan == null) 6 else 11

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			class PropertyInfo(val name: Int, val value: String)

			val view = holder.view
			val info = vm.user.value!!.let {
				when (position) {
					0 -> PropertyInfo(R.string.student_id, it.studentId)
					1 -> PropertyInfo(R.string.name, it.name)
					2 -> PropertyInfo(
						R.string.gender,
						when (it.gender) {
							Gender.Male -> view.context.getString(R.string.male)
							Gender.Female -> view.context.getString(R.string.female)
						}
					)
					3 -> PropertyInfo(R.string.admission_year, it.admissionYear)
					4 -> PropertyInfo(R.string.class_id, it.classId)
					5 -> PropertyInfo(R.string.school, it.schoolName)
					6 -> PropertyInfo(
						R.string.speed_range,
						"%.2f-%.2f".format(it.plan!!.minSpeed, it.plan.maxSpeed)
					)
					7 -> PropertyInfo(R.string.min_distance, "%.2f".format(it.plan!!.minDistance))
					8 -> PropertyInfo(
						R.string.max_times_per_day,
						it.plan!!.maxTimesPerDay.toString()
					)
					9 -> PropertyInfo(
						R.string.allowed_date,
						"%s%s%s".format(
							LocalDate.ofEpochDay(it.plan!!.startDate).format(DateTimeFormatter.ISO_LOCAL_DATE),
							getString(R.string.to),
							LocalDate.ofEpochDay(it.plan.endDate).format(DateTimeFormatter.ISO_LOCAL_DATE),
						),
					)
					10 -> {
						val builder = StringBuilder()
						val weekDays = it.plan!!.weekDays
						if (weekDays.isNotEmpty()) {
							val dayToString = { day: DayOfWeek ->
								day.getDisplayName(TextStyle.SHORT, resources.configuration.locales[0])
							}
							val segments = mutableListOf<String>()
							var segStart = 0
							for (i in 1..weekDays.size) {
								if (i == weekDays.size || weekDays[i] > weekDays[i - 1] + 1) {
									when (i - segStart) {
										0 -> Unit
										1 -> segments.add(dayToString(weekDays[segStart]))
										2 -> {
											segments.add(dayToString(weekDays[segStart]))
											segments.add(dayToString(weekDays[segStart + 1]))
										}
										else -> segments.add("%s%s%s".format(
											dayToString(weekDays[segStart]),
											getString(R.string.to),
											dayToString(weekDays[i - 1]),
										))
									}
									segStart = i
								}
							}
							builder.append(segments.joinToString(", "))
							builder.append('\n')
						}
						builder.append("%s-%s".format(
							LocalTime.ofSecondOfDay(it.plan.startTime.toLong())
								.format(DateTimeFormatter.ISO_LOCAL_TIME),
							LocalTime.ofSecondOfDay(it.plan.endTime.toLong())
								.format(DateTimeFormatter.ISO_LOCAL_TIME),
						))
						PropertyInfo(R.string.allowed_time, builder.toString())
					}
					else -> throw IndexOutOfBoundsException()
				}
			}
			view.findViewById<TextView>(R.id.text_info_name).setText(info.name)
			view.findViewById<TextView>(R.id.text_info_value).run {
				maxLines = 2
				text = info.value
			}
		}
	}

	class UserInfoViewModel : ViewModel() {
		val user = MutableLiveData<UserInfo>()
	}

	private val vm by lazy { ViewModelProvider(requireActivity())[UserInfoViewModel::class.java] }

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		return (inflater.inflate(
			R.layout.fragment_user_info,
			container,
			false,
		) as RecyclerView).apply {
			layoutManager = LinearLayoutManager(requireContext())
			adapter = RecyclerAdapter()
			addItemDecoration(
				DividerItemDecoration(
					requireContext(),
					DividerItemDecoration.VERTICAL
				)
			)
		}
	}
}
