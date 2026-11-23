package app.pairline.android.log

/**
 * Removes personal data from log text (AGENTS.md rule 9): phone numbers, email addresses, Bluetooth/Wi-Fi
 * MAC addresses, pairing links and long key-like tokens. Message text must never be passed to the log at
 * all; this is the safety net, not the plan.
 */
object Redactor {
    private val pairingLink = Regex("""pairline://\S+""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}.\-]+\.\p{L}{2,}""")
    private val mac = Regex("""\b[0-9A-Fa-f]{2}(?:[:\-][0-9A-Fa-f]{2}){5}\b""")

    // Base64url/hex blobs of 24+ characters with both letters and digits: keys, secrets, device ids.
    private val token = Regex("""(?<![\p{L}\p{N}_\-])(?=[A-Za-z0-9_\-]*\d)(?=[A-Za-z0-9_\-]*[A-Za-z])[A-Za-z0-9_\-]{24,}""")

    // Digit runs, in any script, that may be separated by spaces, dashes, dots or brackets.
    private val digits = Regex("""\+?\(?\p{Nd}[\p{Nd}\s\-.()/]*\p{Nd}""")

    /** Number sequences with at least this many digits are treated as phone numbers. */
    private const val MIN_PHONE_DIGITS = 6

    fun redact(text: String): String {
        var out = pairingLink.replace(text, "[pairing-link]")
        out = email.replace(out, "[email]")
        out = mac.replace(out, "[mac]")
        out = token.replace(out, "[key]")
        out = digits.replace(out) { m ->
            val count = m.value.count { Character.isDigit(it) }
            if (count >= MIN_PHONE_DIGITS) "[number]" else m.value
        }
        return out
    }
}
