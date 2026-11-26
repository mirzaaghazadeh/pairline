package app.pairline.android.telephony

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.util.Base64
import app.pairline.android.files.FileSource
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** One part of an MMS: text, a picture, a contact card… */
data class MmsPart(val pid: Long, val type: String, val name: String?, val text: String?)

/** One MMS as the bridge reads it from Android's store (D-089). */
data class MmsRow(
    val id: Long,
    val thread: Long,
    /** Milliseconds (Android keeps MMS dates in seconds). */
    val time: Long,
    val msgBox: Int,
    val subId: Int,
    val from: String?,
    val recipients: List<String>,
    val parts: List<MmsPart>,
) {
    val incoming: Boolean get() = msgBox == Telephony.Mms.MESSAGE_BOX_INBOX
    val text: String get() = parts.filter { MmsMapping.isText(it.type) }.mapNotNull { it.text }.joinToString("\n").trim()
}

/** Pure mappings for MMS rows (unit-tested). */
object MmsMapping {
    fun key(id: Long) = "mms:$id"

    /** The MMS id in a key, or null when it isn't an MMS key. */
    fun id(key: String): Long? = key.removePrefix("mms:").takeIf { key.startsWith("mms:") }?.toLongOrNull()?.takeIf { it > 0 }

    fun state(msgBox: Int): String = when (msgBox) {
        Telephony.Mms.MESSAGE_BOX_INBOX -> "received"
        Telephony.Mms.MESSAGE_BOX_OUTBOX -> "pending"
        Telephony.Mms.MESSAGE_BOX_FAILED -> "failed"
        else -> "sent"
    }

    const val ADDR_FROM = 137
    const val ADDR_TO = 151
    const val ADDR_CC = 130

    fun isText(type: String) = type.equals("text/plain", ignoreCase = true)
    fun isImage(type: String) = type.startsWith("image/", ignoreCase = true)

    /** Parts worth showing besides the text: not the text itself and not the SMIL layout. */
    fun attachments(parts: List<MmsPart>) =
        parts.filter { !isText(it.type) && !it.type.equals("application/smil", ignoreCase = true) }

    /**
     * Everyone in the conversation except this phone's own numbers: the sender and the other recipients of an
     * incoming message, the recipients of an outgoing one. "insert-address-token" is Android's placeholder for self.
     */
    fun others(row: MmsRow, isOwn: (String) -> Boolean): List<String> {
        val all = if (row.incoming) listOfNotNull(row.from) + row.recipients else row.recipients
        val out = LinkedHashMap<String, String>()
        for (a in all) {
            if (a.isBlank() || a == "insert-address-token" || isOwn(a)) continue
            out.putIfAbsent(PhoneNumbers.matchKey(a) ?: a, a)
        }
        return out.values.toList()
    }
}

/**
 * Reads picture and group messages from Android's MMS store (READ_SMS, D-089). Never writes to it. Pictures are
 * offered as small thumbnails in the message; the full part goes to the iPhone with the file transfer on request
 * (`mms.part`). Received MMS from a blocked sender are left out, like texts (D-043).
 */
