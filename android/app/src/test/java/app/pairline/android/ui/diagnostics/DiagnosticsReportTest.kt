package app.pairline.android.ui.diagnostics

import app.pairline.android.link.BluetoothStatus
import app.pairline.android.link.LinkSnapshot
import app.pairline.android.link.TransportKind
import app.pairline.android.link.WifiLinkStatus
import app.pairline.android.protocol.WifiHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shareable report must never contain personal data, even if a log line slipped through (rule 9). */
class DiagnosticsReportTest {
    private fun facts(logLines: List<String>) = DiagnosticsFacts(
        appVersion = "0.1.0 (1)",
        buildType = "release",
        androidRelease = "16",
        sdk = 36,
        device = "Google Pixel 7",
        language = "fa",
        keyStorage = "keystore",
        paired = true,
        pairedAt = 1_764_000_000_000,
        peerApp = "0.1.0",
        peerOs = "18.1",
        link = LinkSnapshot(
            running = true, bluetooth = BluetoothStatus.ON, advertising = true, connected = true,
            transport = TransportKind.BLUETOOTH, transports = setOf(TransportKind.BLUETOOTH, TransportKind.WIFI),
            connectedSince = 1_764_000_100_000, wifi = WifiLinkStatus.LISTENING,
            wifiHint = WifiHint(listOf("192.168.1.23"), 47820, "AndroidShare_4821", "secret pass 1"),
        ),
        lastSeen = 1_764_000_100_000,
        batteryExempt = true,
        maker = "other",
        permissions = mapOf("sms" to true, "phone" to false),
        caps = listOf("sms.receive", "sms.send"),
        simCount = 2,
        companionBound = false,
        logLines = logLines,
    )

    @Test
    fun personalDataNeverAppears() {
        val report = DiagnosticsReport.build(
            facts(
                listOf(
                    "2026-11-24 10:00:00.000 I/sms: SMS from +989121234567 received",
                    "2026-11-24 10:00:01.000 I/ble: device AA:BB:CC:DD:EE:FF connected",
                    "2026-11-24 10:00:02.000 I/x: mail ada@example.com",
                    "2026-11-24 10:00:03.000 I/x: pairline://pair?v=0&id=obLD1OX2Bxg&k=BMFsdl&s=RckDaSOwfAzcD1hyQhuXOt7zUoCYIJD",
                ),
            ),
            now = 1_764_000_200_000,
        )
        assertFalse(report.contains("9121234567"))
        assertFalse(report.contains("AA:BB"))
        assertFalse(report.contains("ada@"))
        assertFalse(report.contains("RckDaSOw"))
        assertTrue(report.contains("[number]"))
        assertTrue(report.contains("[mac]"))
        assertTrue(report.contains("[email]"))
        assertTrue(report.contains("[pairing-link]"))
    }

    @Test
    fun usefulFactsStay() {
        val report = DiagnosticsReport.build(facts(listOf("2026-11-24 10:00:00.000 I/link: session established")), now = 1_764_000_200_000)
        for (expected in listOf(
            "App: 0.1.0 (1), release",
            "Android: 16 (API 36), Google Pixel 7",
            "Identity key: keystore",
            "iPhone app: 0.1.0, iOS 18.1",
            "Bluetooth: on, advertising: yes",
            "Connected: yes, via bluetooth + wifi",
            "Wi-Fi link: listening, local addresses: 1, hotspot: on",
            "Capabilities: sms.receive, sms.send",
            "Active SIMs: 2",
            "2026-11-24 10:00:00.000 I/link: session established",
        )) {
            assertTrue("missing: $expected\n$report", report.contains(expected))
        }
        assertFalse(report.contains("Debug TCP"))
        // The Wi-Fi addresses and the hotspot's name and passphrase stay out of a shared report.
        assertFalse(report.contains("192.168"))
        assertFalse(report.contains("AndroidShare"))
        assertFalse(report.contains("secret pass"))
    }
}
