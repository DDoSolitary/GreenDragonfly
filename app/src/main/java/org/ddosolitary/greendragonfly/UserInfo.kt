package org.ddosolitary.greendragonfly

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

enum class Gender {
	Male,
	Female
}

@Serializable
data class RunningPlan(
	val name: String,
	val eventId: String,
	val attType: String,
	val minDistance: Double,
	val minSpeed: Double,
	val maxSpeed: Double,
	val maxTimesPerDay: Int,
	val startDate: Long,
	val endDate: Long,
	val startTime: Int,
	val endTime: Int,
	val weekDays: List<DayOfWeek>,
)

@Serializable
data class UserInfo(
	val studentId: String,
	val name: String,
	val gender: Gender,
	val admissionYear: String,
	val token: String,
	val classId: String,
	val schoolId: String,
	val schoolName: String,
	val apiUrl: String,
	val plan: RunningPlan?,
) {
	companion object {
		private var user: UserInfo? = null

		fun getUser(context: Context): UserInfo? {
			if (user == null) {
				context.getSharedPreferences(
					context.getString(R.string.pref_main),
					Context.MODE_PRIVATE
				).getString(context.getString(R.string.pref_key_user), null)?.let {
					try {
						user = Json.decodeFromString(serializer(), it)
					} catch (_: SerializationException) {
						// Probably it's from an older version, so just ignore it.
					}
				}
			}
			return user
		}

		fun saveUser(context: Context, user: UserInfo) {
			this.user = user
			val json = Json.encodeToString(serializer(), user)
			context.getSharedPreferences(
				context.getString(R.string.pref_main),
				Context.MODE_PRIVATE
			).edit {
				putString(context.getString(R.string.pref_key_user), json)
				apply()
			}
		}
	}
}
