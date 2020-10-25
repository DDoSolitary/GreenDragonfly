package org.ddosolitary.greendragonfly

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bugsnag.android.Bugsnag
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
	companion object {
		const val ACTION_SHOW_RECORDS = "org.ddosolitary.greendragonfly.action.SHOW_RECORDS"
		const val ACTION_UPDATE_USER = "org.ddosolitary.greendragonfly.action.UPDATE_USER"
		const val EXTRA_ADDED_RECORD_ID = "org.ddosolitary.greendragonfly.extra.ADDED_RECORD_ID"
		private const val LOCATION_PERMISSION_REQUEST = 0
		private const val SCROLL_THRESHOLD = 200
		private const val LOG_TAG = "MainActivity"
	}

	class MainViewModel(app: Application) : AndroidViewModel(app) {
		val uploadCount = SingleLiveEvent<Int>()
		val queryCountError = SingleLiveEvent<Exception>()
		val latestVersion = SingleLiveEvent<String>()

		init { fetchLatestVersion() }

		private fun fetchLatestVersion() {
			viewModelScope.launch(Dispatchers.Main) {
				val context = getApplication<MyApplication>().applicationContext
				val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
				val pref = context.getSharedPreferences(context.getString(R.string.pref_main), Context.MODE_PRIVATE)
				val lastCheck = pref.getString(context.getString(R.string.pref_key_update_last_check), null)
				if (lastCheck != today) {
					pref.edit {
						putString(context.getString(R.string.pref_key_update_last_check), today)
						apply()
					}
					try {
						val json = Json.parseToJsonElement(
							context.getString(R.string.update_url).httpGet().awaitString()
						)
						latestVersion.setValue(json.jsonArray[0].jsonObject["tag_name"]!!.jsonPrimitive.content)
					} catch (e: Exception) {
						Bugsnag.notify(e)
						Log.e(LOG_TAG, Log.getStackTraceString(e))
					}
				}
			}
		}

		fun queryCount() {
			viewModelScope.launch(Dispatchers.Main) {
				val context = getApplication<MyApplication>().applicationContext
				try {
					val user = UserInfo.getUser(context)!!
					val res = Json.parseToJsonElement(
						context.getString(R.string.api_path_query_count, user.apiUrl)
							.httpPost()
							.body(
								Utils.compressString(
									context.getString(R.string.api_query_template, user.studentId, user.token)
								)
							)
							.headerForApi()
							.awaitString()
					)
					check(Utils.checkApiResponse(res))
					val resMsg = res.jsonObject["m"]!!.jsonPrimitive.content
					val count = resMsg.toIntOrNull()
						?: Regex("${user.plan!!.name}:(\\d+)").find(resMsg)!!.groupValues[1].toInt()
					uploadCount.setValue(count)
				} catch (e: Exception) {
					Log.e(LOG_TAG, Log.getStackTraceString(e))
					Bugsnag.notify(e)
					queryCountError.setValue(e)
				}
			}
		}
	}

	private val mainVm by lazy {
		ViewModelProvider(this)[MainViewModel::class.java]
	}
	private val userVm by lazy {
		ViewModelProvider(this)[UserInfoFragment.UserInfoViewModel::class.java]
	}
	private val pager
		get() = findViewById<ViewPager2>(R.id.pager_tab)
	private val fab
		get() = findViewById<FloatingActionButton>(R.id.fab_run)

	private val gestureDetector by lazy {
		GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
			override fun onScroll(
				e1: MotionEvent?,
				e2: MotionEvent?,
				distanceX: Float,
				distanceY: Float,
			): Boolean {
				if (e1 == null || e2 == null) {
					return super.onScroll(e1, e2, distanceX, distanceY)
				}
				if (e2.y - e1.y < -SCROLL_THRESHOLD && fab.isShown) fab.hide()
				if (e2.y - e1.y > SCROLL_THRESHOLD && !fab.isShown) fab.show()
				return false
			}
		})
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		setSupportActionBar(findViewById(R.id.toolbar))
		userVm.user.value = UserInfo.getUser(this)
		TabLayoutMediator(findViewById(R.id.tabs), pager.apply {
			adapter = object : FragmentStateAdapter(this@MainActivity) {
				override fun getItemCount(): Int = if (BuildConfig.DEBUG) 3 else 2
				override fun createFragment(position: Int): Fragment = when (position) {
					0 -> UserInfoFragment()
					1 -> RecordsFragment()
					else -> DebugOptionsFragment()
				}
			}
			if (intent?.action == ACTION_SHOW_RECORDS) currentItem = 1
		}) { tab, position ->
			tab.text = when (position) {
				0 -> getString(R.string.user)
				1 -> getString(R.string.records)
				2 -> getString(R.string.debug_options)
				else -> null
			}
		}.attach()
		Utils.checkAndShowAbout(this)
		mainVm.run {
			latestVersion.observe(this@MainActivity) { checkUpdate(it) }
			uploadCount.observe(this@MainActivity) { showUploadCount(it) }
			queryCountError.observe(this@MainActivity) { showQueryCountError(it) }
		}
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.menu_main, menu)
		menu!!.findItem(R.id.item_version).apply {
			isEnabled = false
			title = SpannableString(getString(R.string.version_template, BuildConfig.VERSION_NAME))
				.apply { setSpan(ForegroundColorSpan(getColor(R.color.textDisabled)), 0, length, 0) }
		}
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.item_rebind -> startActivity(Intent(this, BindActivity::class.java))
			R.id.item_query_count -> mainVm.queryCount()
			R.id.item_about -> Utils.showAboutDialog(this)
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	override fun onResume() {
		super.onResume()
		bindService(Intent(this, RecordingService::class.java), object : ServiceConnection {
			override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
				val service = (binder as RecordingService.LocalBinder).getService()
				fab.backgroundTintList = ColorStateList.valueOf(
					getColor(
						if (service.isRecording) {
							R.color.fabRunRunning
						} else {
							R.color.fabRun
						}
					)
				)
				fab.imageTintList = ColorStateList.valueOf(
					getColor(
						if (service.isRecording) {
							R.color.fabIconDark
						} else {
							R.color.fabIconLight
						}
					)
				)
				unbindService(this)
			}

			override fun onServiceDisconnected(name: ComponentName?) {}
		}, Context.BIND_AUTO_CREATE)
	}

	override fun onNewIntent(intent: Intent?) {
		super.onNewIntent(intent)
		when (intent?.action) {
			ACTION_SHOW_RECORDS -> {
				val recordId = intent.getIntExtra(EXTRA_ADDED_RECORD_ID, -1)
				if (recordId != -1) {
					ViewModelProvider(this)[RecordsFragment.RecordsViewModel::class.java]
						.addedRecordId.setValue(recordId)
				}
				pager.setCurrentItem(1, true)
			}
			ACTION_UPDATE_USER -> {
				userVm.user.value = UserInfo.getUser(this)
			}
		}
	}

	override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
		gestureDetector.onTouchEvent(ev)
		return super.dispatchTouchEvent(ev)
	}

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray,
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == LOCATION_PERMISSION_REQUEST) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				startActivity(Intent(this, RunActivity::class.java))
			} else {
				MaterialAlertDialogBuilder(this)
					.setTitle(R.string.error)
					.setMessage(R.string.location_permission_denied)
					.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
					.show()
			}
		}
	}

	fun onRunClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			requestPermissions(
				arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
				LOCATION_PERMISSION_REQUEST
			)
		} else {
			val startRunning = {
				startActivity(Intent(this, RunActivity::class.java).apply {
					flags = Intent.FLAG_ACTIVITY_NEW_TASK
				})
			}
			val plan = UserInfo.getUser(this)!!.plan!!
			val msg = when {
				LocalDate.now().toEpochDay() !in plan.startDate..plan.endDate -> R.string.warn_invalid_date
				LocalTime.now().toSecondOfDay() !in plan.startTime..plan.endTime -> R.string.warn_invalid_time
				else -> null
			}
			if (msg != null) {
				MaterialAlertDialogBuilder(this)
					.setTitle(R.string.warning)
					.setMessage(msg)
					.setPositiveButton(R.string.ok) { dialog, _ ->
						startRunning()
						dialog.dismiss()
					}
					.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
					.show()
			} else {
				startRunning()
			}
		}
	}

	private fun showUploadCount(count: Int) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.query_count_title)
			.setMessage(getString(R.string.query_count_message, count))
			.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
			.show()
	}

	private fun showQueryCountError(e: Exception) {
		Snackbar.make(
			pager,
			getString(R.string.error_query_count, e.localizedMessage),
			Snackbar.LENGTH_LONG
		).useErrorStyle(this).show()
	}

	private fun checkUpdate(latest: String) {
		if ("v${BuildConfig.VERSION_NAME}" != latest) {
			MaterialAlertDialogBuilder(this)
				.setTitle(R.string.update_available_title)
				.setMessage(getString(R.string.update_available_message, latest))
				.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
				.show()
		}
	}
}
