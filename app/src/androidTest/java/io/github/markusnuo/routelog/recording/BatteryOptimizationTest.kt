package io.github.markusnuo.routelog.recording

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.testing.MockGps
import io.github.markusnuo.routelog.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * routelog asks once for the battery optimisation exemption and, while it is missing, shows the
 * hint. The exemption is switched with the device-idle allow list, as the system dialog would do.
 */
@RunWith(AndroidJUnit4::class)
class BatteryOptimizationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val battery get() = AppGraph.get(context).battery
    private val store = ViewModelStore()
    private var askedBefore = false
    private var hiddenBefore = false
    private var exemptBefore = false

    @Before
    fun remember() {
        askedBefore = battery.asked
        hiddenBefore = battery.hintHidden
        exemptBefore = battery.isExempt()
    }

    @After
    fun restore() {
        // The view models first: their scopes must not outlive the test.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        battery.asked = askedBefore
        battery.hintHidden = hiddenBefore
        exempt(exemptBefore)
    }

    private fun exempt(on: Boolean) {
        MockGps.shell("cmd deviceidle whitelist ${if (on) "+" else "-"}${context.packageName}")
    }

    private fun viewModel(): MainViewModel {
        var viewModel: MainViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context as Application))[MainViewModel::class.java]
        }
        return viewModel!!
    }

    @Test
    fun askedOnceWhileOptimisedThenOnlyTheHint() {
        exempt(false)
        battery.asked = false
        battery.hintHidden = false
        val viewModel = viewModel()
        assertFalse(battery.isExempt())

        assertTrue("first recording: ask", viewModel.shouldAskForBatteryExemption())
        viewModel.batteryExemptionAsked()
        assertFalse("never asked twice on its own", viewModel.shouldAskForBatteryExemption())

        viewModel.refreshBattery()
        assertTrue("declined: the hint shows", viewModel.batteryHint.value)

        viewModel.hideBatteryHint()
        assertFalse(viewModel.batteryHint.value)
        assertTrue(battery.hintHidden)
    }

    @Test
    fun noQuestionAndNoHintOnceExempt() {
        exempt(true)
        battery.asked = true
        battery.hintHidden = false
        val viewModel = viewModel()
        assertTrue(battery.isExempt())
        assertFalse(viewModel.shouldAskForBatteryExemption())
        viewModel.refreshBattery()
        assertFalse(viewModel.batteryHint.value)
    }

    @Test
    fun theRequestIsTheSystemDialogForThisApp() {
        val intent = battery.requestIntent()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }
}
