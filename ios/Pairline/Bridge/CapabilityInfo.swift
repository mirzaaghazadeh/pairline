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
        case .callMedia: return "ear"
        case .callMediaTalk: return "mic"
        case .callMediaLink: return "dot.radiowaves.left.and.right"
        case .netShare: return "network"
        case .netSwitch: return "personalhotspot"
        case .smsRead: return "envelope.open"
        case .smsSchedule: return "calendar.badge.clock"
        case .dataUsage: return "chart.bar"
        case .callHold: return "pause.circle"
        case .callVoicemail: return "recordingtape"
        case .contactsEdit: return "person.crop.circle.badge.checkmark"
        case .phoneFind: return "bell.and.waves.left.and.right"
        case .phoneRinger: return "speaker.wave.2.circle"
        case .notifMirror: return "app.badge"
        case .wifi: return "wifi"
        case .files: return "doc.on.doc"
        case .calllogDelete: return "trash"
        case .block: return "hand.raised"
        case .callScreen: return "phone.badge.waveform.fill"
        case .mediaPhotos: return "photo.on.rectangle"
        case .mms: return "photo.badge.plus"
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
        case .callAudio: return String(localized: "Mute and speaker from iPhone", table: "CallAudio")
        case .contacts: return String(localized: "Contacts")
        case .calllog: return String(localized: "Call history")
        case .contactsAdd: return String(localized: "Add contacts from iPhone")
        case .ussd: return String(localized: "USSD codes")
        case .dualSIM: return String(localized: "Dual SIM")
        case .callMedia: return String(localized: "Hear calls on iPhone", table: "CallAudio")
        case .callMediaTalk: return String(localized: "Talk from iPhone", table: "CallAudio")
        case .callMediaLink: return String(localized: "Call audio over Bluetooth", table: "CallAudio")
        case .netShare: return String(localized: "Internet sharing", table: "NetShare")
        case .netSwitch: return String(localized: "Hotspot and data switches", table: "NetShare")
        case .smsRead: return String(localized: "Read on both phones", table: "PhoneTools")
        case .smsSchedule: return String(localized: "Scheduled messages", table: "Extras")
        case .dataUsage: return String(localized: "Mobile data meter", table: "Extras")
        case .callHold: return String(localized: "Hold calls", table: "PhoneTools")
        case .callVoicemail: return String(localized: "Voicemail", table: "Carrier")
        case .contactsEdit: return String(localized: "Edit contacts from iPhone", table: "PhoneTools")
        case .phoneFind: return String(localized: "Find phone", table: "PhoneTools")
        case .phoneRinger: return String(localized: "Ringer switch", table: "PhoneTools")
        case .notifMirror: return String(localized: "Notifications from Android apps", table: "PhoneTools")
        case .wifi: return String(localized: "Wi-Fi link")
        case .files: return String(localized: "Send files")
        case .calllogDelete: return String(localized: "Delete calls on the bridge phone")
        case .block: return String(localized: "Block list")
        case .callScreen: return String(localized: "Call screening", table: "Screening")
        case .mediaPhotos: return String(localized: "Photos on the Android phone", table: "Photos")
        case .mms: return String(localized: "Picture and group messages", table: "MMS")
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
        case .callDTMF, .callAudio:
            return String(localized: "Turn on Talk on iPhone in Pairline Bridge on your Android phone (needs Android 12 or later and Wireless debugging).", table: "CallAudio")
        case .callMedia:
            return String(localized: "Turn on Talk on iPhone in Pairline Bridge on your Android phone. It needs Android 11 or later and Wireless debugging (no extra app).", table: "CallAudio")
        case .callMediaTalk:
            return String(localized: "This bridge phone can't feed audio into calls. You hear calls on the iPhone and talk on the Android phone.", table: "CallAudio")
        case .callMediaLink:
            return String(localized: "Needs Talk on iPhone and the latest Pairline Bridge on your Android phone. Until then call audio goes over Wi-Fi.", table: "CallAudio")
        case .netShare:
            return String(localized: "Turn on Internet sharing in Pairline Bridge › Settings on your Android phone.", table: "NetShare")
        case .netSwitch:
            return String(localized: "Needs Talk on iPhone on, and the latest Pairline Bridge, on your Android phone (Android 11 or later).", table: "NetShare")
        case .smsRead:
            return String(localized: "Needs Talk on iPhone on, and the latest Pairline Bridge, on your Android phone. Until then, messages you read here stay unread there.", table: "PhoneTools")
        case .callHold:
            return String(localized: "Turn on Talk on iPhone in Pairline Bridge on your Android phone (needs Android 12 or later and Wireless debugging).", table: "CallAudio")
        case .contactsEdit:
            return String(localized: "Turn on \"Add contacts from iPhone\" in Pairline Bridge on your Android phone.")
        case .phoneFind, .phoneRinger, .callVoicemail, .smsSchedule, .mms:
            return String(localized: "Update Pairline Bridge on your Android phone.", table: "PhoneTools")
        case .dataUsage:
            return String(localized: "Allow Usage access for Pairline Bridge on your Android phone: open the data meter here and it shows a notification there that leads to it.", table: "Extras")
        case .notifMirror:
            return String(localized: "In Pairline Bridge › Settings › Notifications from apps on your Android phone, allow notification access and choose the apps.", table: "PhoneTools")
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
        case .calllogDelete:
            return String(localized: "Allow the call history permission in Pairline Bridge on your Android phone. Until then, calls you delete are removed from this iPhone only.")
        case .block:
            return String(localized: "Update Pairline Bridge on your Android phone to block numbers.")
        case .callScreen:
            return String(localized: "In Pairline Bridge › Blocked numbers on your Android phone, make it the call-screening app (Android 10 or later).", table: "Screening")
        case .mediaPhotos:
            return String(localized: "Turn on Photos for the iPhone in Pairline Bridge › Settings on your Android phone, with the Wi-Fi link on.", table: "Photos")
        }
    }
}
