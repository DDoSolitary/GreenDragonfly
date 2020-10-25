package org.ddosolitary.greendragonfly

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bugsnag.android.Bugsnag
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class UpdateCheckerViewModel(app: Application) : AndroidViewModel(app) {
	companion object {
		private const val LOG_TAG = "UpdateCheckerViewModel"
	}

	fun checkUpdate() {
		viewModelScope.launch(Dispatchers.Main) {
			val context = getApplication<MyApplication>().applicationContext
			val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
			val pref = context.getSharedPreferences(context.getString(R.string.pref_main), Context.MODE_PRIVATE)
			val lastCheck = pref.getString(context.getString(R.string.pref_key_update_last_check), null)
			pref.edit {
				putString(context.getString(R.string.pref_key_update_last_check), today)
				apply()
			}
			if (lastCheck == today) return@launch
			val latestVersion: String
			val releaseUrl: String
			try {
				val json = Json.parseToJsonElement(
					context.getString(R.string.update_url).httpGet().awaitString()
				)
				json.jsonArray[0].jsonObject.let {
					latestVersion = it["tag_name"]!!.jsonPrimitive.content
					releaseUrl = it["html_url"]!!.jsonPrimitive.content
				}
			} catch (e: Exception) {
				Bugsnag.notify(e)
				Log.e(LOG_TAG, Log.getStackTraceString(e))
				return@launch
			}
			if (BuildConfig.VERSION_NAME == latestVersion.substring(1)) return@launch
			val mgr = NotificationManagerCompat.from(context)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				val channel = NotificationChannel(
					context.getString(R.string.update_channel_id),
					context.getString(R.string.update_channel_name),
					NotificationManager.IMPORTANCE_HIGH
				)
				mgr.createNotificationChannel(channel)
			}
			val pi = PendingIntent.getActivity(
				context,
				0,
				Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)),
				PendingIntent.FLAG_UPDATE_CURRENT,
			)
			val notification = NotificationCompat.Builder(context, context.getString(R.string.update_channel_id))
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setSmallIcon(R.drawable.ic_update_notification)
				.setContentTitle(context.getString(R.string.update_available_title))
				.setContentText(context.getString(R.string.update_available_message).format(latestVersion))
				.setContentIntent(pi)
				.setAutoCancel(true)
				.build()
			mgr.notify(context.resources.getInteger(R.integer.update_notification_id), notification)
		}
	}
}
