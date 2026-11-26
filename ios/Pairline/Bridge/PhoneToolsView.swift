import SwiftUI

private func t(_ key: String) -> Text { Text(LocalizedStringKey(key), tableName: "PhoneTools") }

/// Bridge › Find phone (D-074): ring the Android phone loudly, even on silent, and switch its ringer.
struct PhoneToolsView: View {
    @EnvironmentObject private var bridge: BridgeController
    @State private var ringing = false
    @State private var busy = false
    @State private var pendingRinger: String?
    @State private var error: String?
    @State private var stopTask: Task<Void, Never>?

    private var connected: Bool { bridge.phase.isConnected }

    var body: some View {
        List {
            Section {
                Button {
                    ring(!ringing)
                } label: {
                    Label {
                        ringing ? t("Stop ringing") : t("Ring the Android phone")
                    } icon: {
                        Image(systemName: ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right.fill")
                    }
                }
                .disabled(busy || !connected || !bridge.has(.phoneFind))
            } footer: {
                if bridge.has(.phoneFind) {
                    t("It rings loudly for a minute, even on silent. Stop it here or on the Android phone.")
                } else {
                    Text(CapabilityInfo(.phoneFind).missingText)
                }
            }

            Section {
                Picker(selection: Binding(get: { pendingRinger ?? bridge.status?.ringer ?? "" },
                                          set: { setRinger($0) })) {
                    t("Sound").tag("normal")
                    t("Vibrate").tag("vibrate")
                    t("Silent").tag("silent")
                } label: {
                    t("Ringer")
                }
                .pickerStyle(.segmented)
                .disabled(busy || !connected || !bridge.has(.phoneRinger) || pendingRinger != nil)
            } header: {
                t("Android phone's ringer")
            } footer: {
                t("Silent needs Do Not Disturb access for Pairline Bridge: the Android phone asks for it the first time.")
            }

            if let error {
                Section { Text(verbatim: error).foregroundStyle(.red) }
            }
        }
        .navigationTitle(t("Find phone"))
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(bridge.$status) { status in
            if let pending = pendingRinger, status?.ringer == pending { pendingRinger = nil }
        }
        .onDisappear { stopTask?.cancel() }
    }

    private func ring(_ on: Bool) {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                _ = try await bridge.link.request(.phoneFind(on: on))
                ringing = on
                stopTask?.cancel()
                if on {
                    // The bridge stops by itself after a minute.
                    stopTask = Task {
                        try? await Task.sleep(nanoseconds: 60_000_000_000)
                        if !Task.isCancelled { ringing = false }
                    }
                }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func setRinger(_ mode: String) {
        guard mode != bridge.status?.ringer else { return }
        pendingRinger = mode
        error = nil
        Task {
            do {
                _ = try await bridge.link.request(.phoneRinger(mode))
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            } catch LinkError.bridge(let reply) where reply.code == "permission" {
                self.error = String(localized: "Allow Do Not Disturb access for Pairline Bridge on the Android phone (it shows a notification for it), then try again.", table: "PhoneTools")
            } catch {
                self.error = error.localizedDescription
            }
            pendingRinger = nil
        }
    }
}
