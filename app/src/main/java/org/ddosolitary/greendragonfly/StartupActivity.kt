package org.ddosolitary.greendragonfly

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class StartupActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (UserInfo.getUser(this) == null) {
			startActivity(Intent(this, BindActivity::class.java))
		} else {
			startActivity(Intent(this, MainActivity::class.java))
		}
		finish()
	}
}
