import Foundation
import UIKit
@preconcurrency import UserNotifications

/// Local notifications only (no push, no server): new SMS, one-time codes with a "Copy code" action, bridge
/// battery alerts and the trial reminder. Notification content stays on this iPhone (and, per iOS settings,
/// a paired Apple Watch).
@MainActor
final class Notifier: NSObject, ObservableObject {
    static let shared = Notifier()

    static let smsCategory = "sms"
    static let otpCategory = "sms.otp"
    static let copyCodeAction = "copy-code"
    static let markReadAction = "mark-read"
    static let replyAction = "reply"
    static let mirrorCategory = "mirror"
    /// A mirrored notification whose Android app takes a typed reply (D-086).
    static let mirrorReplyCategory = "mirror.reply"
    static let mirrorReplyAction = "mirror-reply"
    static let trialReminderID = "trial.reminder"
    static let callsPausedID = "calls.paused"
    static let incomingCallID = "calls.incoming"

    @Published private(set) var authorization: UNAuthorizationStatus = .notDetermined

    /// The conversation on screen; its messages don't raise banners.
    var visibleThread: Int64?
    var onOpenThread: ((Int64) -> Void)?
    var onMarkRead: ((Int64) -> Void)?
    /// A reply typed in a message notification (D-074): thread, address, SIM, text.
    var onReply: ((Int64, String, Int?, String) -> Void)?
    /// A mirrored notification was dismissed here (D-077): its key on the bridge phone.
    var onMirrorDismissed: ((String) -> Void)?
    /// A reply typed in a mirrored notification (D-086): its key and the text.
    var onMirrorReply: ((String, String) -> Void)?
    var onOpenStatus: (() -> Void)?
    /// Remote mode (D-092): a CloudKit alert push reached the open app.
    var onRemotePush: (() -> Void)?

    // MARK: Muted conversations (D-074): no banner or sound, still counted as unread.

    private static let mutedKey = "messages.muted"

    @Published private(set) var muted: Set<Int64> = Set(
        (UserDefaults.standard.array(forKey: "messages.muted") as? [NSNumber] ?? []).map(\.int64Value))

    func isMuted(_ thread: Int64) -> Bool { muted.contains(thread) }

    func setMuted(_ thread: Int64, _ on: Bool) {
        if on { muted.insert(thread) } else { muted.remove(thread) }
        UserDefaults.standard.set(muted.map { NSNumber(value: $0) }, forKey: Self.mutedKey)
    }

    private let center = UNUserNotificationCenter.current()

    func configure() {
        center.delegate = self
        let copy = UNNotificationAction(identifier: Self.copyCodeAction, title: String(localized: "Copy code"), options: [])
        let read = UNNotificationAction(identifier: Self.markReadAction, title: String(localized: "Mark as read"),
                                        options: [])
        // Sent through the bridge from the background, like a message typed in Pairline (D-074).
        let reply = UNTextInputNotificationAction(identifier: Self.replyAction,
                                                  title: String(localized: "Reply", table: "PhoneTools"),
                                                  options: [.authenticationRequired],
                                                  textInputButtonTitle: String(localized: "Send", table: "PhoneTools"),
                                                  textInputPlaceholder: String(localized: "Message", table: "PhoneTools"))
        let sms = UNNotificationCategory(identifier: Self.smsCategory, actions: [reply, read], intentIdentifiers: [],
                                         hiddenPreviewsBodyPlaceholder: String(localized: "New message"),
                                         options: [])
        let otp = UNNotificationCategory(identifier: Self.otpCategory, actions: [copy, read], intentIdentifiers: [],
                                         hiddenPreviewsBodyPlaceholder: String(localized: "New message"),
                                         options: [])
        // Mirrored app notifications (D-077): swiping one away here clears it on the bridge phone too.
        let mirror = UNNotificationCategory(identifier: Self.mirrorCategory, actions: [], intentIdentifiers: [],
                                            hiddenPreviewsBodyPlaceholder: String(localized: "New notification", table: "PhoneTools"),
                                            options: [.customDismissAction])
        // …and with a reply field when the Android app's notification takes one (D-086).
        let mirrorReply = UNTextInputNotificationAction(identifier: Self.mirrorReplyAction,
                                                        title: String(localized: "Reply", table: "PhoneTools"),
                                                        options: [.authenticationRequired],
                                                        textInputButtonTitle: String(localized: "Send", table: "PhoneTools"),
                                                        textInputPlaceholder: String(localized: "Message", table: "PhoneTools"))
        let mirrorWithReply = UNNotificationCategory(identifier: Self.mirrorReplyCategory, actions: [mirrorReply],
                                                     intentIdentifiers: [],
                                                     hiddenPreviewsBodyPlaceholder: String(localized: "New notification", table: "PhoneTools"),
                                                     options: [.customDismissAction])
        center.setNotificationCategories([sms, otp, mirror, mirrorWithReply])
        // Remote mode's notification extension follows "Show message text" too.
        RemoteInbox.setShowsMessageText(AppSettings.shared.showMessagePreviews)
        Task { await refreshAuthorization() }
    }

