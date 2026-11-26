import SwiftUI

struct ContactsTab: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @State private var contacts: [Contact] = []
    @State private var search = ""
    @State private var showAdd = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// The contact open beside the list on a large display (D-063).
    @State private var selected: Contact.ID?

    private var filtered: [Contact] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return contacts }
        let digits = PhoneNumber.asciiDigits(q).filter(\.isNumber)
        return contacts.filter { contact in
            contact.name.lowercased().contains(q)
                || (digits.count >= 2 && contact.numbers.contains {
                    PhoneNumber.asciiDigits($0.n).filter(\.isNumber).contains(digits)
                })
        }
    }

    private var sections: [(title: String, contacts: [Contact])] {
        let grouped = Dictionary(grouping: filtered) { contact -> String in
            guard let first = contact.name.first, first.isLetter else { return "#" }
            return String(first).uppercased()
        }
        return grouped.keys.sorted { a, b in
            if a == "#" { return false }
            if b == "#" { return true }
            return a.localizedStandardCompare(b) == .orderedAscending
        }.map { ($0, grouped[$0]!.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }) }
    }

    var body: some View {
        Group {
            if sizeClass == .regular {
                // A large display: the contacts and the chosen one side by side; on iPhone Duo each on its own half
                // (D-063).
                ListDetailSplit {
                    contactList
                } detail: {
                    if let contact = contacts.first(where: { $0.id == selected }) {
                        ContactDetailView(contact: contact)
                            .id(contact.id)
                    } else {
                        EmptyStateView(symbol: "person.crop.circle", title: Text("Choose a contact"),
                                       message: Text("Their details show here."))
                    }
                }
            } else {
                NavigationStack { contactList }
            }
        }
        .sheet(isPresented: $showAdd) {
            AddContactView()
                .environmentObject(bridge)
        }
        .onAppear(perform: reload)
        .onValueChange(db.revision) { _ in reload() }
    }

    private func reload() {
        contacts = db.contacts()
        // Beside the list there's room for a contact: start with the first favorite rather than an empty half.
        if sizeClass == .regular, !contacts.contains(where: { $0.id == selected }) {
            selected = (contacts.first(where: \.starred) ?? contacts.first)?.id
        }
    }

    /// Opens the contact beside the list on a large display, or pushes it on a phone.
    @ViewBuilder private func contactLink<Label: View>(_ contact: Contact, @ViewBuilder label: () -> Label) -> some View {
        if sizeClass == .regular {
            Button {
                selected = contact.id
            } label: {
                label()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected == contact.id ? .isSelected : [])
        } else {
            NavigationLink {
                ContactDetailView(contact: contact)
            } label: {
                label()
            }
        }
    }

    private var contactList: some View {
            List {
                Section {
                    BridgeBanners()
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                if contacts.isEmpty {
                    emptyState
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                } else {
                    let favorites = filtered.filter(\.starred)
                    if search.isEmpty && !favorites.isEmpty {
                        Section {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 16) {
                                    ForEach(favorites) { contact in
                                        contactLink(contact) {
                                            VStack(spacing: 6) {
                                                Avatar(name: contact.name, size: 58)
                                                Text(verbatim: contact.name.split(separator: " ").first.map(String.init) ?? contact.name)
                                                    .font(.caption)
                                                    .lineLimit(1)
                                                    .frame(width: 70)
                                            }
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.vertical, 6)
                            }
                        } header: {
                            Text("Favorites")
                        }
                    }
                    ForEach(sections, id: \.title) { section in
                        Section {
                            ForEach(section.contacts) { contact in
                                contactLink(contact) {
                                    ContactRow(contact: contact)
                                }
                                .listRowBackground(selected == contact.id && sizeClass == .regular
                                                   ? Color.brandBlue.opacity(0.14) : nil)
                            }
                        } header: {
                            Text(verbatim: section.title)
                        }
                    }
                    if filtered.isEmpty {
                        EmptyStateView(symbol: "magnifyingglass", title: Text("No results"),
                                       message: Text("No contacts match “\(search)”."))
                            .listRowBackground(Color.clear)
                    }
                }
            }
            .listStyle(.plain)
            .searchable(text: $search, prompt: Text("Search contacts"))
            .navigationTitle(Text("Contacts"))
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        if bridge.has(.contactsAdd) {
                            showAdd = true
                        } else {
                            app.toast = CapabilityInfo(.contactsAdd).missingText
                        }
                    } label: {
                        // A title as well as the symbol, for overflow menus and iPhone Duo's side bar (D-063).
                        Label("Add contact", systemImage: "person.badge.plus")
                    }
                }
            }
            .refreshable { await bridge.sync() }
    }

    @ViewBuilder private var emptyState: some View {
        if bridge.status != nil && !bridge.has(.contacts) {
            EmptyStateView(symbol: "person.2.slash", title: Text("Contacts sync is off"),
                           message: Text(CapabilityInfo(.contacts).missingText))
        } else {
            EmptyStateView(symbol: "person.2", title: Text("No contacts yet"),
                           message: Text("Contacts from your Android phone appear here after they sync. They stay on this iPhone only."))
        }
    }
}

