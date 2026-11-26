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
    fun wizardStartsWithLanguageThenWelcomeAndEndsWithPairingThenDone() {
        val steps = SetupStep.all(sdk = 36, maker = Maker.SAMSUNG)
        assertEquals(SetupStep.Language, steps.first())
        assertEquals(SetupStep.Welcome, steps[1])
        assertEquals(SetupStep.Privacy, steps[2])
        assertEquals(SetupStep.Pair, steps[steps.size - 3])
        assertEquals(SetupStep.Talk, steps[steps.size - 2])
        assertEquals(SetupStep.Done, steps.last())
        // Android 9 can't relay call audio, so the optional Talk step is skipped there.
        assertFalse(SetupStep.Talk in SetupStep.all(sdk = 28, maker = Maker.OTHER))
        val battery = steps.indexOf(SetupStep.Battery)
        assertTrue(steps.subList(0, battery).any { it is SetupStep.Permission } || steps.none { it is SetupStep.Permission })
    }

    @Test
    fun firstLanguageComesFromTheFirstKnownCountry() {
        // SIM, network, time zone, region: the first one known decides.
        assertEquals("fa", FirstLanguage.suggest(listOf("ir", "tr", "TR", "US"), "en"))
        assertEquals("tr", FirstLanguage.suggest(listOf("", null, "TR", "US"), "en"))
        assertEquals("ar", FirstLanguage.suggest(listOf(null, "ae", "AE", ""), "en"))
        assertEquals("fa", FirstLanguage.suggest(listOf("", "", "001", "AF"), "de"))
        // A US SIM keeps the phone's language even in Tehran's time zone.
        assertEquals(null, FirstLanguage.suggest(listOf("us", "us", "IR", "IR"), "en"))
        assertEquals(null, FirstLanguage.suggest(listOf(null, null, null, ""), "en"))
    }

    @Test
    fun firstLanguageKeepsAPhoneAlreadyInOneOfOurLanguages() {
        assertEquals(null, FirstLanguage.suggest(listOf("ir"), "tr"))
        assertEquals(null, FirstLanguage.suggest(listOf("tr"), "fa"))
        assertEquals(null, FirstLanguage.suggest(listOf("sa"), "ar"))
    }

    @Test
    fun ltrWrapsInIsolates() {
        assertEquals("⁦+1 555⁩", app.pairline.android.ui.components.ltr("+1 555"))
    }
}
