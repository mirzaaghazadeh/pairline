import SwiftUI

/// Strings for choosing SIMs live in their own table, `SIM.xcstrings` (D-062).
private func t(_ key: String) -> Text { Text(LocalizedStringKey(key), tableName: "SIM") }

/// Which SIM new calls and messages from this iPhone use when the bridge phone has two (D-062). It's Pairline's own
/// choice, kept on this iPhone: Android's defaults stay as they are. "Same as the Android phone" follows Android's
/// default; where Android asks every time, the bridge uses the first active SIM, and this says which.
struct SIMDefaultsSection: View {
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        Section {
            picker(forSMS: false, selection: $settings.defaultCallSim)
            picker(forSMS: true, selection: $settings.defaultSMSSim)
        } header: {
            Text("SIM cards")
        } footer: {
            bridge.isDualSIM
                ? t("New calls and messages from this iPhone go out on these SIMs. You can still pick the other SIM for one call or message. Android’s own defaults don’t change.")
                : t("Only one SIM is active on the Android phone. With two, choose here which one calls and messages use.")
        }
    }

    private func picker(forSMS: Bool, selection: Binding<Int>) -> some View {
        Picker(selection: selection) {
            Text(verbatim: sameAsAndroid(forSMS: forSMS)).tag(-1)
            ForEach(bridge.sims) { sim in
                Text(verbatim: name(sim.slot)).tag(sim.slot)
            }
        } label: {
            Label(forSMS ? "SIM for messages" : "SIM for calls", systemImage: forSMS ? "message" : "phone")
        }
        .disabled(!bridge.isDualSIM)
    }

    private func sameAsAndroid(forSMS: Bool) -> String {
        guard let slot = bridge.androidDefaultSim(forSMS: forSMS) else {
            return String(localized: "Same as the Android phone", table: "SIM")
        }
        return String(format: String(localized: "Same as the Android phone (%@)", table: "SIM"), name(slot))
    }

    /// "SIM 2 · Carrier", or "SIM 2" when the bridge sends no name.
    private func name(_ slot: Int) -> String {
        let number = String(localized: "SIM \(slot + 1)")
        let label = bridge.simLabel(slot)
        return label == number ? number : "\(number) · \(label)"
    }
}
