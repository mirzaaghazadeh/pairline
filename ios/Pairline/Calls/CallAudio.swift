import AVFoundation
import Foundation
import os

/// The Wi-Fi link's encrypted media channel as call audio needs it (M5): lossy, unordered, never blocks.
protocol AudioPacketTransport: AnyObject {
    var isOpen: Bool { get }
    func send(_ packet: Data)
    var onPacket: ((Data) -> Void)? { get set }
}

/// Call-audio packets (PROTOCOL.md "Call audio", D-047): `kind ‖ sequence (2 bytes, big-endian) ‖ samples`.
/// Kind 0x01 is one 20 ms frame of 16-bit little-endian mono PCM at 16 kHz. Unknown kinds and sizes are dropped.
enum AudioPackets {
    static let kindPCM16: UInt8 = 0x01
    static let sampleRate: Double = 16_000
    static let frameSamples = 320
    static let headerBytes = 3

    static func encode(sequence: UInt16, frame: [Int16]) -> Data {
        precondition(frame.count == frameSamples)
        var data = Data(capacity: headerBytes + frameSamples * 2)
        data.append(kindPCM16)
        data.append(UInt8(sequence >> 8))
        data.append(UInt8(sequence & 0xFF))
        for sample in frame {
            let v = UInt16(bitPattern: sample)
            data.append(UInt8(v & 0xFF))
            data.append(UInt8(v >> 8))
        }
        return data
    }

    static func decode(_ packet: Data) -> (sequence: UInt16, frame: [Int16])? {
        let bytes = [UInt8](packet)
        guard bytes.count == headerBytes + frameSamples * 2, bytes[0] == kindPCM16 else { return nil }
        let sequence = UInt16(bytes[1]) << 8 | UInt16(bytes[2])
        var frame = [Int16](repeating: 0, count: frameSamples)
        for i in 0..<frameSamples {
            let lo = UInt16(bytes[headerBytes + 2 * i])
            let hi = UInt16(bytes[headerBytes + 2 * i + 1])
            frame[i] = Int16(bitPattern: lo | hi << 8)
        }
        return (sequence, frame)
    }
}

/// Evens out network jitter exactly like the bridge's: holds 60 ms before playing, plays in sequence order, covers a
/// lost frame by repeating the previous one at half volume (twice at most), then silence. Extra delay (a burst after a
/// Wi-Fi stall) drains by skipping quiet frames only, so speech is never cut; only past 1 s does it jump. Thread-safe.
final class JitterBuffer: @unchecked Sendable {
    private let target: Int
    private let capacity: Int
    private let lock = OSAllocatedUnfairLock()
    private var frames: [UInt16: [Int16]] = [:]
    private var next: UInt16?
    private var buffering = true
    private var last: [Int16]?
    private var misses = 0
    private var skippedFrames = 0

    /// Frames above the target before quiet frames are skipped, and the level (RMS) below which a frame is quiet.
    static let drainAbove = 4
    static let quietRMS = 160.0

    /// Quiet frames skipped to bring the delay back down.
    var skipped: Int { lock.withLock { skippedFrames } }

    init(target: Int = 3, capacity: Int = 50) {
        self.target = target
        self.capacity = capacity
    }

    static func rms(_ frame: [Int16]) -> Double {
        guard !frame.isEmpty else { return 0 }
        let sum = frame.reduce(0.0) { $0 + Double($1) * Double($1) }
        return (sum / Double(frame.count)).squareRoot()
    }

    /// Signed distance a − b on the 16-bit sequence circle.
    static func diff(_ a: UInt16, _ b: UInt16) -> Int { Int(Int16(bitPattern: a &- b)) }

    func put(_ sequence: UInt16, _ frame: [Int16]) {
        guard frame.count == AudioPackets.frameSamples else { return }
        lock.withLock {
            if next == nil { next = sequence }
            guard let expected = next else { return }
            if buffering {
                if Self.diff(sequence, expected) < 0 { next = sequence }
            } else if Self.diff(sequence, expected) < 0 {
                return // arrived after its turn
            }
            frames[sequence] = frame
            if buffering, frames.count >= target { buffering = false }
            if frames.count > capacity, let newest = frames.keys.max(by: { Self.diff($0, $1) < 0 }) {
                let start = newest &- UInt16(target - 1)
                next = start
                for key in frames.keys where Self.diff(key, start) < 0 { frames[key] = nil }
            }
        }
    }

