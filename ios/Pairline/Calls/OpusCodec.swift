import AudioToolbox
import Foundation

/// Opus with iOS's own codec (AudioToolbox, `kAudioFormatOpus`): no library of ours. Call audio inside the Bluetooth
/// session travels as Opus, because raw PCM doesn't fit Bluetooth LE (PROTOCOL.md "Call audio", D-072). 16 kHz mono,
/// 20 ms per packet. Each instance belongs to one thread.
enum Opus {
    static let sampleRate: Double = 16_000
    static let frameSamples = AudioPackets.frameSamples
    static let bitRate: UInt32 = 32_000
    /// The largest Opus packet either side sends or accepts (a 20 ms packet at 32 kbit/s is about 80 bytes).
    static let maxPacketBytes = AudioPackets.maxOpusBytes

    static var pcmFormat: AudioStreamBasicDescription {
        AudioStreamBasicDescription(mSampleRate: sampleRate, mFormatID: kAudioFormatLinearPCM,
                                    mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
                                    mBytesPerPacket: 2, mFramesPerPacket: 1, mBytesPerFrame: 2, mChannelsPerFrame: 1,
                                    mBitsPerChannel: 16, mReserved: 0)
    }

    static var opusFormat: AudioStreamBasicDescription {
        AudioStreamBasicDescription(mSampleRate: sampleRate, mFormatID: kAudioFormatOpus, mFormatFlags: 0,
                                    mBytesPerPacket: 0, mFramesPerPacket: UInt32(frameSamples), mBytesPerFrame: 0,
                                    mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
    }

    /// Returned by the input callbacks when they have nothing more right now; not an error.
    static let noMoreInput: OSStatus = 0x6E6D6F72 // 'nmor'
}

enum OpusError: LocalizedError {
    case unavailable(OSStatus)

    var errorDescription: String? {
        switch self {
        case .unavailable(let status): return "Opus isn't available on this iPhone (\(status))."
        }
    }
}

/// 20 ms frames of 16 kHz mono PCM in, Opus packets out.
final class OpusEncoder {
    private let converter: AudioConverterRef
    private let outCapacity: Int
    private let out: UnsafeMutableRawPointer
    /// Samples handed over but not yet taken by the converter.
    fileprivate var pending: [Int16] = []
    /// What the input callback last gave the converter; it must stay valid until the next callback.
    fileprivate let feed: UnsafeMutablePointer<Int16>
    fileprivate static let feedCapacity = 4_096

    init() throws {
        var input = Opus.pcmFormat
        var output = Opus.opusFormat
        var ref: AudioConverterRef?
        let status = AudioConverterNew(&input, &output, &ref)
        guard status == noErr, let ref else { throw OpusError.unavailable(status) }
        converter = ref
        var rate = Opus.bitRate
        // Not every system takes every bit rate; the converter's own default is fine then.
        _ = AudioConverterSetProperty(ref, kAudioConverterEncodeBitRate, UInt32(MemoryLayout<UInt32>.size), &rate)
        var maxSize: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        _ = AudioConverterGetProperty(ref, kAudioConverterPropertyMaximumOutputPacketSize, &size, &maxSize)
        outCapacity = max(Int(maxSize), 1_500)
        out = UnsafeMutableRawPointer.allocate(byteCount: outCapacity, alignment: 16)
        feed = UnsafeMutablePointer<Int16>.allocate(capacity: Self.feedCapacity)
    }

    deinit {
        AudioConverterDispose(converter)
        out.deallocate()
        feed.deallocate()
    }

    /// The bit rate the converter settled on (for the log).
    var bitRate: UInt32 {
        var rate: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        _ = AudioConverterGetProperty(converter, kAudioConverterEncodeBitRate, &size, &rate)
        return rate
    }

    /// The Opus packet for `frame` (320 samples), or nil while the codec warms up or if it fails.
    func encode(_ frame: [Int16]) -> Data? {
        pending.append(contentsOf: frame)
        var packets: UInt32 = 1
        var description = AudioStreamPacketDescription()
        var list = AudioBufferList(mNumberBuffers: 1,
                                   mBuffers: AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(outCapacity),
                                                         mData: out))
        let status = AudioConverterFillComplexBuffer(converter, encoderInput, Unmanaged.passUnretained(self).toOpaque(),
                                                     &packets, &list, &description)
        // Never let unconsumed samples pile up (a converter that stopped taking input).
        if pending.count > Opus.frameSamples * 4 { pending.removeFirst(pending.count - Opus.frameSamples * 4) }
        guard status == noErr || status == Opus.noMoreInput, packets == 1 else { return nil }
        let bytes = Int(list.mBuffers.mDataByteSize)
        guard bytes > 0, bytes <= Opus.maxPacketBytes else { return nil }
        return Data(bytes: out, count: bytes)
    }
}

