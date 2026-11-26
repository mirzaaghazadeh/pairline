import SwiftUI

struct ThreadView: View {
    let thread: Int64
    /// Set when the conversation is the detail pane of Messages' list-and-conversation layout on a large display
    /// (D-063): the tab bar stays, and deleting the conversation calls this instead of going back.
    var closeInSplitView: (() -> Void)?
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @EnvironmentObject private var calls: CallCenter
    @ObservedObject private var notifier = Notifier.shared
    @State private var messages: [StoredMessage] = []
    @State private var conversation: Conversation?
    @State private var loadingOlder = false
    @State private var lastCount = 0
    @State private var confirmDeleteMessage: StoredMessage?
    @State private var confirmDeleteConversation = false
    @State private var blockRequest: BlockRequest?
    @Environment(\.dismiss) private var dismiss

    private var address: String { conversation?.primaryAddress ?? messages.last?.addr ?? "" }
    private var name: String { db.displayName(for: address) }
    private var isBlocked: Bool { bridge.isBlocked(address) }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 3) {
                    if isBlocked {
                        InfoBanner(symbol: "hand.raised.fill", tint: .brandRed,
                                   text: Text("You blocked this number. Its texts and calls don't reach this iPhone.")) {
                            Button("Unblock") {
                                BlockActions.run(app: app, bridge: bridge, numbers: [address], block: false)
                            }
                            .font(.subheadline.weight(.semibold))
                        }
                        .padding(.top, 8)
                    }
                    if conversation?.hasOlder == true, thread >= 0, bridge.phase.isConnected {
                        olderLoader
                    }
                    ForEach(Array(groupedByDay.enumerated()), id: \.offset) { _, group in
                        Text(Formatters.dayHeader(group.date))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .padding(.top, 14)
                            .padding(.bottom, 6)
                        ForEach(Array(group.messages.enumerated()), id: \.element.key) { index, message in
                            let next = index + 1 < group.messages.count ? group.messages[index + 1] : nil
                            MessageBubble(message: message, isLastInGroup: next?.isOutgoing != message.isOutgoing,
                                          simLabel: bridge.isDualSIM ? message.sim : nil,
                                          onDelete: { confirmDeleteMessage = message })
                                .id(message.key)
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom) {
                Composer(thread: thread, address: address) {
                    withAnimation { proxy.scrollTo(messages.last?.key, anchor: .bottom) }
                }
            }
            .onAppear {
                reload()
                proxy.scrollTo(messages.last?.key, anchor: .bottom)
                Notifier.shared.visibleThread = thread
                Notifier.shared.removeDelivered(thread: thread)
                bridge.markRead(thread: thread)
                if messages.isEmpty { Task { await loadOlder() } }
            }
            .onDisappear {
                if Notifier.shared.visibleThread == thread { Notifier.shared.visibleThread = nil }
            }
            .onValueChange(db.revision) { _ in
                reload()
                if messages.count != lastCount {
                    lastCount = messages.count
                    withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(messages.last?.key, anchor: .bottom) }
                    bridge.markRead(thread: thread)
                }
            }
        }
        .background(Color(.systemBackground))
        .toolbar(closeInSplitView == nil ? .hidden : .automatic, for: .tabBar)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    titleText.font(.headline).lineLimit(1)
                    if name != address {
                        LTRText(address).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if PhoneNumber.isPhoneNumber(address) {
                    Button {
                        calls.startCall(number: address, sim: bridge.defaultSim(forSMS: false))
                    } label: {
                        // A title as well as the symbol, for overflow menus and iPhone Duo's side bar (D-063).
                        Label("Call", systemImage: "phone")
                    }
                }
                Menu {
                    Button {
                        notifier.setMuted(thread, !notifier.isMuted(thread))
                    } label: {
                        if notifier.isMuted(thread) {
                            Label { Text("Unmute", tableName: "PhoneTools") } icon: { Image(systemName: "bell") }
                        } else {
                            Label { Text("Mute", tableName: "PhoneTools") } icon: { Image(systemName: "bell.slash") }
                        }
                    }
                    if bridge.has(.block) && !address.isEmpty {
                        if isBlocked {
                            Button {
                                BlockActions.run(app: app, bridge: bridge, numbers: [address], block: false)
                            } label: { Label("Unblock", systemImage: "hand.raised.slash") }
                        } else {
                            Button(role: .destructive) {
                                blockRequest = BlockRequest(numbers: [address])
                            } label: { Label("Block", systemImage: "hand.raised") }
                        }
                    }
                    Button(role: .destructive) {
                        confirmDeleteConversation = true
                    } label: { Label("Delete Conversation", systemImage: "trash") }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                }
            }
        }
        .blockPrompt($blockRequest)
        .confirmationDialog(Text("Delete from this iPhone?"), isPresented: $confirmDeleteConversation,
                            titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                bridge.deleteConversations([thread])
                Haptics.success()
                if let closeInSplitView { closeInSplitView() } else { dismiss() }
            }
        } message: {
            Text("The messages stay on your Android phone, because Android lets only its default messaging app delete texts. A new message brings the conversation back here.")
        }
        .confirmationDialog(Text("Delete this message from this iPhone?"),
                            isPresented: Binding(get: { confirmDeleteMessage != nil },
                                                 set: { if !$0 { confirmDeleteMessage = nil } }),
                            titleVisibility: .visible, presenting: confirmDeleteMessage) { message in
            Button("Delete", role: .destructive) {
                bridge.deleteMessages([message.key])
                confirmDeleteMessage = nil
            }
        } message: { _ in
            Text("It stays on your Android phone, because Android lets only its default messaging app delete texts.")
        }
    }

    private var titleText: Text {
        PhoneNumber.isPhoneNumber(name) ? Text(verbatim: PhoneNumber.ltr(name)) : Text(verbatim: name)
    }

    private var olderLoader: some View {
        HStack {
            Spacer()
            if loadingOlder {
                ProgressView()
            } else {
                Button("Load earlier messages") { Task { await loadOlder() } }
                    .font(.footnote.weight(.semibold))
            }
            Spacer()
        }
        .padding(.vertical, 10)
    }

    private func reload() {
        messages = db.messages(thread: thread)
        conversation = db.conversation(thread: thread)
        if lastCount == 0 { lastCount = messages.count }
    }

    private func loadOlder() async {
        guard !loadingOlder else { return }
        loadingOlder = true
        await bridge.loadOlder(thread: thread)
        loadingOlder = false
    }

    private var groupedByDay: [(date: Date, messages: [StoredMessage])] {
        let calendar = Calendar.current
        var groups: [(date: Date, messages: [StoredMessage])] = []
        for message in messages {
            if let last = groups.last, calendar.isDate(last.date, inSameDayAs: message.time) {
                groups[groups.count - 1].messages.append(message)
            } else {
                groups.append((message.time, [message]))
            }
        }
        return groups
    }
}

