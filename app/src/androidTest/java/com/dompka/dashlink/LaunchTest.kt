package com.dompka.dashlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starting the app and tapping Connect, with Android's real permission dialogs and its real
 * "connect to device" prompt. Nothing is faked here except that there is no camera to find.
 */
@RunWith(AndroidJUnit4::class)
class LaunchTest {

    @Before
    fun setUp() = Kit.prepare()

    @After
    fun tearDown() {
        Kit.onMain { Link.disconnect() }
        Kit.shell("svc wifi enable")
    }

    private fun granted(permission: String) =
        Kit.ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Test
    fun launch_withAllPermissionsGranted() {
        Kit.grantAll()
        Kit.launch().use {
            assertTrue(Kit.text("status").startsWith("Not connected to the camera."))
            assertEquals("CONNECT", Kit.text("connect").uppercase())
            assertEquals(LinkState.IDLE, Link.state)
            // Nothing to complain about: no permission question, no notice.
            SystemClock.sleep(1500)
            assertEquals(null, Kit.device.findObject(By.res(Kit.PKG, "notice")))
            assertTrue(Report.missingPermissions(Kit.ctx).isEmpty())
        }
    }

    @Test
    fun launch_withAllPermissionsDenied() {
        Kit.launch().use { scenario ->
            // First start: Android asks for location, nearby devices and notifications. Say no to each.
            val asked = Kit.answerPermissionDialogs(allow = false)
            assertEquals("one question per permission group, got $asked", 3, asked.size)
            assertFalse(granted(Manifest.permission.ACCESS_FINE_LOCATION))
            assertFalse(granted(Manifest.permission.NEARBY_WIFI_DEVICES))
            assertFalse(granted(Manifest.permission.POST_NOTIFICATIONS))

            // The app is still there, says what is missing and what that costs, and offers to ask again.
            assertTrue(Kit.text("status").startsWith("Not connected to the camera."))
            val notice = Kit.text("notice")
            assertTrue(notice, notice.contains("Location") && notice.contains("Nearby devices") && notice.contains("Notifications"))
            assertEquals("ALLOW", Kit.text("notice_action").uppercase())

            // It does not nag: rebuilding the screen does not bring the system dialog back.
            scenario.recreate()
            Kit.view("status")
            SystemClock.sleep(1500)
            assertFalse("the permission question came back by itself", Kit.permissionDialogShowing())

            // "Allow" asks again, and granting this time clears the notice.
            Kit.tapUntil("notice_action", "Android's permission question") { Kit.permissionDialogShowing() }
            assertTrue(Kit.answerPermissionDialogs(allow = true).isNotEmpty())
            Kit.until("all permissions to be granted") { Report.missingPermissions(Kit.ctx).isEmpty() }
            Kit.until("the notice to go away") { Kit.device.findObject(By.res(Kit.PKG, "notice")) == null }
        }
    }

    /** Exactly one of the three is missing: the app starts, names exactly that one, and is otherwise unimpressed. */
    private fun launchWithOnlyOneDenied(missing: String, label: String) {
        val all = Report.wantedPermissions()
        for ((p, _) in all) {
            if (p != missing) Kit.instr.uiAutomation.grantRuntimePermission(Kit.PKG, p)
        }
        Kit.launch().use {
            val asked = Kit.answerPermissionDialogs(allow = false)
            assertEquals("asked only for the one that is missing, got $asked", 1, asked.size)
            assertFalse(granted(missing))
            assertEquals(listOf(label), Report.missingPermissions(Kit.ctx).map { it.second })
            val notice = Kit.text("notice")
            for ((_, other) in all) assertEquals("$label missing, notice: $notice", other == label, notice.contains(other))
            assertTrue(Kit.text("status").startsWith("Not connected to the camera."))
            assertEquals(LinkState.IDLE, Link.state)
        }
    }

    @Test
    fun launch_withOnlyLocationDenied() = launchWithOnlyOneDenied(Manifest.permission.ACCESS_FINE_LOCATION, "Location")

    @Test
    fun launch_withOnlyNearbyDevicesDenied() = launchWithOnlyOneDenied(Manifest.permission.NEARBY_WIFI_DEVICES, "Nearby devices")

