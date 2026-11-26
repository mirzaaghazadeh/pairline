import AVFoundation
import Speech
import SwiftUI

/// Live captions of the caller's voice during a call relayed to this iPhone (D-087). Apple's speech recognition runs
/// with `requiresOnDeviceRecognition`, and only languages this iPhone can recognize on the device are offered, so the
/// call audio never leaves the iPhone (AGENTS.md rule 1). Nothing is stored: the text is on screen during the call and
/// gone when it ends. Only the caller's side (the audio from the bridge) is captioned, not this iPhone's microphone.
@MainActor
final class LiveCaptions: ObservableObject {
    static let shared = LiveCaptions()

    /// The last lines recognized, for the call screen.
    @Published private(set) var text = ""
    @Published private(set) var running = false
    /// Why captions can't run, for the call screen; nil when they can.
    @Published private(set) var problem: String?

    private var recognizer: SFSpeechRecognizer?
    private var task: SFSpeechRecognitionTask?
    private var restart: Task<Void, Never>?
    /// The request the audio goes into; read on the audio channel's queue, so behind a lock.
    private let feed = RequestBox()
    /// Text recognized by earlier requests in this call (a request is renewed every minute).
    private var earlier = ""

    private static let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: AudioPackets.sampleRate,
                                              channels: 1, interleaved: false)!

    private init() {}

    /// Languages offered: the ones this iPhone recognizes on the device, Pairline's four first.
    static func availableLocales() -> [Locale] {
        var seen = Set<String>()
        let candidates = [Locale.current.identifier] + Locale.preferredLanguages + ["en-US", "fa-IR", "ar-SA", "tr-TR"]
        return candidates.compactMap { id -> Locale? in
            guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: id)),
                  recognizer.supportsOnDeviceRecognition, seen.insert(recognizer.locale.identifier).inserted
            else { return nil }
            return recognizer.locale
        }
    }

    /// The language chosen in the call screen, or the first one available.
    static func chosenLocale() -> Locale? {
        let stored = UserDefaults.standard.string(forKey: "captions.locale")
        let available = availableLocales()
        return available.first { $0.identifier == stored } ?? available.first
    }

    static func choose(_ locale: Locale) {
        UserDefaults.standard.set(locale.identifier, forKey: "captions.locale")
    }

    func start() async {
        guard !running else { return }
        problem = nil
        let status = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        guard status == .authorized else {
            problem = String(localized: "Allow Speech Recognition for Pairline in iPhone Settings to see captions.", table: "Captions")
            return
        }
        guard let locale = Self.chosenLocale(), let recognizer = SFSpeechRecognizer(locale: locale),
              recognizer.supportsOnDeviceRecognition else {
            problem = String(localized: "This iPhone can't recognize speech on the device for your languages, and Pairline never sends call audio anywhere else. Add a dictation language in iPhone Settings › General › Keyboard.", table: "Captions")
            return
        }
        self.recognizer = recognizer
        earlier = ""
        text = ""
        running = true
        beginRequest()
        log.info("captions", "Live captions on (on-device, \(locale.identifier))")
    }

    func stop() {
        guard running else { return }
        running = false
        restart?.cancel()
        restart = nil
        feed.request?.endAudio()
        feed.request = nil
        task?.cancel()
        task = nil
        recognizer = nil
        text = ""
        earlier = ""
        log.info("captions", "Live captions off")
    }

    /// One 20 ms frame of the caller's voice, 16 kHz, from the audio channel's queue.
    nonisolated func append(_ frame: [Int16]) {
        guard let request = feed.request,
              let buffer = AVAudioPCMBuffer(pcmFormat: Self.format, frameCapacity: AVAudioFrameCount(frame.count)),
              let samples = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(frame.count)
        for (i, sample) in frame.enumerated() { samples[i] = Float(sample) / 32768 }
        request.append(buffer)
    }

    /// A request for up to a minute of speech, then a fresh one, so long calls keep being captioned.
    private func beginRequest() {
        guard running, let recognizer else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        if #available(iOS 16, *) { request.addsPunctuation = true }
        feed.request = request
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let transcript = result?.bestTranscription.formattedString
            let final = result?.isFinal == true || error != nil
            Task { @MainActor in
                guard let self, self.running else { return }
                if let transcript { self.show(transcript) }
                if final { self.renew(with: transcript) }
            }
        }
        restart?.cancel()
        restart = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 55_000_000_000)
            guard !Task.isCancelled else { return }
            self?.feed.request?.endAudio()
        }
    }

    private func renew(with transcript: String?) {
        if let transcript, !transcript.isEmpty { earlier = Self.tail(earlier + " " + transcript) }
        feed.request = nil
        task = nil
        beginRequest()
    }

    private func show(_ transcript: String) {
        text = Self.tail((earlier.isEmpty ? "" : earlier + " ") + transcript)
    }

    /// The last ~240 characters, cut at a word.
    nonisolated static func tail(_ text: String, limit: Int = 240) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard trimmed.count > limit else { return trimmed }
        let cut = trimmed.suffix(limit)
        if let space = cut.firstIndex(of: " ") { return String(cut[cut.index(after: space)...]) }
        return String(cut)
    }
}

/// The current recognition request, shared between the main actor and the audio queue.
private final class RequestBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: SFSpeechAudioBufferRecognitionRequest?

    var request: SFSpeechAudioBufferRecognitionRequest? {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}

/// The captions panel and its switch on Pairline's call screen.
struct CaptionsPanel: View {
    @ObservedObject private var captions = LiveCaptions.shared

    var body: some View {
        if captions.running {
            ScrollView {
                Text(verbatim: captions.text.isEmpty ? "…" : captions.text)
                    .font(.title3.weight(.medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
            }
            .frame(maxHeight: 140)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.black.opacity(0.35)))
            .accessibilityLabel(Text("Live captions", tableName: "Captions"))
        } else if let problem = captions.problem {
            Text(verbatim: problem)
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.85))
                .multilineTextAlignment(.center)
        }
    }
}

/// The CC button: on, off, and the language (touch and hold).
struct CaptionsButton: View {
    @ObservedObject private var captions = LiveCaptions.shared

    var body: some View {
        Button {
            if captions.running { captions.stop() } else { Task { await captions.start() } }
            Haptics.selection()
        } label: {
            Image(systemName: captions.running ? "captions.bubble.fill" : "captions.bubble")
                .font(.title2)
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel(Text("Live captions", tableName: "Captions"))
        .contextMenu {
            ForEach(LiveCaptions.availableLocales(), id: \.identifier) { locale in
                Button {
                    LiveCaptions.choose(locale)
                    if captions.running {
                        captions.stop()
                        Task { await captions.start() }
                    }
                } label: {
                    Text(verbatim: locale.localizedString(forIdentifier: locale.identifier) ?? locale.identifier)
                }
            }
        }
    }
}
