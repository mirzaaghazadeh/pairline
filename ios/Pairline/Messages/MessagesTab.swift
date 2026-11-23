import SwiftUI

struct MessagesTab: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @State private var path: [Int64] = []
    @State private var search = ""
    @State private var showCompose = false
    @State private var prefill: (name: String?, number: String)?

    var body: some View {
        NavigationStack(path: $path) {
            ConversationList(search: search)
                .navigationTitle(Text("Messages"))
                .searchable(text: $search, prompt: Text("Search messages and people"))
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            prefill = nil
                            showCompose = true
                        } label: {
                            Image(systemName: "square.and.pencil")
                        }
                        .accessibilityLabel(Text("New message"))
                    }
                }
                .navigationDestination(for: Int64.self) { thread in
                    ThreadView(thread: thread)
                }
                .refreshable { await bridge.sync() }
        }
        .sheet(isPresented: $showCompose) {
            NewMessageView(initialRecipient: prefill) { thread in
                path = [thread]
            }
            .environmentObject(bridge)
            .environmentObject(db)
            .environmentObject(app)
        }
        .onReceive(app.$openThread.compactMap { $0 }) { thread in
            path = [thread]
            app.openThread = nil
        }
        .onReceive(app.$composeTo.compactMap { $0 }) { recipient in
            prefill = recipient
            showCompose = true
            app.composeTo = nil
        }
    }
}

struct ConversationList: View {
    let search: String
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @State private var rows: [(Conversation, StoredMessage?)] = []

    var body: some View {
        List {
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
                    NavigationLink(value: conversation.thread) {
                        ConversationRow(conversation: conversation, match: match)
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
