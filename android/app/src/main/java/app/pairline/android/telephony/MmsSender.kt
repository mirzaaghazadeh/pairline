package app.pairline.android.telephony

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Base64
import androidx.core.content.FileProvider
import app.pairline.android.AppGraph
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Picture and group messages from the iPhone (D-089). A picture comes first in pieces (`mms.attach`, any session, so
 * Bluetooth works too), then `mms.send` builds the MMS send request ([MmsPdu]) and hands it to Android's own MMS
 * service, which needs mobile data on. Android stores the sent message itself for an app that isn't the default
 * messaging app; `sms.state` reports sent or failed (MMS has no delivery reports here). Pieces and requests live in
 * the app's cache only until sent, at most 10 minutes.
 */
class MmsSender(private val context: Context, private val sims: SimDirectory, private val emit: (JSONObject) -> Unit) {
    private class Attachment(val file: File, val type: String, val name: String) {
        var nextSeq = 0
        var size = 0L
        var complete = false
        val created = System.currentTimeMillis()
    }

    private class Pending(val cid: String, val pdu: File, val startedAt: Long)

    private val attachments = ConcurrentHashMap<String, Attachment>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val inDir get() = File(context.cacheDir, "mms-in").apply { mkdirs() }
    private val outDir get() = File(context.cacheDir, "mms-out").apply { mkdirs() }

    /** `mms.attach`: one piece of a picture, in order. */
    fun attach(aid: String, seq: Int, data: String, last: Boolean, type: String?, name: String?) {
        prune()
        if (!AID.matches(aid)) throw RequestException(ErrorCode.INVALID, "bad aid")
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
            ?: throw RequestException(ErrorCode.INVALID, "bad data")
        val a = if (seq == 0) {
            if (attachments.size >= MAX_ATTACHMENTS) throw RequestException(ErrorCode.BUSY, "too many attachments")
            val t = type?.lowercase()?.takeIf { it.startsWith("image/") } ?: throw RequestException(ErrorCode.INVALID, "not a picture")
            Attachment(File(inDir, aid), t, MmsName.safe(name, t)).also {
                it.file.delete()
                attachments[aid] = it
            }
        } else {
            attachments[aid] ?: throw RequestException(ErrorCode.INVALID, "unknown aid")
        }
        synchronized(a) {
            if (a.complete || seq != a.nextSeq) throw RequestException(ErrorCode.INVALID, "out of order")
            if (a.size + bytes.size > MAX_ATTACHMENT_BYTES) {
                drop(aid)
                throw RequestException(ErrorCode.INVALID, "picture too large")
            }
            FileOutputStream(a.file, true).use { it.write(bytes) }
            a.size += bytes.size
            a.nextSeq++
            a.complete = last
        }
    }

    /** `mms.send`: text and/or one picture to one or more numbers (several make a group message). */
    fun send(cid: String, sim: Int?, to: List<String>, body: String?, aid: String?) {
        prune()
        if (!Perms.granted(context, Manifest.permission.SEND_SMS)) throw RequestException(ErrorCode.PERMISSION, "SEND_SMS not granted")
        if (cid.isEmpty() || cid.length > 128 || pending.containsKey(cid)) throw RequestException(ErrorCode.INVALID, "bad cid")
        if (to.isEmpty() || to.size > MAX_RECIPIENTS) throw RequestException(ErrorCode.INVALID, "bad recipients")
        val recipients = to.map { PhoneNumbers.normalizeSmsRecipient(it) ?: throw RequestException(ErrorCode.INVALID, "bad number") }
        val parts = ArrayList<MmsPdu.Part>()
        val text = body?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) {
            if (text.length > 5_000) throw RequestException(ErrorCode.INVALID, "text too long")
            parts += MmsPdu.Part("text/plain", "text.txt", text.toByteArray(Charsets.UTF_8))
        }
        if (aid != null) {
            val a = attachments[aid]?.takeIf { it.complete } ?: throw RequestException(ErrorCode.INVALID, "picture not received")
            parts += MmsPdu.Part(a.type, a.name, a.file.readBytes())
            drop(aid)
        }
        if (parts.isEmpty()) throw RequestException(ErrorCode.INVALID, "nothing to send")
        val subId = sims.subIdFor(sim, SimDirectory.Purpose.SMS)
        if (sim != null && subId == null) throw RequestException(ErrorCode.INVALID, "no SIM in that slot")

