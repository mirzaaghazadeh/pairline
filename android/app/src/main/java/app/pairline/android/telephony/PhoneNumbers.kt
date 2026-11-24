package app.pairline.android.telephony

/** Checks on numbers and codes that arrive from the iPhone, before they reach Android's telephony APIs. */
object PhoneNumbers {
    private const val MAX_LENGTH = 64

    /**
     * Normalizes a number to dial or text: drops spaces, dashes, dots and brackets, converts Persian and
     * Arabic-Indic digits to ASCII, and keeps a leading '+'. Returns null if what's left isn't a number.
     * Allows '*', '#', ',' and ';' after the first digit (pauses, extensions, service codes).
     */
    fun normalizeDialable(raw: String): String? {
        val out = StringBuilder()
        for ((i, ch) in raw.trim().withIndex()) {
            val c = asciiDigit(ch)
            when {
                c != null -> out.append(c)
                ch == '+' && out.isEmpty() && i == raw.trim().indexOf('+') -> out.append('+')
                ch == '*' || ch == '#' || ch == ',' || ch == ';' -> out.append(ch)
                ch == ' ' || ch == '-' || ch == '.' || ch == '(' || ch == ')' || ch == '/' || ch == ' ' -> Unit
                ch == '‎' || ch == '‏' || ch == '⁦' || ch == '⁩' -> Unit // bidi marks
                else -> return null
            }
        }
        val s = out.toString()
        if (s.isEmpty() || s.length > MAX_LENGTH) return null
        if (s.none { it.isDigit() }) return null
        if (s.indexOf('+', 1) >= 0) return null
        return s
    }

    /** A recipient for `sms.send`: a dialable number (no pauses or service codes). */
    fun normalizeSmsRecipient(raw: String): String? {
        val n = normalizeDialable(raw) ?: return null
        if (n.any { it == '*' || it == '#' || it == ',' || it == ';' }) return null
        return n
    }

    /** A USSD or MMI code such as `*100#` or `*123*1#`. */
    fun normalizeUssd(raw: String): String? {
        val n = normalizeDialable(raw) ?: return null
        if (n.length < 2 || !(n.startsWith('*') || n.startsWith('#'))) return null
        if (!n.endsWith('#')) return null
        if (n.any { it == ',' || it == ';' || it == '+' }) return null
        return n
    }

    /** A single DTMF digit: 0–9, '*', '#', or A–D. */
    fun dtmfDigit(raw: String): Char? {
        if (raw.length != 1) return null
        val c = asciiDigit(raw[0]) ?: raw[0].uppercaseChar()
        return c.takeIf { it in '0'..'9' || it == '*' || it == '#' || it in 'A'..'D' }
    }

    /** Last digits used to compare two numbers that may be written differently (+98912… vs 0912…). */
    fun tail(number: String?, digits: Int = 9): String? {
        if (number == null) return null
        val d = number.mapNotNull(::asciiDigit).joinToString("")
        if (d.isEmpty()) return null
        return d.takeLast(digits)
    }

    fun sameNumber(a: String?, b: String?): Boolean {
        val ta = tail(a) ?: return false
        val tb = tail(b) ?: return false
        return ta == tb
    }

    /**
     * The block list's matching key, the same rule as the iPhone's `PhoneNumber.matchKey` (D-043): with five or more
     * digits, the last 9 (so "+98 912 123 4567" and "0912 123 4567" match); otherwise the text itself, lowercased,
     * for short codes and sender names such as "BANK". Null for empty input.
     */
    fun matchKey(raw: String?): String? {
        if (raw == null) return null
        val digits = raw.mapNotNull(::asciiDigit).joinToString("")
        if (digits.length >= 5) return digits.takeLast(9)
        val text = raw.trim().lowercase()
        return if (text.isEmpty()) null else "t:$text"
    }

    /**
     * Whether a blocked entry covers [number] (D-043), the same rule on both phones. With seven or more digits on both
     * sides, the last digits must agree, as many as the shorter one has, up to 9, so "+354 555 1234" covers "5551234"
     * and "+98 912 123 4567" covers "0912 123 4567". Shorter numbers and sender names must match exactly ([matchKey]).
     */
    fun blockMatches(blocked: String?, number: String?): Boolean {
        if (blocked == null || number == null) return false
        val a = digits(blocked)
        val b = digits(number)
        if (a.length >= MIN_MATCH && b.length >= MIN_MATCH) return sameEnding(a, b)
        val ka = matchKey(blocked) ?: return false
        return ka == matchKey(number)
    }

    /** Both digit strings have at least [MIN_MATCH] digits: compare as many trailing digits as the shorter has, up to 9. */
    internal fun sameEnding(a: String, b: String): Boolean {
        val n = minOf(9, a.length, b.length)
        return a.regionMatches(a.length - n, b, b.length - n, n)
    }

    /** The digits of [raw] in ASCII (Persian and Arabic-Indic digits converted), everything else dropped. */
    fun digits(raw: String): String = raw.mapNotNull(::asciiDigit).joinToString("")

    /** Fewest digits compared when matching numbers loosely (Android's own PhoneNumberUtils uses 7 too). */
    const val MIN_MATCH = 7

    /**
     * A number or sender name to block, as the user typed it: trimmed, bidi marks removed, 1–64 characters, no
     * control characters. Numbers are normalized like [normalizeDialable]; anything else (an alphanumeric sender ID)
     * is kept as text.
     */
    fun normalizeBlockable(raw: String): String? {
        val cleaned = raw.filterNot { it == '‎' || it == '‏' || it == '⁦' || it == '⁩' || it == '⁨' }.trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_LENGTH || cleaned.any { it.isISOControl() }) return null
        return normalizeDialable(cleaned) ?: cleaned.takeIf { matchKey(it) != null }
    }

    private fun asciiDigit(ch: Char): Char? = when (ch) {
        in '0'..'9' -> ch
        in '۰'..'۹' -> '0' + (ch - '۰') // Persian
        in '٠'..'٩' -> '0' + (ch - '٠') // Arabic-Indic
        else -> null
    }
}