struct MessageBubble: View {
    let message: StoredMessage
    let isLastInGroup: Bool
    let simLabel: Int?
    var onDelete: (() -> Void)?
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase

    private var code: String? { message.isOutgoing ? nil : OTPDetector.code(in: message.body) }
    private var rtl: Bool { TextDirection.isRightToLeft(message.body) }

    var body: some View {
        HStack(alignment: .bottom) {
            if message.isOutgoing { Spacer(minLength: 48) }
            VStack(alignment: message.isOutgoing ? .trailing : .leading, spacing: 4) {
                Text(LinkDetector.attributed(message.body))
                    .font(.body)
                    .multilineTextAlignment(.leading)
                    .environment(\.layoutDirection, rtl ? .rightToLeft : .leftToRight)
                    .foregroundStyle(message.isOutgoing ? Color.white : Color.primary)
                    .tint(message.isOutgoing ? .white : .brandBlue)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(bubbleShape.fill(message.isOutgoing ? bubbleColor : Color.bubbleIncoming))
                    .textSelection(.enabled)
                if let code {
                    CopyChip(label: Text("Copy code \(PhoneNumber.ltr(code))"), value: code, isCode: true)
                }
                if isLastInGroup || message.state == .failed || message.state == .pending {
                    footer
                }
            }
            .contextMenu {
                Button {
                    Clipboard.copy(message.body)
                } label: { Label("Copy", systemImage: "doc.on.doc") }
                if let code {
                    Button {
                        Clipboard.copy(code, isCode: true)
                    } label: { Label("Copy code", systemImage: "number") }
                }
                if message.state == .failed {
                    Button {
                        bridge.retry(message)
                    } label: { Label("Try again", systemImage: "arrow.clockwise") }
                }
                if let onDelete {
                    Button(role: .destructive, action: onDelete) {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
            if !message.isOutgoing { Spacer(minLength: 48) }
        }
        .padding(.top, isLastInGroup ? 2 : 0)
        .padding(.bottom, isLastInGroup ? 6 : 0)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var bubbleColor: Color {
        message.state == .failed ? Color.brandRed.opacity(0.85) : Color.brandFill
    }

    private var bubbleShape: some Shape {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
    }

    private var footer: some View {
        HStack(spacing: 4) {
            if let simLabel {
                SIMBadge(slot: simLabel)
            }
            Text(message.time, style: .time)
            if message.isOutgoing {
                switch message.state {
                case .pending:
                    Image(systemName: "clock")
                    Text(bridge.phase.canReachBridge ? "Sending…" : "Waiting for bridge")
                case .sent:
                    Image(systemName: "checkmark")
                    Text("Sent")
                case .delivered:
                    Image(systemName: "checkmark.circle.fill")
                    Text("Delivered")
                case .failed:
                    Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Color.brandRed)
                    Button("Not sent · Try again") { bridge.retry(message) }
                        .foregroundStyle(Color.brandRed)
                case .received:
                    EmptyView()
                }
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 6)
    }

    private var accessibilityText: Text {
        let who = message.isOutgoing ? Text("You") : Text(verbatim: db.displayName(for: message.addr))
        return who + Text(verbatim: ", ") + Text(verbatim: message.body)
    }
}

/// Unsent text per conversation, kept in memory only (never stored on disk or sent anywhere), so a draft survives
/// leaving the conversation and iPhone Duo opening or closing, which shows it in the other Messages layout (D-063).
@MainActor enum ComposerDrafts {
    private static var drafts: [Int64: String] = [:]

    static func text(for thread: Int64?) -> String {
        thread.flatMap { drafts[$0] } ?? ""
    }

    static func set(_ text: String, for thread: Int64?) {
        guard let thread else { return }
        drafts[thread] = text.isEmpty ? nil : text
    }
}

struct Composer: View {
    let thread: Int64?
    let address: String
    var onSend: () -> Void = {}
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @State private var draft: String
    @State private var sim: Int?
    @FocusState private var focused: Bool

    init(thread: Int64?, address: String, onSend: @escaping () -> Void = {}) {
        self.thread = thread
        self.address = address
        self.onSend = onSend
        _draft = State(initialValue: ComposerDrafts.text(for: thread))
    }

    private var segments: SMSSegments.Info { SMSSegments.info(for: draft) }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !address.isEmpty }

    var body: some View {
        VStack(spacing: 6) {
            if !bridge.canUseLink && !bridge.isDemo {
                Button {
                    app.showPaywall = true
                } label: {
                    Label("Unlock Pairline to send messages", systemImage: "lock.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
            } else if bridge.status != nil && !bridge.has(.smsSend) {
                Label(CapabilityInfo(.smsSend).missingText, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                if !bridge.phase.canReachBridge && !draft.isEmpty {
                    Text("Your message will be sent when the bridge phone reconnects.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                HStack(alignment: .bottom, spacing: 8) {
                    if bridge.isDualSIM {
                        simMenu
                    }
                    TextField(String(localized: "Text message"), text: $draft, axis: .vertical)
                        .lineLimit(1...6)
                        .focused($focused)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .strokeBorder(Color.secondary.opacity(0.3)))
                    Button(action: send) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 32))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, canSend ? Color.brandBlue : Color.secondary.opacity(0.4))
                    }
                    .disabled(!canSend)
                    .accessibilityLabel(Text("Send"))
                }
                if segments.parts > 1 || (segments.parts == 1 && segments.remaining < 20) {
                    Text("SMS parts: \(segments.parts) · left: \(segments.remaining)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
        .onAppear {
            sim = bridge.effectiveSim(forSMS: true)
        }
        .onValueChange(draft) { ComposerDrafts.set($0, for: thread) }
    }

    private var simMenu: some View {
        Menu {
            ForEach(bridge.sims) { info in
                Button {
                    sim = info.slot
                    Haptics.selection()
                } label: {
                    if sim == info.slot {
                        Label(bridge.simLabel(info.slot), systemImage: "checkmark")
                    } else {
                        Text(bridge.simLabel(info.slot))
                    }
                }
            }
        } label: {
            HStack(spacing: 2) {
                SIMBadge(slot: sim ?? 0)
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 9, weight: .bold))
            }
            .padding(.vertical, 10)
        }
        .accessibilityLabel(Text("Choose SIM"))
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        do {
            try bridge.sendSMS(to: address, body: text, sim: sim, thread: thread)
            draft = ""
            onSend()
        } catch {
            app.toast = error.localizedDescription
        }
    }
}
