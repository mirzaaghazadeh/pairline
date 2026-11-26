import CoreBluetooth
import Foundation

/// GATT identifiers from PROTOCOL.md, "BLE".
enum PairlineGATT {
    static let service = CBUUID(string: "8CC09D09-4084-4E91-A358-A153EB79D422")
    static let rx = CBUUID(string: "15347F70-79F0-40C4-92DE-ED6612B4CE5F")
    static let tx = CBUUID(string: "8A38CBD3-5771-4174-9C10-7EB7FD219E6B")
    static let id = CBUUID(string: "CFFC7A8D-1012-4A2D-B52F-50A9E32F8B32")
}

enum BluetoothAvailability: Equatable {
    case unknown
    case poweredOn
    case poweredOff
    case unauthorized
    case unsupported
}

enum ConnectError: Error {
    case cancelled
    case unavailable
    case failed
}

/// What the link layer asks a connector to find.
struct ConnectTarget {
    var deviceID: Data
    var knownPeripheral: UUID?
    /// Wi-Fi: endpoints to try first (the pair link's addresses while pairing).
    var wifi: [WiFiEndpoint] = []
    /// Wi-Fi: try only `wifi` (pairing never sends `PAIR_HELLO` to an address the link didn't give).
    var wifiExplicitOnly = false
    /// The direct connection: the bridge's public addresses from its authenticated `remote.addr`, IPv6 first.
    /// Nothing else ever fills it.
    var direct: [WiFiEndpoint] = []
}

/// Finds the bridge and opens a frame transport to it.
protocol LinkConnector: AnyObject {
    var kind: TransportKind { get }
    var availability: BluetoothAvailability { get }
    var onAvailabilityChange: ((BluetoothAvailability) -> Void)? { get set }
    /// Completes once with a transport to a bridge whose `id` matches, or with an error.
    func connect(_ target: ConnectTarget, completion: @escaping (Result<(FrameTransport, UUID?), Error>) -> Void)
    /// Stops looking (an open transport is unaffected).
    func cancel()
}

/// CoreBluetooth central (iPhone side). Keeps a pending `connect` to the known peripheral and also scans for the
/// service, because Android rotates its BLE address. Uses state preservation and restoration so iOS can relaunch
/// the app for Bluetooth events. Everything runs on the main queue.
final class BluetoothCentral: NSObject, LinkConnector {
    static let restoreIdentifier = "app.pairline.ios.central"
    /// A scanned candidate that doesn't finish connecting and identifying in this time is dropped.
    private static let candidateTimeout: TimeInterval = 15

    let kind: TransportKind = .bluetooth
    private(set) var availability: BluetoothAvailability = .unknown
    var onAvailabilityChange: ((BluetoothAvailability) -> Void)?

    private var manager: CBCentralManager!
    private var target: ConnectTarget?
    private var completion: ((Result<(FrameTransport, UUID?), Error>) -> Void)?
    private var candidates: [UUID: CBPeripheral] = [:]
    private var candidateTimers: [UUID: Timer] = [:]
    private var rejected: [UUID: Date] = [:]
    private var chosen: CBPeripheral?
    private var transport: BLETransport?
    private var restored: [CBPeripheral] = []

    override init() {
        super.init()
        manager = CBCentralManager(delegate: self, queue: .main, options: [
            CBCentralManagerOptionRestoreIdentifierKey: BluetoothCentral.restoreIdentifier,
            CBCentralManagerOptionShowPowerAlertKey: true,
        ])
    }

    func connect(_ target: ConnectTarget, completion: @escaping (Result<(FrameTransport, UUID?), Error>) -> Void) {
        cancel()
        self.target = target
        self.completion = completion
        if availability == .poweredOn { begin() }
    }

    func cancel() {
        if let completion {
            self.completion = nil
            completion(.failure(ConnectError.cancelled))
        }
        target = nil
        if manager.isScanning { manager.stopScan() }
        for (id, peripheral) in candidates where peripheral != transport?.peripheral {
            manager.cancelPeripheralConnection(peripheral)
            candidateTimers[id]?.invalidate()
        }
        candidates.removeAll()
        candidateTimers.removeAll()
        if chosen != transport?.peripheral { chosen = nil }
    }

    func disconnect(_ peripheral: CBPeripheral) {
        manager.cancelPeripheralConnection(peripheral)
    }

