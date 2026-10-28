package com.glasshome.tv.web

import java.net.Inet4Address
import java.net.NetworkInterface

/** Address checks, ported from the Samsung version's service/netutil.js. */
object Net {
    fun parseV4(s: String?): IntArray? {
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(s ?: return null) ?: return null
        val p = IntArray(4) { m.groupValues[it + 1].toInt() }
        return if (p.all { it <= 255 }) p else null
    }

    private fun hextets(part: String): MutableList<Int>? {
        if (part.isEmpty()) return mutableListOf()
        return part.split(':').map { h ->
            if (!Regex("^[0-9a-f]{1,4}$").matches(h)) return null
            h.toInt(16)
        }.toMutableList()
    }

    /** "2001:db8::1" -> eight 16-bit groups. Accepts one "::" and a dotted IPv4 tail; zones must be stripped first. */
    fun parseV6(addr: String?): IntArray? {
        var s = (addr ?: return null).lowercase()
        if (s.length > 45 || ':' !in s || !Regex("^[0-9a-f:.]+$").matches(s)) return null
        var tail = listOf<Int>()
        val dot = s.indexOf('.')
        if (dot >= 0) {
            val cut = s.lastIndexOf(':')
            if (cut > dot) return null
            val v4 = parseV4(s.substring(cut + 1)) ?: return null
            tail = listOf(v4[0] * 256 + v4[1], v4[2] * 256 + v4[3])
            s = s.substring(0, cut + 1)
            if (!s.endsWith("::")) s = s.dropLast(1)
        }
        val halves = s.split("::")
        if (halves.size > 2) return null
        val head = hextets(halves[0]) ?: return null
        val rest = (if (halves.size == 2) hextets(halves[1]) ?: return null else mutableListOf()).apply { addAll(tail) }
        var zeros = 8 - head.size - rest.size
        if (if (halves.size == 2) zeros < 1 else zeros != 0) return null
        while (zeros-- > 0) head.add(0)
        return (head + rest).toIntArray()
    }

    private fun mappedV4(w: IntArray?): IntArray? {
        if (w == null || w[0] != 0 || w[1] != 0 || w[2] != 0 || w[3] != 0 || w[4] != 0 || w[5] != 0xffff) return null
        return intArrayOf(w[6] shr 8, w[6] and 255, w[7] shr 8, w[7] and 255)
    }

    /** An IPv4-mapped IPv6 address becomes plain IPv4; an IPv6 zone ("%wlan0") is dropped. */
    fun normalize(addr: String?): String {
        var a = (addr ?: "").lowercase()
        val z = a.indexOf('%')
        if (z > 0) a = a.substring(0, z)
        val v4 = if (':' in a) mappedV4(parseV6(a)) else null
        return v4?.joinToString(".") ?: a
    }

    fun isLoopback(addr: String?): Boolean {
        val a = normalize(addr)
        parseV4(a)?.let { return it[0] == 127 }
        return a == "::1"
    }

