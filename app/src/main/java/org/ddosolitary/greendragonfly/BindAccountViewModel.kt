package org.ddosolitary.greendragonfly

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.serialization.Serializable

class BindAccountViewModel : ViewModel() {
	@Serializable
	data class ApiSchoolInfo(val schoolno: String, val schoolname: String) {
		override fun toString(): String = schoolname
	}

	val schools = MutableLiveData<List<ApiSchoolInfo>>()
	var selectedSchool: Int? = null
	var studentId = ""
	var password = ""
	var name: String = ""
	var genderStr: String = ""
	var admissionYear: String = ""
	var classId: String = ""

	val gender: Gender
		get() = if (genderStr == "男") Gender.Male else Gender.Female

	fun toUserInfo(token: String = "", apiUrl: String = "", plan: RunningPlan? = null): UserInfo {
		val school = schools.value!![selectedSchool!!]
		return UserInfo(
			studentId,
			name,
			gender,
			admissionYear,
			token,
			classId,
			school.schoolno,
			school.schoolname,
			apiUrl,
			plan
		)
	}
}
