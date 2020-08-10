package org.ddosolitary.greendragonfly

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.model.LatLngBounds

const val EXTRA_RECORD = "org.ddosolitary.greendragonfly.extra.RECORD"
private const val SHOW_ROUTE_PADDING = 100

class ShowRecordActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_show_record)
		setSupportActionBar(findViewById(R.id.toolbar))
		val locations = StampedLocation.jsonToList(intent.getStringExtra(EXTRA_RECORD)!!)
		val map = findViewById<MapView>(R.id.view_map).apply { showZoomControls(false) }.map
		map.setOnMapLoadedCallback {
			map.animateMapStatus(
				MapStatusUpdateFactory.newLatLngBounds(
					LatLngBounds.Builder().apply {
						locations.forEach { include(LatLng(it.latitude, it.longitude)) }
					}.build(),
					SHOW_ROUTE_PADDING,
					SHOW_ROUTE_PADDING,
					SHOW_ROUTE_PADDING,
					SHOW_ROUTE_PADDING
				)
			)
			Utils.drawLine(this, map, locations)
			Utils.drawPoint(map, locations.first(), "icon_start.png")
			Utils.drawPoint(map, locations.last(), "icon_end.png")
		}
	}

	fun onCloseClicked(@Suppress("UNUSED_PARAMETER") vie: View) {
		finish()
	}
}