/// Feeds the encoder whatever samples are waiting; with none left, says so (the converter returns what it made).
private func encoderInput(_ converter: AudioConverterRef, _ count: UnsafeMutablePointer<UInt32>,
                          _ data: UnsafeMutablePointer<AudioBufferList>,
                          _ descriptions: UnsafeMutablePointer<UnsafeMutablePointer<AudioStreamPacketDescription>?>?,
                          _ user: UnsafeMutableRawPointer?) -> OSStatus {
    guard let user else { return Opus.noMoreInput }
    let encoder = Unmanaged<OpusEncoder>.fromOpaque(user).takeUnretainedValue()
    let take = min(Int(count.pointee), encoder.pending.count, OpusEncoder.feedCapacity)
    guard take > 0 else {
        count.pointee = 0
        return Opus.noMoreInput
    }
    encoder.pending.withUnsafeBufferPointer { source in
        encoder.feed.update(from: source.baseAddress!, count: take)
    }
    encoder.pending.removeFirst(take)
    count.pointee = UInt32(take)
    data.pointee.mNumberBuffers = 1
    data.pointee.mBuffers.mNumberChannels = 1
    data.pointee.mBuffers.mDataByteSize = UInt32(take * MemoryLayout<Int16>.size)
    data.pointee.mBuffers.mData = UnsafeMutableRawPointer(encoder.feed)
    return noErr
}

/// Opus packets of 20 ms in, 320 samples of 16 kHz mono PCM out.
final class OpusDecoder {
    private let converter: AudioConverterRef
    /// The packet being decoded and its description; they must stay valid during the converter's call.
    fileprivate let packet: UnsafeMutableRawPointer
    fileprivate let description: UnsafeMutablePointer<AudioStreamPacketDescription>
    fileprivate var given = true

    init() throws {
        var input = Opus.opusFormat
        var output = Opus.pcmFormat
        var ref: AudioConverterRef?
        let status = AudioConverterNew(&input, &output, &ref)
        guard status == noErr, let ref else { throw OpusError.unavailable(status) }
        converter = ref
        packet = UnsafeMutableRawPointer.allocate(byteCount: Opus.maxPacketBytes, alignment: 16)
        description = UnsafeMutablePointer<AudioStreamPacketDescription>.allocate(capacity: 1)
        description.initialize(to: AudioStreamPacketDescription())
    }

    deinit {
        AudioConverterDispose(converter)
        packet.deallocate()
        description.deallocate()
    }

    /// The packet's 320 samples, or nil if the codec gave nothing back (the jitter buffer then conceals it).
    func decode(_ opus: Data) -> [Int16]? {
        guard !opus.isEmpty, opus.count <= Opus.maxPacketBytes else { return nil }
        opus.withUnsafeBytes { packet.copyMemory(from: $0.baseAddress!, byteCount: opus.count) }
        description.pointee = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0,
                                                           mDataByteSize: UInt32(opus.count))
        given = false
        var samples = [Int16](repeating: 0, count: Opus.frameSamples)
        var frames = UInt32(Opus.frameSamples)
        let status: OSStatus = samples.withUnsafeMutableBytes { raw in
            var list = AudioBufferList(mNumberBuffers: 1,
                                       mBuffers: AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(raw.count),
                                                             mData: raw.baseAddress))
            return AudioConverterFillComplexBuffer(converter, decoderInput, Unmanaged.passUnretained(self).toOpaque(),
                                                   &frames, &list, nil)
        }
        // Samples past `frames` stay silent: the start of a stream can be short.
        guard status == noErr || status == Opus.noMoreInput, frames > 0 else { return nil }
        return samples
    }
}

/// Hands the decoder the one packet it was given, then says there's nothing more.
private func decoderInput(_ converter: AudioConverterRef, _ count: UnsafeMutablePointer<UInt32>,
                          _ data: UnsafeMutablePointer<AudioBufferList>,
                          _ descriptions: UnsafeMutablePointer<UnsafeMutablePointer<AudioStreamPacketDescription>?>?,
                          _ user: UnsafeMutableRawPointer?) -> OSStatus {
    guard let user else { return Opus.noMoreInput }
    let decoder = Unmanaged<OpusDecoder>.fromOpaque(user).takeUnretainedValue()
    guard !decoder.given else {
        count.pointee = 0
        return Opus.noMoreInput
    }
    decoder.given = true
    count.pointee = 1
    data.pointee.mNumberBuffers = 1
    data.pointee.mBuffers.mNumberChannels = 1
    data.pointee.mBuffers.mDataByteSize = decoder.description.pointee.mDataByteSize
    data.pointee.mBuffers.mData = decoder.packet
    descriptions?.pointee = decoder.description
    return noErr
}
