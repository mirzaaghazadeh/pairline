package app.pairline.android.privileged

import app.pairline.android.privileged.PrivilegedManager.Failure
import app.pairline.android.privileged.PrivilegedManager.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `status.talk` (D-107; PROTOCOL.md "Talk on iPhone's state") and the default-on migration. */
class TalkStatusTest {
    private fun of(
        state: State,
        switchOn: Boolean = true,
        paired: Boolean = false,
        rootMode: Boolean = false,
        setUpBefore: Boolean = false,
        onWifi: Boolean = true,
        failure: Failure? = null,
        sdk: Int = 34,
    ) = TalkStatus.of(sdk, state, failure, switchOn, paired, rootMode, setUpBefore, onWifi)

    @Test
    fun android10OrOlderIsUnsupported() {
        assertEquals(TalkStatus(TalkStatus.UNSUPPORTED), of(State.UNSUPPORTED, sdk = 29))
        assertEquals(TalkStatus(TalkStatus.UNSUPPORTED), of(State.NEEDS_PAIRING, sdk = 29))
    }

    @Test
    fun neverSetUpAsksForTheSetup() {
        for (s in listOf(State.NEEDS_DEVELOPER_OPTIONS, State.NEEDS_WIRELESS_DEBUGGING, State.NEEDS_PAIRING)) {
            assertEquals(s.name, TalkStatus(TalkStatus.SETUP), of(s))
            // The switch off doesn't make it "off" before it was ever set up.
            assertEquals(s.name, TalkStatus(TalkStatus.SETUP), of(s, switchOn = false))
        }
    }

    @Test
    fun readyAndStarting() {
        assertEquals(TalkStatus(TalkStatus.READY), of(State.READY, paired = true))
        assertEquals(TalkStatus(TalkStatus.STARTING), of(State.STARTING, paired = true))
        // IDLE with the switch on: the helper starts right after.
        assertEquals(TalkStatus(TalkStatus.STARTING), of(State.IDLE, paired = true))
    }

    @Test
    fun setUpAndSwitchedOffIsOff() {
        assertEquals(TalkStatus(TalkStatus.OFF), of(State.IDLE, switchOn = false, paired = true))
        // The setup screen runs the helper for its checks even with the switch off.
        assertEquals(TalkStatus(TalkStatus.OFF), of(State.READY, switchOn = false, paired = true))
        assertEquals(TalkStatus(TalkStatus.OFF), of(State.NEEDS_WIRELESS_DEBUGGING, switchOn = false, paired = true))
        assertEquals(TalkStatus(TalkStatus.OFF), of(State.IDLE, switchOn = false, rootMode = true))
    }

    @Test
    fun setUpButDebuggingOffWaitsForWifiOrDebugging() {
        assertEquals(
            TalkStatus(TalkStatus.WAITING, TalkStatus.REASON_WIFI),
            of(State.NEEDS_WIRELESS_DEBUGGING, paired = true, onWifi = false),
        )
        assertEquals(
            TalkStatus(TalkStatus.WAITING, TalkStatus.REASON_DEBUGGING),
            of(State.NEEDS_WIRELESS_DEBUGGING, paired = true, onWifi = true),
        )
        assertEquals(
            TalkStatus(TalkStatus.WAITING, TalkStatus.REASON_DEBUGGING),
            of(State.NEEDS_DEVELOPER_OPTIONS, paired = true),
        )
    }

    @Test
    fun aLostPairingAsksToPairAgain() {
        assertEquals(
            TalkStatus(TalkStatus.FAILED, TalkStatus.REASON_PAIRING),
            of(State.NEEDS_PAIRING, setUpBefore = true),
        )
        assertEquals(
            TalkStatus(TalkStatus.FAILED, TalkStatus.REASON_PAIRING),
            of(State.NEEDS_WIRELESS_DEBUGGING, setUpBefore = true),
        )
        assertEquals(TalkStatus(TalkStatus.OFF), of(State.NEEDS_PAIRING, switchOn = false, setUpBefore = true))
    }

    @Test
    fun failuresNameTheirReason() {
        assertEquals(
            TalkStatus(TalkStatus.FAILED, TalkStatus.REASON_ROOT),
            of(State.FAILED, rootMode = true, failure = Failure.NO_ROOT),
        )
        assertEquals(
            TalkStatus(TalkStatus.FAILED, TalkStatus.REASON_DEBUGGING),
            of(State.FAILED, paired = true, failure = Failure.NO_SERVICE),
        )
        assertEquals(
            TalkStatus(TalkStatus.FAILED, TalkStatus.REASON_OTHER),
            of(State.FAILED, paired = true, failure = Failure.NO_HELPER),
        )
        assertEquals(TalkStatus(TalkStatus.FAILED, TalkStatus.REASON_OTHER), of(State.FAILED, paired = true))
        assertEquals(TalkStatus(TalkStatus.OFF), of(State.FAILED, switchOn = false, paired = true, failure = Failure.OTHER))
    }

    @Test
    fun jsonCarriesStateAndReason() {
        val ready = TalkStatus(TalkStatus.READY).toJson()
        assertEquals("ready", ready.getString("state"))
        assertEquals(true, ready.isNull("reason"))
        val waiting = TalkStatus(TalkStatus.WAITING, TalkStatus.REASON_WIFI).toJson()
        assertEquals("waiting", waiting.getString("state"))
        assertEquals("wifi", waiting.getString("reason"))
    }

    @Test
    fun onByDefaultFromAndroid11() {
        assertEquals(true, TalkDefault.on(30))
        assertEquals(true, TalkDefault.on(36))
        assertEquals(false, TalkDefault.on(29))
    }

    @Test
    fun migrationKeepsASetUpPhoneWithAnUntouchedSwitchOff() {
        // Set up (paired or root mode) but the switch never touched: it stays off, as before D-107.
        assertEquals(false, TalkDefault.migrate(stored = null, paired = true, rootMode = false))
        assertEquals(false, TalkDefault.migrate(stored = null, paired = false, rootMode = true))
        // Never set up: the new default (on) applies.
        assertNull(TalkDefault.migrate(stored = null, paired = false, rootMode = false))
        // The user's own choice is never changed.
        assertNull(TalkDefault.migrate(stored = true, paired = true, rootMode = false))
        assertNull(TalkDefault.migrate(stored = false, paired = false, rootMode = false))
    }
}
