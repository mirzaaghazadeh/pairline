package app.pairline.android.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import app.pairline.android.log.Log
import app.pairline.android.protocol.BleFragmenter
import app.pairline.android.protocol.BleReassembler
import app.pairline.android.protocol.ProtocolException
import app.pairline.android.telephony.Perms
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE peripheral (PROTOCOL.md, "BLE"): advertises the Pairline service and serves `rx` (write without
 * response, iPhone → bridge), `tx` (notify, bridge → iPhone) and `id` (read, the 8-byte device id).
 * Frames are fragmented to the negotiated ATT payload; the next notification goes out only after
 * onNotificationSent. Everything runs on one handler thread.
 */
@SuppressLint("MissingPermission") // callers check Perms.BLUETOOTH before start()
class BleTransport(
    private val context: Context,
    private val listener: ConnectionListener,
    private val deviceId: () -> ByteArray,
    private val onStatus: (BluetoothStatus, advertising: Boolean) -> Unit,
) : LinkTransport {
    override val kind = TransportKind.BLUETOOTH

    private val thread = HandlerThread("pairline-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val connections = HashMap<String, BleConnection>()
    private val labels = AtomicInteger(1)

    override fun start() {
        handler.post { startInternal() }
    }

    override fun stop() {
        handler.post { stopInternal() }
    }

    private fun startInternal() {
        if (server != null) return
        val adapter = manager?.adapter
        when {
            adapter == null -> return onStatus(BluetoothStatus.UNSUPPORTED, false)
            !Perms.allGranted(context, Perms.BLUETOOTH) -> return onStatus(BluetoothStatus.NO_PERMISSION, false)
            !adapter.isEnabled -> return onStatus(BluetoothStatus.OFF, false)
        }
        val s = try {
            manager!!.openGattServer(context, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "can't open the GATT server", e)
            return onStatus(BluetoothStatus.NO_PERMISSION, false)
        }
        if (s == null) {
            Log.w(TAG, "openGattServer returned null")
            return onStatus(BluetoothStatus.ADVERTISE_FAILED, false)
        }
        server = s
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val id = BluetoothGattCharacteristic(
            ID_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        service.addCharacteristic(id)
        txCharacteristic = tx
        s.addService(service) // advertising starts in onServiceAdded
        onStatus(BluetoothStatus.ON, false)
    }

    private fun startAdvertising() {
        val adapter = manager?.adapter ?: return
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w(TAG, "this phone can't advertise over BLE")
            onStatus(BluetoothStatus.UNSUPPORTED, false)
            return
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Only the service UUID: no device name, so the advertisement says nothing about the user.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        try {
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            onStatus(BluetoothStatus.NO_PERMISSION, false)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            handler.post {
                advertising = true
                Log.i(TAG, "advertising")
                onStatus(BluetoothStatus.ON, true)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                advertising = false
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                    advertising = true
                    onStatus(BluetoothStatus.ON, true)
                    return@post
                }
                Log.w(TAG, "advertising failed (code $errorCode)")
                onStatus(BluetoothStatus.ADVERTISE_FAILED, false)
                // Some chips can't advertise while connected; retry later.
                handler.postDelayed({ resumeAdvertisingIfIdle() }, 30_000)
            }
        }
    }

    /** Every LE link of this phone (the GATT server hears all of them), and those that used the Pairline service. */
    private val links = HashSet<String>()
    private val claimed = HashSet<String>()

    /**
     * Stops advertising while a phone is connected. Android advertises again right after a connection, under a new
     * random address. An iPhone still looking for its bridge takes that for another bridge and connects to it too,
     * and some phones (Galaxy Note 8, Android 9) then drop the first link, over and over (D-039). Advertising
     * resumes when the link ends, or after [UNCLAIMED_LINK_MS] if the link never used the Pairline service (a watch
     * or earbuds connecting to this phone mustn't hide the bridge).
     */
    private fun onLinkUp(device: BluetoothDevice) {
        if (server == null) return
        links += device.address
        if (advertising) {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            advertising = false
            onStatus(BluetoothStatus.ON, false)
        }
        handler.postDelayed({ resumeAdvertisingIfIdle() }, UNCLAIMED_LINK_MS)
    }

    /** The device read `id`, subscribed or wrote: it's a Pairline central, so stay hidden while it's connected. */
    private fun claim(device: BluetoothDevice) {
        if (device.address in links) claimed += device.address
    }

    private fun resumeAdvertisingIfIdle() {
        if (server == null || advertising || claimed.isNotEmpty()) return
        startAdvertising()
    }

    private fun stopInternal() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        advertiser = null
        for (c in connections.values.toList()) c.disconnect("Bluetooth link stopped")
        connections.clear()
        subscribed.clear()
        links.clear()
        claimed.clear()
        runCatching { server?.close() }
        server = null
        txCharacteristic = null
        // Keep "off" visible when Bluetooth itself was turned off.
        onStatus(if (manager?.adapter?.isEnabled == true) BluetoothStatus.UNKNOWN else BluetoothStatus.OFF, false)
    }

    /** Notification subscriptions are per link (the CCCD), so they outlive a closed session on the same link. */
    private val subscribed = HashSet<String>()

    /**
     * The connection for a device that talks to Pairline. Created on first use, not on every LE connection:
     * Android reports the phone's watch or earbuds connecting too, and those must never get a session.
     */
    private fun connectionFor(device: BluetoothDevice): BleConnection? {
        if (server == null) return null
        return connections.getOrPut(device.address) { BleConnection(device, "ble#${labels.getAndIncrement()}") }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    startAdvertising()
                } else {
                    Log.w(TAG, "adding the GATT service failed ($status)")
                    onStatus(BluetoothStatus.ADVERTISE_FAILED, false)
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Sessions start on first use; a new link only pauses advertising.
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> handler.post { onLinkUp(device) }
                BluetoothProfile.STATE_DISCONNECTED -> handler.post {
                    subscribed.remove(device.address)
                    mtus.remove(device.address)
                    links.remove(device.address)
                    claimed.remove(device.address)
                    connections[device.address]?.disconnect("Bluetooth disconnected (status $status)", linkGone = true)
                    resumeAdvertisingIfIdle()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { mtus[device.address] = mtu }
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            Log.d(TAG, "PHY update: tx $txPhy, rx $rxPhy (status $status)")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val s = server ?: return
            if (characteristic.uuid == ID_UUID) {
                handler.post { claim(device) }
                val id = deviceId()
                val value = if (offset < id.size) id.copyOfRange(offset, id.size) else ByteArray(0)
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val ok = characteristic.uuid == RX_UUID && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset,
                    null,
                )
            }
            if (!ok) return
            val bytes = value!!.copyOf()
            handler.post {
                claim(device)
                connectionFor(device)?.onFragment(bytes)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (device.address in subscribed) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val ok = descriptor.uuid == CCCD_UUID && value != null && value.size == 2
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset,
                    null,
                )
            }
            if (!ok) return
            val enable = (value!![0].toInt() and 0x01) != 0
            handler.post {
                claim(device)
                if (enable) {
                    subscribed += device.address
                    connectionFor(device)?.pump()
                } else {
                    subscribed -= device.address
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { connections[device.address]?.onSent(status) }
        }
    }

    /** Negotiated MTU per link. */
    private val mtus = HashMap<String, Int>()

    /**
     * One iPhone link. Everything runs on the handler thread. An object is "current" while it's the one in
     * [connections] for its address; once disconnected it's dead, so a late retry or delayed close can never touch
     * a newer link from the same address.
     */
    private inner class BleConnection(val device: BluetoothDevice, override val label: String) : LinkConnection {
        override val kind = TransportKind.BLUETOOTH
        private var opened = false
        private var alive = true
        private var closing = false
        private var inFlight = false
        private val queue = ArrayDeque<ByteArray>()
        private val reassembler = BleReassembler()

        private val current: Boolean get() = alive && connections[device.address] === this

        /** The session starts when the device first writes to `rx` (or subscribes to `tx`). */
        private fun ensureOpened() {
            if (opened || !current) return
            opened = true
            listener.onOpened(this)
        }

        override fun send(frame: ByteArray) {
            handler.post {
                if (closing || !current) return@post
                val payload = BleFragmenter.payloadFor(mtus[device.address] ?: 23)
                val fragments = try {
                    BleFragmenter.fragment(frame, payload)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "$label: can't send a frame of ${frame.size} bytes", e)
                    disconnect("frame too large")
                    return@post
                }
                queue.addAll(fragments)
                pump()
            }
        }

        /** Disconnects once frames already queued (such as a final `unpair`) are sent, or after 2 s at most. */
        override fun close() {
            handler.post {
                if (closing || !current) return@post
                closing = true
                if (queue.isEmpty() && !inFlight) {
                    disconnect("closed by the bridge")
                } else {
                    handler.postDelayed({ disconnect("closed by the bridge") }, 2_000)
                }
            }
        }

        /**
         * Ends this connection now: forgets it, tells the session, and drops the link. A GATT server can't drop a
         * link the iPhone opened with cancelConnection() alone; connect() first gives this app a hold on it.
         */
        fun disconnect(reason: String, linkGone: Boolean = false) {
            if (!alive) return
            alive = false
            queue.clear()
            if (connections[device.address] === this) connections.remove(device.address)
            if (!linkGone) {
                runCatching {
                    server?.connect(device, false)
                    server?.cancelConnection(device)
                }
            }
            if (opened) listener.onClosed(this, reason)
        }

        fun onFragment(bytes: ByteArray) {
            ensureOpened()
            if (!current || closing) return
            val frame = try {
                reassembler.accept(bytes)
            } catch (e: ProtocolException) {
                Log.w(TAG, "$label: bad fragment (${e.message})")
                reassembler.reset()
                disconnect("bad fragment")
                return
            } ?: return
            listener.onFrame(this, frame)
        }

        fun pump() {
            ensureOpened()
            if (!current || inFlight || device.address !in subscribed) return
            val next = queue.firstOrNull() ?: return
            val s = server ?: return
            val tx = txCharacteristic ?: return
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    s.notifyCharacteristicChanged(device, tx, false, next) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    tx.value = next
                    @Suppress("DEPRECATION")
                    s.notifyCharacteristicChanged(device, tx, false)
                }
            } catch (e: RuntimeException) {
                // A rejected notification (bad length, link gone, permission revoked) ends this link, not the app.
                Log.w(TAG, "$label: notification rejected", e)
                disconnect("notification rejected")
                return
            }
            if (ok) {
                queue.removeFirst()
                inFlight = true
            } else {
                // The stack is busy; try again shortly.
                handler.postDelayed({ pump() }, 20)
            }
        }

        fun onSent(status: Int) {
            inFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // A frame is now cut short; the iPhone can't recover this session.
                Log.w(TAG, "$label: notification failed ($status)")
                disconnect("notification failed")
                return
            }
            pump()
            if (closing && queue.isEmpty() && !inFlight) disconnect("closed by the bridge")
        }
    }

    companion object {
        private const val TAG = "ble"
        private const val UNCLAIMED_LINK_MS = 8_000L
        val SERVICE_UUID: UUID = UUID.fromString("8CC09D09-4084-4E91-A358-A153EB79D422")
        val RX_UUID: UUID = UUID.fromString("15347F70-79F0-40C4-92DE-ED6612B4CE5F")
        val TX_UUID: UUID = UUID.fromString("8A38CBD3-5771-4174-9C10-7EB7FD219E6B")
        val ID_UUID: UUID = UUID.fromString("CFFC7A8D-1012-4A2D-B52F-50A9E32F8B32")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun isBluetoothOn(context: Context): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

        fun hasBluetooth(context: Context): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter != null
    }
}
