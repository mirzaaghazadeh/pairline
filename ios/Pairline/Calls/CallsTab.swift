import SwiftUI

struct CallsTab: View {
    enum Section: Hashable {
        case recents
        case keypad
    }

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var section: Section = .recents

    var body: some View {
        if sizeClass == .regular {
            // A large display: recent calls and the keypad side by side, with the gap on iPhone Duo's fold so no key
            // sits on it (D-063). One navigation bar, its title on the leading side rather than over the fold.
            NavigationStack {
                FoldAwarePanes(axes: .horizontal) {
                    CallLogView()
                } second: {
                    DialerView()
                } single: {
                    sections
                }
                .toolbar { ussdItem }
                .navigationTitle(Text("Calls"))
                .navigationBarTitleDisplayMode(.large)
            }
        } else {
            phone
        }
    }

    private var ussdItem: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            NavigationLink {
                USSDView()
            } label: {
                // A title as well as the symbol, for overflow menus and iPhone Duo's side bar (D-063).
                Label("USSD codes", systemImage: "number.square")
            }
        }
    }

    private var phone: some View {
        NavigationStack {
            sections
                .toolbar { ussdItem }
                .navigationBarTitleDisplayMode(.inline)
                .navigationTitle(Text("Calls"))
        }
    }

    /// Recents or the keypad, switched in the bar: on a phone, and on a large display too narrow for both.
    private var sections: some View {
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
        }
        .onReceive(app.$dialerPrefill) { if $0 != nil { section = .keypad } }
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
                    // Asks first (the dialog below); the ellipsis is left to the system's overflow menu.
                    Button(role: .destructive) {
                        confirmClear = true
                    } label: { Label("Clear All Calls", systemImage: "trash") }
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

    /// One column on a phone. On a large display (iPhone Duo open, iPad) the number gets one pane and the keys the other,
    /// on either side of the fold, so no key lands in its curve; the keys shrink to fit when the pane is short.
    var body: some View {
        FoldAwarePanes {
            VStack(spacing: 0) {
                BridgeBanners()
                Spacer(minLength: 8)
                numberDisplay
                Spacer(minLength: 8)
            }
        } second: {
            GeometryReader { proxy in
                let key = Self.keySize(fitting: proxy.size)
                VStack(spacing: 0) {
                    Spacer(minLength: 8)
                    keypad(key: key)
                    callRow(key: key)
                        .padding(.top, 12 * key / Self.fullKey)
                        .padding(.bottom, 16)
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
            }
        } single: {
            // One column: full-size keys on a phone; smaller ones in a short space, like half of iPhone Duo (D-063).
            GeometryReader { proxy in
                let key = min(Self.fullKey, Self.keySize(fitting: CGSize(width: proxy.size.width,
                                                                         height: proxy.size.height - Self.numberHeight)))
                VStack(spacing: 0) {
                    BridgeBanners()
                    Spacer(minLength: 8)
                    numberDisplay
                    Spacer(minLength: 8)
                    keypad(key: key)
                    callRow(key: key)
                        .padding(.top, 12 * key / Self.fullKey)
                        .padding(.bottom, 24 * key / Self.fullKey)
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
        .onAppear {
            if sim == nil {
                sim = bridge.effectiveSim(forSMS: false)
            }
        }
        // A `pairline://call` link fills in the number; the user still taps Call (D-074).
        .onReceive(app.$dialerPrefill) { prefill in
            guard let prefill else { return }
            number = prefill
            app.dialerPrefill = nil
        }
        .sheet(item: $ussdResult) { item in
            USSDResultSheet(item: item)
        }
    }

    /// The size of a key on a phone.
    static let fullKey: CGFloat = 78
    /// Room the number, its name line, the SIM picker and the banners take above the keys in one column.
    static let numberHeight: CGFloat = 150

    /// The largest key, up to a phone's, that fits four rows of keys and the call row in `size`.
    static func keySize(fitting size: CGSize) -> CGFloat {
        // Height: 5 key rows plus spacing that scales with the key (3 × 16 + 12) and 30 pt of fixed padding.
        let byHeight = (size.height - 30) / (5 + 60 / fullKey)
        // Width: 3 keys of key + 6 plus 2 × 22 scaled spacing, with 24 pt to spare.
        let byWidth = (size.width - 42) / (3 + 44 / fullKey)
        return max(44, min(fullKey, byHeight, byWidth))
    }

    private var numberDisplay: some View {
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
    }

    /// The keys, `key` points wide. The keypad keeps its phone layout in right-to-left languages.
    private func keypad(key: CGFloat) -> some View {
        let scale = key / Self.fullKey
        return LazyVGrid(columns: Array(repeating: GridItem(.fixed(key + 6), spacing: 22 * scale), count: 3),
                         spacing: 16 * scale) {
            ForEach(keys, id: \.0) { item in
                DialKey(digit: item.0, letters: item.1, size: key) {
                    number.append(item.0)
                    Haptics.tap()
                } onLongPress: {
                    Haptics.tap()
                    // Touch and hold 1 calls voicemail on an empty keypad, like a phone's own (D-082).
                    if item.0 == "1" {
                        if number.isEmpty { calls.startVoicemailCall(sim: sim) } else { number.append("1") }
                    } else {
                        number.append("+")
                    }
                }
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }

    /// SIM choice, call (or run the USSD code) and delete, under the keys.
    private func callRow(key: CGFloat) -> some View {
        let scale = key / Self.fullKey
        return HStack(spacing: 22 * scale) {
            Group {
                if bridge.isDualSIM {
                    simPicker
                } else {
                    Color.clear
                }
            }
            .frame(width: key + 6, height: key + 6)
            Button(action: primaryAction) {
                ZStack {
                    Circle().fill(Color.brandGreen)
                    if runningUSSD {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: PhoneNumber.isUSSD(number) ? "number" : "phone.fill")
                            .font(.system(size: 30 * scale, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
                .frame(width: key, height: key)
            }
            .disabled(number.isEmpty || runningUSSD)
            .accessibilityLabel(PhoneNumber.isUSSD(number) ? Text("Run code") : Text("Call"))
            // Hide the caller ID on this call only: the GSM "#31#" prefix (D-082).
            .contextMenu {
                if !number.isEmpty, !PhoneNumber.isUSSD(number), !number.hasPrefix("#31#") {
                    Button {
                        calls.startCall(number: "#31#" + number, sim: sim)
                    } label: {
                        Label {
                            Text("Call with my number hidden", tableName: "Carrier")
                        } icon: {
                            Image(systemName: "eye.slash")
                        }
                    }
                }
            }
            Button {
                if !number.isEmpty { number.removeLast() }
                Haptics.tap()
            } label: {
                Image(systemName: "delete.left.fill")
                    .font(.system(size: 26 * scale))
                    .foregroundStyle(.secondary)
                    .frame(width: key + 6, height: key + 6)
            }
            .opacity(number.isEmpty ? 0 : 1)
            .simultaneousGesture(LongPressGesture().onEnded { _ in number = "" })
            .accessibilityLabel(Text("Delete"))
        }
        .environment(\.layoutDirection, .leftToRight)
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
    /// The key's width and height; the labels scale with it.
    var size: CGFloat = DialerView.fullKey
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
                    .font(.system(size: 34 * size / DialerView.fullKey, weight: .regular, design: .rounded))
                if !letters.isEmpty {
                    Text(verbatim: letters)
                        .font(.system(size: max(8, 10 * size / DialerView.fullKey), weight: .bold))
                        .tracking(1.5)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: size, height: size)
            .background(Circle().fill(Color.secondary.opacity(0.13)))
        }
        .buttonStyle(DialKeyStyle())
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            guard digit == "0" || digit == "1" else { return }
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