    @Test
    fun launch_withOnlyNotificationsDenied() = launchWithOnlyOneDenied(Manifest.permission.POST_NOTIFICATIONS, "Notifications")

    @Test
    fun connect_withEmptyFields_doesNothing() {
        Kit.grantAll()
        Kit.launch().use {
            Kit.type("ssid", "")
            Kit.type("pass", "")
            Kit.tap("connect")
            Kit.holds("still idle", 2000) { Link.state == LinkState.IDLE && Link.core.requests == 0 }
            // A name but no password, and a password that is too short, are caught the same way.
            Kit.type("ssid", "MiVue")
            Kit.tap("connect")
            Kit.type("pass", "1234567")
            Kit.tap("connect")
            Kit.holds("still idle", 2000) { Link.state == LinkState.IDLE && Link.core.requests == 0 }
            assertEquals("CONNECT", Kit.text("connect").uppercase())
        }
    }

    @Test
    fun connect_withAPasswordAndroidCannotUse_doesNotCrash() {
        Kit.grantAll()
        Kit.launch().use {
            // The screen refuses it with a sentence...
            Kit.type("ssid", "MiVue")
            Kit.type("pass", "pässwörd-ñ")
            Kit.tap("connect")
            Kit.holds("still idle", 1500) { Link.state == LinkState.IDLE }
            // ...and if it ever got past the screen, Android's own exception is reported, not thrown.
            Kit.onMain { Link.connect(Kit.ctx, "MiVue", "pässwörd-ñ", false) }
            assertEquals(LinkState.NOT_FOUND, Link.state)
            assertTrue(Link.lastError!!, Link.lastError!!.contains("Android refused"))
            Kit.until("the reason to be shown") { Kit.text("status").contains("Android refused") }
            // The same for a name Android cannot search for.
            Kit.onMain { Link.connect(Kit.ctx, "", "12345678", true) }
            assertTrue(Link.state == LinkState.NOT_FOUND || Link.state == LinkState.SEARCHING)
            Kit.onMain { Link.disconnect() }
            assertEquals(LinkState.IDLE, Link.state)
        }
    }

    @Test
    fun connect_toACameraThatIsNotThere_promptDeclined_noRetryLoop() {
        Kit.grantAll()
        Kit.launch().use {
            Kit.connectThroughUi("DashLinkNoSuchCam", "12345678")
            Kit.until("the search to start") { Link.state == LinkState.SEARCHING }
            // Android's own "choose a device" prompt comes up. The user declines.
            Kit.tapAwayText("cancel", "com.android.settings", 30_000)
            Kit.until("Android to report the refusal") { Link.state == LinkState.NOT_FOUND }
            assertEquals(1, Link.core.requests)
            assertNotNull(Link.lastError)
            Kit.until("the status line to say so") { Kit.text("status").contains("declined") }
            assertEquals("CONNECT", Kit.text("connect").uppercase())
            // And that is the end of it: no second request, no prompt coming back.
            Kit.holds("no retry after a declined prompt", 8000) { Link.core.requests == 1 && Link.state == LinkState.NOT_FOUND }
            assertEquals(null, Kit.find("cancel", "com.android.settings"))
        }
    }

    @Test
    fun connect_toACameraThatIsNotThere_timesOut() {
        Kit.grantAll()
        Kit.onMain {
            Link.core.timeoutMs = 6000
            Link.core.promptTimeoutMs = 6000
        }
        Kit.launch().use {
            Kit.connectThroughUi("DashLinkNoSuchCam", "12345678")
            Kit.until("the search to start") { Link.state == LinkState.SEARCHING }
            // Nobody touches the prompt. After the time limit the app takes its request back.
            Kit.until("the time-out", 40_000) { Link.state == LinkState.NOT_FOUND }
            assertTrue(Link.lastError!!, Link.lastError!!.contains("found within 6 s"))
            assertEquals(1, Link.core.requests)
            // Android turns its prompt into a "request cancelled" box and waits for OK.
            Kit.dismissCancelledBox()
            Kit.until("the app to be usable again") { Kit.text("connect").uppercase() == "CONNECT" }
            assertTrue(Kit.text("status").contains("found within 6 s"))
            Kit.holds("no retry after a first-time time-out", 5000) { Link.core.requests == 1 && Link.state == LinkState.NOT_FOUND }
        }
    }