    func refreshAuthorization() async {
        authorization = await center.notificationSettings().authorizationStatus
    }

    @discardableResult
    func requestAuthorization() async -> Bool {
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        await refreshAuthorization()
        return granted
    }

    /// A new incoming SMS. `sender` is the contact name or the number.
    func notifyMessage(_ message: StoredMessage, sender: String, isDemo: Bool) {
        guard authorization == .authorized || authorization == .provisional else { return }
        // A muted conversation stays quiet, except for one-time codes, which are usually wanted right away.
        if isMuted(message.thread) && OTPDetector.code(in: message.body) == nil { return }
        let content = UNMutableNotificationContent()
        content.title = sender
        let code = OTPDetector.code(in: message.body)
        if AppSettings.shared.showMessagePreviews {
            // A picture message without text (D-089).
            content.body = message.body.isEmpty && !message.parts.isEmpty
                ? "📷 " + String(localized: "Picture message", table: "MMS") : message.body
        } else {
            content.body = code == nil ? String(localized: "New message") : String(localized: "New one-time code")
        }
        if isDemo { content.subtitle = String(localized: "Demo") }
        content.sound = .default
        content.threadIdentifier = "thread-\(message.thread)"
        content.categoryIdentifier = code == nil ? Self.smsCategory : Self.otpCategory
        var info: [String: Any] = ["thread": NSNumber(value: message.thread), "addr": message.addr]
        if let sim = message.sim { info["sim"] = NSNumber(value: sim) }
        if let code { info["code"] = code }
        content.userInfo = info
        if #available(iOS 15.0, *) {
            content.interruptionLevel = code == nil ? .active : .timeSensitive
            content.relevanceScore = code == nil ? 0.5 : 1
        }
        center.add(UNNotificationRequest(identifier: "sms-\(message.key)", content: content, trigger: nil))
    }

    // MARK: Notifications from Android apps (D-077)

    private static func mirrorID(_ key: String) -> String { "mirror-" + key }

    /// A notification from an app the user chose on the bridge phone. The same key replaces the earlier one (an app
    /// updating its notification); grouped by app.
    func notifyMirrored(_ n: MirroredNotification) {
        guard authorization == .authorized || authorization == .provisional else { return }
        let content = UNMutableNotificationContent()
        let appName = n.appName ?? n.app
        if let title = n.title, !title.isEmpty {
            content.title = title
            content.subtitle = appName
        } else {
            content.title = appName
        }
        if AppSettings.shared.showMessagePreviews {
            content.body = n.text ?? ""
        } else {
            content.body = String(localized: "New notification", table: "PhoneTools")
        }
        content.sound = .default
        content.threadIdentifier = "app-" + n.app
        content.categoryIdentifier = n.reply == true ? Self.mirrorReplyCategory : Self.mirrorCategory
        content.userInfo = ["mirror": n.key]
        if #available(iOS 15.0, *) { content.relevanceScore = 0.3 }
        center.add(UNNotificationRequest(identifier: Self.mirrorID(n.key), content: content, trigger: nil))
    }

    /// A reply typed in a mirrored notification couldn't go (D-086): the bridge isn't linked, or the notification is
    /// gone there. Said once, without the text.
    func notifyMirrorReplyFailed() {
        guard authorization == .authorized || authorization == .provisional else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Reply not sent", table: "Extras")
        content.body = String(localized: "The Android phone isn't connected, or that notification is gone there. Reply on the Android phone instead.", table: "Extras")
        content.sound = .default
        center.add(UNNotificationRequest(identifier: "mirror-reply-failed", content: content, trigger: nil))
    }

    /// Gone on the bridge phone (read or dismissed there): gone here too.
    func removeMirrored(key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [Self.mirrorID(key)])
    }

    /// One summary instead of many banners after catching up.
    func notifyCaughtUp(count: Int) {
        guard authorization == .authorized, count > 0 else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Pairline")
        content.body = String(localized: "\(count) new messages arrived while you were away.")
        content.sound = .default
        center.add(UNNotificationRequest(identifier: "sms-catchup", content: content, trigger: nil))
    }

    /// iOS didn't show the incoming call screen (Focus, or CallKit unavailable) while Pairline is in the background.
    func notifyIncomingCall(from caller: String) {
        guard authorization == .authorized else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Incoming call", table: "CallAudio")
        content.body = String(localized: "\(caller) is calling your Android number. Open Pairline to answer.", table: "CallAudio")
        content.sound = .default
        center.add(UNNotificationRequest(identifier: Self.incomingCallID, content: content, trigger: nil))
    }

    func clearIncomingCall() {
        center.removeDeliveredNotifications(withIdentifiers: [Self.incomingCallID])
    }

    /// Pairline was swiped away in the app switcher (see AppDelegate.applicationWillTerminate).
    func notifyCallsPaused() {
        guard authorization == .authorized else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Calls and messages paused", table: "Hotspot")
        content.body = String(localized: "Pairline was closed, so calls and messages from your Android phone can't reach this iPhone. Open Pairline to get them again, and next time leave it in the background instead of swiping it away.", table: "Hotspot")
        content.sound = .default
        center.add(UNNotificationRequest(identifier: Self.callsPausedID, content: content,
                                         trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)))
    }

    func clearCallsPaused() {
        center.removePendingNotificationRequests(withIdentifiers: [Self.callsPausedID])
        center.removeDeliveredNotifications(withIdentifiers: [Self.callsPausedID])
    }

    func notifyLowBattery(level: Int) {
        guard authorization == .authorized, AppSettings.shared.lowBatteryAlert else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Bridge phone battery low")
        content.body = String(localized: "Your bridge phone is at \(level)%. Charge it to keep calls and messages coming to this iPhone.")
        content.sound = .default
        content.userInfo = ["status": true]
        center.add(UNNotificationRequest(identifier: "battery-low", content: content, trigger: nil))
    }

    // MARK: Data meter and ringing (D-084)

    /// The bridge phone's mobile data reached 80% or 100% of the user's monthly limit.
    func notifyDataUsage(percent: Int, bytes: Int64) {
        guard authorization == .authorized || authorization == .provisional else { return }
        let content = UNMutableNotificationContent()
        let used = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .decimal)
        content.title = percent >= 100
            ? String(localized: "Mobile data limit reached", table: "Extras")
            : String(localized: "Mobile data almost used up", table: "Extras")
        content.body = String(localized: "The Android phone has used \(used) of mobile data this month (\(percent)% of your limit).", table: "Extras")
        content.sound = .default
        content.userInfo = ["status": true]
        center.add(UNNotificationRequest(identifier: "data-\(percent)", content: content, trigger: nil))
    }

    static let ringingID = "iphone.ringing"

    /// Shown while the Android phone rings this iPhone, so the user knows why it's ringing; a tap opens Pairline,
    /// which stops it.
    func notifyRinging() {
        guard authorization == .authorized || authorization == .provisional else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Your Android phone is looking for this iPhone", table: "Extras")
        content.body = String(localized: "Open Pairline to stop the sound.", table: "Extras")
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        center.add(UNNotificationRequest(identifier: Self.ringingID, content: content, trigger: nil))
    }

    func clearRinging() {
        center.removeDeliveredNotifications(withIdentifiers: [Self.ringingID])
    }

    // MARK: Bridge phone left behind (D-083)

    static let leftBehindID = "bridge.leftBehind"
    /// How long the link may stay down before the alert: long enough for a walk to the next room and back.
    static let leftBehindDelay: TimeInterval = 5 * 60

    /// Scheduled when the link drops; iOS delivers it only if the link isn't back by then, because reconnecting
    /// removes it ([cancelLeftBehind]). Works while Pairline is suspended, since iOS holds the timer.
    func scheduleLeftBehind() {
        guard AppSettings.shared.leftBehindAlert,
              authorization == .authorized || authorization == .provisional else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Bridge phone out of reach", table: "Inbox")
        content.body = String(localized: "Calls and texts to your Android phone's SIM can't reach this iPhone. Did you leave it behind?", table: "Inbox")
        content.sound = .default
        content.userInfo = ["status": true]
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        center.add(UNNotificationRequest(identifier: Self.leftBehindID, content: content,
                                         trigger: UNTimeIntervalNotificationTrigger(timeInterval: Self.leftBehindDelay,
                                                                                    repeats: false)))
    }

    func cancelLeftBehind() {
        center.removePendingNotificationRequests(withIdentifiers: [Self.leftBehindID])
        center.removeDeliveredNotifications(withIdentifiers: [Self.leftBehindID])
    }

    /// The carrier says a voicemail is waiting on a SIM of the bridge phone (D-082). Tapping opens the Bridge tab,
    /// whose SIM card has "Call voicemail".
    func notifyVoicemail(simName: String?) {
        guard authorization == .authorized || authorization == .provisional else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "New voicemail", table: "Carrier")
        if let simName {
            content.body = String(localized: "A voicemail is waiting on \(simName). Open Pairline to call your voicemail.", table: "Carrier")
        } else {
            content.body = String(localized: "A voicemail is waiting. Open Pairline to call your voicemail.", table: "Carrier")
        }
        content.sound = .default
        content.userInfo = ["status": true]
        center.add(UNNotificationRequest(identifier: "voicemail", content: content, trigger: nil))
    }

    func scheduleTrialReminder(trialEnd: Date, price: String) {
        center.removePendingNotificationRequests(withIdentifiers: [Self.trialReminderID])
        guard AppSettings.shared.trialReminder else { return }
        let fireDate = TrialPolicy.reminderDate(trialEnd: trialEnd)
        let interval = fireDate.timeIntervalSinceNow
        guard interval > 60 else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Your Pairline trial ends tomorrow")
        content.body = String(localized: "Unlock Pairline for a one-time \(price) to keep using your Android SIM on this iPhone. Your synced messages stay readable either way.")
        content.sound = .default
        center.add(UNNotificationRequest(identifier: Self.trialReminderID, content: content,
                                         trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)))
    }

    func cancelTrialReminder() {
        center.removePendingNotificationRequests(withIdentifiers: [Self.trialReminderID])
    }

    func removeDelivered(thread: Int64) {
        center.getDeliveredNotifications { [center] notes in
            let ids = notes.filter { ($0.request.content.userInfo["thread"] as? NSNumber)?.int64Value == thread }
                .map(\.request.identifier)
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    func setBadge(_ count: Int) {
        if #available(iOS 16.0, *) {
            center.setBadgeCount(count)
        }
    }

    /// Removes everything (unpair).
    func removeAll() {
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
        setBadge(0)
    }
}

