package app.pairline.android.link

enum class TransportKind { BLUETOOTH, DEBUG_TCP }

/** One connection from an iPhone, over any transport. Methods are thread-safe. */
interface LinkConnection {
    val kind: TransportKind

    /** A short label for logs ("ble#3"). Never a MAC address. */
    val label: String

    /** Queues one whole frame; the transport splits it as needed. */
    fun send(frame: ByteArray)

    /** Drops the connection. The transport reports it through [ConnectionListener.onClosed]. */
    fun close()
}

/** Transports report connection events here. Calls may come from any thread. */
interface ConnectionListener {
    fun onOpened(connection: LinkConnection)
    fun onFrame(connection: LinkConnection, frame: ByteArray)
    fun onClosed(connection: LinkConnection, reason: String)
}

/** A way for the iPhone to reach the bridge: BLE in every build, TCP in debug builds only (D-018). */
interface LinkTransport {
    val kind: TransportKind
    fun start()
    fun stop()
}