struct ContactRow: View {
    let contact: Contact

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: contact.name, size: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: contact.name)
                    .font(.body.weight(.medium))
                if let number = contact.primaryNumber {
                    LTRText(number)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if contact.starred {
                Image(systemName: "star.fill")
                    .font(.caption)
                    .foregroundStyle(Color.brandAmber)
                    .accessibilityLabel(Text("Favorite"))
            }
        }
    }
}

struct ContactDetailView: View {
    let contact: Contact
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @EnvironmentObject private var calls: CallCenter
    @State private var blockRequest: BlockRequest?
    @State private var showEdit = false
    /// The favourite as just set here, until the next sync shows it.
    @State private var starred: Bool?
    @State private var editError: String?

    private var numbers: [String] { contact.numbers.map(\.n).filter { !$0.isEmpty } }
    private var anyBlocked: Bool { numbers.contains { bridge.isBlocked($0) } }

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Avatar(name: contact.name, size: 96)
                    Text(verbatim: contact.name)
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                    if let number = contact.primaryNumber {
                        HStack(spacing: 14) {
                            QuickAction(symbol: "message.fill", title: Text("Message")) { message(number) }
                            QuickAction(symbol: "phone.fill", title: Text("Call")) {
                                calls.startCall(number: number, sim: bridge.defaultSim(forSMS: false))
                            }
                        }
                        .padding(.top, 4)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }

            Section {
                ForEach(contact.numbers, id: \.n) { number in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ContactLabels.title(number.label))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            LTRText(number.n)
                                .font(.body)
                                .foregroundStyle(Color.brandBlue)
                        }
                        Spacer()
                        Button {
                            message(number.n)
                        } label: {
                            Image(systemName: "message")
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(Text("Message"))
                        Button {
                            calls.startCall(number: number.n, sim: bridge.defaultSim(forSMS: false))
                        } label: {
                            Image(systemName: "phone")
                        }
                        .buttonStyle(.borderless)
                        .padding(.leading, 12)
                        .accessibilityLabel(Text("Call"))
                    }
                    .contextMenu {
                        Button {
                            Clipboard.copy(number.n)
                        } label: { Label("Copy", systemImage: "doc.on.doc") }
                    }
                }
                ForEach(contact.emails, id: \.self) { email in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("email").font(.caption).foregroundStyle(.secondary)
                        Text(verbatim: email).foregroundStyle(Color.brandBlue)
                    }
                    .contextMenu {
                        Button {
                            Clipboard.copy(email)
                        } label: { Label("Copy", systemImage: "doc.on.doc") }
                    }
                }
            }

            let recent = contact.numbers.flatMap { db.calls(matching: $0.n, limit: 10) }.sorted { $0.time > $1.time }
            if !recent.isEmpty {
                Section {
                    ForEach(recent.prefix(10)) { item in
                        CallLogRow(item: item) {
                            calls.startCall(number: item.number, sim: item.sim)
                        }
                    }
                } header: {
                    Text("Recent calls")
                }
            }

            if bridge.has(.block) && !numbers.isEmpty {
                Section {
                    if anyBlocked {
                        Button {
                            BlockActions.run(app: app, bridge: bridge, numbers: numbers, block: false)
                        } label: {
                            Label("Unblock Contact", systemImage: "hand.raised.slash")
                        }
                    } else {
                        Button(role: .destructive) {
                            blockRequest = BlockRequest(numbers: numbers, contactName: contact.name)
                        } label: {
                            Label("Block Contact", systemImage: "hand.raised")
                                .foregroundStyle(Color.brandRed)
                        }
                    }
                }
            }

            Section {
                Label("Synced from your bridge phone. Kept only on this iPhone.", systemImage: "lock.shield")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .blockPrompt($blockRequest)
        // D-074: edit on the bridge phone, and its favourites.
        .toolbar {
            if bridge.has(.contactsEdit) {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        setStarred(!(starred ?? contact.starred))
                    } label: {
                        Label {
                            Text("Favourite", tableName: "PhoneTools")
                        } icon: {
                            Image(systemName: (starred ?? contact.starred) ? "star.fill" : "star")
                        }
                    }
                    Button { showEdit = true } label: { Text("Edit", tableName: "PhoneTools") }
                }
            }
        }
        .sheet(isPresented: $showEdit) { AddContactView(editing: contact) }
        .alert(Text(verbatim: editError ?? ""), isPresented: Binding(get: { editError != nil }, set: { if !$0 { editError = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func setStarred(_ on: Bool) {
        starred = on
        Task {
            do {
                try await bridge.updateContact(contact, name: nil, numbers: nil, starred: on)
            } catch {
                starred = nil
                editError = error.localizedDescription
            }
        }
    }

    private func message(_ number: String) {
        let thread = db.existingThread(for: number)
        app.tab = .messages
        if let thread {
            app.openThread = thread
        } else {
            app.composeTo = (contact.name, number)
        }
    }
}

struct QuickAction: View {
    let symbol: String
    let title: Text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol).font(.title3)
                title.font(.caption.weight(.medium))
            }
            .frame(width: 84, height: 62)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.brandBlue.opacity(0.12)))
            .foregroundStyle(Color.brandBlue)
        }
        .buttonStyle(.plain)
    }
}

