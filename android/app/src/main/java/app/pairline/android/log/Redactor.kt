package app.pairline.android.log

/**
 * Removes personal data from log text (AGENTS.md rule 9): phone numbers, email addresses, Bluetooth/Wi-Fi
 * MAC addresses, IP addresses (remote mode, D-092: a home's internet address tells roughly where it is), pairing
 * links and long key-like tokens. Message text must never be passed to the log at all; this is the safety net, not
 * the plan.
 */
object Redactor {
    private val pairingLink = Regex("""pairline://\S+""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}.\-]+\.\p{L}{2,}""")
    private val mac = Regex("""\b[0-9A-Fa-f]{2}(?:[:\-][0-9A-Fa-f]{2}){5}\b""")

    // IPv4 with an optional port; IPv6 in brackets (with a port), compressed ("::") or written out in full.
    private val ipv4 = Regex("""(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?![\d.])""")
    private val ipv6Bracketed = Regex("""\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(?:%\w+)?](?::\d{1,5})?""")
    private val ipv6 = Regex(
        """(?<![\w:])(?:(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|(?:[0-9A-Fa-f]{1,4}:){0,7}:(?::?[0-9A-Fa-f]{1,4}){0,7})(?:%\w+)?(?![\w:])""",
    )

    // Base64url/hex blobs of 24+ characters with both letters and digits: keys, secrets, device ids.
    private val token = Regex("""(?<![\p{L}\p{N}_\-])(?=[A-Za-z0-9_\-]*\d)(?=[A-Za-z0-9_\-]*[A-Za-z])[A-Za-z0-9_\-]{24,}""")

    // Digit runs, in any script, that may be separated by spaces, dashes, dots or brackets.
    private val digits = Regex("""\+?\(?\p{Nd}[\p{Nd}\s\-.()/]*\p{Nd}""")

    /** Our own timestamps ("2026-11-24 10:22"), which the digit rule would otherwise take for a number. */
    private val timestamp = Regex("""^20\d{2}-[01]\d-[0-3]\d( [0-2]\d)?$""")

    /** Number sequences with at least this many digits are treated as phone numbers. */
    private const val MIN_PHONE_DIGITS = 6

    fun redact(text: String): String {
        var out = pairingLink.replace(text, "[pairing-link]")
        out = email.replace(out, "[email]")
        out = mac.replace(out, "[mac]")
        out = ipv6Bracketed.replace(out, "[ip]")
        out = ipv6.replace(out) { m -> if (m.value.contains("::") || m.value.count { it == ':' } == 7) "[ip]" else m.value }
        out = ipv4.replace(out, "[ip]")
        out = token.replace(out, "[key]")
        out = digits.replace(out) { m ->
            val value = m.value.trimEnd()
            val count = value.count { Character.isDigit(it) }
            when {
                count < MIN_PHONE_DIGITS -> m.value
                timestamp.matches(value) -> m.value
                else -> "[number]" + m.value.substring(value.length)
            }
        }
        return out
    }
}
