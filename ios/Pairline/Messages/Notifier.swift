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
    var onOpenStatus: (() -> Void)?

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
        center.setNotificationCategories([sms, otp])
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
            content.body = message.body
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
        Task { @MainActor in
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
        Task { @MainActor in
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
