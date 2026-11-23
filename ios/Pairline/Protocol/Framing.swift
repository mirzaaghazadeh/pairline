import Foundation

/// BLE fragmenting (PROTOCOL.md, "BLE"): each fragment starts with a 1-byte header (bit 0 = first,
/// bit 1 = last); the first fragment adds the frame's 2-byte big-endian total length.
enum BLEFragmenter {
    static let headerFirst: UInt8 = 0x01
    static let headerLast: UInt8 = 0x02
    /// Header, length and at least one byte of frame.
    static let minimumPayload = 4

    static func fragments(of frame: Data, attPayload: Int) -> [Data] {
        precondition(attPayload >= minimumPayload, "ATT payload too small")
        precondition(!frame.isEmpty && frame.count <= WireProtocol.maxFrameLength, "Invalid frame length")
        let bytes = [UInt8](frame)
        var out: [Data] = []
        var position = 0
        var first = true
        while true {
            let room = attPayload - 1 - (first ? 2 : 0)
            let end = min(position + room, bytes.count)
            let chunk = bytes[position..<end]
            position = end
            let last = position >= bytes.count
            var fragment = Data([(first ? headerFirst : 0) | (last ? headerLast : 0)])
            if first { fragment.append(Data.bigEndian(UInt16(bytes.count))) }
            fragment.append(contentsOf: chunk)
            out.append(fragment)
            first = false
            if last { return out }
        }
    }
}

/// Reassembles BLE fragments into frames. Any inconsistency throws, and the caller closes the connection.
struct BLEReassembler {
    private var buffer = Data()
    private var expected = 0
    private var inProgress = false

    mutating func reset() {
        buffer = Data()
        expected = 0
        inProgress = false
    }

    /// Returns a complete frame when `fragment` was the last one.
    mutating func feed(_ fragment: Data) throws -> Data? {
        let bytes = [UInt8](fragment)
        guard let header = bytes.first, header & ~0x03 == 0 else { throw ProtocolError.malformedFragment }
        let isFirst = header & BLEFragmenter.headerFirst != 0
        let isLast = header & BLEFragmenter.headerLast != 0
        var payload: ArraySlice<UInt8>
        if isFirst {
            guard !inProgress, bytes.count >= 3 else { throw ProtocolError.malformedFragment }
            expected = Int(bytes[1]) << 8 | Int(bytes[2])
            guard expected > 0 else { throw ProtocolError.malformedFragment }
            buffer = Data(capacity: expected)
            inProgress = true
            payload = bytes[3...]
        } else {
            guard inProgress else { throw ProtocolError.malformedFragment }
            payload = bytes[1...]
        }
        guard buffer.count + payload.count <= expected else {
            reset()
            throw ProtocolError.malformedFragment
        }
        buffer.append(contentsOf: payload)
        if isLast {
            guard buffer.count == expected else {
                reset()
                throw ProtocolError.malformedFragment
            }
            let frame = buffer
            reset()
            return frame
        }
        return nil
    }
}

/// Debug TCP framing: each frame is preceded by its 4-byte big-endian length.
enum TCPFraming {
    static func encode(_ frame: Data) -> Data {
        Data.bigEndian(UInt32(frame.count)) + frame
    }
}

struct TCPDeframer {
    private var buffer = Data()

    mutating func feed(_ data: Data) throws -> [Data] {
        buffer.append(data)
        var frames: [Data] = []
        while buffer.count >= 4 {
            guard let length = buffer.readBigEndian(UInt32.self) else { break }
            guard length > 0, length <= UInt32(WireProtocol.maxFrameLength) else { throw ProtocolError.frameTooLong }
            let total = 4 + Int(length)
            guard buffer.count >= total else { break }
            frames.append(Data(buffer[(buffer.startIndex + 4)..<(buffer.startIndex + total)]))
            buffer = Data(buffer[(buffer.startIndex + total)...])
        }
        return frames
    }
}
