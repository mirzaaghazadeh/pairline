import SwiftUI

struct MessagesTab: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// The open conversation in either layout, so it stays open when iPhone Duo opens or closes (D-063).
    @State private var current: Int64?
    @State private var search = ""
    @State private var showCompose = false
    @State private var prefill: (name: String?, number: String)?
    @State private var editMode: EditMode = .inactive
    @State private var selection = Set<Int64>()
    @State private var confirmDelete: [Int64] = []

    var body: some View {
        Group {
            if sizeClass == .regular {
                // A large display (iPhone Duo open, iPad): the conversations and the open one side by side, the same
                // hierarchy with one more level showing; on iPhone Duo each on its own half (D-063).
                ListDetailSplit {
                    conversationList(current: $current)
                } detail: {
                    if let current {
                        ThreadView(thread: current, closeInSplitView: { self.current = nil })
                            .id(current)
                    } else {
                        EmptyStateView(symbol: "bubble.left.and.bubble.right",
                                       title: Text("Choose a conversation"),
                                       message: Text("Its messages show here."))
                    }
                }
            } else {
                NavigationStack(path: Binding(get: { current.map { [$0] } ?? [] }, set: { current = $0.last })) {
                    conversationList(current: nil)
                        .navigationDestination(for: Int64.self) { thread in
                            ThreadView(thread: thread)
                        }
                }
            }
        }
        .sheet(isPresented: $showCompose) {
            NewMessageView(initialRecipient: prefill) { thread in
                current = thread
            }
            .environmentObject(bridge)
            .environmentObject(db)
            .environmentObject(app)
        }
        .onReceive(app.$openThread.compactMap { $0 }) { thread in
            current = thread
            app.openThread = nil
        }
        .onReceive(app.$composeTo.compactMap { $0 }) { recipient in
            prefill = recipient
            showCompose = true
            app.composeTo = nil
        }
    }

    /// The conversations with their toolbar, search and delete confirmation. `current` is set in the side-by-side
    /// layout, where a tap opens the conversation in the other pane instead of pushing it.
    private func conversationList(current: Binding<Int64?>?) -> some View {
        ConversationList(search: search, selection: $selection, current: current, onDelete: { confirmDelete = $0 })
            .environment(\.editMode, $editMode)
            .navigationTitle(Text("Messages"))
            .searchable(text: $search, prompt: Text("Search messages and people"))
            .toolbar {
                // Text-only, so it stays in the top bar on iPhone Duo; the compose and delete items below never share
                // a slot, so none of them jumps between the top bar and the side bar (D-063).
                ToolbarItem(placement: .navigationBarLeading) {
                    if editMode.isEditing || !db.conversations().isEmpty {
                        Button(editMode.isEditing ? String(localized: "Done") : String(localized: "Edit")) {
                            withAnimation {
                                editMode = editMode.isEditing ? .inactive : .active
                                selection.removeAll()
                            }
                        }
                    }
                }
                if editMode.isEditing {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button(role: .destructive) {
                            confirmDelete = Array(selection)
                        } label: {
                            Text("Delete")
                        }
                        .tint(Color.brandRed)
                        .disabled(selection.isEmpty)
                    }
                } else {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            prefill = nil
                            showCompose = true
                        } label: {
                            Label("New message", systemImage: "square.and.pencil")
                        }
                    }
                }
            }
            .confirmationDialog(Text("Delete from this iPhone?"),
                                isPresented: Binding(get: { !confirmDelete.isEmpty },
                                                     set: { if !$0 { confirmDelete = [] } }),
                                titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    bridge.deleteConversations(confirmDelete)
                    if let open = self.current, confirmDelete.contains(open) { self.current = nil }
                    confirmDelete = []
                    selection.removeAll()
                    editMode = .inactive
                    Haptics.success()
                }
            } message: {
                Text("The messages stay on your Android phone, because Android lets only its default messaging app delete texts. A new message brings the conversation back here.")
            }
            .refreshable { await bridge.sync() }
    }
}

struct ConversationList: View {
    let search: String
    @Binding var selection: Set<Int64>
    /// The conversation open beside the list, in the side-by-side layout; nil when a tap pushes the conversation.
    var current: Binding<Int64?>?
    /// Asks to delete these conversations (the caller confirms first).
    var onDelete: ([Int64]) -> Void = { _ in }
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @Environment(\.editMode) private var editMode
    @State private var rows: [(Conversation, StoredMessage?)] = []