struct AddContactView: View {
    /// The contact to edit on the bridge phone (D-074); nil adds a new one.
    var editing: Contact?
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var bridge: BridgeController
    @State private var name = ""
    @State private var numbers: [ContactNumber] = [ContactNumber(n: "", label: "mobile")]
    @State private var saving = false
    @State private var error: String?

    init(editing: Contact? = nil) {
        self.editing = editing
        if let editing {
            _name = State(initialValue: editing.name)
            _numbers = State(initialValue: editing.numbers.isEmpty ? [ContactNumber(n: "", label: "mobile")] : editing.numbers)
        }
    }

    private var valid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && numbers.contains { PhoneNumber.dialable($0.n).filter(\.isNumber).count >= 3 }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(String(localized: "Name"), text: $name)
                        .textContentType(.name)
                }
                Section {
                    ForEach(numbers.indices, id: \.self) { index in
                        HStack {
                            Menu {
                                ForEach(["mobile", "home", "work", "other"], id: \.self) { label in
                                    Button(ContactLabels.title(label)) { numbers[index].label = label }
                                }
                            } label: {
                                HStack(spacing: 2) {
                                    Text(ContactLabels.title(numbers[index].label))
                                    Image(systemName: "chevron.up.chevron.down").font(.caption2)
                                }
                                .frame(width: 84, alignment: .leading)
                            }
                            TextField(String(localized: "Phone number"), text: $numbers[index].n)
                                .keyboardType(.phonePad)
                                .environment(\.layoutDirection, .leftToRight)
                        }
                    }
                    Button {
                        numbers.append(ContactNumber(n: "", label: "mobile"))
                    } label: {
                        Label("Add number", systemImage: "plus.circle.fill")
                    }
                } footer: {
                    Text("The contact is saved in the Android phone's contacts.")
                }
                if let error {
                    Section {
                        Text(error).foregroundStyle(Color.brandRed)
                    }
                }
            }
            .navigationTitle(editing == nil ? Text("New Contact") : Text("Edit Contact", tableName: "PhoneTools"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button("Save") { save() }.disabled(!valid)
                    }
                }
            }
        }
    }

    private func save() {
        saving = true
        let cleaned = numbers.filter { !$0.n.trimmingCharacters(in: .whitespaces).isEmpty }
        Task {
            defer { saving = false }
            do {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                if let editing {
                    try await bridge.updateContact(editing, name: trimmed == editing.name ? nil : trimmed,
                                                   numbers: cleaned == editing.numbers ? nil : cleaned, starred: nil)
                } else {
                    try await bridge.addContact(name: trimmed, numbers: cleaned)
                }
                Haptics.success()
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