    private func begin() {
        guard let target else { return }
        let now = Date()
        rejected = rejected.filter { now.timeIntervalSince($0.value) < 60 }
        var known: [CBPeripheral] = []
        if let id = target.knownPeripheral {
            known += manager.retrievePeripherals(withIdentifiers: [id])
        }
        known += manager.retrieveConnectedPeripherals(withServices: [PairlineGATT.service])
        known += restored
        restored.removeAll()
        for peripheral in known where candidates[peripheral.identifier] == nil {
            // Pending connect without a timeout: iOS connects when the bridge shows up again.
            addCandidate(peripheral, timeout: peripheral.identifier == target.knownPeripheral ? nil : Self.candidateTimeout)
        }
        manager.scanForPeripherals(withServices: [PairlineGATT.service], options: nil)
        log.info("ble", "Looking for the bridge (known peripheral: \(target.knownPeripheral != nil))")
    }

    private func addCandidate(_ peripheral: CBPeripheral, timeout: TimeInterval?) {
        candidates[peripheral.identifier] = peripheral
        peripheral.delegate = self
        if peripheral.state == .connected {
            discover(peripheral)
        } else {
            manager.connect(peripheral, options: nil)
        }
        if let timeout {
            candidateTimers[peripheral.identifier] = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) {
                [weak self] _ in
                self?.dropCandidate(peripheral, reject: false)
            }
        }
    }

    private func dropCandidate(_ peripheral: CBPeripheral, reject: Bool) {
        let id = peripheral.identifier
        candidateTimers[id]?.invalidate()
        candidateTimers[id] = nil
        candidates[id] = nil
        if reject { rejected[id] = Date() }
        if peripheral == chosen, peripheral != transport?.peripheral {
            // It was identified but never became ready: look again.
            chosen = nil
            if target != nil, availability == .poweredOn, !manager.isScanning {
                manager.scanForPeripherals(withServices: [PairlineGATT.service], options: nil)
            }
        }
        if peripheral != transport?.peripheral { manager.cancelPeripheralConnection(peripheral) }
    }

    private func discover(_ peripheral: CBPeripheral) {
        peripheral.discoverServices([PairlineGATT.service])
    }

    private func characteristic(_ uuid: CBUUID, of peripheral: CBPeripheral) -> CBCharacteristic? {
        peripheral.services?.first(where: { $0.uuid == PairlineGATT.service })?
            .characteristics?.first(where: { $0.uuid == uuid })
    }

    private func identified(_ peripheral: CBPeripheral) {
        guard target != nil else { return }
        chosen = peripheral
        if manager.isScanning { manager.stopScan() }
        for (id, other) in candidates where id != peripheral.identifier {
            dropCandidate(other, reject: false)
        }
        candidateTimers[peripheral.identifier]?.invalidate()
        candidateTimers[peripheral.identifier] = nil
        guard let tx = characteristic(PairlineGATT.tx, of: peripheral) else { return dropCandidate(peripheral, reject: true) }
        peripheral.setNotifyValue(true, for: tx)
    }

    private func ready(_ peripheral: CBPeripheral) {
        guard let completion, peripheral == chosen,
              let rx = characteristic(PairlineGATT.rx, of: peripheral),
              let tx = characteristic(PairlineGATT.tx, of: peripheral) else { return }
        let transport = BLETransport(central: self, peripheral: peripheral, rx: rx, tx: tx)
        self.transport = transport
        self.completion = nil
        target = nil
        candidates.removeAll()
        log.info("ble", "Connected; MTU payload \(peripheral.maximumWriteValueLength(for: .withoutResponse)) bytes")
        completion(.success((transport, peripheral.identifier)))
    }
}

extension BluetoothCentral: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let new: BluetoothAvailability
        switch central.state {
        case .poweredOn: new = .poweredOn
        case .poweredOff: new = .poweredOff
        case .unauthorized: new = .unauthorized
        case .unsupported: new = .unsupported
        default: new = .unknown
        }
        guard new != availability else { return }
        availability = new
        log.info("ble", "Bluetooth state: \(new)")
        if new == .poweredOn, target != nil { begin() }
        if new != .poweredOn {
            candidates.removeAll()
            candidateTimers.values.forEach { $0.invalidate() }
            candidateTimers.removeAll()
        }
        onAvailabilityChange?(new)
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        log.info("ble", "Restored by iOS with \(peripherals.count) peripheral(s)")
        // Session keys live only in memory, so a restored connection can't continue its old session.
        // Drop it and reconnect; the bridge resends what we missed through the next sync.
        for peripheral in peripherals {
            peripheral.delegate = self
            if peripheral.state == .connected || peripheral.state == .connecting {
                central.cancelPeripheralConnection(peripheral)
            }
        }
        restored = peripherals
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard target != nil, candidates[peripheral.identifier] == nil, rejected[peripheral.identifier] == nil,
              chosen == nil else { return }
        addCandidate(peripheral, timeout: Self.candidateTimeout)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard candidates[peripheral.identifier] != nil else {
            if peripheral != transport?.peripheral { central.cancelPeripheralConnection(peripheral) }
            return
        }
        discover(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        retryOrDrop(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if let transport, transport.peripheral == peripheral {
            self.transport = nil
            if chosen == peripheral { chosen = nil }
            transport.didDisconnect(error)
            return
        }
        if chosen == peripheral { chosen = nil }
        retryOrDrop(peripheral)
    }

    private func retryOrDrop(_ peripheral: CBPeripheral) {
        guard let target, candidates[peripheral.identifier] != nil else { return }
        if peripheral.identifier == target.knownPeripheral {
            manager.connect(peripheral, options: nil)
        } else {
            dropCandidate(peripheral, reject: false)
        }
    }
}