    /// The next 20 ms to play, or nil while buffering (play silence). Call once per frame period.
    func take() -> [Int16]? {
        lock.withLock {
            guard !buffering, var expected = next else { return nil }
            // Too much waiting: skip one quiet frame per period until back near the target (never speech).
            if frames.count > target + Self.drainAbove, let waiting = frames[expected], Self.rms(waiting) <= Self.quietRMS {
                frames[expected] = nil
                expected = expected &+ 1
                skippedFrames += 1
            }
            next = expected &+ 1
            if let frame = frames.removeValue(forKey: expected) {
                last = frame
                misses = 0
                return frame
            }
            misses += 1
            if misses > 25 {
                frames.removeAll()
                next = nil
                buffering = true
                last = nil
                misses = 0
                return nil
            }
            guard misses <= 2, let previous = last else { return nil }
            let gain = misses == 1 ? 0.5 : 0.25
            return previous.map { Int16(Double($0) * gain) }
        }
    }
}

/// Plays the caller's voice and sends this iPhone's microphone during a relayed call. Runs inside the audio session
/// CallKit activates (or one the call screen activates when CallKit isn't available). Voice processing gives echo
/// cancellation, so the speaker can be used. Nothing is recorded or kept.
final class CallAudioEngine {
    private let engine = AVAudioEngine()
    private let transport: AudioPacketTransport
    /// Echo cancellation (Apple's voice processing). Some routes and devices refuse to start with it; the call
    /// center then tries a fresh engine without it rather than giving the audio back to the Android phone.
    let voiceProcessing: Bool
    private let jitter = JitterBuffer()
    private let state = OSAllocatedUnfairLock(initialState: SendState())
    private var converter: AVAudioConverter?
    private var sourceNode: AVAudioSourceNode?
    private var playFrame: [Int16] = []
    private var playIndex = 0
    private var configurationObserver: NSObjectProtocol?
    private(set) var isRunning = false

    /// Whether AVAudioEngine itself still runs. A configuration change may stop it while this object still counts as
    /// running ([isRunning] says it was started and not stopped by us).
    var engineRunning: Bool { engine.isRunning }

    /// iOS stopped the engine because the audio route or format changed (headset, car, speaker, a CallKit
    /// interruption). Called on the main queue; the call center builds a fresh engine.
    var onConfigurationChange: (() -> Void)?

    private struct SendState {
        var pending: [Int16] = []
        var sequence: UInt16 = 0
        var muted = false
        var sent = 0
    }

    init(transport: AudioPacketTransport, voiceProcessing: Bool = true) {
        self.transport = transport
        self.voiceProcessing = voiceProcessing
    }

    var muted: Bool {
        get { state.withLock { $0.muted } }
        set { state.withLock { $0.muted = newValue } }
    }

