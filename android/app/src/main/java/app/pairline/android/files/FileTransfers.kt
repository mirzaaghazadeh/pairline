package app.pairline.android.files

import android.content.Context
import android.net.Uri
import app.pairline.android.link.FileMessageHandler
import app.pairline.android.link.LinkManager
import app.pairline.android.link.WifiSessionListener
import app.pairline.android.log.Log
import app.pairline.android.protocol.FileData
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.Proto
import app.pairline.android.protocol.boolOrNull
import app.pairline.android.protocol.longOrNull
import app.pairline.android.protocol.stringOrNull
import app.pairline.android.protocol.type
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** One transfer as the UI and notifications show it. */
data class TransferItem(
    val id: Long,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val transferred: Long,
    val phase: TransferPhase,
    /** Why it failed or was declined: the protocol's reason ("too_large", "no_space", …) or "wifi_lost". */
    val reason: String? = null,
    /** Received files: where to open them. */
    val uri: Uri? = null,
)

enum class TransferPhase { WAITING, OFFERED, ACTIVE, DONE, FAILED, CANCELLED }

/**
 * File transfer both ways over the Wi-Fi session (PROTOCOL.md "File transfer"; D-045). Outgoing files wait until
 * a Wi-Fi session to an iPhone that accepts files is up, then go one at a time: `file.offer`, `file.answer`, data in
 * `BULK` records (at most 4 chunks queued, so a big file never sits in memory), `file.done`. Incoming files are
 * accepted automatically (both phones are the user's) up to 1 GiB, streamed to Downloads/Pairline, and announced in
 * a notification. A transfer fails when its Wi-Fi session ends; there's no resume in v0.
 *
 * Messages and data arrive on the link thread; sending runs on its own thread.
 */
