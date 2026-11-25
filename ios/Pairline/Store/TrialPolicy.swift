import Foundation

/// Access to link features under D-017 and D-036: 7 free days from the trial's Apple-signed purchase date, then a
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

    var isExpired: Bool {
        if case .expired = self { return true }
        return false
    }
}

enum TrialPolicy {
    /// A free week (D-036).
    static let duration: TimeInterval = 7 * 24 * 3600
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

/// One free first sync before a trial (D-069): after pairing, the link stays up until the bridge's messages, contacts
/// and call log have synced once, so people see their own messages before choosing the free week. Then the link waits
/// for the trial or the purchase; sending, calls and the rest always need one of them. Not time-based, and what synced
/// stays readable, like after a trial.
enum FirstSyncPolicy {
    static func linkAllowed(access: AccessState, firstSyncUsed: Bool) -> Bool {
        access.allowsLink || firstSyncOnly(access: access, firstSyncUsed: firstSyncUsed)
    }

    /// The link is up only for the free first sync.
    static func firstSyncOnly(access: AccessState, firstSyncUsed: Bool) -> Bool {
        access == .notStarted && !firstSyncUsed
    }
}

/// Whether the free first sync was used. In the Keychain, like the trial clock, so unpairing and pairing again or
/// reinstalling doesn't give another one.
enum FirstSyncStore {
    private static let account = "trial.firstsync"

    static var used: Bool { Keychain.get(account: account) != nil }

    static func markUsed() {
        try? Keychain.set(Data("1".utf8), account: account)
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