    @Test
    fun androidsPrompt_isLeftAloneWellPastASilentSearchesLimit() {
        // The prompt has a 30-second search and a "Try again" of its own. The app's limit for a silent search
        // (shortened to 5 s here) must not apply while the prompt is the one in charge.
        Kit.grantAll()
        Kit.onMain { Link.core.timeoutMs = 5000 }
        Kit.launch().use {
            Kit.connectThroughUi("DashLinkNoSuchCam", "12345678")
            Kit.until("the search to start") { Link.state == LinkState.SEARCHING }
            Kit.see("cancel", "com.android.settings", 30_000)
            Kit.holds("the prompt stays, the search goes on", 12_000) { Link.state == LinkState.SEARCHING && Link.core.requests == 1 }
            assertNotNull("the prompt is still Android's own, not a \"cancelled\" box", Kit.find("cancel", "com.android.settings"))
            Kit.tapAwayText("cancel", "com.android.settings")
            Kit.until("the refusal") { Link.state == LinkState.NOT_FOUND }
        }
    }

    @Test
    fun connect_withAllPermissionsDenied() {
        Kit.launch().use {
            assertEquals(3, Kit.answerPermissionDialogs(allow = false).size)
            Kit.connectThroughUi("DashLinkNoSuchCam", "12345678")
            // Whatever Android makes of a request without the permissions - a prompt, a refusal, an
            // exception - the app must end up in a state the user can act on, not crashed and not looping.
            Kit.until("an answer from Android") { Link.state == LinkState.SEARCHING || Link.state == LinkState.NOT_FOUND }
            if (Link.state == LinkState.SEARCHING) {
                Kit.tapAwayText("cancel", "com.android.settings", 30_000)
                Kit.until("the refusal") { Link.state == LinkState.NOT_FOUND }
            }
            assertEquals(1, Link.core.requests)
            Kit.holds("no retry", 5000) { Link.core.requests == 1 }
            assertTrue(Kit.text("notice").contains("Location"))
        }
    }

    @Test
    fun connect_withWifiSwitchedOff() {
        Kit.grantAll()
        Kit.shell("svc wifi disable")
        Kit.until("Wi-Fi to be off") { !Kit.wifi.isWifiEnabled }
        Kit.launch().use {
            // The screen notices before bothering Android...
            Kit.type("ssid", "DashLinkNoSuchCam")
            Kit.type("pass", "12345678")
            Kit.tap("connect")
            Kit.see(".*Wi-Fi is switched off.*")
            Kit.dialogButton(positive = false)
            assertEquals(LinkState.IDLE, Link.state)
            assertEquals(0, Link.core.requests)
            // ...and if Wi-Fi goes off at the wrong moment, Android's refusal is taken as final.
            Kit.onMain {
                Link.core.timeoutMs = 8000
                Link.connect(Kit.ctx, "DashLinkNoSuchCam", "12345678", true)
            }
            Kit.until("Android to refuse", 30_000) { Link.state == LinkState.NOT_FOUND }
            Kit.holds("no retry with Wi-Fi off", 5000) { Link.core.requests == 1 && Link.state == LinkState.NOT_FOUND }
        }
    }

    @Test
    fun disconnect_whileSearching_leavesNothingRunning() {
        Kit.grantAll()
        Kit.launch().use {
            Kit.connectThroughUi("DashLinkNoSuchCam", "12345678")
            Kit.until("the search to start") { Link.state == LinkState.SEARCHING }
            Kit.see("cancel", "com.android.settings", 30_000)
            // The app withdraws its request. Android answers with its "request cancelled" box.
            Kit.onMain { Link.disconnect() }
            assertEquals(LinkState.IDLE, Link.state)
            Kit.until("the prompt to stop offering a choice", 15_000) { Kit.find("cancel", "com.android.settings") == null }
            Kit.dismissCancelledBox()
            Kit.until("the button to say Connect again") { Kit.text("connect").uppercase() == "CONNECT" }
            Kit.holds("nothing comes back", 4000) { Link.state == LinkState.IDLE && Link.core.requests == 1 }
        }
    }
}
