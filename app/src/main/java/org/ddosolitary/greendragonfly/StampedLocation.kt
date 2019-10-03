package org.ddosolitary.greendragonfly

import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.utils.DistanceUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.list

@Serializable
data class StampedLocation(
	val timeStamp: Long,
	val latitude: Double,
	val longitude: Double
) {
	companion object {
		fun getDuration(route: List<StampedLocation>): Double =
			if (route.isNotEmpty()) {
				(route.last().timeStamp - route.first().timeStamp) / 1000.0
			} else 0.0

		fun getDurationToNow(route: List<StampedLocation>): Double =
			if (route.isNotEmpty()) {
				(System.currentTimeMillis() - route.first().timeStamp) / 1000.0
			} else 0.0

		fun getDistance(route: List<StampedLocation>): Double {
			var sum = 0.0
			for (i in route.indices.drop(1)) {
				sum += DistanceUtil.getDistance(
					LatLng(route[i - 1].latitude, route[i - 1].longitude),
					LatLng(route[i].latitude, route[i].longitude)
				)
			}
			return sum
		}

		fun getAverageSpeed(route: List<StampedLocation>) =
			if (route.size >= 2) {
				getDistance(route) / getDuration(route)
			} else 0.0

		fun getCurrentSpeed(route: List<StampedLocation>) =
			if (route.size >= 2) {
				getAverageSpeed(route.subList(maxOf(route.size - 3, 0), route.size))
			} else 0.0

		fun jsonToList(json: String): List<StampedLocation> {
			return Json(JsonConfiguration.Stable).parse(serializer().list, json)
		}

		fun listToJson(list: List<StampedLocation>): String {
			return Json(JsonConfiguration.Stable).stringify(serializer().list, list)
		}
	}
}
