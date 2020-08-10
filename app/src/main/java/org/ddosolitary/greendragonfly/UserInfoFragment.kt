package org.ddosolitary.greendragonfly

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class UserInfoFragment : Fragment() {
	private data class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)
	private inner class RecyclerAdapter :
		RecyclerView.Adapter<ViewHolder>() {

		init {
			vm.user.observe(this@UserInfoFragment, Observer {
				notifyDataSetChanged()
			})
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			return ViewHolder(
				LayoutInflater.from(parent.context).inflate(
					R.layout.user_info_item, parent, false
				)
			)
		}

		override fun getItemCount(): Int =
			if (vm.user.value == null) 0 else if (vm.user.value!!.plan == null) 6 else 9

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			class PropertyInfo(val name: Int, val value: String)

			val view = holder.view
			val info = vm.user.value!!.let {
				when (position) {
					0 -> PropertyInfo(R.string.student_id, it.studentId)
					1 -> PropertyInfo(R.string.name, it.name)
					2 -> PropertyInfo(
						R.string.gender, when (it.gender) {
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
					else -> throw IndexOutOfBoundsException()
				}
			}
			view.findViewById<TextView>(R.id.text_info_name).setText(info.name)
			view.findViewById<TextView>(R.id.text_info_value).text = info.value
		}
	}

	class UserInfoViewModel : ViewModel() {
		val user = MutableLiveData<UserInfo>()
	}

	private val vm by lazy { ViewModelProvider(requireActivity())[UserInfoViewModel::class.java] }

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		return (inflater.inflate(
			R.layout.fragment_user_info,
			container,
			false
		) as RecyclerView).apply {
			layoutManager = LinearLayoutManager(container!!.context)
			adapter = RecyclerAdapter()
			addItemDecoration(
				DividerItemDecoration(
					container.context,
					DividerItemDecoration.VERTICAL
				)
			)
		}
	}
}