        val pdu = File(outDir, "${cid.filter { it.isLetterOrDigit() || it == '-' }}.pdu")
        pdu.writeBytes(MmsPdu.sendRequest("T" + System.currentTimeMillis().toString(16), recipients, parts))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", pdu)
        for (pkg in MMS_PACKAGES) {
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val sent = PendingIntent.getBroadcast(
            context,
            cid.hashCode(),
            Intent(context, MmsResultReceiver::class.java).setAction(ACTION_SENT).putExtra(EXTRA_CID, cid)
                .setData(Uri.parse("pairline-mms://sent/${Uri.encode(cid)}")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        pending[cid] = Pending(cid, pdu, System.currentTimeMillis())
        try {
            smsManager(subId).sendMultimediaMessage(context, uri, null, null, sent)
            Log.i(TAG, "MMS queued (${parts.size} part(s), ${recipients.size} recipient(s), SIM ${sim?.let { it + 1 } ?: "default"})")
        } catch (e: Exception) {
            pending.remove(cid)
            pdu.delete()
            Log.w(TAG, "MMS send failed to start", e)
            throw RequestException(ErrorCode.FAILED, "the MMS couldn't be queued")
        }
    }

    internal fun onSent(cid: String, resultCode: Int) {
        val p = pending.remove(cid) ?: return
        p.pdu.delete()
        val ok = resultCode == Activity.RESULT_OK
        Log.i(TAG, if (ok) "MMS sent" else "MMS failed (code $resultCode)")
        emit(
            JSONObject()
                .put("t", Msg.SMS_STATE)
                .put("cid", cid)
                .put("state", if (ok) "sent" else "failed")
                .putNullable("key", if (ok) findSent(p.startedAt) else null)
                .putNullable("error", if (ok) null else "MMS not sent (is mobile data on?)"),
        )
    }

    /** The message Android stored for what we just sent, so the iPhone can match its own copy. */
    private fun findSent(since: Long): String? = runCatching {
        context.contentResolver.query(
            Telephony.Mms.Sent.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.DATE} * 1000 >= ${since - 10_000}",
            null,
            "${Telephony.Mms.DATE} DESC LIMIT 1",
        )?.use { c -> if (c.moveToFirst()) MmsMapping.key(c.getLong(0)) else null }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun smsManager(subId: Int?): SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val base = context.getSystemService(SmsManager::class.java)
        if (subId != null) base.createForSubscriptionId(subId) else base
    } else {
        if (subId != null) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }

    private fun drop(aid: String) {
        attachments.remove(aid)?.file?.delete()
    }

    /** Pieces and requests older than 10 minutes go. */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        attachments.entries.removeIf { (_, a) -> (a.created < cutoff).also { if (it) a.file.delete() } }
        pending.entries.removeIf { (_, p) -> (p.startedAt < cutoff).also { if (it) p.pdu.delete() } }
    }

    companion object {
        private const val TAG = "mms"
        private val AID = Regex("[A-Za-z0-9-]{1,64}")
        private const val MAX_ATTACHMENTS = 3
        private const val MAX_RECIPIENTS = 20
        private const val TTL_MS = 10 * 60 * 1000L

        /** Carriers commonly take 300 KB to 1 MB; the iPhone sizes pictures under this. */
        const val MAX_ATTACHMENT_BYTES = 1_000_000L

        /** Where Android's MMS service runs: it reads the request through our FileProvider. */
        private val MMS_PACKAGES = listOf("com.android.phone", "com.android.mms.service")
        const val ACTION_SENT = "app.pairline.android.MMS_SENT"
        const val EXTRA_CID = "cid"
    }
}

/** A safe file name for a picture part, with an extension that matches its type. */
object MmsName {
    fun safe(name: String?, type: String): String {
        val ext = when (type) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            else -> "jpg"
        }
        val base = name?.substringAfterLast('/')?.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            ?.substringBeforeLast('.')?.take(40)?.takeIf { it.isNotEmpty() } ?: "image"
        return "$base.$ext"
    }
}

/** Android's answer for an MMS the iPhone sent; reached only through our own PendingIntent. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsSender.ACTION_SENT) return
        val cid = intent.getStringExtra(MmsSender.EXTRA_CID) ?: return
        val code = resultCode
        val pending = goAsync()
        AppGraph.io.execute {
            try {
                AppGraph.mmsSender.onSent(cid, code)
            } finally {
                pending.finish()
            }
        }
    }
}
