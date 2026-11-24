import SwiftUI

struct CallsTab: View {
    enum Section: Hashable {
        case recents
        case keypad
    }

    @EnvironmentObject private var bridge: BridgeController
    @State private var section: Section = .recents

    var body: some View {
        NavigationStack {
            Group {
                switch section {
                case .recents: CallLogView()
                case .keypad: DialerView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Picker(selection: $section) {
                        Text("Recents").tag(Section.recents)
                        Text("Keypad").tag(Section.keypad)
                    } label: {
                        Text("Calls")
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 220)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    NavigationLink {
                        USSDView()
                    } label: {
                        Image(systemName: "number.square")
                    }
                    .accessibilityLabel(Text("USSD codes"))
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .navigationTitle(Text("Calls"))
        }
    }
}

struct CallLogView: View {
    enum Filter: Hashable {
        case all
        case missed
    }

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @EnvironmentObject private var calls: CallCenter
    @State private var items: [CallLogItem] = []
    @State private var filter: Filter = .all
    @State private var detail: Contact?
    @State private var confirmDelete: CallLogItem?
    @State private var confirmClear = false
    @State private var blockRequest: BlockRequest?

    private var shown: [CallLogItem] {
        filter == .all ? items : items.filter { $0.type == .missed || $0.type == .rejected }
    }

    var body: some View {
        List {
            Section {
                BridgeBanners()
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                Picker(selection: $filter) {
                    Text("All").tag(Filter.all)
                    Text("Missed").tag(Filter.missed)
                } label: {
                    Text("Show")
                }
                .pickerStyle(.segmented)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            }
            if shown.isEmpty {
                emptyState
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            } else {
                ForEach(shown) { item in
                    CallLogRow(item: item, showContact: { detail = $0 }) {
                        calls.startCall(number: item.number, sim: item.sim ?? bridge.defaultSim(forSMS: false))
                    }
                    .swipeActions(edge: .trailing) {
                        Button {
                            confirmDelete = item
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                        .tint(Color.brandRed)
                    }
                    .contextMenu {
                        if bridge.has(.block) && !item.number.isEmpty {
                            if bridge.isBlocked(item.number) {
                                Button {
                                    BlockActions.run(app: app, bridge: bridge, numbers: [item.number], block: false)
                                } label: { Label("Unblock", systemImage: "hand.raised.slash") }
                            } else {
                                Button(role: .destructive) {
                                    blockRequest = BlockRequest(numbers: [item.number])
                                } label: { Label("Block", systemImage: "hand.raised") }
                            }
                        }
                        Button(role: .destructive) {
                            confirmDelete = item
                        } label: { Label("Delete", systemImage: "trash") }
                    }
                }
            }
        }
        .listStyle(.plain)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                if !items.isEmpty {
                    Menu {
                        Button(role: .destructive) {
                            confirmClear = true
                        } label: { Label("Clear All Calls", systemImage: "trash") }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(Text("More"))
                }
            }
        }
        .blockPrompt($blockRequest)
        .confirmationDialog(Text("Delete this call?"),
                            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
                            titleVisibility: .visible, presenting: confirmDelete) { item in
            Button("Delete", role: .destructive) {
                confirmDelete = nil
                Task { report(await bridge.deleteCalls([item.key])) }
            }
        } message: { _ in
            deletionNote
        }
        .confirmationDialog(Text("Clear all calls?"), isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear All", role: .destructive) {
                Task { report(await bridge.clearCallHistory()) }
            }
        } message: {
            deletionNote
        }
        .navigationDestination(isPresented: Binding(get: { detail != nil }, set: { if !$0 { detail = nil } })) {
            if let detail { ContactDetailView(contact: detail) }
        }
        .refreshable { await bridge.sync() }
        .onAppear { items = db.calls() }
        .onValueChange(db.revision) { _ in items = db.calls() }
    }

    /// Says where deleted calls go before the user confirms (D-043).
    private var deletionNote: Text {
        if bridge.deletesCallsOnBridge {
            return Text("Deleted calls are also removed from the Android phone's call history.")
        } else if !bridge.phase.isConnected {
            return Text("The bridge phone isn't connected, so deleted calls are removed from this iPhone only. The Android phone keeps them.")
        } else if bridge.canUseLink && !bridge.has(.calllogDelete) {
            return Text("Deleted calls are removed from this iPhone only. To delete them on the Android phone too, allow the call history permission in Pairline Bridge.")
        } else {
            return Text("Deleted calls are removed from this iPhone only. The Android phone keeps them.")
        }
    }

    private func report(_ outcome: BridgeController.CallDeletion) {
        Haptics.success()
        if outcome == .bridgeFailed {
            app.toast = String(localized: "The bridge phone couldn't delete the calls, so they were removed from this iPhone only.")
        }
    }

    @ViewBuilder private var emptyState: some View {
        if bridge.status != nil && !bridge.has(.calllog) {
            EmptyStateView(symbol: "list.bullet.rectangle", title: Text("Call history is off"),
                           message: Text(CapabilityInfo(.calllog).missingText))
        } else if filter == .missed {
            EmptyStateView(symbol: "phone.badge.checkmark", title: Text("No missed calls"),
                           message: Text("You're all caught up."))
        } else {
            EmptyStateView(symbol: "phone", title: Text("No recent calls"),
                           message: Text("Calls on your Android phone's SIM will be listed here."))
        }
    }
}

struct CallLogRow: View {
    let item: CallLogItem
    var showContact: ((Contact) -> Void)?
    let call: () -> Void
    @EnvironmentObject private var db: AppDatabase
    @EnvironmentObject private var bridge: BridgeController

