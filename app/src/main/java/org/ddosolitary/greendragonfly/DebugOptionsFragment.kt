package org.ddosolitary.greendragonfly

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class DebugOptionsFragment : PreferenceFragmentCompat() {
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		setPreferencesFromResource(R.xml.debug_pref, rootKey)
	}
}