    func start() throws {
        guard !isRunning else { return }
        do {
            try startEngine()
        } catch {
            // Leave nothing half-built behind: the caller may try a fresh engine next.
            transport.onPacket = nil
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            if let sourceNode { engine.detach(sourceNode) }
            sourceNode = nil
            if voiceProcessing { try? engine.inputNode.setVoiceProcessingEnabled(false) }
            throw error
        }
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main
        ) { [weak self] _ in
            guard let self, self.isRunning else { return }
            log.info("audio", "Audio route changed; restarting call audio")
            self.onConfigurationChange?()
        }
    }

    private func startEngine() throws {
        let input = engine.inputNode
        if voiceProcessing { try input.setVoiceProcessingEnabled(true) }
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let wire = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: AudioPackets.sampleRate,
                                       channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: inputFormat, to: wire) else {
            throw CallAudioError.noMicrophone
        }
        self.converter = converter
        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.captured(buffer, wire: wire)
        }

        guard let playFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: AudioPackets.sampleRate,
                                             channels: 1, interleaved: false) else { throw CallAudioError.noMicrophone }
        let source = AVAudioSourceNode(format: playFormat) { [weak self] _, _, frameCount, bufferList -> OSStatus in
            self?.render(Int(frameCount), bufferList)
            return noErr
        }
        sourceNode = source
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: playFormat)

        transport.onPacket = { [weak self] packet in
            guard let (sequence, frame) = AudioPackets.decode(packet) else { return }
            self?.jitter.put(sequence, frame)
        }
        engine.prepare()
        try engine.start()
        isRunning = true
        log.info("audio", "Call audio on this iPhone: mic \(Int(inputFormat.sampleRate)) Hz, echo cancellation \(voiceProcessing ? "on" : "off")")
    }

    /// After iOS stopped the engine for a configuration change: rebuild the microphone tap and the player for the new
    /// formats and start the same engine again. Echo cancellation stays as it is: switching it on in a fresh engine is
    /// itself a configuration change, so a fresh engine per change stopped again 0.15 s later, six times, and the call
    /// stayed silent (seen on a tester's iPhone 14 Pro Max, D-065).
    func restartAfterConfigurationChange() throws {
        guard isRunning else { return }
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let wire = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: AudioPackets.sampleRate,
                                       channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: inputFormat, to: wire),
              let playFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: AudioPackets.sampleRate,
                                             channels: 1, interleaved: false) else {
            throw CallAudioError.noMicrophone
        }
        self.converter = converter
        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.captured(buffer, wire: wire)
        }
        if let sourceNode {
            engine.disconnectNodeOutput(sourceNode)
            engine.connect(sourceNode, to: engine.mainMixerNode, format: playFormat)
        }
        engine.prepare()
        try engine.start()
        log.info("audio", "Call audio restarted after a route change: mic \(Int(inputFormat.sampleRate)) Hz")
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
        configurationObserver = nil
        transport.onPacket = nil
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        if let sourceNode { engine.detach(sourceNode) }
        sourceNode = nil
        if voiceProcessing { try? engine.inputNode.setVoiceProcessingEnabled(false) }
        let sent = state.withLock { $0.sent }
        log.info("audio", "Call audio stopped: sent \(sent) frames, quiet frames skipped \(jitter.skipped)")
    }

    /// Routes the call to the loudspeaker or back to the receiver.
    func setSpeaker(_ on: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
    }

    // Microphone → 16 kHz frames → bridge. Runs on the tap's thread.
    private func captured(_ buffer: AVAudioPCMBuffer, wire: AVAudioFormat) {
        guard let converter else { return }
        let ratio = wire.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: wire, frameCapacity: capacity) else { return }
        var delivered = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if delivered {
                status.pointee = .noDataNow
                return nil
            }
            delivered = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let samples = out.int16ChannelData?[0] else { return }
        let converted = Array(UnsafeBufferPointer(start: samples, count: Int(out.frameLength)))
        var packets: [Data] = []
        state.withLock { s in
            s.pending.append(contentsOf: converted)
            while s.pending.count >= AudioPackets.frameSamples {
                let frame = s.muted
                    ? [Int16](repeating: 0, count: AudioPackets.frameSamples)
                    : Array(s.pending.prefix(AudioPackets.frameSamples))
                s.pending.removeFirst(AudioPackets.frameSamples)
                packets.append(AudioPackets.encode(sequence: s.sequence, frame: frame))
                s.sequence &+= 1
                s.sent += 1
            }
        }
        for packet in packets { transport.send(packet) }
    }

    // Bridge → speaker. Runs on the audio render thread: one jitter-buffer frame per 20 ms of output.
    private func render(_ frameCount: Int, _ bufferList: UnsafeMutablePointer<AudioBufferList>) {
        let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
        guard let data = buffers.first?.mData else { return }
        let out = data.assumingMemoryBound(to: Float.self)
        var i = 0
        while i < frameCount {
            if playIndex >= playFrame.count {
                playFrame = jitter.take() ?? CallAudioEngine.silence
                playIndex = 0
            }
            out[i] = Float(playFrame[playIndex]) / 32768
            playIndex += 1
            i += 1
        }
    }

    private static let silence = [Int16](repeating: 0, count: AudioPackets.frameSamples)
}

enum CallAudioError: LocalizedError {
    case noMicrophone

    var errorDescription: String? {
        String(localized: "The iPhone's microphone isn't available for the call.", table: "CallAudio")
    }
}
