package org.ddosolitary.greendragonfly

import android.content.Context
import androidx.room.Room
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.github.kittinunf.fuel.core.Request
import com.google.android.material.snackbar.Snackbar
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.toUtf8Bytes
import org.threeten.bp.Instant
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToLong

private const val MAP_LINE_WIDTH = 10
fun Snackbar.useErrorStyle(context: Context): Snackbar {
	view.apply {
		setBackgroundColor(context.getColor(R.color.snackbarErrorText))
		setActionTextColor(context.getColor(R.color.snackbarErrorButton))
	}
	return this
}


fun Request.headerForApi(): Request {
	return header("Content-Type", "application/x-www-form-urlencoded")
}

class Utils {
	companion object {
		private var databaseSingleton: RecordDatabase? = null

		fun getRecordDao(context: Context): RecordDao {
			if (databaseSingleton == null) {
				databaseSingleton =
					Room.databaseBuilder(context, RecordDatabase::class.java, "records").build()
			}
			return databaseSingleton!!.recordDao()
		}

		fun formatSeconds(seconds: Double): String {
			val x = seconds.roundToLong()
			return if (x >= 3600) {
				"%02d:%02d:%02d".format(x / 3600, x % 3600 / 60, x % 60)
			} else {
				"%02d:%02d".format(x / 60, x % 60)
			}
		}

		fun millisToTime(millis: Long): LocalDateTime {
			return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
		}

		fun drawLine(context: Context, map: BaiduMap, points: List<StampedLocation>) {
			if (points.size < 2) return
			map.addOverlay(
				PolylineOptions()
					.width(MAP_LINE_WIDTH)
					.color(context.getColor(R.color.mapLine))
					.points(points.map { LatLng(it.latitude, it.longitude) })
			)
		}

		fun drawPoint(map: BaiduMap, point: StampedLocation, imagePath: String) {
			map.addOverlay(
				MarkerOptions()
					.position(LatLng(point.latitude, point.longitude))
					.icon(BitmapDescriptorFactory.fromAssetWithDpi(imagePath))
			)
		}

		fun compressString(s: String): ByteArray {
			ByteArrayOutputStream().use { buf ->
				GZIPOutputStream(buf).use { it.write(s.toUtf8Bytes()) }
				return buf.toByteArray()
			}
		}

		fun checkApiResponse(res: JsonElement): Boolean {
			return res.jsonObject["r"]?.contentOrNull == "1"
		}
	}
}
