package org.ddosolitary.greendragonfly

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.bugsnag.android.Bugsnag
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class BindAccountViewModel(app: Application) : AndroidViewModel(app) {
	companion object {
		private const val LOG_TAG = "BindAccountViewModel"
		private const val PASSWORD_HASH_ALGORITHM = "MD5"
	}

	@Serializable
	data class ApiSchoolInfo(val schoolno: String, val schoolname: String) {
		override fun toString(): String = schoolname
	}

	@Serializable
	data class ApiUserInfo(
		val name: String,
		val genderStr: String,
		val admissionYear: String,
		val classId: String,
	) {
		val gender: Gender
			get() = if (genderStr == "男") Gender.Male else Gender.Female
	}

	@Serializable
	private data class ApiPlanInfo(
		val r: String,
		val m: List<RunningPlan>,
	) {
		@Serializable
		data class RunningPlan(
			val atttype: String,
			val eventname: String,
			val eventno: String,
			val femalemiles: String,
			val femalespeed: String,
			val malemiles: String,
			val malespeed: String,
			val maxtimesperday: String,
			val rulestartdt: String,
			val ruleenddt: String,
			val starttms: String,
			val endtms: String,
			val weekrg: String,
		)
	}

	val schools = MutableLiveData<List<ApiSchoolInfo>>()
	var selectedSchool: Int? = null
	var studentId = ""
	var password = ""
	val apiUserInfo = MutableLiveData<ApiUserInfo>()
	val isWorking = MutableLiveData<Boolean>()
	val fetchSchoolListException = MutableLiveData<Exception?>()
	val fetchUserInfoError = SingleLiveEvent<String?>()
	val bindAccountResult = SingleLiveEvent<Exception?>()

	init {
		fetchSchoolList()
	}

	fun fetchSchoolList() {
		isWorking.value = true
		val context = getApplication<MyApplication>().applicationContext
		viewModelScope.launch(Dispatchers.Main) {
			try {
				val res = context.getString(R.string.server_url)
					.httpPost()
					.body(context.getString(R.string.api_get_schools))
					.headerForApi()
					.awaitString()
				schools.value =
					Json.decodeFromString(ListSerializer(ApiSchoolInfo.serializer()), res)
				fetchSchoolListException.value = null
				isWorking.value = false
			} catch (e: Exception) {
				Log.e(LOG_TAG, Log.getStackTraceString(e))
				Bugsnag.notify(e)
				fetchSchoolListException.value = e
			}
		}
	}

	fun fetchUserInfo() {
		isWorking.value = true
		val context = getApplication<MyApplication>().applicationContext
		val schoolId = schools.value!![selectedSchool!!].schoolno
		val hashedPassword = MessageDigest.getInstance(PASSWORD_HASH_ALGORITHM).run {
			update(password.encodeToByteArray())
			digest().joinToString("") { "%02x".format(it) }
		}
		viewModelScope.launch(Dispatchers.Main) {
			try {
				val res = context.getString(R.string.server_url)
					.httpPost()
					.body(
						context.getString(
							R.string.api_get_user_template,
							schoolId,
							studentId,
							hashedPassword
						)
					)
					.headerForApi()
					.awaitString()
				if (!res.startsWith("姓名:")) {
					fetchUserInfoError.setValue(res)
					return@launch
				}
				val fields = res.split(',').map { it.split(':')[1] }
				apiUserInfo.value = ApiUserInfo(fields[0], fields[1], fields[2], fields[3])
			} catch (e: Exception) {
				Log.e(LOG_TAG, Log.getStackTraceString(e))
				Bugsnag.notify(e)
				fetchUserInfoError.setValue(e.localizedMessage)
			} finally {
				isWorking.value = false
			}
		}
	}

	fun bindAccount() {
		isWorking.value = true
		val context = getApplication<MyApplication>().applicationContext
		viewModelScope.launch(Dispatchers.Main) {
			try {
				val bindRes = context.getString(R.string.server_url)
					.httpPost()
					.body(
						context.getString(
							R.string.api_bind_template,
							schools.value!![selectedSchool!!].schoolno,
							studentId
						)
					)
					.headerForApi()
					.awaitString()
				val fields = bindRes.split(',')
				val planRes = Json.decodeFromString(
					ApiPlanInfo.serializer(),
					context.getString(R.string.api_path_get_plan, fields[1])
						.httpPost()
						.body(
							Utils.compressString(
								context.getString(R.string.api_query_template, studentId, fields[2])
							)
						)
						.headerForApi()
						.awaitString()
				)
				check(planRes.r == "1")
				val apiPlan = planRes.m[0]
				val minDistance = when (apiUserInfo.value!!.gender) {
					Gender.Male -> apiPlan.malemiles
					Gender.Female -> apiPlan.femalemiles
				}.toDouble()
				val speedRange = when (apiUserInfo.value!!.gender) {
					Gender.Male -> apiPlan.malespeed
					Gender.Female -> apiPlan.femalespeed
				}.split('-').map { it.toDouble() }
				val plan = RunningPlan(
					apiPlan.eventname,
					apiPlan.eventno,
					apiPlan.atttype,
					minDistance,
					speedRange[0],
					speedRange[1],
					apiPlan.maxtimesperday.toInt(),
					LocalDate.parse(apiPlan.rulestartdt).toEpochDay(),
					LocalDate.parse(apiPlan.ruleenddt).toEpochDay(),
					LocalTime.parse(apiPlan.starttms).toSecondOfDay(),
					LocalTime.parse(apiPlan.endtms).toSecondOfDay(),
					apiPlan.weekrg.split(';')
						.mapNotNull { it.toIntOrNull() }
						.map { DayOfWeek.of(it + 1) },
				)
				UserInfo.saveUser(context, toUserInfo(fields[2], fields[1], plan))
				bindAccountResult.setValue(null)
			} catch (e: Exception) {
				Log.e(LOG_TAG, Log.getStackTraceString(e))
				Bugsnag.notify(e)
				bindAccountResult.setValue(e)
			}
			isWorking.value = false
		}
	}

	fun toUserInfo(token: String = "", apiUrl: String = "", plan: RunningPlan? = null): UserInfo {
		val school = schools.value!![selectedSchool!!]
		val info = apiUserInfo.value!!
		return UserInfo(
			studentId,
			info.name,
			info.gender,
			info.admissionYear,
			token,
			info.classId,
			school.schoolno,
			school.schoolname,
			apiUrl,
			plan
		)
	}
}
