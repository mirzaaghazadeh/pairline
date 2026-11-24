package app.pairline.android.netshare

/**
 * The proxy auto-config file the iPhone loads from `http://<bridge>:<port>/proxy.pac` (M8, D-048). Local addresses
 * and the chosen domains go direct; everything else goes through the bridge phone (and its VPN). There is no
 * "DIRECT" fallback for the rest: if the bridge goes away, traffic stops instead of silently leaving the VPN.
 */
object Pac {
    /** Domain suffixes that ship with the app (D-048). Users choose which apply. */
    val IRAN = listOf("ir")

    fun build(proxyHost: String, port: Int, directSuffixes: List<String>): String {
        val direct = directSuffixes
            .map { it.trim().trimStart('.').lowercase() }
            .filter { it.matches(Regex("[a-z0-9-]+(\\.[a-z0-9-]+)*")) }
            .distinct()
        val rules = buildString {
            for (suffix in direct) append("  if (host == \"$suffix\" || dnsDomainIs(host, \".$suffix\")) return \"DIRECT\";\n")
        }
        val target = if (':' in proxyHost) "[$proxyHost]" else proxyHost
        return """
            |function FindProxyForURL(url, host) {
            |  host = host.toLowerCase();
            |  if (isPlainHostName(host) || dnsDomainIs(host, ".local")) return "DIRECT";
            |  if (/^\d+\.\d+\.\d+\.\d+$/.test(host) && (isInNet(host, "10.0.0.0", "255.0.0.0") ||
            |      isInNet(host, "172.16.0.0", "255.240.0.0") || isInNet(host, "192.168.0.0", "255.255.0.0") ||
            |      isInNet(host, "169.254.0.0", "255.255.0.0") || isInNet(host, "127.0.0.0", "255.0.0.0"))) return "DIRECT";
            |$rules  return "PROXY $target:$port";
            |}
            |""".trimMargin()
    }
}
