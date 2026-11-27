import Foundation

/// Contact names for the notification service extension (D-092, D-104). In Cloud mode the extension shows texts and
/// calls by itself, before the app runs, and without the app's database it could only show numbers. The app keeps a
/// copy of its contact index here, number match keys (`PhoneNumber.matchKey`) and names and nothing else, in the app
/// group container on this iPhone, protected until first unlock. Unpairing deletes it; it never leaves the iPhone.
enum ContactNames {
    private static var file: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedOutbox.appGroup)?
            .appendingPathComponent("contact-names.json")
    }

    /// The app: replaces the copy with its index (match key → name).
    static func save(_ index: [String: String]) {
        guard !index.isEmpty else { return clear() }
        guard let file, let data = try? JSONEncoder().encode(index) else { return }
        try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func clear() {
        guard let file else { return }
        try? FileManager.default.removeItem(at: file)
    }

    /// The extension: the saved contact's name for a number, if there is one.
    static func name(for number: String) -> String? {
        guard let key = PhoneNumber.matchKey(number), let file, let data = try? Data(contentsOf: file),
              let index = try? JSONDecoder().decode([String: String].self, from: data) else { return nil }
        return index[key]
    }

    /// "Name (number)" for a saved contact, the number alone for anyone else, the name alone without a number; nil
    /// with neither. [name] is the bridge's, when it sent one; otherwise this iPhone's copy of the contacts is asked.
    static func caller(name: String?, number: String?) -> String? {
        let number = number.map { $0.trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : $0 }
            .map(PhoneNumber.international)
        let name = name.flatMap { $0.isEmpty ? nil : $0 } ?? number.flatMap(Self.name(for:))
        switch (name, number) {
        case let (name?, number?) where PhoneNumber.matchKey(name) != PhoneNumber.matchKey(number):
            return "\(name) (\(PhoneNumber.ltr(number)))"
        case let (name?, _): return name
        case let (nil, number?): return PhoneNumber.ltr(number)
        case (nil, nil): return nil
        }
    }
}