    var body: some View {
        // Selection only while editing, so a tap opens the conversation otherwise.
        List(selection: editMode?.wrappedValue.isEditing == true ? $selection : nil) {
            Section {
                BridgeBanners()
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            if rows.isEmpty {
                emptyState
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            } else {
                ForEach(rows, id: \.0.thread) { conversation, match in
                    row(conversation, match: match)
                        .tag(conversation.thread)
                        .swipeActions(edge: .trailing) {
                            // Not `role: .destructive`: that removes the row before the user has confirmed.
                            Button {
                                onDelete([conversation.thread])
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                            .tint(Color.brandRed)
                        }
                        .swipeActions(edge: .leading) {
                            if conversation.unread > 0 {
                                Button {
                                    db.markRead(thread: conversation.thread)
                                    bridge.updateBadge()
                                } label: {
                                    Label("Read", systemImage: "envelope.open")
                                }
                                .tint(.brandBlue)
                            }
                        }
                }
            }
        }
        .listStyle(.plain)
        .onAppear(perform: reload)
        .onValueChange(db.revision) { _ in reload() }
        .onValueChange(search) { _ in reload() }
    }

    private func reload() {
        rows = db.searchConversations(search)
    }

    /// Pushes the conversation on a phone. Beside an open conversation, a tap opens it there and the open one is
    /// highlighted; while editing, the list's own selection takes the tap.
    @ViewBuilder private func row(_ conversation: Conversation, match: StoredMessage?) -> some View {
        if let current {
            if editMode?.wrappedValue.isEditing == true {
                ConversationRow(conversation: conversation, match: match)
            } else {
                Button {
                    current.wrappedValue = conversation.thread
                } label: {
                    ConversationRow(conversation: conversation, match: match)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .listRowBackground(current.wrappedValue == conversation.thread ? Color.brandBlue.opacity(0.14) : nil)
                .accessibilityAddTraits(current.wrappedValue == conversation.thread ? .isSelected : [])
            }
        } else {
            NavigationLink(value: conversation.thread) {
                ConversationRow(conversation: conversation, match: match)
            }
        }
    }

    @ViewBuilder private var emptyState: some View {
        if !search.isEmpty {
            EmptyStateView(symbol: "magnifyingglass", title: Text("No results"),
                           message: Text("No messages or people match “\(search)”."))
        } else if !bridge.isDemo && !app.isPaired {
            EmptyStateView(symbol: "iphone.gen2.radiowaves.left.and.right",
                           title: Text("Pair your bridge phone"),
                           message: Text("Messages from your Android phone's SIM will appear here once it's paired.")) {
                VStack(spacing: 10) {
                    Button("Pair now") { app.showPairing = true }
                        .buttonStyle(PrimaryButtonStyle())
                    Button("Explore the demo") { app.enterDemo() }
                        .buttonStyle(SecondaryButtonStyle())
                }
                .frame(maxWidth: 280)
            }
        } else if bridge.syncing {
            EmptyStateView(symbol: "arrow.triangle.2.circlepath", title: Text("Syncing messages…"),
                           message: Text("Your conversations are on their way from the bridge phone."))
        } else {
            EmptyStateView(symbol: "message", title: Text("No messages yet"),
                           message: Text("New SMS to your Android phone's number will show up here."))
        }
    }
}

struct ConversationRow: View {
    let conversation: Conversation
    let match: StoredMessage?
    @EnvironmentObject private var db: AppDatabase

    private var name: String { db.displayName(for: conversation.primaryAddress) }
    private var snippet: String { match?.body ?? conversation.snippet }
    private var code: String? {
        guard match == nil, !conversation.lastIsOutgoing, Date().timeIntervalSince(conversation.time) < 30 * 60 else {
            return nil
        }
        return OTPDetector.code(in: conversation.snippet)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .topLeading) {
                Avatar(name: name, size: 48)
                if conversation.unread > 0 {
                    Circle()
                        .fill(Color.brandBlue)
                        .frame(width: 12, height: 12)
                        .overlay(Circle().strokeBorder(Color(.systemBackground), lineWidth: 2))
                        .offset(x: -2, y: -2)
                        .accessibilityLabel(Text("Unread"))
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    nameText
                        .font(.headline.weight(conversation.unread > 0 ? .bold : .semibold))
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(Formatters.listTime(conversation.time))
                        .font(.caption)
                        .foregroundStyle(conversation.unread > 0 ? Color.brandBlue : .secondary)
                }
                HStack(alignment: .top, spacing: 4) {
                    if conversation.lastIsOutgoing && match == nil {
                        stateIcon
                    }
                    Text(snippet)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                if let code {
                    CopyChip(label: Text("Copy code \(PhoneNumber.ltr(code))"), value: code, isCode: true)
                        .buttonStyle(.borderless)
                        .padding(.top, 4)
                }
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }

    private var nameText: Text {
        PhoneNumber.isPhoneNumber(name) ? Text(verbatim: PhoneNumber.ltr(name)) : Text(verbatim: name)
    }

    @ViewBuilder private var stateIcon: some View {
        switch conversation.lastState {
        case .pending?:
            Image(systemName: "clock").font(.caption).foregroundStyle(.secondary)
        case .failed?:
            Image(systemName: "exclamationmark.circle.fill").font(.caption).foregroundStyle(Color.brandRed)
        default:
            EmptyView()
        }
    }
}
