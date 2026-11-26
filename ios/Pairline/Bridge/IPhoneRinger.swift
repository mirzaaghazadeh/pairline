import AVFoundation
import SwiftUI

/// "Ring my iPhone" from the Android phone (D-084): a two-tone beep for up to 30 seconds, through the playback audio
/// category so it sounds even with the ring switch on silent (at the media volume the user set; iOS doesn't let apps
/// change it). The tone is made here, so there's no sound file. Pairline's `audio` background mode lets it start when
/// the Bluetooth link wakes the app; it never starts during a call.
@MainActor
final class IPhoneRinger: ObservableObject {
    static let shared = IPhoneRinger()

    @Published private(set) var ringing = false

    private var engine: AVAudioEngine?
    private var stopTask: Task<Void, Never>?
    static let duration: TimeInterval = 30

    private init() {}

    func start() {
        guard !ringing else { return }
        guard AppModel.shared.real.calls.current == nil else {
            log.info("ring", "Not ringing: a call is in progress")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            guard let buffer = Self.beepBuffer(format: format) else { return }
            try engine.start()
            player.scheduleBuffer(buffer, at: nil, options: .loops)
            player.play()
            self.engine = engine
        } catch {
            log.warning("ring", "Couldn't ring: \(error.localizedDescription)")
            return
        }
        ringing = true
        Notifier.shared.notifyRinging()
        log.info("ring", "Ringing for the Android phone")
        stopTask?.cancel()
        stopTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.duration * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    func stop() {
        stopTask?.cancel()
        stopTask = nil
        guard ringing else { return }
        engine?.stop()
        engine = nil
        ringing = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        Notifier.shared.clearRinging()
        log.info("ring", "Stopped ringing")
    }

    /// One second: 880 Hz and 1320 Hz beeps of 0.15 s with short fades, then a pause; looped.
    private static func beepBuffer(format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let rate = format.sampleRate
        let frames = AVAudioFrameCount(rate)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames),
              let samples = buffer.floatChannelData?[0] else { return nil }
        buffer.frameLength = frames
        let beeps: [(start: Double, frequency: Double)] = [(0, 880), (0.25, 1320)]
        let length = 0.15
        let fade = 0.01
        for i in 0..<Int(frames) {
            let t = Double(i) / rate
            var value = 0.0
            for beep in beeps where t >= beep.start && t < beep.start + length {
                let local = t - beep.start
                let envelope = min(1, local / fade, (length - local) / fade)
                value = sin(2 * .pi * beep.frequency * local) * envelope
            }
            samples[i] = Float(value * 0.9)
        }
        return buffer
    }
}

/// Over everything while the iPhone rings for the Android phone: one big button to stop it.
struct RingingOverlay: View {
    @ObservedObject private var ringer = IPhoneRinger.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if ringer.ringing {
            VStack(spacing: 16) {
                Image(systemName: "bell.and.waves.left.and.right.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(Color.brandBlue)
                    .symbolRenderingMode(.hierarchical)
                Text("Your Android phone is looking for this iPhone", tableName: "Extras")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Button {
                    ringer.stop()
                    Haptics.success()
                } label: {
                    Text("Found it", tableName: "Extras").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(24)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.regularMaterial))
            .padding(20)
            .transition(.slide(from: .bottom, reduceMotion: reduceMotion))
        }
    }
}
