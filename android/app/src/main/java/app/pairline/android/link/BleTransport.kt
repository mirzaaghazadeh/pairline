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
                handler.postDelayed({ if (server != null && !advertising) startAdvertising() }, 30_000)
            }
        }
    }

    private fun stopInternal() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        advertiser = null
        for (c in connections.values.toList()) {
            runCatching { server?.cancelConnection(c.device) }
            listener.onClosed(c, "Bluetooth link stopped")
        }
        connections.clear()
        runCatching { server?.close() }
        server = null
        txCharacteristic = null
        onStatus(BluetoothStatus.UNKNOWN, false)
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
            handler.post {
                val key = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (connections.containsKey(key)) return@post
                    val c = BleConnection(device, "ble#${labels.getAndIncrement()}")
                    connections[key] = c
                    listener.onOpened(c)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val c = connections.remove(key) ?: return@post
                    listener.onClosed(c, "Bluetooth disconnected (status $status)")
                    if (!advertising && server != null) startAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { connections[device.address]?.mtu = mtu }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val s = server ?: return
            if (characteristic.uuid == ID_UUID) {
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
            handler.post { connections[device.address]?.onFragment(bytes) }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val subscribed = connections[device.address]?.subscribed == true
            val value = if (subscribed) {
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
                connections[device.address]?.let {
                    it.subscribed = enable
                    if (enable) it.pump()
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { connections[device.address]?.onSent(status) }
        }
    }

    private inner class BleConnection(val device: BluetoothDevice, override val label: String) : LinkConnection {
        override val kind = TransportKind.BLUETOOTH
        var mtu = 23
        var subscribed = false
        private var inFlight = false
        private val queue = ArrayDeque<ByteArray>()
        private val reassembler = BleReassembler()

        override fun send(frame: ByteArray) {
            handler.post {
                if (!connections.containsKey(device.address)) return@post
                // ATT payload is MTU − 3; never below the BLE minimum.
                val payload = maxOf(BleFragmenter.MIN_ATT_PAYLOAD, mtu - 3)
                queue.addAll(BleFragmenter.fragment(frame, payload))
                pump()
            }
        }

        override fun close() {
            handler.post { runCatching { server?.cancelConnection(device) } }
        }

        fun onFragment(bytes: ByteArray) {
            val frame = try {
                reassembler.accept(bytes)
            } catch (e: ProtocolException) {
                Log.w(TAG, "$label: bad fragment (${e.message})")
                reassembler.reset()
                close()
                return
            } ?: return
            listener.onFrame(this, frame)
        }

        fun pump() {
            if (inFlight || !subscribed) return
            val next = queue.firstOrNull() ?: return
            val s = server ?: return
            val tx = txCharacteristic ?: return
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, tx, false, next) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                tx.value = next
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, tx, false)
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
                Log.w(TAG, "$label: notification failed ($status)")
                close()
                return
            }
            pump()
        }
    }

    companion object {
        private const val TAG = "ble"
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
