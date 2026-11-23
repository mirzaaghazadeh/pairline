import SwiftUI

/// Pick a recipient (contact autocomplete or any number), write and send.
struct NewMessageView: View {
    var initialRecipient: (name: String?, number: String)?
    var onOpenThread: (Int64) -> Void
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @State private var query = ""
    @State private var recipient: (name: String?, number: String)?
    @State private var contacts: [Contact] = []
    @FocusState private var toFocused: Bool

    private var suggestions: [(Contact, ContactNumber)] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let digits = PhoneNumber.asciiDigits(q).filter(\.isNumber)
        var out: [(Contact, ContactNumber)] = []
        for contact in contacts {
            let nameMatch = q.isEmpty || contact.name.lowercased().contains(q)
            for number in contact.numbers {
                let numberMatch = digits.count >= 2 && PhoneNumber.asciiDigits(number.n).filter(\.isNumber).contains(digits)
                if nameMatch || numberMatch { out.append((contact, number)) }
            }
        }
        return Array(out.prefix(50))
    }

    private var typedNumber: String? {
        let dialable = PhoneNumber.dialable(query)
        return dialable.filter(\.isNumber).count >= 3 ? dialable : nil
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                toField
                Divider()
                if let recipient {
                    Spacer()
                    if let thread = db.existingThread(for: recipient.number) {
                        Button("Open the conversation") {
                            dismiss()
                            onOpenThread(thread)
                        }
                        .font(.subheadline.weight(.semibold))
                        .padding(.bottom, 8)
                    }
                    Composer(thread: nil, address: recipient.number) {
                        let thread = db.existingThread(for: recipient.number)
                            ?? PhoneNumber.syntheticThreadID(for: recipient.number)
                        dismiss()
                        onOpenThread(thread)
                    }
                } else {
                    List {
                        if let typedNumber {
                            Button {
                                choose(name: nil, number: typedNumber)
                            } label: {
                                Label {
                                    Text("Send to \(PhoneNumber.ltr(typedNumber))")
                                } icon: {
                                    Image(systemName: "number.circle.fill").foregroundStyle(Color.brandBlue)
                                }
                            }
                        }
                        ForEach(suggestions, id: \.1.n) { contact, number in
                            Button {
                                choose(name: contact.name, number: number.n)
                            } label: {
                                HStack(spacing: 12) {
                                    Avatar(name: contact.name, size: 36)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(verbatim: contact.name).foregroundStyle(.primary)
                                        HStack(spacing: 4) {
                                            Text(ContactLabels.title(number.label))
                                            LTRText(number.n)
                                        }
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                        if suggestions.isEmpty && typedNumber == nil {
                            Text(contacts.isEmpty
                                 ? "Type a phone number. Contacts appear here once they sync from the bridge phone."
                                 : "No contacts match.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .listRowBackground(Color.clear)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(Text("New Message"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear {
                contacts = db.contacts()
                if let initialRecipient, recipient == nil {
                    recipient = initialRecipient
                } else {
                    toFocused = true
                }
            }
        }
    }

    private var toField: some View {
        HStack(spacing: 8) {
            Text("To:").foregroundStyle(.secondary)
            if let recipient {
                HStack(spacing: 6) {
                    if let name = recipient.name {
                        Text(verbatim: name)
                    } else {
                        LTRText(recipient.number)
                    }
                    Button {
                        self.recipient = nil
                        toFocused = true
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .accessibilityLabel(Text("Remove recipient"))
                }
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(Color.brandBlue.opacity(0.14)))
                Spacer()
            } else {
                TextField(String(localized: "Name or number"), text: $query)
                    .focused($toFocused)
                    .textContentType(.telephoneNumber)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .onSubmit {
                        if let typedNumber { choose(name: nil, number: typedNumber) }
                    }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func choose(name: String?, number: String) {
        Haptics.selection()
        recipient = (name, number)
    }
}

enum ContactLabels {
    static func title(_ label: String?) -> String {
        switch label?.lowercased() {
        case "mobile", "cell", nil: return String(localized: "mobile")
        case "home": return String(localized: "home")
        case "work": return String(localized: "work")
        case "main": return String(localized: "main")
        case "other": return String(localized: "other")
        default: return label ?? ""
        }
    }
}
