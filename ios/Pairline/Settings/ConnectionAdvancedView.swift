import SwiftUI

/// Settings › Connection's first row: how the phones are connected right now, in plain words, with the next step
/// when it isn't fine (D-099).
struct ConnectionStatusRow: View {
    @ObservedObject var bridge: BridgeController
    var cloudOn: Bool

    var body: some View {
        let status = ConnectionStatus.of(bridge, cloudOn: cloudOn)
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: status.symbol)
                .font(.title3)
                .foregroundStyle(status.color)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                status.title
                    .font(.body.weight(.semibold))
                if let detail = status.detail {
                    detail
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        if bridge.phase == .bluetoothUnauthorized {
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            } label: {
                Label("Open Settings", systemImage: "gear")
            }
        }
    }
}

/// Settings › Connection › Advanced (D-099): everything technical about the connection, one level below the simple
/// switches. Cloud mode's router, port forwarding, home check and log, and which way Talk on iPhone's call audio
/// tries first.
struct ConnectionAdvancedView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        List {
            if RemoteFeature.isAvailable {
                if !app.isDemo, app.isPaired, let remote = app.link.remote {
                    CloudModeAdvancedSection(remote: remote, bridge: app.real)
                } else {
                    // Demo mode, or nothing paired yet: the guides only; the live controls need a real Android phone.
                    Section {
                        CloudModeInfoLink()
                        NavigationLink {
                            RouterHelpView(remote: nil)
                        } label: {
                            Label {
                                Text("Allow calls from far away", tableName: "RemoteMode")
                            } icon: {
                                Image(systemName: "wrench.and.screwdriver")
                            }
                        }
                        NavigationLink {
                            OwnServerGuideView()
                        } label: {
                            Label {
                                Text("Set up your own server", tableName: "RemoteMode")
                            } icon: {
                                Image(systemName: "server.rack")
                            }
                        }
                    } header: {
                        Text("Cloud mode", tableName: "RemoteMode")
                    } footer: {
                        Text("The router check, port forwarding and the connection log appear here once your Android phone is paired.", tableName: "Connection")
                    }
                }
            }
            // Talk on iPhone: which way a call's audio tries first (D-072). Bluetooth by default; the other is the fallback.
            Section {
                Picker(selection: $settings.callAudioPath) {
                    Text("Bluetooth", tableName: "CallAudio").tag(CallAudioPath.link)
                    Text("Wi-Fi", tableName: "CallAudio").tag(CallAudioPath.wifi)
                } label: {
                    Label { Text("Call audio", tableName: "CallAudio") } icon: { Image(systemName: "waveform") }
                }
            } header: {
                Text("Talk on iPhone", tableName: "Connection")
            } footer: {
                Text("With Talk on iPhone, a call's audio goes between your phones the way you choose here; if that one isn't connected, Pairline uses the other. Bluetooth works wherever the phones are near each other; Wi-Fi needs both on the same network or the Android phone's hotspot.", tableName: "CallAudio")
            }
            Section {
                NavigationLink {
                    HelpCenterView()
                } label: {
                    Label("Troubleshooting", systemImage: "questionmark.circle")
                }
                NavigationLink {
                    DiagnosticsView()
                } label: {
                    Label("Diagnostics", systemImage: "stethoscope")
                }
            }
        }
        .navigationTitle(Text("Advanced", tableName: "Connection"))
    }
}
