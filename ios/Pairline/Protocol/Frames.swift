import Foundation

/// Wire constants from docs/PROTOCOL.md (wire version 0).
enum WireProtocol {
    static let version: UInt8 = 0
    static let roleRemote: UInt8 = 0x52 // 'R'
    static let roleBridge: UInt8 = 0x42 // 'B'
    static let maxFrameLength = 65_535
    static let helloLength = 99
    static let publicKeyLength = 65
    static let randomLength = 32
    static let deviceIDLength = 8
    static let pairingSecretLength = 32
}

enum FrameType: UInt8 {
    case pairHello = 0x01
    case pairOK = 0x02
    case pairFail = 0x03
    case hello = 0x10
    case record = 0x20
    /// A record on the bulk channel (M5): Wi-Fi TCP only, after the handshake.
    case bulk = 0x21
    /// A record on the link media channel (call audio, D-072): Bluetooth and debug TCP only, after the handshake.
    case media = 0x22
}

enum ProtocolError: Error, Equatable {
    case emptyFrame
    case frameTooLong
    case unknownFrameType(UInt8)
    case malformedHello
    case invalidPublicKey
    case unexpectedFrame
    case decryptFailed
    case replayedCounter
    case counterExhausted
    case badConfirm
    case malformedFragment
    case malformedJSON
    case invalidPairingLink
    case pairingMACMismatch
    case pairingRejected(String)
}

/// One complete unit after transport reassembly: a 1-byte type followed by the body.
struct Frame: Equatable {
    let type: FrameType
    let body: Data

    var encoded: Data {
        var out = Data([type.rawValue])
        out.append(body)
        return out
    }

    static func decode(_ data: Data) throws -> Frame {
        guard let first = data.first else { throw ProtocolError.emptyFrame }
        guard data.count <= WireProtocol.maxFrameLength else { throw ProtocolError.frameTooLong }
        guard let type = FrameType(rawValue: first) else { throw ProtocolError.unknownFrameType(first) }
        return Frame(type: type, body: Data(data.dropFirst()))
    }
}

/// The 99-byte hello body: version ‖ role ‖ ephemeral public key (65) ‖ random (32).
struct Hello: Equatable {
    let version: UInt8
    let role: UInt8
    let ephemeralPublicKey: Data
    let random: Data

    init(role: UInt8, ephemeralPublicKey: Data, random: Data) {
        self.version = WireProtocol.version
        self.role = role
        self.ephemeralPublicKey = ephemeralPublicKey
        self.random = random
    }

    var encoded: Data {
        var out = Data([version, role])
        out.append(ephemeralPublicKey)
        out.append(random)
        return out
    }

    /// Parses and validates a hello from the peer with `expectedRole`.
    static func decode(_ body: Data, expectedRole: UInt8) throws -> Hello {
        guard body.count == WireProtocol.helloLength else { throw ProtocolError.malformedHello }
        let bytes = [UInt8](body)
        guard bytes[0] == WireProtocol.version, bytes[1] == expectedRole else { throw ProtocolError.malformedHello }
        let key = Data(bytes[2..<67])
        guard key.first == 0x04 else { throw ProtocolError.invalidPublicKey }
        return Hello(role: bytes[1], ephemeralPublicKey: key, random: Data(bytes[67..<99]))
    }
}