extension Notifier: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let thread = (notification.request.content.userInfo["thread"] as? NSNumber)?.int64Value
        // CloudKit's pushes carry a "ck" dictionary.
        let isRemotePush = notification.request.content.userInfo["ck"] != nil
        Task { @MainActor in
            if isRemotePush { Notifier.shared.onRemotePush?() }
            if let thread, thread == Notifier.shared.visibleThread {
                completionHandler([])
            } else {
                completionHandler([.banner, .sound, .list])
            }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        let thread = (info["thread"] as? NSNumber)?.int64Value
        let code = info["code"] as? String
        let isStatus = info["status"] != nil
        let addr = info["addr"] as? String
        let sim = (info["sim"] as? NSNumber)?.intValue
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        let mirrorKey = info["mirror"] as? String
        Task { @MainActor in
            if let mirrorKey, action == UNNotificationDismissActionIdentifier {
                Notifier.shared.onMirrorDismissed?(mirrorKey)
                return completionHandler()
            }
            if let mirrorKey, action == Notifier.mirrorReplyAction {
                if let typed, !typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Notifier.shared.onMirrorReply?(mirrorKey, typed)
                }
                return completionHandler()
            }
            switch action {
            case Notifier.replyAction:
                if let thread, let addr, let typed, !typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Notifier.shared.onReply?(thread, addr, sim, typed)
                }
            case Notifier.copyCodeAction:
                if let code {
                    Clipboard.copy(code, isCode: true)
                    log.info("notify", "Copied a one-time code from a notification")
                }
            case Notifier.markReadAction:
                if let thread { Notifier.shared.onMarkRead?(thread) }
            case UNNotificationDefaultActionIdentifier:
                if let thread {
                    Notifier.shared.onOpenThread?(thread)
                } else if isStatus {
                    Notifier.shared.onOpenStatus?()
                }
            default:
                break
            }
            completionHandler()
        }
    }
}
