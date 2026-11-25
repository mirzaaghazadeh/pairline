import Foundation
import NetworkExtension

/// Joins the bridge phone's own hotspot when it offers one (`wifi.offer` with `ssid`/`password`, PROTOCOL.md,
/// "Hotspot"). iOS asks the user before joining. Needs the Hotspot Configuration capability
/// (`com.apple.developer.networking.HotspotConfiguration`); without it, or on the Simulator, it only logs.
@MainActor
final class HotspotJoiner {
    enum Outcome: Equatable {
        case joined
        case alreadyJoined
        case declined
        case unavailable
        case failed
    }

    /// Whether this build can ask iOS to join a network at all.
    static var isSupported: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return true
        #endif
    }

    /// One prompt per hotspot name in this app run: if the user said no, don't ask again.
    private var asked = Set<String>()
    private(set) var lastOutcome: Outcome?

    func join(ssid: String, password: String, completion: ((Outcome) -> Void)? = nil) {
        guard !asked.contains(ssid) else { return }
        asked.insert(ssid)
        guard Self.isSupported, password.utf8.count >= 8, password.utf8.count <= 63 else {
            log.info("wifi", "Joining the bridge hotspot isn't possible here")
            lastOutcome = .unavailable
            completion?(.unavailable)
            return
        }
        let configuration = NEHotspotConfiguration(ssid: ssid, passphrase: password, isWEP: false)
        configuration.joinOnce = false
        log.info("wifi", "Asking iOS to join the bridge hotspot")
        NEHotspotConfigurationManager.shared.apply(configuration) { [weak self] error in
            Task { @MainActor in
                let outcome: Outcome
                if let error = error as NSError?, error.domain == NEHotspotConfigurationErrorDomain {
                    switch NEHotspotConfigurationError(rawValue: error.code) {
                    case .alreadyAssociated?: outcome = .alreadyJoined
                    case .userDenied?: outcome = .declined
                    default: outcome = .failed
                    }
                } else if error != nil {
                    outcome = .failed
                } else {
                    outcome = .joined
                }
                log.info("wifi", "Bridge hotspot: \(outcome)")
                self?.lastOutcome = outcome
                completion?(outcome)
            }
        }
    }

    /// Forget the prompts (a new pairing, or the user asked to try again).
    func reset() {
        asked.removeAll()
    }

    /// Removes the bridge hotspots this app saved in iOS's known networks, except `keeping`: Android names its
    /// local-only hotspot anew each time (AndroidShare_…), so without this old ones pile up in Settings › Wi-Fi, and
    /// iOS may rejoin one with no internet (D-061). iOS lists only the networks this app added.
    func forgetSaved(keeping ssid: String?) {
        guard Self.isSupported else { return }
        NEHotspotConfigurationManager.shared.getConfiguredSSIDs { saved in
            let stale = saved.filter { $0 != ssid }
            guard !stale.isEmpty else { return }
            stale.forEach { NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: $0) }
            Task { @MainActor in log.info("wifi", "Forgot \(stale.count) old bridge hotspot(s)") }
        }
    }
}
