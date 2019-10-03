package org.ddosolitary.greendragonfly

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.viewModelScope
import androidx.viewpager.widget.ViewPager
import com.crashlytics.android.Crashlytics
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpPost
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.list
import kotlinx.serialization.toUtf8Bytes
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import java.security.MessageDigest

private const val PASSWORD_HASH_ALGORITHM = "MD5"

class BindActivity : AppCompatActivity() {
	@Serializable
	private data class ApiPlanInfo(
		val r: String,
		val m: List<RunningPlan>
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
			val maxtimesperday: String
		)
	}

	private val vm by lazy { ViewModelProviders.of(this)[BindAccountViewModel::class.java] }
	private val nextButton
		get() = findViewById<Button>(R.id.button_next)
	private val cancelButton
		get() = findViewById<Button>(R.id.button_cancel)
	private val bindButton
		get() = findViewById<Button>(R.id.button_bind)
	private val backButton
		get() = findViewById<Button>(R.id.button_back)
	private val pager
		get() = findViewById<ViewPager>(R.id.pager_bind)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_bind)
		setSupportActionBar(findViewById(R.id.toolbar))
		if (vm.schools.value == null) {
			vm.viewModelScope.launch { fetchSchoolList() }
		} else {
			setIsWorking(false)
		}
		pager.apply {
			adapter = object : FragmentPagerAdapter(
				supportFragmentManager,
				BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
			) {
				private val fragments = arrayOf(BindLoginFragment(), UserInfoFragment())
				override fun getCount(): Int = fragments.size
				override fun getItem(position: Int): Fragment = fragments[position]
			}
			addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
				override fun onPageSelected(position: Int) {
					super.onPageSelected(position)
					when (position) {
						0 -> {
							bindButton.visibility = View.GONE
							backButton.visibility = View.GONE
							nextButton.visibility = View.VISIBLE
							cancelButton.visibility = View.VISIBLE
						}
						1 -> {
							bindButton.visibility = View.VISIBLE
							backButton.visibility = View.VISIBLE
							nextButton.visibility = View.GONE
							cancelButton.visibility = View.GONE
						}
					}
				}
			})
		}
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		pager.setCurrentItem(0, false)
	}

	override fun onBackPressed() {
		if (pager.currentItem == 1) {
			pager.setCurrentItem(0, true)
		} else {
			super.onBackPressed()
		}
	}

	private fun getErrorSnackbar(resId: Int): Snackbar {
		return Snackbar.make(pager, resId, Snackbar.LENGTH_LONG)
			.useErrorStyle(this)
	}

	fun onCancelClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		finish()
	}

	fun onBackClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		onBackPressed()
	}

	fun onNextClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		currentFocus?.windowToken?.let {
			(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
				.hideSoftInputFromWindow(it, 0)
		}
		if (vm.selectedSchool == null) {
			Snackbar.make(
				pager,
				R.string.school_not_selected,
				Snackbar.LENGTH_LONG
			).useErrorStyle(this).show()
			return
		}
		vm.viewModelScope.launch { fetchUserInfo() }
	}

	fun onBindClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		vm.viewModelScope.launch { bindAccount() }
	}

	private fun setProgressEnabled(isEnabled: Boolean) {
		findViewById<MaterialProgressBar>(R.id.progress_bind).apply {
			visibility = if (isEnabled) View.VISIBLE else View.INVISIBLE
		}
	}

	private fun setIsWorking(isWorking: Boolean) {
		nextButton.isEnabled = !isWorking
		cancelButton.isEnabled = !isWorking
		bindButton.isEnabled = !isWorking
		backButton.isEnabled = !isWorking
		setProgressEnabled(isWorking)
	}

	private suspend fun fetchSchoolList(): Unit = withContext(Dispatchers.Main) {
		setIsWorking(true)
		try {
			val res = getString(R.string.server_url)
				.httpPost()
				.body(getString(R.string.api_get_schools))
				.headerForApi()
				.awaitString()
			vm.schools.value = Json(JsonConfiguration.Stable)
				.parse(BindAccountViewModel.ApiSchoolInfo.serializer().list, res)
			setIsWorking(false)
		} catch (e: Exception) {
			Log.e(null, Log.getStackTraceString(e))
			Crashlytics.logException(e)
			setProgressEnabled(false)
			getErrorSnackbar(R.string.error_get_schools).apply {
				setAction(R.string.retry) { vm.viewModelScope.launch { fetchSchoolList() } }
			}.show()
		}
	}

	private suspend fun fetchUserInfo() = withContext(Dispatchers.Main) {
		setIsWorking(true)
		val schoolId = vm.schools.value!![vm.selectedSchool!!].schoolno
		val hashedPassword = MessageDigest.getInstance(PASSWORD_HASH_ALGORITHM).run {
			update(vm.password.toUtf8Bytes())
			digest().joinToString("") { "%02x".format(it) }
		}
		try {
			val res = getString(R.string.server_url)
				.httpPost()
				.body(
					getString(
						R.string.api_get_user_template,
						schoolId,
						vm.studentId,
						hashedPassword
					)
				)
				.headerForApi()
				.awaitString()
			val fields = res.split(',').map { it.split(':')[1] }
			vm.name = fields[0]
			vm.genderStr = fields[1]
			vm.classId = fields[2]
			vm.admissionYear = fields[3]
			pager.setCurrentItem(1, true)
			ViewModelProviders.of(this@BindActivity)[UserInfoFragment.UserInfoViewModel::class.java]
				.user.value = vm.toUserInfo()
		} catch (e: Exception) {
			Log.e(null, Log.getStackTraceString(e))
			Crashlytics.logException(e)
			getErrorSnackbar(R.string.error_get_user).show()
		} finally {
			setIsWorking(false)
		}
	}

	private suspend fun bindAccount() = withContext(Dispatchers.Main) {
		setIsWorking(true)
		try {
			val bindRes = getString(R.string.server_url)
				.httpPost()
				.body(
					getString(
						R.string.api_bind_template,
						vm.schools.value!![vm.selectedSchool!!].schoolno,
						vm.studentId
					)
				)
				.headerForApi()
				.awaitString()
			val fields = bindRes.split(',')
			val planRes = Json(JsonConfiguration.Stable).parse(
				ApiPlanInfo.serializer(),
				getString(R.string.api_path_get_plan, fields[1])
					.httpPost()
					.body(
						Utils.compressString(
							getString(R.string.api_query_template, vm.studentId, fields[2])
						)
					)
					.headerForApi()
					.awaitString()
			)
			check(planRes.r == "1")
			val apiPlan = planRes.m[0]
			val minDistance = when (vm.gender) {
				Gender.Male -> apiPlan.malemiles
				Gender.Female -> apiPlan.femalemiles
			}.toDouble()
			val speedRange = when (vm.gender) {
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
				apiPlan.maxtimesperday.toInt()
			)
			UserInfo.saveUser(this@BindActivity, vm.toUserInfo(fields[2], fields[1], plan))
			startActivity(Intent(this@BindActivity, MainActivity::class.java).apply {
				flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
				action = ACTION_UPDATE_USER
			})
			finish()
		} catch (e: Exception) {
			Log.e(null, Log.getStackTraceString(e))
			Crashlytics.logException(e)
			getErrorSnackbar(R.string.error_bind).show()
		} finally {
			setIsWorking(false)
		}
	}
}
