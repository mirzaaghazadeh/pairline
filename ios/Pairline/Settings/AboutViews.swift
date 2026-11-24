import SwiftUI

/// What Pairline does with data, in plain words (the full list is docs/PRIVACY.md and the website).
struct PrivacySummaryView: View {
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(Color.brandGreen)
                    Text("Nothing leaves your two phones")
                        .font(.title2.weight(.bold))
                    Text("Your messages, contacts, call history and codes travel only between your Android phone and this iPhone, encrypted end to end.")
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
            }
            Section {
                PrivacyRow(symbol: "server.rack", title: Text("No servers, no accounts"),
                           text: Text("Pairline has no backend. You never sign up."))
                PrivacyRow(symbol: "chart.bar.xaxis", title: Text("No analytics or ads"),
                           text: Text("No tracking, crash-reporting or advertising code is in the app."))
                PrivacyRow(symbol: "iphone", title: Text("Stored on this iPhone only"),
                           text: Text("Synced messages, contacts and call history are kept in a protected database that's excluded from backups. Unpairing deletes them."))
                PrivacyRow(symbol: "key.fill", title: Text("Keys stay in secure hardware"),
                           text: Text("This iPhone's pairing key lives in the Secure Enclave when available, otherwise in the Keychain, and never leaves the device."))
                PrivacyRow(symbol: "bag", title: Text("Apple handles purchases"),
                           text: Text("Buying Pairline goes through the App Store. The app checks Apple-signed transactions on the device."))
                PrivacyRow(symbol: "doc.text.magnifyingglass", title: Text("Readable logs"),
                           text: Text("The diagnostic log is stored here, has personal details removed, and is shared only if you choose to, after you've seen it."))
            } header: {
                Text("How it works")
            }
            Section {
                PrivacyRow(symbol: "antenna.radiowaves.left.and.right", title: Text("Bluetooth"),
                           text: Text("To connect to your bridge phone."))
                PrivacyRow(symbol: "camera", title: Text("Camera"), text: Text("Only to scan the pairing code."))
                PrivacyRow(symbol: "bell", title: Text("Notifications"), text: Text("For new messages, codes and bridge alerts."))
            } header: {
                Text("Permissions")
            }
            Section {
                Link(destination: AppInfo.privacyURL) {
                    Label("Full privacy policy", systemImage: "safari")
                }
            }
        }
        .navigationTitle(Text("Privacy"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct PrivacyRow: View {
    let symbol: String
    let title: Text
    let text: Text

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .foregroundStyle(Color.brandBlue)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                title.font(.body.weight(.semibold))
                text.font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 4)
    }
}

struct AboutView: View {
    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Image("BrandMark")
                        .resizable()
                        .frame(width: 96, height: 96)
                        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                        .shadow(color: .black.opacity(0.15), radius: 10, y: 5)
                    Text(verbatim: "Pairline").font(.title.weight(.bold))
                    Text("Your Android SIM, on your iPhone.")
                        .foregroundStyle(.secondary)
                    Text("Version \(AppInfo.version) (\(AppInfo.build))")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .listRowBackground(Color.clear)
            }
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: "FSL-1.1-ALv2").font(.headline)
                    Text("Pairline's source code is public so you can check what it does. Under the Functional Source License you may read, build and change it for any non-competing use. Each release becomes Apache 2.0 two years after it's published.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
                Link(destination: AppInfo.sourceURL) {
                    Label("Source code on GitHub", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            } header: {
                Text("Source code")
            }
            Section {
                Link(destination: AppInfo.privacyURL) {
                    Label("Privacy Policy", systemImage: "hand.raised")
                }
                Link(destination: AppInfo.termsURL) {
                    Label("Terms of Use", systemImage: "doc.text")
                }
                Link(destination: AppInfo.website) {
                    Label {
                        Text(verbatim: "getpairline.com")
                    } icon: {
                        Image(systemName: "globe")
                    }
                }
            }
            Section {
                Text("Pairline uses only Apple's frameworks: no third-party code.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                // Shown only where the typeface is used, so the screen doesn't name languages for everyone else.
                if AppFont.usesEstedad {
                    Text("Persian text is set in Estedad, an open-source typeface by the Estedad Project, under the SIL Open Font License 1.1.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Acknowledgements")
            }
        }
        .navigationTitle(Text("About"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
