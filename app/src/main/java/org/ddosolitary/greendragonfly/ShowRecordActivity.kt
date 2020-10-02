package org.ddosolitary.greendragonfly

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.model.LatLngBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHOW_ROUTE_PADDING = 100

class ShowRecordActivity : AppCompatActivity() {
	companion object {
		const val EXTRA_RECORD_ID = "org.ddosolitary.greendragonfly.extra.RECORD_ID"
	}

	class ShowRecordViewModel : ViewModel() {
		val record = MutableLiveData<Record>()
	}

	private val vm by lazy { ViewModelProvider(this)[ShowRecordViewModel::class.java] }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_show_record)
		setSupportActionBar(findViewById(R.id.toolbar))
		vm.viewModelScope.launch(Dispatchers.Main) {
			val recordId = intent.getIntExtra(EXTRA_RECORD_ID, -1)
			val recordEntry = withContext(Dispatchers.IO) {
				Utils.getRecordDao(this@ShowRecordActivity).getRecordById(recordId)
			}
			vm.record.value = withContext(Dispatchers.Default) { recordEntry.decryptRecord() }!!
		}
		val map = findViewById<MapView>(R.id.view_map).apply { showZoomControls(false) }.map
		map.setOnMapLoadedCallback {
			vm.record.observe(this) { record ->
				map.animateMapStatus(
					MapStatusUpdateFactory.newLatLngBounds(
						LatLngBounds.Builder().apply {
							record.locations.forEach { include(LatLng(it.latitude, it.longitude)) }
						}.build(),
						SHOW_ROUTE_PADDING,
						SHOW_ROUTE_PADDING,
						SHOW_ROUTE_PADDING,
						SHOW_ROUTE_PADDING,
					)
				)
				Utils.drawLine(this@ShowRecordActivity, map, record.locations)
				Utils.drawPoint(map, record.locations.first(), "icon_start.png")
				Utils.drawPoint(map, record.locations.last(), "icon_end.png")
			}
		}
	}

	fun onCloseClicked(@Suppress("UNUSED_PARAMETER") vie: View) {
		finish()
	}
}
