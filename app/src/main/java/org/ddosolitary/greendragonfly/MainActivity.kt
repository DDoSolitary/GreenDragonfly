package org.ddosolitary.greendragonfly

import android.Manifest
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
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.viewpager.widget.ViewPager
import com.bugsnag.android.Bugsnag
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
	companion object {
		const val ACTION_SHOW_RECORDS = "org.ddosolitary.greendragonfly.action.SHOW_RECORDS"
		const val ACTION_UPDATE_USER = "org.ddosolitary.greendragonfly.action.UPDATE_USER"
		private const val LOCATION_PERMISSION_REQUEST = 0
		private const val SCROLL_THRESHOLD = 200
		private const val LOG_TAG = "MainActivity"
	}

	private val recordsFragment = RecordsFragment()
	private val userVm by lazy {
		ViewModelProvider(this)[UserInfoFragment.UserInfoViewModel::class.java]
	}
	private val pager
		get() = findViewById<ViewPager>(R.id.pager_tab)
	private val fab
		get() = findViewById<FloatingActionButton>(R.id.fab_run)

	private val gestureDetector by lazy {
		GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
			override fun onScroll(
				e1: MotionEvent?,
				e2: MotionEvent?,
				distanceX: Float,
				distanceY: Float
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
		findViewById<TabLayout>(R.id.tabs).setupWithViewPager(pager.apply {
			adapter = object : FragmentPagerAdapter(
				supportFragmentManager,
				BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
			) {
				private val fragments = if (BuildConfig.DEBUG) {
					arrayOf(UserInfoFragment(), recordsFragment, DebugOptionsFragment())
				} else {
					arrayOf(UserInfoFragment(), recordsFragment)
				}

				override fun getCount(): Int = fragments.size
				override fun getItem(position: Int): Fragment = fragments[position]
				override fun getPageTitle(position: Int): CharSequence? = when (position) {
					0 -> getString(R.string.user)
					1 -> getString(R.string.records)
					2 -> getString(R.string.debug_options)
					else -> null
				}
			}
			if (intent?.action == ACTION_SHOW_RECORDS) currentItem = 1
		})
		Utils.checkAndShowAbout(this)
		userVm.viewModelScope.launch { checkUpdate() }
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
			R.id.item_query_count -> userVm.viewModelScope.launch { queryCount() }
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
				recordsFragment.updateForAppending()
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
		grantResults: IntArray
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
			startActivity(Intent(this, RunActivity::class.java).apply {
				flags = Intent.FLAG_ACTIVITY_NEW_TASK
			})
		}
	}

	private suspend fun queryCount() = withContext(Dispatchers.Main) {
		try {
			val user = UserInfo.getUser(this@MainActivity)!!
			val res = Json.parseToJsonElement(
				getString(R.string.api_path_query_count, user.apiUrl)
					.httpPost()
					.body(
						Utils.compressString(
							getString(R.string.api_query_template, user.studentId, user.token)
						)
					)
					.headerForApi()
					.awaitString()
			)
			check(Utils.checkApiResponse(res))
			MaterialAlertDialogBuilder(this@MainActivity)
				.setTitle(R.string.query_count_title)
				.setMessage(getString(R.string.query_count_message, res.jsonObject["m"]!!.jsonPrimitive.int))
				.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
				.show()
		} catch (e: Exception) {
			Log.e(LOG_TAG, Log.getStackTraceString(e))
			Bugsnag.notify(e)
			Snackbar.make(
				pager,
				getString(R.string.error_query_count, e.localizedMessage),
				Snackbar.LENGTH_LONG
			).useErrorStyle(this@MainActivity).show()
		}
	}

	private suspend fun checkUpdate() = withContext(Dispatchers.Main) {
		val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
		val pref = getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE)
		val lastCheck = pref.getString(getString(R.string.pref_key_update_last_check), null)
		if (lastCheck != today) {
			pref.edit {
				putString(getString(R.string.pref_key_update_last_check), today)
				apply()
			}
			try {
				val json = Json.parseToJsonElement(getString(R.string.update_url).httpGet().awaitString())
				val latestVersion = json.jsonArray[0].jsonObject["tag_name"]!!.jsonPrimitive.content
				if ("v${BuildConfig.VERSION_NAME}" != latestVersion) {
					MaterialAlertDialogBuilder(this@MainActivity)
						.setTitle(R.string.update_available_title)
						.setMessage(getString(R.string.update_available_message, latestVersion))
						.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
						.show()
				}
			} catch (e: Exception) {
				Bugsnag.notify(e)
				Log.e(LOG_TAG, Log.getStackTraceString(e))
			}
		}
	}
}
