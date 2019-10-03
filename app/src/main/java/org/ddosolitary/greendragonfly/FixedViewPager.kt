package org.ddosolitary.greendragonfly

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

class FixedViewPager(context: Context, attrs: AttributeSet?) : ViewPager(context, attrs) {
	override fun onTouchEvent(ev: MotionEvent?): Boolean = false
	override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
	override fun executeKeyEvent(event: KeyEvent): Boolean = false
}
