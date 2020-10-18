package org.ddosolitary.greendragonfly

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.model.LatLng
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RunActivity : AppCompatActivity() {
	companion object {
		private const val DEFAULT_ZOOM_LEVEL = 19f
	}

	private lateinit var conn: ServiceConnection
	private lateinit var service: RecordingService
	private var lastStatusInRange = false
	private val mapView
		get() = findViewById<MapView>(R.id.view_map)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_run)
		setSupportActionBar(findViewById(R.id.toolbar))
		val zoomLevel = getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE)
			.getFloat(getString(R.string.pref_key_zoom), DEFAULT_ZOOM_LEVEL)
		mapView.apply {
			showZoomControls(false)
			map.apply {
				isMyLocationEnabled = true
				setOnMapLoadedCallback {
					animateMapStatus(MapStatusUpdateFactory.zoomTo(zoomLevel))
				}
			}
		}
		val serviceIntent = Intent(this, RecordingService::class.java)
		conn = object : ServiceConnection {
			override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
				service = (binder as RecordingService.LocalBinder).getService()
				if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 || service.isRecording) {
					ContextCompat.startForegroundService(
						this@RunActivity,
						serviceIntent.apply { action = RecordingService.ACTION_START_RECORDING })
					service.statusLiveData.observe(this@RunActivity) {
						if (!it.finished) {
							onStatusUpdate()
						} else {
							startActivity(
								Intent(this@RunActivity, MainActivity::class.java).apply {
									action = MainActivity.ACTION_SHOW_RECORDS
									if (it.recordId != null) {
										putExtra(MainActivity.EXTRA_ADDED_RECORD_ID, it.recordId)
									}
								}
							)
							finish()
						}
					}
				} else {
					startActivity(Intent(this@RunActivity, StartupActivity::class.java))
					finish()
				}
			}

			override fun onServiceDisconnected(name: ComponentName?) {}
		}.also { bindService(serviceIntent, it, Context.BIND_AUTO_CREATE) }
	}

	override fun onDestroy() {
		getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE).edit {
			putFloat(getString(R.string.pref_key_zoom), mapView.map.mapStatus.zoom)
			apply()
		}
		unbindService(conn)
		super.onDestroy()
	}

	private fun stopRunning() {
		startService(Intent(this, RecordingService::class.java).apply {
			action = RecordingService.ACTION_FINISH_RECORDING
		})
	}

	fun onFinishClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.warning)
			.setMessage(R.string.warn_not_in_range)
			.setPositiveButton(R.string.confirm) { dialog, _ ->
				stopRunning()
				dialog.dismiss()
			}
			.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
		if (service.route.size < 2) {
			dialog.apply {
				setMessage(R.string.warn_no_location)
				show()
			}
		} else if (!lastStatusInRange) {
			dialog.apply {
				setMessage(R.string.warn_not_in_range)
				show()
			}
		} else stopRunning()
	}

	private fun TextView.setStatusColor(isNormal: Boolean) {
		setTextColor(getColor(if (isNormal) R.color.textLight else R.color.textWarning))
	}

	private fun onStatusUpdate() {
		val plan = UserInfo.getUser(this)!!.plan!!
		val curSpeed = StampedLocation.getCurrentSpeed(service.route)
		val avgSpeed = StampedLocation.getAverageSpeed(service.route)
		val distance = StampedLocation.getDistance(service.route)
		val curSpeedInRange = curSpeed in plan.minSpeed..plan.maxSpeed
		val avgSpeedInRange = avgSpeed in plan.minSpeed..plan.maxSpeed
		val distanceInRange = distance >= plan.minDistance
		lastStatusInRange = avgSpeedInRange && distanceInRange
		findViewById<TextView>(R.id.text_current_speed).apply {
			text = getString(R.string.speed_template, curSpeed)
			setStatusColor(curSpeedInRange)
		}
		findViewById<TextView>(R.id.text_average_speed).apply {
			text = getString(R.string.speed_template, avgSpeed)
			setStatusColor(avgSpeedInRange)
		}
		findViewById<TextView>(R.id.text_distance).apply {
			text = getString(R.string.distance_template, distance)
			setStatusColor(distanceInRange)
		}
		findViewById<TextView>(R.id.text_time).text =
			Utils.formatSeconds(StampedLocation.getDurationToNow(service.route))
		mapView.map.clear()
		val n = service.route.size
		if (n > 1) {
			Utils.drawLine(this, mapView.map, service.route)
		}
		if (n > 0) {
			Utils.drawPoint(mapView.map, service.route.first(), "icon_start.png")
			val p = LatLng(
				service.route.last().latitude,
				service.route.last().longitude
			)
			mapView.map.apply {
				setMyLocationData(
					MyLocationData.Builder()
						.latitude(service.route.last().latitude)
						.longitude(service.route.last().longitude)
						.build()
				)
				animateMapStatus(MapStatusUpdateFactory.newLatLng(p))
			}
		}
	}
}
