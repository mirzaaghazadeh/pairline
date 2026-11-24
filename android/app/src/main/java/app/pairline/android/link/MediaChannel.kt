package app.pairline.android.link

/**
 * The encrypted, low-latency media channel to the iPhone (PROTOCOL.md, "Media channel (UDP)"): one UDP datagram per
 * packet, AES-256-GCM with the Wi-Fi session's media keys, a 64-record replay window. Built for 20 ms audio packets
 * (M6): packets may be lost or reordered, nothing is retransmitted, and [send] never blocks.
 *
 * Get the current one from [LinkManager.media] (null while there's no open channel). The channel belongs to one
 * Wi-Fi session: when that session ends it closes ([isOpen] turns false) and the flow goes back to null.
 */
interface MediaChannel {
    /** True once a datagram from the iPhone authenticated, until the Wi-Fi session ends. */
    val isOpen: Boolean

    /**
     * Sends one packet of 1 to 1200 bytes (larger packets throw [IllegalArgumentException]). Thread-safe and
     * non-blocking: if the send queue is full, or the channel is closed, the packet is dropped.
     */
    fun send(packet: ByteArray)

    /**
     * Called for every authentic packet from the iPhone, in arrival order (not send order), on the channel's own
     * receive thread. Keep it short: hand the packet to your audio queue and return.
     */
    var onPacket: ((ByteArray) -> Unit)?
}

/** The link layer's handle on a media channel it opened for a Wi-Fi session. Implemented in `link.wifi`. */
interface MediaEndpoint : MediaChannel {
    /** The local UDP port, sent to the iPhone in `wifi.media`. */
    val port: Int

    /** Called once, on the receive thread, when the first authentic datagram arrives (the channel opens). */
    var onOpened: (() -> Unit)?

    fun close()
}
