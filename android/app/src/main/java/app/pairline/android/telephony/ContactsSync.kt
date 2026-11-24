package app.pairline.android.telephony

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.JsonPager
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sync cursors are opaque strings to the iPhone (PROTOCOL.md clarifications). Inside they hold where the
 * last page ended, so the next request continues there.
 */
data class ContactsCursor(val updatedAt: Long, val contactId: Long, val deletedAt: Long) {
    fun encode() = "c1.$updatedAt.$contactId.$deletedAt"

    companion object {
        fun parse(s: String?): ContactsCursor? {
            val p = s?.split('.') ?: return null
            if (p.size != 4 || p[0] != "c1") return null
            return ContactsCursor(p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null, p[3].toLongOrNull() ?: return null)
        }
    }
}

data class CallLogCursor(val lastId: Long) {
    fun encode() = "l1.$lastId"

    companion object {
        fun parse(s: String?): CallLogCursor? {
            val p = s?.split('.') ?: return null
            if (p.size != 2 || p[0] != "l1") return null
            return CallLogCursor(p[1].toLongOrNull() ?: return null)
        }
    }
}

/** Phone-number labels on the wire are fixed English words, not localized UI text. */
object ContactLabels {
    fun phone(type: Int, custom: String?): String = when (type) {
        Phone.TYPE_MOBILE -> "mobile"
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "work"
        Phone.TYPE_MAIN -> "main"
        Phone.TYPE_FAX_HOME, Phone.TYPE_FAX_WORK, Phone.TYPE_OTHER_FAX -> "fax"
        Phone.TYPE_PAGER, Phone.TYPE_WORK_PAGER -> "pager"
        Phone.TYPE_CUSTOM -> custom?.takeIf { it.isNotBlank() } ?: "other"
        else -> "other"
    }

    fun phoneType(label: String?): Int = when (label?.lowercase()) {
        "mobile", "cell", null, "" -> Phone.TYPE_MOBILE
        "home" -> Phone.TYPE_HOME
        "work" -> Phone.TYPE_WORK
        "main" -> Phone.TYPE_MAIN
        "fax" -> Phone.TYPE_FAX_WORK
        "pager" -> Phone.TYPE_PAGER
        "other" -> Phone.TYPE_OTHER
        else -> Phone.TYPE_CUSTOM
    }
}

/** Contacts and call log for the iPhone (`contacts.sync`, `calllog.sync`, `contacts.add`). */
class ContactsSync(private val context: Context, private val sims: SimDirectory) {

    private val resolver get() = context.contentResolver

    private fun require(p: String) {
        if (!Perms.granted(context, p)) throw RequestException(ErrorCode.PERMISSION, "${p.substringAfterLast('.')} not granted")
    }

