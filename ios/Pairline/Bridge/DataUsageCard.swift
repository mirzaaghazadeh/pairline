import SwiftUI

/// The bridge phone's mobile data this month, with an optional monthly limit (D-084). The figure comes from Android's
/// own statistics through Usage access, which the user gives on the Android phone.
struct DataUsageCard: View {
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var settings: AppSettings
    @State private var loading = false
    @State private var editing = false

    var body: some View {
        if bridge.phase.isConnected || bridge.dataUsage != nil {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label {
                        Text("Mobile data", tableName: "Extras").font(.headline)
                    } icon: {
                        Image(systemName: "chart.bar")
                    }
                    Spacer()
                    Button {
                        editing = true
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                    .accessibilityLabel(Text("Data month and limit", tableName: "Extras"))
                }
                content
            }
            .card()
            .task { await load(force: false) }
            .sheet(isPresented: $editing, onDismiss: { Task { await load(force: true) } }) {
                DataLimitSheet()
            }
        }
    }

    @ViewBuilder private var content: some View {
        if let usage = bridge.dataUsage {
            let used = ByteCountFormatter.string(fromByteCount: usage.bytes, countStyle: .decimal)
            if settings.dataLimitGB > 0 {
                let limit = Double(settings.dataLimitGB) * 1_000_000_000
                let ratio = min(1, Double(usage.bytes) / limit)
                Text("\(used) of \(settings.dataLimitGB) GB", tableName: "Extras")
                    .font(.rounded(.title3, weight: .bold))
                ProgressView(value: ratio)
                    .tint(ratio >= 1 ? Color.brandRed : (ratio >= 0.8 ? Color.brandAmber : Color.brandGreen))
            } else {
                Text(verbatim: used).font(.rounded(.title3, weight: .bold))
            }
            Text("Since \(usage.since.formatted(date: .abbreviated, time: .omitted)), every SIM together.", tableName: "Extras")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else if bridge.dataUsageProblem == "permission" {
            Text("Allow Usage access for Pairline Bridge on the Android phone: it shows a notification that leads there.", tableName: "Extras")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            retryButton
        } else if bridge.dataUsageProblem == "unsupported" {
            Text(CapabilityInfo(.smsSchedule).missingText)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        } else {
            retryButton
        }
    }

    private var retryButton: some View {
        Button {
            Task { await load(force: true) }
        } label: {
            HStack {
                Spacer()
                if loading { ProgressView() } else { Text("Show mobile data", tableName: "Extras").font(.subheadline.weight(.semibold)) }
                Spacer()
            }
        }
        .buttonStyle(.bordered)
        .disabled(loading || !bridge.phase.isConnected)
    }

    private func load(force: Bool) async {
        loading = true
        defer { loading = false }
        await bridge.refreshDataUsage(force: force || bridge.dataUsage == nil && bridge.has(.dataUsage))
    }
}

/// The day the data month starts and the monthly limit (0 = none), on this iPhone.
private struct DataLimitSheet: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(selection: $settings.dataCycleDay) {
                        ForEach(1...28, id: \.self) { day in
                            Text(verbatim: "\(day)").tag(day)
                        }
                    } label: {
                        Text("Month starts on day", tableName: "Extras")
                    }
                    Stepper(value: $settings.dataLimitGB, in: 0...500) {
                        if settings.dataLimitGB == 0 {
                            Text("No limit", tableName: "Extras")
                        } else {
                            Text("Limit: \(settings.dataLimitGB) GB", tableName: "Extras")
                        }
                    }
                } footer: {
                    Text("With a limit, Pairline notifies you at 80% and at 100%, once each month.", tableName: "Extras")
                }
            }
            .navigationTitle(Text("Mobile data", tableName: "Extras"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
