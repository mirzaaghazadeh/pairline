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
    static let trialReminderID = "trial.reminder"

    @Published private(set) var authorization: UNAuthorizationStatus = .notDetermined

    /// The conversation on screen; its messages don't raise banners.
    var visibleThread: Int64?
    var onOpenThread: ((Int64) -> Void)?
    var onMarkRead: ((Int64) -> Void)?
    var onOpenStatus: (() -> Void)?

    private let center = UNUserNotificationCenter.current()

    func configure() {
        center.delegate = self
        let copy = UNNotificationAction(identifier: Self.copyCodeAction, title: String(localized: "Copy code"), options: [])
        let read = UNNotificationAction(identifier: Self.markReadAction, title: String(localized: "Mark as read"),
                                        options: [])
        let sms = UNNotificationCategory(identifier: Self.smsCategory, actions: [read], intentIdentifiers: [],
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
        var info: [String: Any] = ["thread": NSNumber(value: message.thread)]
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
        Task { @MainActor in
            switch action {
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