    private var name: String? { item.name ?? db.knownName(for: item.number) }
    private var isMissed: Bool { item.type == .missed || item.type == .rejected }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(isMissed ? Color.brandRed : .secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Group {
                    if let name {
                        Text(verbatim: name)
                    } else {
                        LTRText(item.number.isEmpty ? String(localized: "Unknown") : item.number)
                    }
                }
                .font(.body.weight(.semibold))
                .foregroundStyle(isMissed ? Color.brandRed : .primary)
                .lineLimit(1)
                HStack(spacing: 6) {
                    if bridge.isDualSIM, let sim = item.sim { SIMBadge(slot: sim) }
                    typeText
                    if item.duration > 0 {
                        Text(verbatim: "·")
                        Text(Formatters.duration(item.duration))
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            Text(Formatters.listTime(item.time))
                .font(.caption)
                .foregroundStyle(.secondary)
            if let showContact, let contact = db.contact(forNumber: item.number) {
                Button {
                    showContact(contact)
                } label: {
                    Image(systemName: "info.circle").foregroundStyle(Color.brandBlue)
                }
                .buttonStyle(.borderless)
                .frame(width: 28)
                .accessibilityLabel(Text("Contact details"))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: call)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(Text("Calls back"))
    }

    private var symbol: String {
        switch item.type {
        case .incoming: return "phone.arrow.down.left"
        case .outgoing: return "phone.arrow.up.right"
        case .missed: return "phone.down"
        case .rejected: return "phone.down.circle"
        case .blocked: return "nosign"
        case .voicemail: return "recordingtape"
        }
    }

    private var typeText: Text {
        switch item.type {
        case .incoming: return Text("Incoming")
        case .outgoing: return Text("Outgoing")
        case .missed: return Text("Missed")
        case .rejected: return Text("Declined")
        case .blocked: return Text("Blocked")
        case .voicemail: return Text("Voicemail")
        }
    }
}

struct DialerView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @EnvironmentObject private var calls: CallCenter
    @State private var number = ""
    @State private var sim: Int?
    @State private var ussdResult: USSDResultSheet.Item?
    @State private var runningUSSD = false

    private let keys: [(String, String)] = [
        ("1", " "), ("2", "ABC"), ("3", "DEF"),
        ("4", "GHI"), ("5", "JKL"), ("6", "MNO"),
        ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ"),
        ("*", ""), ("0", "+"), ("#", ""),
    ]

    var body: some View {
        VStack(spacing: 0) {
            BridgeBanners()
            Spacer(minLength: 8)
            VStack(spacing: 6) {
                Text(verbatim: number.isEmpty ? " " : number)
                    .font(.system(size: number.count > 13 ? 28 : 36, weight: .regular, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 24)
                    .contextMenu {
                        Button {
                            if let pasted = UIPasteboard.general.string { number = PhoneNumber.dialable(pasted) }
                        } label: { Label("Paste", systemImage: "doc.on.clipboard") }
                    }
                Group {
                    if let name = db.knownName(for: number), number.count >= 5 {
                        Text(verbatim: name)
                    } else if PhoneNumber.isUSSD(number) {
                        Text("USSD code")
                    } else {
                        Text(verbatim: " ")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(Color.brandBlue)
            }
            Spacer(minLength: 8)
            // The keypad keeps its phone layout in right-to-left languages.
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(84), spacing: 22), count: 3), spacing: 16) {
                ForEach(keys, id: \.0) { key in
                    DialKey(digit: key.0, letters: key.1) {
                        number.append(key.0)
                        Haptics.tap()
                    } onLongPress: {
                        number.append("+")
                        Haptics.tap()
                    }
                }
            }
            .environment(\.layoutDirection, .leftToRight)
            HStack(spacing: 22) {
                Group {
                    if bridge.isDualSIM {
                        simPicker
                    } else {
                        Color.clear
                    }
                }
                .frame(width: 84, height: 84)
                Button(action: primaryAction) {
                    ZStack {
                        Circle().fill(Color.brandGreen)
                        if runningUSSD {
                            ProgressView().tint(.white)
                        } else {
                            Image(systemName: PhoneNumber.isUSSD(number) ? "number" : "phone.fill")
                                .font(.system(size: 30, weight: .semibold))
                                .foregroundStyle(.white)
                        }
                    }
                    .frame(width: 78, height: 78)
                }
                .disabled(number.isEmpty || runningUSSD)
                .accessibilityLabel(PhoneNumber.isUSSD(number) ? Text("Run code") : Text("Call"))
                Button {
                    if !number.isEmpty { number.removeLast() }
                    Haptics.tap()
                } label: {
                    Image(systemName: "delete.left.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(.secondary)
                        .frame(width: 84, height: 84)
                }
                .opacity(number.isEmpty ? 0 : 1)
                .simultaneousGesture(LongPressGesture().onEnded { _ in number = "" })
                .accessibilityLabel(Text("Delete"))
            }
            .environment(\.layoutDirection, .leftToRight)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
        .onAppear {
            if sim == nil {
                sim = bridge.defaultSim(forSMS: false) ?? bridge.sims.first(where: { $0.defaultVoice == true })?.slot
            }
        }
        .sheet(item: $ussdResult) { item in
            USSDResultSheet(item: item)
        }
    }

    private var simPicker: some View {
        Menu {
            ForEach(bridge.sims) { info in
                Button {
                    sim = info.slot
                } label: {
                    if sim == info.slot {
                        Label(bridge.simLabel(info.slot), systemImage: "checkmark")
                    } else {
                        Text(bridge.simLabel(info.slot))
                    }
                }
            }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: "simcard").font(.title2)
                Text(bridge.simLabel(sim)).font(.caption2).lineLimit(1)
            }
            .foregroundStyle(Color.brandBlue)
        }
        .accessibilityLabel(Text("Choose SIM"))
    }

    private func primaryAction() {
        guard !number.isEmpty else { return }
        if PhoneNumber.isUSSD(number) {
            runningUSSD = true
            Task {
                defer { runningUSSD = false }
                do {
                    let result = try await bridge.runUSSD(code: number, sim: sim)
                    ussdResult = .init(code: number, result: result)
                } catch {
                    app.toast = error.localizedDescription
                }
            }
        } else {
            calls.startCall(number: number, sim: sim)
        }
    }
}

struct DialKey: View {
    let digit: String
    let letters: String
    let action: () -> Void
    var onLongPress: () -> Void = {}
    @State private var longPressed = false

    var body: some View {
        Button {
            // A long press on 0 types "+" instead; skip the tap that follows it.
            if longPressed {
                longPressed = false
            } else {
                action()
            }
        } label: {
            VStack(spacing: 0) {
                Text(verbatim: digit)
                    .font(.system(size: 34, weight: .regular, design: .rounded))
                if !letters.isEmpty {
                    Text(verbatim: letters)
                        .font(.system(size: 10, weight: .bold))
                        .tracking(1.5)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 78, height: 78)
            .background(Circle().fill(Color.secondary.opacity(0.13)))
        }
        .buttonStyle(DialKeyStyle())
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            guard digit == "0" else { return }
            longPressed = true
            onLongPress()
        })
        .accessibilityLabel(Text(verbatim: digit))
    }
}

private struct DialKeyStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.primary)
            .brightness(configuration.isPressed ? -0.08 : 0)
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
    }
}