    /** Loopback and the private / link-local ranges a home network uses. */
    fun isPrivate(addr: String?): Boolean {
        val a = normalize(addr)
        parseV4(a)?.let { p ->
            return p[0] == 10 || p[0] == 127 ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 169 && p[1] == 254)
        }
        if (a == "::1") return true
        return Regex("^f[cd][0-9a-f]{2}:").containsMatchIn(a) || Regex("^fe[89ab][0-9a-f]:").containsMatchIn(a)
    }

    private fun isPublicV4(p: IntArray): Boolean {
        if (p[0] == 0 || p[0] == 10 || p[0] == 127) return false
        if (p[0] == 100 && p[1] in 64..127) return false                 // CGNAT
        if (p[0] == 169 && p[1] == 254) return false
        if (p[0] == 172 && p[1] in 16..31) return false
        if (p[0] == 192 && p[1] == 168) return false
        if (p[0] == 192 && p[1] == 0 && (p[2] == 0 || p[2] == 2)) return false
        if (p[0] == 192 && p[1] == 88 && p[2] == 99) return false         // 6to4 relay anycast
        if (p[0] == 198 && (p[1] == 18 || p[1] == 19)) return false
        if (p[0] == 198 && p[1] == 51 && p[2] == 100) return false
        if (p[0] == 203 && p[1] == 0 && p[2] == 113) return false
        if (p[0] >= 224) return false                                    // multicast, reserved
        return true
    }

    /**
     * For outbound fetches (site icons, YouTube): only public unicast addresses,
     * so a shortcut can never make the TV probe the home network. IPv6 must be
     * global unicast (2000::/3) minus the documentation and IETF blocks; 6to4 is
     * judged by the IPv4 address it carries.
     */
    fun isPublicUnicast(addr: String?): Boolean {
        val a = normalize(addr)
        parseV4(a)?.let { return isPublicV4(it) }
        val w = parseV6(a) ?: return false
        if (w[0] < 0x2000 || w[0] > 0x3fff) return false
        if (w[0] == 0x2001 && w[1] < 0x0200) return false
        if (w[0] == 0x2001 && w[1] == 0x0db8) return false
        if (w[0] == 0x3fff && w[1] < 0x1000) return false
        if (w[0] == 0x2002) return isPublicV4(intArrayOf(w[1] shr 8, w[1] and 255, w[2] shr 8, w[2] and 255))
        return true
    }

    /**
     * True when [addr] is on one of the TV's own networks. A home network's IPv6
     * addresses are usually global (2000::/3), so "public" alone would let a
     * shortcut reach the TV or the devices next to it.
     */
    fun isOnLocalNetwork(addr: java.net.InetAddress): Boolean {
        val target = addr.address
        val ifs = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return false
        for (nif in ifs) for (ia in nif.interfaceAddresses) {
            val own = ia.address?.address ?: continue
            if (own.size != target.size) continue
            val bits = ia.networkPrefixLength.toInt().coerceIn(0, own.size * 8)
            var same = true
            for (i in 0 until bits) {
                val mask = 0x80 shr (i % 8)
                if ((own[i / 8].toInt() and mask) != (target[i / 8].toInt() and mask)) { same = false; break }
            }
            if (same) return true
        }
        return false
    }

    /** Who is calling, for per-device limits: an IPv4 address, or an IPv6 /64 (a device can pick any address in it). */
    fun clientKey(addr: String?): String {
        val a = normalize(addr)
        if (parseV4(a) != null) return a
        val w = parseV6(a) ?: return a
        return (0 until 4).joinToString(":") { "%x".format(w[it]) } + "::/64"
    }

    fun isIpLiteral(host: String?): Boolean {
        val h = host ?: return false
        if (parseV4(h) != null) return true
        return ':' in h && Regex("^[0-9a-fA-F:.]+$").matches(h)
    }

    /** DNS-rebinding guard: the Host header must be an address or a local name. */
    fun hostAllowed(hostHeader: String?): Boolean {
        var h = (hostHeader ?: return false).lowercase().trim()
        if (h.isEmpty()) return false
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end < 2 || parseV6(h.substring(1, end)) == null) return false
            val rest = h.substring(end + 1)
            return (rest.isEmpty() || Regex("^:\\d{1,5}$").matches(rest)) && isPrivate(h.substring(1, end))
        }
        val colon = h.indexOf(':')
        if (colon >= 0) {
            if (!Regex("^\\d{1,5}$").matches(h.substring(colon + 1))) return false
            h = h.substring(0, colon)
        }
        if (parseV4(h) != null) return isPrivate(h)          // a public address here means a rebound name
        if (h == "localhost") return true
        if (Regex("^[a-z0-9-]{1,63}$").matches(h)) return true
        return Regex("^[a-z0-9.-]+\\.(local|lan|home|home\\.arpa|internal|localdomain)$").matches(h)
    }

    /** The TV's own LAN IPv4 addresses, best first. */
    fun lanAddresses(): List<String> {
        val out = mutableListOf<Pair<String, Int>>()
        val ifs = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return emptyList()
        for (nif in ifs) {
            if (runCatching { !nif.isUp || nif.isLoopback }.getOrDefault(true)) continue
            val name = nif.name ?: ""
            for (a in nif.inetAddresses) {
                if (a !is Inet4Address) continue
                val ip = a.hostAddress ?: continue
                if (!isPrivate(ip) || isLoopback(ip)) continue
                var score = 0
                if (Regex("^(wlan|wl|eth|en)", RegexOption.IGNORE_CASE).containsMatchIn(name)) score += 10
                if (Regex("^(p2p|docker|br-|veth|tun|tap|virbr)", RegexOption.IGNORE_CASE).containsMatchIn(name)) score -= 20
                score += when {
                    ip.startsWith("192.168.") -> 3
                    ip.startsWith("10.") -> 2
                    ip.startsWith("172.") -> 1
                    else -> 0
                }
                if (ip.startsWith("169.254.")) score -= 30
                if (ip.startsWith("192.168.49.")) score -= 15                  // Wi-Fi Direct group
                out += ip to score
            }
        }
        return out.sortedByDescending { it.second }.map { it.first }
    }
}
