import UIKit
import UniformTypeIdentifiers

/// Copies user content to this iPhone's clipboard only: never to Universal Clipboard (the user's other Apple
/// devices), and one-time codes expire after a few minutes (AGENTS.md rule 1).
enum Clipboard {
    static let codeLifetime: TimeInterval = 3 * 60

    static func copy(_ text: String, isCode: Bool = false) {
        var options: [UIPasteboard.OptionsKey: Any] = [.localOnly: true]
        if isCode { options[.expirationDate] = Date().addingTimeInterval(codeLifetime) }
        UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: text]], options: options)
    }
}
