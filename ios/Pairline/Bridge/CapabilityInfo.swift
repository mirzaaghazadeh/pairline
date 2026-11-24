import Foundation

/// User-facing explanation of each bridge capability (`status.caps`).
struct CapabilityInfo: Identifiable {
    let capability: Capability

    init(_ capability: Capability) {
        self.capability = capability
    }

    var id: String { capability.rawValue }

    var symbol: String {
        switch capability {
        case .smsReceive: return "tray.and.arrow.down"
        case .smsSend: return "paperplane"
        case .smsHistory: return "clock.arrow.circlepath"
        case .callObserve: return "phone.arrow.down.left"
        case .callControl: return "phone.badge.checkmark"
        case .callDial: return "phone.arrow.up.right"
        case .callDTMF: return "circle.grid.3x3"
        case .callAudio: return "speaker.wave.2"
        case .contacts: return "person.2"
        case .calllog: return "list.bullet.rectangle"
        case .contactsAdd: return "person.badge.plus"
        case .ussd: return "number"
        case .dualSIM: return "simcard.2"
        case .wifi: return "wifi"
        case .files: return "doc.on.doc"
        }
    }

    var title: String {
        switch capability {
        case .smsReceive: return String(localized: "Receive messages")
        case .smsSend: return String(localized: "Send messages")
        case .smsHistory: return String(localized: "Message history")
        case .callObserve: return String(localized: "Incoming call alerts")
        case .callControl: return String(localized: "Answer and end calls")
        case .callDial: return String(localized: "Place calls")
        case .callDTMF: return String(localized: "Keypad tones during calls")
        case .callAudio: return String(localized: "Call audio on iPhone")
        case .contacts: return String(localized: "Contacts")
        case .calllog: return String(localized: "Call history")
        case .contactsAdd: return String(localized: "Add contacts from iPhone")
        case .ussd: return String(localized: "USSD codes")
        case .dualSIM: return String(localized: "Dual SIM")
        case .wifi: return String(localized: "Wi-Fi link")
        case .files: return String(localized: "Send files")
        }
    }

    /// Shown when the bridge doesn't list the capability.
    var missingText: String {
        switch capability {
        case .smsReceive, .smsHistory:
            return String(localized: "Allow SMS access in Pairline Bridge on your Android phone.")
        case .smsSend:
            return String(localized: "Allow sending SMS in Pairline Bridge on your Android phone.")
        case .callObserve, .callControl:
            return String(localized: "Turn on call control in Pairline Bridge on your Android phone.")
        case .callDial:
            return String(localized: "Allow the phone permission in Pairline Bridge on your Android phone.")
        case .callDTMF:
            return String(localized: "This bridge phone can't send keypad tones yet. Use the Android phone's keypad.")
        case .callAudio:
            return String(localized: "Coming in a later update. Until then, talk on the Android phone.")
        case .contacts:
            return String(localized: "Turn on contacts sync in Pairline Bridge on your Android phone.")
        case .calllog:
            return String(localized: "Turn on call-history sync in Pairline Bridge on your Android phone.")
        case .contactsAdd:
            return String(localized: "Turn on \"Add contacts from iPhone\" in Pairline Bridge on your Android phone.")
        case .ussd:
            return String(localized: "Allow the phone permission in Pairline Bridge on your Android phone.")
        case .dualSIM:
            return String(localized: "This bridge phone has one active SIM.")
        case .wifi:
            return String(localized: "Turn on the Wi-Fi link in Pairline Bridge on your Android phone.")
        case .files:
            return String(localized: "Update Pairline Bridge on your Android phone to send files.")
        }
    }
}
