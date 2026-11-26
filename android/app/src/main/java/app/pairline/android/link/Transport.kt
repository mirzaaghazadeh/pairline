package app.pairline.android.link

enum class TransportKind {
    BLUETOOTH,
    DEBUG_TCP,

    /** The local Wi-Fi link (M5): a full session, plus the media and bulk channels. */
    WIFI,

    /**
     * Remote mode's direct connection through the home router (D-072): the Wi-Fi session's framing, handshake and
     * messages from anywhere on the internet, without media or bulk channels, on a lane of its own.
     */
    DIRECT;

    /** BLE and the debug TCP link share the "radio" lane; Wi-Fi has its own (PROTOCOL.md, "Two sessions at once"). */
    val isWifi: Boolean get() = this == WIFI

    /** Wi-Fi and the direct connection notice a dead peer within 45 s (PROTOCOL.md, "Wi-Fi" keep-alive). */
    val fastKeepAlive: Boolean get() = this == WIFI || this == DIRECT
}

/** One connection from an iPhone, over any transport. Methods are thread-safe. */
interface LinkConnection {
    val kind: TransportKind

    /** A short label for logs ("ble#3"). Never a MAC or IP address. */
    val label: String

    /** The iPhone's IP address on a Wi-Fi connection; null on other transports. Never logged. */
    val peerAddress: java.net.InetAddress? get() = null

    /** Queues one whole frame; the transport splits it as needed. */
    fun send(frame: ByteArray)

    /**
     * Queues one frame and calls [onSent] once it has been written (or dropped because the connection closed).
     * File transfers use this to keep only a few chunks in memory.
     */
    fun send(frame: ByteArray, onSent: () -> Unit) {
        send(frame)
        onSent()
    }

    /** Drops the connection. The transport reports it through [ConnectionListener.onClosed]. */
    fun close()

    /**
     * The link thread finished one frame this connection delivered through [ConnectionListener.onFrame]. Wi-Fi uses
     * it to stop reading while too many frames wait (file data arrives faster than BLE ever could).
     */
    fun frameProcessed() {}

    /**
     * The session handshake on this connection authenticated the pinned iPhone. Called on the link thread. The direct
     * connection reads nothing after the hello and the confirm until this.
     */
    fun onAuthenticated() {}

    /**
     * True if this connection pulls call audio from a [MediaFrameSource] itself, ahead of frames that haven't started
     * (Bluetooth: a slow link mustn't queue audio behind a sync page). Otherwise the source sends its frames through
     * [send] as they come.
     */
    val pullsMedia: Boolean get() = false

    /** Call audio is waiting in [source] (D-072). Only called when [pullsMedia]. Any thread. */
    fun mediaReady(source: MediaFrameSource) {}
}

/** Call audio waiting to go out as `MEDIA` frames (PROTOCOL.md, "Call audio", D-072). */
interface MediaFrameSource {
    /** The next `MEDIA` frame, with every packet that's waiting in it, or null when nothing is. Thread-safe. */
    fun nextFrame(): ByteArray?
}

/** A Wi-Fi connection can open the UDP media channel for its session. */
interface MediaCapable {
    /**
     * Binds the media socket on this connection's local address. [sealKey] is B→R, [openKey] R→B; the endpoint
     * owns them from here on. Null if no socket could be bound.
     */
    fun openMedia(sealKey: ByteArray, openKey: ByteArray): MediaEndpoint?
}

/** Transports report connection events here. Calls may come from any thread. */
interface ConnectionListener {
    fun onOpened(connection: LinkConnection)
    fun onFrame(connection: LinkConnection, frame: ByteArray)
    fun onClosed(connection: LinkConnection, reason: String)
}

/** A way for the iPhone to reach the bridge: BLE and Wi-Fi in every build, TCP in debug builds only (D-018). */
interface LinkTransport {
    val kind: TransportKind
    fun start()
    fun stop()
}
