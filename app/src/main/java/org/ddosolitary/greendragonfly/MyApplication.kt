package org.ddosolitary.greendragonfly

import android.app.Application
import com.baidu.mapapi.SDKInitializer

@Suppress("UNUSED")
class MyApplication : Application() {
	override fun onCreate() {
		super.onCreate()
		SDKInitializer.initialize(this)
	}
}