extension BluetoothCentral: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == PairlineGATT.service }) else {
            return dropCandidate(peripheral, reject: true)
        }
        peripheral.discoverCharacteristics([PairlineGATT.rx, PairlineGATT.tx, PairlineGATT.id], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let id = characteristic(PairlineGATT.id, of: peripheral),
              characteristic(PairlineGATT.rx, of: peripheral) != nil,
              characteristic(PairlineGATT.tx, of: peripheral) != nil else {
            return dropCandidate(peripheral, reject: true)
        }
        peripheral.readValue(for: id)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        switch characteristic.uuid {
        case PairlineGATT.id:
            guard let target, candidates[peripheral.identifier] != nil else { return }
            if error == nil, let value = characteristic.value, value == target.deviceID {
                identified(peripheral)
            } else {
                log.info("ble", "A different Pairline bridge is nearby; ignoring it")
                dropCandidate(peripheral, reject: true)
            }
        case PairlineGATT.tx:
            guard error == nil, let value = characteristic.value, let transport, transport.peripheral == peripheral else {
                return
            }
            transport.receive(value)
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == PairlineGATT.tx else { return }
        if error == nil, characteristic.isNotifying {
            ready(peripheral)
        } else {
            dropCandidate(peripheral, reject: false)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        if let transport, transport.peripheral == peripheral { transport.pump() }
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        if invalidatedServices.contains(where: { $0.uuid == PairlineGATT.service }) {
            log.warning("ble", "The bridge's service went away")
            manager.cancelPeripheralConnection(peripheral)
        }
    }
}

/// Frames over the `rx` (write without response) and `tx` (notify) characteristics, with fragmenting and
/// `canSendWriteWithoutResponse` flow control.
final class BLETransport: FrameTransport {
    let kind: TransportKind = .bluetooth
    let peripheral: CBPeripheral
    private let rx: CBCharacteristic
    private weak var central: BluetoothCentral?
    private var queue: [Data] = []
    private var reassembler = BLEReassembler()
    private var closed = false

    var onFrame: ((Data) -> Void)?
    var onClose: ((Error?) -> Void)?

    init(central: BluetoothCentral, peripheral: CBPeripheral, rx: CBCharacteristic, tx: CBCharacteristic) {
        self.central = central
        self.peripheral = peripheral
        self.rx = rx
    }

    func send(_ frame: Data) {
        guard !closed else { return }
        let payload = min(peripheral.maximumWriteValueLength(for: .withoutResponse), BLEFragmenter.maximumPayload)
        guard payload >= BLEFragmenter.minimumPayload, frame.count <= WireProtocol.maxFrameLength else {
            log.error("ble", "Can't send a frame of \(frame.count) bytes with payload \(payload)")
            close()
            return
        }
        queue.append(contentsOf: BLEFragmenter.fragments(of: frame, attPayload: payload))
        pump()
    }

    func pump() {
        while !closed, !queue.isEmpty, peripheral.canSendWriteWithoutResponse {
            peripheral.writeValue(queue.removeFirst(), for: rx, type: .withoutResponse)
        }
    }

    func receive(_ fragment: Data) {
        guard !closed else { return }
        do {
            if let frame = try reassembler.feed(fragment) {
                onFrame?(frame)
            }
        } catch {
            log.warning("ble", "Bad fragment; closing the connection")
            close()
        }
    }

    func close() {
        guard !closed else { return }
        central?.disconnect(peripheral)
        finish(nil)
    }

    func didDisconnect(_ error: Error?) {
        finish(error)
    }

    private func finish(_ error: Error?) {
        guard !closed else { return }
        closed = true
        queue.removeAll()
        let callback = onClose
        onClose = nil
        onFrame = nil
        callback?(error)
    }
}