class MmsStore(
    private val context: Context,
    private val sims: SimDirectory,
    private val isBlocked: (String?) -> Boolean = { false },
) {
    private fun granted() = Perms.granted(context, Manifest.permission.READ_SMS)

    /** This phone's own numbers, to keep them out of a group's participants. */
    private fun isOwn(address: String): Boolean =
        sims.snapshots().mapNotNull { it.number }.any { PhoneNumbers.sameNumber(it, address) }

    /** MMS rows for `sms.history`, with the same bounds: [before]/[after] in milliseconds, [thread] or every thread. */
    fun history(thread: Long?, before: Long?, after: Long?, fetch: Int): List<MmsRow> {
        if (!granted()) return emptyList()
        val w = StringBuilder("${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}")
        // Sent requests (128) and downloaded messages (132); a notification not yet downloaded has nothing to show.
        w.append(" AND ${Telephony.Mms.MESSAGE_TYPE} IN (128, 132)")
        if (thread != null) w.append(" AND ${Telephony.Mms.THREAD_ID} = $thread")
        if (before != null) w.append(" AND ${Telephony.Mms.DATE} * 1000 < $before")
        if (after != null) w.append(" AND ${Telephony.Mms.DATE} * 1000 > $after")
        val order = if (after != null) "${Telephony.Mms.DATE} ASC" else "${Telephony.Mms.DATE} DESC"
        return rows(w.toString(), "$order, ${Telephony.Mms._ID} ASC LIMIT $fetch")
            .filterNot { it.incoming && isBlocked(it.from) }
    }

    /** The newest MMS of each thread (for `sms.threads`), and how many each thread has. */
    fun latestPerThread(): Map<Long, Pair<MmsRow, Int>> {
        if (!granted()) return emptyMap()
        val latest = LinkedHashMap<Long, Long>()
        val counts = HashMap<Long, Int>()
        runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
                "${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS} AND ${Telephony.Mms.MESSAGE_TYPE} IN (128, 132)",
                null,
                "${Telephony.Mms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val thread = c.getLong(1)
                    latest.putIfAbsent(thread, c.getLong(0))
                    counts[thread] = (counts[thread] ?: 0) + 1
                }
            }
        }
        if (latest.isEmpty()) return emptyMap()
        val rows = rows("${Telephony.Mms._ID} IN (${latest.values.joinToString(",")})", "${Telephony.Mms.DATE} DESC")
        return rows.filterNot { it.incoming && isBlocked(it.from) }
            .associate { it.thread to (it to (counts[it.thread] ?: 1)) }
    }

    // ---- New picture messages, live (D-089) ----

    private var observer: android.database.ContentObserver? = null
    private var lastSeenId = 0L
    private val watchHandler by lazy {
        android.os.Handler(android.os.HandlerThread("pairline-mms").apply { start() }.looper)
    }

    /**
     * Announces each MMS the default messaging app downloads from now on (`sms.new`), like texts. Android writes the
     * message and then its parts, so the check waits a moment after each change.
     */
    fun watch(emit: (JSONObject) -> Unit) {
        if (observer != null || !granted()) return
        lastSeenId = newestId()
        val check = Runnable {
            val rows = rows(
                "${Telephony.Mms._ID} > $lastSeenId AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}" +
                    " AND ${Telephony.Mms.MESSAGE_TYPE} = 132",
                "${Telephony.Mms._ID} ASC LIMIT 20",
            )
            if (rows.isEmpty()) return@Runnable
            lastSeenId = maxOf(lastSeenId, rows.maxOf { it.id })
            val slots = sims.activeSubscriptions().associate { it.subscriptionId to it.simSlotIndex }
            for (row in rows) {
                if (isBlocked(row.from)) continue
                emit(JSONObject().put("t", app.pairline.android.protocol.Msg.SMS_NEW).put("msg", toJson(row, slots)))
                app.pairline.android.log.Log.i("mms", "picture message received")
            }
        }
        val o = object : android.database.ContentObserver(watchHandler) {
            override fun onChange(selfChange: Boolean) {
                watchHandler.removeCallbacks(check)
                watchHandler.postDelayed(check, 2_000)
            }
        }
        runCatching { context.contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, o) }
            .onSuccess { observer = o }
    }

    fun unwatch() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    private fun newestId(): Long = runCatching {
        context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null,
            "${Telephony.Mms._ID} DESC LIMIT 1")?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    /** Everyone in this message's conversation but this phone. */
    fun participants(row: MmsRow): List<String> = MmsMapping.others(row, ::isOwn)

    /** The protocol's Message object for an MMS: `parts` with thumbnails, `addrs` for a group. */
    fun toJson(row: MmsRow, slots: Map<Int, Int>): JSONObject {
        val others = MmsMapping.others(row, ::isOwn)
        val addr = if (row.incoming) row.from.orEmpty() else others.firstOrNull() ?: row.recipients.firstOrNull().orEmpty()
        val parts = JSONArray()
        for (p in MmsMapping.attachments(row.parts)) {
            parts.put(
                JSONObject()
                    .put("pid", p.pid)
                    .put("type", p.type)
                    .putNullable("name", p.name)
                    .apply { if (MmsMapping.isImage(p.type)) thumbnail(p.pid)?.let { put("thumb", it) } },
            )
        }
        return JSONObject()
            .put("key", MmsMapping.key(row.id))
            .put("thread", row.thread)
            .put("addr", addr)
            .put("body", row.text)
            .put("time", row.time)
            .put("box", if (row.incoming) "in" else "out")
            .putNullable("sim", slots[row.subId])
            .put("state", MmsMapping.state(row.msgBox))
            .apply { if (parts.length() > 0) put("parts", parts) }
            .apply { if (others.size > 1) put("addrs", JSONArray(others)) }
    }

    /** `mms.part`: one attachment as a file for the transfer. */
    fun partSource(key: String, pid: Long): FileSource {
        if (!granted()) throw RequestException(ErrorCode.PERMISSION, "READ_SMS not granted")
        val mmsId = MmsMapping.id(key) ?: throw RequestException(ErrorCode.INVALID, "not an MMS key")
        val part = parts(mmsId).firstOrNull { it.pid == pid } ?: throw RequestException(ErrorCode.INVALID, "no such part")
        val uri = partUri(pid)
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: throw RequestException(ErrorCode.FAILED, "the part can't be read")
        val size = pfd.statSize.also { pfd.close() }
        val name = part.name?.takeIf { it.isNotBlank() } ?: "mms-$pid${extension(part.type)}"
        return FileSource(name, size.coerceAtLeast(0), part.type) {
            context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("part gone")
        }
    }

    private fun rows(selection: String, order: String): List<MmsRow> {
        val out = ArrayList<MmsRow>()
        runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX,
                    Telephony.Mms.SUBSCRIPTION_ID,
                ),
                selection,
                null,
                order,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val (from, to) = addresses(id)
                    out += MmsRow(
                        id = id,
                        thread = c.getLong(1),
                        time = c.getLong(2) * 1000,
                        msgBox = c.getInt(3),
                        subId = if (c.isNull(4)) -1 else c.getInt(4),
                        from = from,
                        recipients = to,
                        parts = parts(id),
                    )
                }
            }
        }
        return out
    }

    private fun addresses(mmsId: Long): Pair<String?, List<String>> {
        var from: String? = null
        val to = ArrayList<String>()
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    when (c.getInt(1)) {
                        MmsMapping.ADDR_FROM -> from = address
                        MmsMapping.ADDR_TO, MmsMapping.ADDR_CC -> to += address
                    }
                }
            }
        }
        return from to to
    }

    private fun parts(mmsId: Long): List<MmsPart> {
        val out = ArrayList<MmsPart>()
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME,
                    Telephony.Mms.Part.CONTENT_LOCATION, Telephony.Mms.Part.TEXT),
                "${Telephony.Mms.Part.MSG_ID} = $mmsId",
                null,
                "${Telephony.Mms.Part._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += MmsPart(
                        pid = c.getLong(0),
                        type = c.getString(1)?.lowercase() ?: "application/octet-stream",
                        name = (c.getString(2) ?: c.getString(3))?.takeIf { it.isNotBlank() },
                        text = c.getString(4),
                    )
                }
            }
        }
        return out
    }

    private fun partUri(pid: Long): Uri = Uri.parse("content://mms/part/$pid")

    /** A picture part as a small JPEG (128 px at most, ~4 KB), base64: shown in the conversation on the iPhone. */
    private fun thumbnail(pid: Long): String? = runCatching {
        val uri = partUri(pid)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null
        var sample = 1
        while (longest / (sample * 2) >= THUMB_PX) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val f = THUMB_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (f < 1) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1),
                (bitmap.height * f).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun extension(type: String) = when (type.lowercase()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "video/mp4" -> ".mp4"
        "video/3gpp" -> ".3gp"
        "audio/amr" -> ".amr"
        "text/x-vcard", "text/vcard" -> ".vcf"
        else -> ""
    }

    private companion object {
        const val THUMB_PX = 128
    }
}
