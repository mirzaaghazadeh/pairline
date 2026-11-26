package app.pairline.android.telephony

import java.io.ByteArrayOutputStream

/**
 * Builds an MMS "send request" (m-send-req) PDU as OMA-WAP-MMS-ENC 1.2 describes it, for
 * `SmsManager.sendMultimediaMessage` (D-089). Only what Pairline sends: recipients, text and pictures, as
 * `application/vnd.wap.multipart.mixed`; Android fills in the sender and the carrier's settings.
 */
object MmsPdu {
    class Part(val contentType: String, val name: String, val data: ByteArray)

    // Header field names (WAP-209 well-known values with the high bit set).
    private const val CONTENT_TYPE = 0x84
    private const val DELIVERY_REPORT = 0x86
    private const val FROM = 0x89
    private const val MESSAGE_CLASS = 0x8A
    private const val MESSAGE_TYPE = 0x8C
    private const val MMS_VERSION = 0x8D
    private const val READ_REPORT = 0x90
    private const val TO = 0x97
    private const val TRANSACTION_ID = 0x98

    private const val M_SEND_REQ = 0x80
    private const val VERSION_1_2 = 0x92
    private const val INSERT_ADDRESS_TOKEN = 0x81
    private const val CLASS_PERSONAL = 0x80
    private const val NO = 0x81
    private const val MULTIPART_MIXED = 0xA3

    // Part headers and well-known content types.
    private const val PART_CONTENT_LOCATION = 0x8E
    private const val PARAM_CHARSET = 0x81
    private const val CHARSET_UTF8 = 0xEA // MIBenum 106 as a short integer
    private const val PARAM_NAME = 0x85

    private val wellKnownTypes = mapOf(
        "text/plain" to 0x83,
        "image/gif" to 0x9D,
        "image/jpeg" to 0x9E,
        "image/png" to 0xA0,
    )

    fun sendRequest(transactionId: String, to: List<String>, parts: List<Part>): ByteArray {
        require(to.isNotEmpty()) { "no recipients" }
        require(parts.isNotEmpty()) { "no parts" }
        val out = ByteArrayOutputStream()
        out.write(MESSAGE_TYPE); out.write(M_SEND_REQ)
        out.write(TRANSACTION_ID); textString(out, transactionId)
        out.write(MMS_VERSION); out.write(VERSION_1_2)
        out.write(FROM); out.write(0x01); out.write(INSERT_ADDRESS_TOKEN)
        for (address in to) {
            out.write(TO); textString(out, "$address/TYPE=PLMN")
        }
        out.write(MESSAGE_CLASS); out.write(CLASS_PERSONAL)
        out.write(DELIVERY_REPORT); out.write(NO)
        out.write(READ_REPORT); out.write(NO)
        // Content-Type is the last header; the multipart body follows.
        out.write(CONTENT_TYPE); out.write(MULTIPART_MIXED)
        uintvar(out, parts.size.toLong())
        for (part in parts) {
            val headers = ByteArrayOutputStream()
            contentType(headers, part.contentType, part.name)
            headers.write(PART_CONTENT_LOCATION); textString(headers, part.name)
            val h = headers.toByteArray()
            uintvar(out, h.size.toLong())
            uintvar(out, part.data.size.toLong())
            out.write(h)
            out.write(part.data)
        }
        return out.toByteArray()
    }

    /** Content-general-form: the length, the media type, then the charset for text and the name for the rest. */
    private fun contentType(out: ByteArrayOutputStream, type: String, name: String) {
        val value = ByteArrayOutputStream()
        val known = wellKnownTypes[type.lowercase()]
        if (known != null) value.write(known) else textString(value, type.lowercase())
        if (type.equals("text/plain", ignoreCase = true)) {
            value.write(PARAM_CHARSET); value.write(CHARSET_UTF8)
        } else {
            value.write(PARAM_NAME); textString(value, name)
        }
        valueLength(out, value.size())
        out.write(value.toByteArray())
    }

    /** Text-string: a leading 0x7F quote when the first byte is 128 or more, then the bytes and a 0. */
    internal fun textString(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) >= 0x80) out.write(0x7F)
        out.write(bytes)
        out.write(0)
    }

    /** Value-length: one byte below 31, else 31 and a uintvar. */
    internal fun valueLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 31) out.write(length) else { out.write(31); uintvar(out, length.toLong()) }
    }

    /** Uintvar: 7 bits per byte, most significant first, the high bit set on all but the last. */
    internal fun uintvar(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0)
        val groups = ArrayList<Int>()
        var v = value
        do {
            groups += (v and 0x7F).toInt()
            v = v shr 7
        } while (v > 0)
        for (i in groups.indices.reversed()) out.write(if (i > 0) groups[i] or 0x80 else groups[i])
    }
}