class FileTransfers(private val context: Context, private val link: LinkManager) :
    FileMessageHandler, WifiSessionListener {

    private val lock = Any()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-files") }
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pairline-files-timer") }
    private val ids = AtomicLong(1)
    private val random = SecureRandom()

    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())

    /** Every transfer since the app started, newest first. */
    val items: StateFlow<List<TransferItem>> = _items

    var notifications: FileNotifications? = null

    private class Outgoing(val id: Long, val fid: Long, val source: FileSource) {
        @Volatile var cancelled = false
        @Volatile var token: Long = 0
        @Volatile var answered: Boolean? = null
        @Volatile var sendFailed = false
    }

    private class Incoming(val id: Long, val fid: Long, val token: Long, val size: Long, val sink: FileSink) {
        var received = 0L
        var lastProgress = 0L
    }

    private val queue = ArrayDeque<Outgoing>()
    private var active: Outgoing? = null

    /** Incoming transfers by the iPhone's fid; [preparing] holds offers whose storage is being created. */
    private val incoming = HashMap<Long, Incoming>()
    private val preparing = HashSet<Long>()

    /** MediaStore calls (create, publish), which can take half a second. */
    private val storage = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-files-storage") }

    // --- Sending ------------------------------------------------------------------------------------------------

    /** Queues shared files for the iPhone. Returns how many could be opened. Call off the main thread. */
    fun send(uris: List<Uri>, fallbackMime: String?): Int {
        var queued = 0
        for (uri in uris) {
            val source = FileStore.openSource(context, uri, fallbackMime)
            if (source == null) {
                Log.w(TAG, "couldn't open a shared item")
                continue
            }
            if (source.size > Proto.FILE_MAX_SIZE) {
                val id = ids.getAndIncrement()
                publish(TransferItem(id, true, source.name, source.size, 0, TransferPhase.FAILED, "too_large"))
                runCatching { source.openStream().close() }
                continue
            }
            enqueue(source)
            queued++
        }
        pump()
        return queued
    }

    /** Queues a file that's already open (shared items go through [send]; debug tools call this directly). */
    fun enqueue(source: FileSource) {
        val out = Outgoing(ids.getAndIncrement(), random.nextInt().toLong() and 0xffffffffL, source)
        synchronized(lock) { queue.addLast(out) }
        publish(TransferItem(out.id, true, source.name, source.size, 0, TransferPhase.WAITING))
        pump()
    }

    /** The user cancelled a transfer (notification action or the app). */
    fun cancel(id: Long) {
        synchronized(lock) {
            queue.firstOrNull { it.id == id }?.let { out ->
                queue.remove(out)
                runCatching { out.source.openStream().close() }
                update(id) { it.copy(phase = TransferPhase.CANCELLED) }
                return
            }
            active?.takeIf { it.id == id }?.let { out ->
                out.cancelled = true
                link.reply(out.token, Messages.fileCancel(out.fid, "cancelled"))
                update(id) { it.copy(phase = TransferPhase.CANCELLED) }
                return
            }
        }
        // Incoming transfers live on the link thread.
        linkThread {
            val entry = incoming.values.firstOrNull { it.id == id } ?: return@linkThread
            incoming.remove(entry.fid)
            entry.sink.abort()
            link.reply(entry.token, Messages.fileDone(entry.fid, false, "cancelled"))
            update(id) { it.copy(phase = TransferPhase.CANCELLED) }
        }
    }

    /** Starts the next queued file when a Wi-Fi session to an iPhone that accepts files is up. */
    private fun pump() = sender.execute {
        val out = synchronized(lock) {
            if (active != null) return@execute
            val token = link.wifiToken ?: return@execute
            if (!link.peerAcceptsFiles) return@execute
            val next = queue.removeFirstOrNull() ?: return@execute
            next.token = token
            active = next
            next
        }
        Log.i(TAG, "file ${out.id}: offering to the iPhone")
        link.reply(out.token, Messages.fileOffer(out.fid, out.source.name, out.source.size, out.source.mime))
        update(out.id) { it.copy(phase = TransferPhase.OFFERED) }
        timers.schedule({
            if (out.answered == null && active === out) finishOutgoing(out, TransferPhase.FAILED, "no_answer")
        }, ANSWER_TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun stream(out: Outgoing) = sender.execute {
        val size = out.source.size
        val gate = Semaphore(MAX_CHUNKS_IN_FLIGHT)
        val buffer = ByteArray(Proto.FILE_CHUNK)
        var offset = 0L
        var lastProgress = 0L
        update(out.id) { it.copy(phase = TransferPhase.ACTIVE) }
        try {
            out.source.openStream().use { input ->
                while (offset < size) {
                    if (out.cancelled || out.sendFailed || link.wifiToken != out.token) break
                    val want = minOf(Proto.FILE_CHUNK.toLong(), size - offset).toInt()
                    val n = readFully(input, buffer, want)
                    if (n < want) {
                        Log.w(TAG, "file ${out.id}: shorter than announced")
                        out.sendFailed = true
                        break
                    }
                    gate.acquire()
                    link.sendBulk(out.token, FileData.encode(out.fid, offset, buffer, n)) { sent ->
                        if (!sent) out.sendFailed = true
                        gate.release()
                    }
                    offset += n
                    val now = System.currentTimeMillis()
                    if (now - lastProgress > PROGRESS_EVERY_MS) {
                        lastProgress = now
                        val done = offset
                        update(out.id) { it.copy(transferred = done) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "file ${out.id}: reading failed", e)
            out.sendFailed = true
        }
        gate.acquire(MAX_CHUNKS_IN_FLIGHT) // every chunk written
        when {
            out.cancelled -> finishOutgoing(out, TransferPhase.CANCELLED, null)
            out.sendFailed || offset < size -> {
                if (link.wifiToken == out.token) link.reply(out.token, Messages.fileCancel(out.fid, "failed"))
                finishOutgoing(out, TransferPhase.FAILED, if (link.wifiToken != out.token) "wifi_lost" else "failed")
            }
            else -> {
                update(out.id) { it.copy(transferred = size) }
                // Now wait for file.done from the iPhone.
                timers.schedule({
                    if (active === out) finishOutgoing(out, TransferPhase.FAILED, "no_answer")
                }, DONE_TIMEOUT_S, TimeUnit.SECONDS)
            }
        }
    }

    private fun finishOutgoing(out: Outgoing, phase: TransferPhase, reason: String?) {
        synchronized(lock) {
            if (active !== out) return
            active = null
        }
        if (phase == TransferPhase.FAILED) Log.i(TAG, "file ${out.id}: not sent (${reason ?: "failed"})")
        if (phase == TransferPhase.DONE) Log.i(TAG, "file ${out.id}: sent")
        update(out.id) { if (it.phase == TransferPhase.CANCELLED) it else it.copy(phase = phase, reason = reason) }
        if (out.answered != true) runCatching { out.source.openStream().close() }
        pump()
    }

    private fun readFully(input: InputStream, into: ByteArray, length: Int): Int {
        var read = 0
        while (read < length) {
            val n = input.read(into, read, length - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    // --- Messages and data (link thread) -------------------------------------------------------------------------

    override fun onMessage(message: JSONObject, token: Long) {
        val fid = message.longOrNull("fid")?.takeIf { it in 0..0xffffffffL } ?: return
        when (message.type) {
            Msg.FILE_OFFER -> onOffer(message, fid, token)
            Msg.FILE_CANCEL -> incoming[fid]?.takeIf { it.token == token }?.let { entry ->
                incoming.remove(fid)
                entry.sink.abort()
                link.reply(token, Messages.fileDone(fid, false, "cancelled"))
                update(entry.id) { it.copy(phase = TransferPhase.CANCELLED) }
            }
            Msg.FILE_ANSWER -> {
                val out = synchronized(lock) { active?.takeIf { it.fid == fid && it.token == token } } ?: return
                if (out.answered != null) return
                val accept = message.boolOrNull("accept") == true
                out.answered = accept
                if (accept) {
                    stream(out)
                } else {
                    finishOutgoing(out, TransferPhase.FAILED, message.stringOrNull("reason") ?: "declined")
                }
            }
            Msg.FILE_DONE -> {
                val out = synchronized(lock) { active?.takeIf { it.fid == fid && it.token == token } } ?: return
                if (message.boolOrNull("ok") == true) {
                    finishOutgoing(out, TransferPhase.DONE, null)
                } else {
                    val reason = message.stringOrNull("reason") ?: "failed"
                    out.sendFailed = true // stop sending; the iPhone gave up on this file
                    out.cancelled = reason == "cancelled"
                    finishOutgoing(out, if (reason == "cancelled") TransferPhase.CANCELLED else TransferPhase.FAILED, reason)
                }
            }
        }
    }

    private fun onOffer(m: JSONObject, fid: Long, token: Long) {
        val name = FileStore.sanitizeName(m.stringOrNull("name"))
        val size = m.longOrNull("size") ?: -1
        val mime = m.stringOrNull("type")?.take(100)
        fun refuse(reason: String) {
            Log.i(TAG, "refused a file from the iPhone ($reason)")
            link.reply(token, Messages.fileAnswer(fid, false, reason))
        }
        if (incoming.containsKey(fid) || fid in preparing) return refuse("busy")
        if (size < 0 || size > Proto.FILE_MAX_SIZE) return refuse("too_large")
        if (incoming.size + preparing.size >= MAX_INCOMING) return refuse("busy")
        preparing += fid
        // Creating the Downloads entry is a slow call into MediaStore: keep it off the link thread, which also
        // carries the Bluetooth session. No data arrives before our file.answer, so nothing is missed meanwhile.
        storage.execute {
            val sink = if (FileStore.hasRoomFor(context, size)) FileStore.createSink(context, name, mime) else null
            val reason = if (sink == null && !FileStore.hasRoomFor(context, size)) "no_space" else "failed"
            linkThread {
                preparing -= fid
                if (sink == null) return@linkThread refuse(reason)
                if (link.wifiToken != token) {
                    sink.abort() // the Wi-Fi session ended meanwhile
                    return@linkThread
                }
                val entry = Incoming(ids.getAndIncrement(), fid, token, size, sink)
                incoming[fid] = entry
                publish(TransferItem(entry.id, false, name, size, 0, TransferPhase.ACTIVE))
                Log.i(TAG, "file ${entry.id}: receiving from the iPhone")
                link.reply(token, Messages.fileAnswer(fid, true))
                if (size == 0L) complete(entry)
            }
        }
    }

    override fun onBulk(token: Long, plaintext: ByteArray) {
        val data = FileData.decode(plaintext) ?: return
        val entry = incoming[data.fid]?.takeIf { it.token == token } ?: return // late data for a finished fid
        if (data.offset != entry.received || entry.received + data.data.size > entry.size) {
            return fail(entry, "failed")
        }
        try {
            entry.sink.write(data.data)
        } catch (e: Exception) {
            Log.w(TAG, "file ${entry.id}: writing failed", e)
            return fail(entry, if (!FileStore.hasRoomFor(context, 0)) "no_space" else "failed")
        }
        entry.received += data.data.size
        if (entry.received == entry.size) {
            complete(entry)
        } else {
            val now = System.currentTimeMillis()
            if (now - entry.lastProgress > PROGRESS_EVERY_MS) {
                entry.lastProgress = now
                val done = entry.received
                update(entry.id) { it.copy(transferred = done) }
            }
        }
    }

    /** All bytes are in: publish the file (a MediaStore call, off the link thread) and tell the iPhone. */
    private fun complete(entry: Incoming) {
        incoming.remove(entry.fid)
        storage.execute {
            val uri = try {
                entry.sink.commit()
            } catch (e: Exception) {
                Log.w(TAG, "file ${entry.id}: saving failed", e)
                entry.sink.abort()
                link.reply(entry.token, Messages.fileDone(entry.fid, false, "failed"))
                update(entry.id) { it.copy(phase = TransferPhase.FAILED, reason = "failed") }
                return@execute
            }
            link.reply(entry.token, Messages.fileDone(entry.fid, true))
            Log.i(TAG, "file ${entry.id}: received")
            update(entry.id) { it.copy(phase = TransferPhase.DONE, transferred = entry.size, uri = uri) }
        }
    }

    private fun fail(entry: Incoming, reason: String) {
        incoming.remove(entry.fid)
        entry.sink.abort()
        link.reply(entry.token, Messages.fileDone(entry.fid, false, reason))
        update(entry.id) { it.copy(phase = TransferPhase.FAILED, reason = reason) }
    }

    override fun onWifiSession(token: Long?) {
        // Transfers belong to the session they started on.
        for (entry in incoming.values.toList()) {
            if (entry.token != token) {
                incoming.remove(entry.fid)
                entry.sink.abort()
                update(entry.id) { it.copy(phase = TransferPhase.FAILED, reason = "wifi_lost") }
            }
        }
        val stale = synchronized(lock) { active?.takeIf { it.token != token } }
        if (stale != null) {
            stale.sendFailed = true
            if (stale.answered != true) finishOutgoing(stale, TransferPhase.FAILED, "wifi_lost")
        }
        if (token != null) pump()
    }

    override fun onPeerCaps() = pump()

    // --- State ----------------------------------------------------------------------------------------------------

    private fun linkThread(block: () -> Unit) {
        link.runOnLinkThread(block)
    }

    private fun publish(item: TransferItem) {
        synchronized(_items) { _items.value = (listOf(item) + _items.value).take(MAX_ITEMS) }
        notifications?.show(item)
    }

    private fun update(id: Long, change: (TransferItem) -> TransferItem) {
        var updated: TransferItem? = null
        synchronized(_items) {
            _items.value = _items.value.map { if (it.id == id) change(it).also { n -> updated = n } else it }
        }
        updated?.let { notifications?.show(it) }
    }

    private companion object {
        const val TAG = "files"
        const val MAX_CHUNKS_IN_FLIGHT = 4
        const val MAX_INCOMING = 4
        const val MAX_ITEMS = 30
        const val PROGRESS_EVERY_MS = 400L
        const val ANSWER_TIMEOUT_S = 60L
        const val DONE_TIMEOUT_S = 60L
    }
}
