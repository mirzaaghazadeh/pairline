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

    private fun asciiDigit(ch: Char): Char? = when (ch) {
        in '0'..'9' -> ch
        in '۰'..'۹' -> '0' + (ch - '۰') // Persian
        in '٠'..'٩' -> '0' + (ch - '٠') // Arabic-Indic
        else -> null
    }
}
