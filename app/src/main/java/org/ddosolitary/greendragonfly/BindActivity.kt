package org.ddosolitary.greendragonfly

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import me.zhanghai.android.materialprogressbar.MaterialProgressBar

class BindActivity : AppCompatActivity() {
	private val vm by lazy { ViewModelProvider(this)[BindAccountViewModel::class.java] }
	private val nextButton
		get() = findViewById<Button>(R.id.button_next)
	private val cancelButton
		get() = findViewById<Button>(R.id.button_cancel)
	private val bindButton
		get() = findViewById<Button>(R.id.button_bind)
	private val backButton
		get() = findViewById<Button>(R.id.button_back)
	private val pager
		get() = findViewById<ViewPager2>(R.id.pager_bind)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_bind)
		setSupportActionBar(findViewById(R.id.toolbar))
		ViewModelProvider(this)[UpdateCheckerViewModel::class.java].checkUpdate()
		pager.apply {
			isUserInputEnabled = false
			adapter = object : FragmentStateAdapter(this@BindActivity) {
				override fun getItemCount(): Int = 2
				override fun createFragment(position: Int): Fragment = when (position) {
					0 -> BindLoginFragment()
					else -> UserInfoFragment()
				}
			}
			registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
					super.onPageSelected(position)
					when (position) {
						0 -> {
							bindButton.visibility = View.GONE
							backButton.visibility = View.GONE
							nextButton.visibility = View.VISIBLE
							cancelButton.visibility = View.VISIBLE
						}
						1 -> {
							bindButton.visibility = View.VISIBLE
							backButton.visibility = View.VISIBLE
							nextButton.visibility = View.GONE
							cancelButton.visibility = View.GONE
						}
					}
				}
			})
		}
		vm.isWorking.observe(this) {
			nextButton.isEnabled = !it
			cancelButton.isEnabled = !it
			bindButton.isEnabled = !it
			backButton.isEnabled = !it
			setProgressEnabled(it)
		}
		vm.fetchSchoolListException.observe(this) {
			if (it != null) {
				setProgressEnabled(false)
				getErrorSnackbar(getString(R.string.error_get_schools, it.localizedMessage)).apply {
					duration = Snackbar.LENGTH_INDEFINITE
					setAction(R.string.retry) { vm.fetchSchoolList() }
				}.show()
			}
		}
		vm.apiUserInfo.observe(this) {
			pager.setCurrentItem(1, true)
			ViewModelProvider(this@BindActivity)[UserInfoFragment.UserInfoViewModel::class.java]
				.user.value = vm.toUserInfo()
		}
		vm.fetchUserInfoError.observe(this) {
			getErrorSnackbar(getString(R.string.error_get_user, it)).show()
		}
		vm.bindAccountResult.observe(this) {
			if (it == null) {
				startActivity(Intent(this@BindActivity, MainActivity::class.java).apply {
					flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
					action = MainActivity.ACTION_UPDATE_USER
				})
				finish()
			} else {
				getErrorSnackbar(getString(R.string.error_bind, it.localizedMessage)).show()
			}
		}
		Utils.checkAndShowAbout(this)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		pager.setCurrentItem(0, false)
	}

	override fun onBackPressed() {
		if (pager.currentItem == 1) {
			pager.setCurrentItem(0, true)
		} else {
			super.onBackPressed()
		}
	}

	private fun getErrorSnackbar(msg: String): Snackbar {
		return Snackbar.make(pager, msg, Snackbar.LENGTH_LONG)
			.useErrorStyle(this)
	}

	fun onCancelClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		finish()
	}

	fun onBackClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		onBackPressed()
	}

	fun onNextClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		currentFocus?.windowToken?.let {
			(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
				.hideSoftInputFromWindow(it, 0)
		}
		if (vm.selectedSchool == null) {
			Snackbar.make(
				pager,
				R.string.school_not_selected,
				Snackbar.LENGTH_LONG
			).useErrorStyle(this).show()
			return
		}
		vm.fetchUserInfo()
	}

	fun onBindClicked(@Suppress("UNUSED_PARAMETER") view: View) {
		vm.bindAccount()
	}

	private fun setProgressEnabled(isEnabled: Boolean) {
		findViewById<MaterialProgressBar>(R.id.progress_bind).apply {
			visibility = if (isEnabled) View.VISIBLE else View.INVISIBLE
		}
	}
}
