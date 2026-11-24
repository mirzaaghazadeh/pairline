package app.pairline.android.ui

import app.pairline.android.ui.setup.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    @Test
    fun makersAreDetectedFromManufacturerOrBrand() {
        assertEquals(Maker.XIAOMI, Maker.detect("Xiaomi", "Redmi"))
        assertEquals(Maker.XIAOMI, Maker.detect("", "POCO"))
        assertEquals(Maker.SAMSUNG, Maker.detect("samsung", "samsung"))
        assertEquals(Maker.OPPO, Maker.detect("OnePlus", "OnePlus"))
        assertEquals(Maker.OPPO, Maker.detect("realme", "realme"))
        assertEquals(Maker.VIVO, Maker.detect("vivo", "iQOO"))
        assertEquals(Maker.HUAWEI, Maker.detect("HONOR", "HONOR"))
        assertEquals(Maker.OTHER, Maker.detect("Google", "google"))
    }

    @Test
    fun onlyMakersWithKnownScreensHaveDeepLinks() {
        assertTrue(Maker.XIAOMI.screens.isNotEmpty())
        assertTrue(Maker.OPPO.screens.isNotEmpty())
        assertTrue(Maker.VIVO.screens.isNotEmpty())
        assertTrue(Maker.HUAWEI.screens.isNotEmpty())
        assertTrue(Maker.SAMSUNG.screens.isEmpty()) // App info → Battery is the right place there
        assertTrue(Maker.OTHER.screens.isEmpty())
    }

    @Test
    fun android11NoticeOnlyOnAndroid11() {
        assertTrue(SetupStep.Android11 in SetupStep.all(sdk = 30, maker = Maker.OTHER))
        assertFalse(SetupStep.Android11 in SetupStep.all(sdk = 31, maker = Maker.OTHER))
    }

    @Test
    fun makerStepOnlyForKnownMakers() {
        assertTrue(SetupStep.all(sdk = 34, maker = Maker.XIAOMI).any { it is SetupStep.Autostart })
        assertFalse(SetupStep.all(sdk = 34, maker = Maker.OTHER).any { it is SetupStep.Autostart })
    }

    @Test
    fun wizardStartsWithWelcomeAndEndsWithPairingThenDone() {
        val steps = SetupStep.all(sdk = 36, maker = Maker.SAMSUNG)
        assertEquals(SetupStep.Welcome, steps.first())
        assertEquals(SetupStep.Privacy, steps[1])
        assertEquals(SetupStep.Pair, steps[steps.size - 3])
        assertEquals(SetupStep.Talk, steps[steps.size - 2])
        assertEquals(SetupStep.Done, steps.last())
        // Android 9 can't relay call audio, so the optional Talk step is skipped there.
        assertFalse(SetupStep.Talk in SetupStep.all(sdk = 28, maker = Maker.OTHER))
        val battery = steps.indexOf(SetupStep.Battery)
        assertTrue(steps.subList(0, battery).any { it is SetupStep.Permission } || steps.none { it is SetupStep.Permission })
    }

    @Test
    fun ltrWrapsInIsolates() {
        assertEquals("⁦+1 555⁩", app.pairline.android.ui.components.ltr("+1 555"))
    }
}
