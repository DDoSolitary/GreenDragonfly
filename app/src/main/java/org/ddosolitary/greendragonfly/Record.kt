package org.ddosolitary.greendragonfly

data class Record(
	val id: Int,
	var locations: List<StampedLocation>,
	var isUploaded: Boolean,
) {
	constructor(locations: List<StampedLocation>, isUploaded: Boolean) :
		this(0, locations, isUploaded)
}
