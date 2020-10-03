package org.ddosolitary.greendragonfly

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.edit
import androidx.room.Room
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.github.kittinunf.fuel.core.Request
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToLong

private const val MAP_LINE_WIDTH = 10
fun Snackbar.useErrorStyle(context: Context): Snackbar {
	view.apply {
		setTextColor(context.getColor(R.color.textLight))
		setBackgroundColor(context.getColor(R.color.snackbarErrorBackground))
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
					Room.databaseBuilder(context, RecordDatabase::class.java, "records")
						.addMigrations(RecordDatabase.MIGRATION_1_2)
						.build()
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
				GZIPOutputStream(buf).use { it.write(s.encodeToByteArray()) }
				return buf.toByteArray()
			}
		}

		fun checkApiResponse(res: JsonElement): Boolean {
			return res.jsonObject["r"]?.jsonPrimitive?.contentOrNull == "1"
		}

		fun showAboutDialog(context: Context) {
			val layout = FrameLayout(context)
			val webView = WebView(context).apply {
				layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
					val margin = context.resources.getDimensionPixelSize(R.dimen.margin_medium)
					leftMargin = margin
					rightMargin = margin
					topMargin = margin
				}
				if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) &&
					resources.configuration.isNightModeActive) {
					WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
				}
				loadUrl("file:///android_asset/about.html")
			}
			MaterialAlertDialogBuilder(context)
				.setTitle(R.string.about)
				.setView(layout.apply { addView(webView) })
				.setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
				.show()
		}

		fun checkAndShowAbout(context: Context) {
			val pref = context.getSharedPreferences(
				context.getString(R.string.pref_main), Context.MODE_PRIVATE
			)
			val keyName = context.getString(R.string.pref_key_is_first_run)
			if (pref.getBoolean(keyName, true)) {
				pref.edit {
					putBoolean(keyName, false)
					apply()
				}
				showAboutDialog(context)
			}
		}

		@ColorInt
		fun resolveColorAttr(context: Context, @AttrRes id: Int): Int {
			val res = TypedValue()
			context.theme.resolveAttribute(id, res, true)
			return context.getColor(if (res.resourceId != 0) res.resourceId else res.data)
		}
	}
}
