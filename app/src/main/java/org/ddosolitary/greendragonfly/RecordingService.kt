package org.ddosolitary.greendragonfly

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import kotlinx.coroutines.*

const val ACTION_START_RECORDING = "org.ddosolitary.greendragonfly.action.START_RECORDING"
const val ACTION_FINISH_RECORDING = "org.ddosolitary.greendragonfly.action.FINISH_RECORDING"
private const val UPDATE_INTERVAL = 1000L
private const val WAKELOCK_TIMEOUT = 60 * 60 * 1000L

class RecordingService : Service() {
	inner class LocalBinder : Binder() {
		fun getService(): RecordingService = this@RecordingService
	}

	private lateinit var locationClient: LocationClient
	private lateinit var updateJob: Job
	private lateinit var wakeLock: PowerManager.WakeLock
	private val binder = LocalBinder()
	lateinit var route: MutableList<StampedLocation>
	var isRecording = false
	val statusLiveData = MutableLiveData<Boolean>()

	override fun onBind(intent: Intent?): IBinder? = binder

	override fun onCreate() {
		super.onCreate()
		wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
			newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag").apply { acquire(WAKELOCK_TIMEOUT) }
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				getString(R.string.recording_channel_id),
				getString(R.string.recording_channel_name),
				NotificationManager.IMPORTANCE_LOW
			).apply { description = getString(R.string.recording_channel_desc) }
			NotificationManagerCompat.from(this).createNotificationChannel(channel)
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_START_RECORDING -> if (!isRecording) {
				isRecording = true
				startRecording()
			}
			ACTION_FINISH_RECORDING -> if (isRecording) {
				isRecording = false
				stopRecording(false)
			}
			else -> throw IllegalStateException()
		}
		return START_REDELIVER_INTENT
	}

	override fun onDestroy() {
		if (isRecording) stopRecording(true)
		wakeLock.release()
		super.onDestroy()
	}

	private suspend fun updateStatus() = withContext(Dispatchers.Main) {
		while (true) {
			try {
				val n = route.size
				if (n >= 2) {
					NotificationManagerCompat.from(this@RecordingService).notify(
						resources.getInteger(R.integer.recording_notification_id),
						generateNotification()
					)
				}
				statusLiveData.value = true
				delay(UPDATE_INTERVAL)
			} catch (_: CancellationException) {
				return@withContext
			}
		}
	}

	private fun generateNotification(): Notification {
		val content = if (route.size >= 2) {
			getString(
				R.string.recording_notification_content,
				StampedLocation.getCurrentSpeed(route),
				StampedLocation.getAverageSpeed(route),
				Utils.formatSeconds(StampedLocation.getDurationToNow(route)),
				StampedLocation.getDistance(route)
			)
		} else getString(R.string.waiting_location)
		val contentIntent = Intent(this, RunActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK
		}
		return NotificationCompat.Builder(this, getString(R.string.recording_channel_id))
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setSmallIcon(R.drawable.ic_run)
			.setContentTitle(getString(R.string.recording_notification_title))
			.setStyle(
				NotificationCompat.BigTextStyle()
					.bigText(content)
			)
			.setContentIntent(PendingIntent.getActivity(this, 0, contentIntent, 0))
			.setOnlyAlertOnce(true)
			.build()
	}

	private fun startRecording() {
		val pref = getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE)
		val json = pref.getString(getString(R.string.pref_key_incomplete_record), null)
		route = if (json != null) {
			pref.edit {
				remove(getString(R.string.pref_key_incomplete_record))
				apply()
			}
			StampedLocation.jsonToList(json).toMutableList()
		} else mutableListOf()
		startForeground(
			resources.getInteger(R.integer.recording_notification_id),
			generateNotification()
		)
		locationClient = LocationClient(this).apply {
			locOption = LocationClientOption().apply {
				openGps = true
				coorType = "bd09ll"
				locationMode = LocationClientOption.LocationMode.Device_Sensors
				setOpenAutoNotifyMode()
				if (BuildConfig.DEBUG) setEnableSimulateGps(true)
			}
			registerLocationListener(object : BDAbstractLocationListener() {
				override fun onReceiveLocation(location: BDLocation?) {
					if (location != null && location.locType == BDLocation.TypeGpsLocation) {
						route.add(
							StampedLocation(
								System.currentTimeMillis(),
								location.latitude,
								location.longitude
							)
						)
					}
				}
			})
			start()
		}
		updateJob = GlobalScope.launch { updateStatus() }
	}

	private fun stopRecording(fromDestroy: Boolean) {
		updateJob.cancel()
		locationClient.stop()
		GlobalScope.launch(Dispatchers.Main) {
			if (fromDestroy) {
				getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE).edit {
					putString(
						getString(R.string.pref_key_incomplete_record),
						StampedLocation.listToJson(route)
					)
					apply()
				}
			} else {
				if (route.size >= 2) {
					withContext(Dispatchers.IO) {
						Utils.getRecordDao(this@RecordingService)
							.addRecord(RecordEntry.fromLocations(route))
					}
				}
				ServiceCompat.stopForeground(
					this@RecordingService,
					ServiceCompat.STOP_FOREGROUND_REMOVE
				)
				stopSelf()
			}
			statusLiveData.value = false
		}
	}
}
