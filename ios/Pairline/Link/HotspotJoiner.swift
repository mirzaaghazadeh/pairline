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
}
