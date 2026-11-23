import Foundation

/// Access to link features under D-017: 3 free days from the trial's Apple-signed purchase date, then a
/// one-time purchase.
enum AccessState: Equatable {
    case loading
    case notStarted
    case trial(endsAt: Date)
    case expired(endedAt: Date)
    case purchased

    var allowsLink: Bool {
        switch self {
        case .purchased, .trial: return true
        default: return false
        }
    }

    var isPurchased: Bool { self == .purchased }

    var isTrial: Bool {
        if case .trial = self { return true }
        return false
    }
}

enum TrialPolicy {
    static let duration: TimeInterval = 3 * 24 * 3600
    /// The reminder goes out about a day before the trial ends.
    static let reminderLead: TimeInterval = 24 * 3600

    static func state(purchased: Bool, trialStart: Date?, now: Date) -> AccessState {
        if purchased { return .purchased }
        guard let trialStart else { return .notStarted }
        let end = trialStart.addingTimeInterval(duration)
        return now < end ? .trial(endsAt: end) : .expired(endedAt: end)
    }

    /// The time used for the trial clock: the latest of the device clock, the newest Apple-signed date seen, and the
    /// highest time seen before (kept in the Keychain), so turning the clock back doesn't extend the trial.
    static func trustedNow(deviceNow: Date, highWater: Date?, signedDates: [Date]) -> Date {
        ([deviceNow] + signedDates + [highWater].compactMap { $0 }).max() ?? deviceNow
    }

    static func reminderDate(trialEnd: Date) -> Date {
        trialEnd.addingTimeInterval(-reminderLead)
    }

    /// Whole days and hours left, rounded up to the hour, for "2 days 5 hours left".
    static func remaining(until end: Date, now: Date) -> (days: Int, hours: Int) {
        let seconds = max(0, end.timeIntervalSince(now))
        let totalHours = Int((seconds / 3600).rounded(.up))
        return (totalHours / 24, totalHours % 24)
    }
}

/// Where the trial clock's high-water mark is kept.
protocol TrustedTimeStore: AnyObject {
    var highWater: Date? { get }
    func save(_ date: Date)
}

final class KeychainTimeStore: TrustedTimeStore {
    private let account = "trial.highwater"

    var highWater: Date? {
        guard let data = Keychain.get(account: account), let seconds = Double(String(decoding: data, as: UTF8.self))
        else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }

    func save(_ date: Date) {
        if let current = highWater, current >= date { return }
        try? Keychain.set(Data(String(date.timeIntervalSince1970).utf8), account: account)
    }
}

final class MemoryTimeStore: TrustedTimeStore {
    var highWater: Date?
    func save(_ date: Date) {
        if let current = highWater, current >= date { return }
        highWater = date
    }
}
