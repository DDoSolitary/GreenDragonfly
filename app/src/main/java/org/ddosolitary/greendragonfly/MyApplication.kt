package org.ddosolitary.greendragonfly

import android.app.Application
import com.baidu.mapapi.SDKInitializer
import com.bugsnag.android.Bugsnag

@Suppress("UNUSED")
class MyApplication : Application() {
	override fun onCreate() {
		super.onCreate()
		SDKInitializer.initialize(this)
		Bugsnag.start(this)
	}
}
