import SwiftUI

/// Saved replies for the composer (D-083): one tap puts one in the message field. Kept in iOS user defaults on this
/// iPhone; a few defaults in the user's language until they edit the list.
enum QuickReplies {
    private static let key = "messages.quickReplies"
    static let maxCount = 20
    static let maxLength = 300

    static var all: [String] {
        get {
            guard let stored = UserDefaults.standard.array(forKey: key) as? [String] else { return defaults }
            return stored
        }
        set {
            let cleaned = newValue
                .map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxLength)) }
                .filter { !$0.isEmpty }
            UserDefaults.standard.set(Array(cleaned.prefix(maxCount)), forKey: key)
        }
    }

    static var defaults: [String] {
        [
            String(localized: "On my way.", table: "Inbox"),
            String(localized: "I'll call you back.", table: "Inbox"),
            String(localized: "Can't talk now. Text me, please.", table: "Inbox"),
            String(localized: "Thanks!", table: "Inbox"),
        ]
    }
}

/// The composer's quick-reply button: the saved replies, and a way to edit them.
struct QuickReplyMenu: View {
    let insert: (String) -> Void
    @State private var editing = false
    @State private var replies = QuickReplies.all

    var body: some View {
        Menu {
            ForEach(replies, id: \.self) { reply in
                Button {
                    insert(reply)
                    Haptics.selection()
                } label: {
                    Text(verbatim: reply)
                }
            }
            Divider()
            Button {
                editing = true
            } label: {
                Label {
                    Text("Edit quick replies", tableName: "Inbox")
                } icon: {
                    Image(systemName: "pencil")
                }
            }
        } label: {
            Image(systemName: "text.bubble")
                .font(.system(size: 20))
                .foregroundStyle(Color.brandBlue)
                .padding(.vertical, 8)
        }
        .accessibilityLabel(Text("Quick replies", tableName: "Inbox"))
        .onAppear { replies = QuickReplies.all }
        .sheet(isPresented: $editing, onDismiss: { replies = QuickReplies.all }) {
            QuickRepliesEditor()
        }
    }
}

struct QuickRepliesEditor: View {
    private struct Item: Identifiable {
        let id = UUID()
        var text: String
    }

    @Environment(\.dismiss) private var dismiss
    @State private var items = QuickReplies.all.map { Item(text: $0) }
    @State private var newReply = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach($items) { $item in
                        TextField(String(localized: "Reply", table: "Inbox"), text: $item.text, axis: .vertical)
                    }
                    .onDelete { items.remove(atOffsets: $0) }
                    .onMove { items.move(fromOffsets: $0, toOffset: $1) }
                    if items.count < QuickReplies.maxCount {
                        HStack {
                            TextField(String(localized: "New quick reply", table: "Inbox"), text: $newReply, axis: .vertical)
                            Button {
                                items.append(Item(text: newReply))
                                newReply = ""
                            } label: {
                                Image(systemName: "plus.circle.fill")
                            }
                            .disabled(newReply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityLabel(Text("Add", tableName: "Inbox"))
                        }
                    }
                } footer: {
                    Text("Tap the speech bubble next to the message field to put one in. They stay on this iPhone.", tableName: "Inbox")
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle(Text("Quick replies", tableName: "Inbox"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        var all = items.map(\.text)
                        if !newReply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { all.append(newReply) }
                        QuickReplies.all = all
                        dismiss()
                    }
                }
            }
        }
    }
}