    /**
     * `contacts.sync`. With `since` null (or older than Android keeps deletions) the reply starts a full sync:
     * `full: true` on the first page tells the iPhone to replace its copy with this page and the ones after it.
     */
    fun contacts(id: Long, since: String?): JSONObject {
        require(Manifest.permission.READ_CONTACTS)
        val parsed = ContactsCursor.parse(since)
        val now = System.currentTimeMillis()
        val tooOld = parsed != null && parsed.deletedAt != 0L &&
            now - parsed.deletedAt > ContactsContract.DeletedContacts.DAYS_KEPT_MILLISECONDS
        val full = parsed == null || tooOld
        val start = if (full) ContactsCursor(0, 0, now) else parsed!!

        // Deleted since the last sync (only on incremental syncs).
        val deleted = JSONArray()
        var deletedAt = start.deletedAt
        if (!full) {
            resolver.query(
                ContactsContract.DeletedContacts.CONTENT_URI,
                arrayOf(ContactsContract.DeletedContacts.CONTACT_ID, ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP),
                // Numbers inline (they're Longs): a "?" argument is text, and provider views may compare it as text.
                "${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} > ${start.deletedAt}",
                null,
                "${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} ASC",
            )?.use { c ->
                while (c.moveToNext() && deleted.length() < MAX_DELETED) {
                    deleted.put("c:${c.getLong(0)}")
                    deletedAt = maxOf(deletedAt, c.getLong(1))
                }
            }
        }

        // Changed or added since the cursor, oldest change first.
        val rows = ArrayList<Triple<Long, String, Pair<Boolean, Long>>>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ),
            "(${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ${start.updatedAt}) OR " +
                "(${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} = ${start.updatedAt} AND " +
                "${ContactsContract.Contacts._ID} > ${start.contactId})",
            null,
            "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} ASC, ${ContactsContract.Contacts._ID} ASC",
        )?.use { c ->
            while (c.moveToNext() && rows.size <= CHUNK) {
                rows += Triple(c.getLong(0), c.getString(1).orEmpty(), (c.getInt(2) != 0) to c.getLong(3))
            }
        }
        val hasMoreRows = rows.size > CHUNK
        val chunk = rows.take(CHUNK)
        val details = details(chunk.map { it.first })
        val (page, taken) = JsonPager.page(chunk.iterator(), CHUNK, budget()) { (cid, name, meta) ->
            val d = details[cid]
            JSONObject()
                .put("key", "c:$cid")
                .put("name", name)
                .put("starred", meta.first)
                .put("numbers", d?.numbers ?: JSONArray())
                .put("emails", d?.emails ?: JSONArray())
        }
        val last = taken.lastOrNull()
        val next = ContactsCursor(
            updatedAt = last?.third?.second ?: start.updatedAt,
            contactId = last?.first ?: start.contactId,
            deletedAt = deletedAt,
        )
        return JSONObject()
            .put("t", Msg.CONTACTS_SYNC)
            .put("id", id)
            .put("contacts", page.items)
            .put("deleted", deleted)
            .put("cursor", next.encode())
            .put("more", page.more || hasMoreRows)
            .put("full", full)
    }

    private class Details {
        val numbers = JSONArray()
        val emails = JSONArray()
    }

    private fun details(ids: List<Long>): Map<Long, Details> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Details>()
        val placeholders = ids.joinToString(",") { "?" }
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
            ),
            "${ContactsContract.Data.CONTACT_ID} IN ($placeholders) AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            ids.map { it.toString() }.toTypedArray() + arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val d = out.getOrPut(c.getLong(0)) { Details() }
                val value = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                when (c.getString(1)) {
                    Phone.CONTENT_ITEM_TYPE -> if (d.numbers.length() < 20) {
                        d.numbers.put(JSONObject().put("n", value).put("label", ContactLabels.phone(c.getInt(3), c.getString(4))))
                    }
                    Email.CONTENT_ITEM_TYPE -> if (d.emails.length() < 10) d.emails.put(value)
                }
            }
        }
        return out
    }

    /** `calllog.sync`: entries after the cursor, oldest first. `full: true` when starting from scratch. */
    fun callLog(id: Long, since: String?): JSONObject {
        require(Manifest.permission.READ_CALL_LOG)
        // A cursor past the newest entry means the call log was cleared: start over with a full sync.
        val parsed = CallLogCursor.parse(since)?.takeIf { it.lastId <= newestCallLogId() }
        val after = parsed?.lastId ?: 0L
        val slotBySub = sims.activeSubscriptions().associate { it.subscriptionId.toString() to it.simSlotIndex }
        val rows = ArrayList<JSONObject>()
        val ids = ArrayList<Long>()
        resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID,
            ),
            "${CallLog.Calls._ID} > $after",
            null,
            "${CallLog.Calls._ID} ASC",
        )?.use { c ->
            while (c.moveToNext() && rows.size <= CHUNK) {
                ids += c.getLong(0)
                rows += JSONObject()
                    .put("key", "cl:${c.getLong(0)}")
                    .put("number", c.getString(1).orEmpty())
                    .putNullable("name", c.getString(2)?.takeIf { it.isNotBlank() })
                    .put("type", CallMapping.callLogType(c.getInt(3)))
                    .put("time", c.getLong(4))
                    .put("dur", c.getLong(5))
                    .putNullable("sim", c.getString(6)?.let { slotBySub[it] })
            }
        }
        val hasMoreRows = rows.size > CHUNK
        val indexed = rows.take(CHUNK).withIndex().toList()
        val (page, taken) = JsonPager.page(indexed.iterator(), CHUNK, budget()) { it.value }
        val lastId = taken.lastOrNull()?.let { ids[it.index] } ?: after
        return JSONObject()
            .put("t", Msg.CALLLOG_SYNC)
            .put("id", id)
            .put("calls", page.items)
            .put("cursor", CallLogCursor(lastId).encode())
            .put("more", page.more || hasMoreRows)
            .put("full", parsed == null)
    }

    /** The call-log provider rejects LIMIT in the sort order, so read the first row of a sorted query. */
    private fun newestCallLogId(): Long = resolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls._ID),
        null,
        null,
        "${CallLog.Calls._ID} DESC",
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L

    /** `contacts.add` (opt-in): a new phone-only contact with a name and numbers. */
    fun add(name: String, numbers: List<Pair<String, String?>>) {
        require(Manifest.permission.WRITE_CONTACTS)
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, name)
            .build()
        for ((number, label) in numbers) {
            val type = ContactLabels.phoneType(label)
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, number)
                .withValue(Phone.TYPE, type)
                .apply { if (type == Phone.TYPE_CUSTOM) withValue(Phone.LABEL, label) }
                .build()
        }
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            throw RequestException(ErrorCode.FAILED, "the contact couldn't be saved")
        }
    }

    private fun budget(): Int = app.pairline.android.protocol.Proto.PAGE_BUDGET_BYTES - DELETED_RESERVE

    private companion object {
        const val CHUNK = 200
        const val MAX_DELETED = 500

        /** Up to 500 deleted keys ("c:12345", ~10 bytes each) share the page with contacts. */
        const val DELETED_RESERVE = 6_000
    }
}
