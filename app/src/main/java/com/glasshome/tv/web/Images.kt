package com.glasshome.tv.web

/** Image sniffing, ported from service/imageutil.js. SVG is left out: the TV can't draw it. */
object Images {
    class Info(val ext: String, val width: Int, val height: Int)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u16le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32be(b: ByteArray, i: Int) = (u16be(b, i).toLong() shl 16) or u16be(b, i + 2).toLong()
    private fun ascii(b: ByteArray, from: Int, to: Int) = String(b, from, to - from, Charsets.ISO_8859_1)

    fun isJpegStart(b: ByteArray, len: Int = b.size) =
        len >= 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF

    /** Walks the JPEG marker segments to the first SOFn; null when not a plausible JPEG. */
    fun jpegSize(b: ByteArray, len: Int = b.size): Pair<Int, Int>? {
        if (!isJpegStart(b, len)) return null
        var i = 2
        while (i + 1 < len) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            if (marker == 0xFF) { i += 1; continue }                         // fill byte
            if (marker == 0x01 || marker in 0xD0..0xD8) { i += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) return null                // EOI / SOS before a frame header
            if (i + 4 > len) return null
            val segLen = u16be(b, i + 2)
            if (segLen < 2) return null
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 9 > len) return null
                val h = u16be(b, i + 5)
                val w = u16be(b, i + 7)
                return if (w > 0 && h > 0) w to h else null
            }
            i += 2 + segLen
        }
        return null
    }

    /** The format and size of an icon the TV can show (PNG, JPEG, GIF, WebP, ICO), or null. */
    fun sniff(b: ByteArray): Info? = sniffAny(b)?.takeIf { it.width in 0..4096 && it.height in 0..4096 }

    private fun sniffAny(b: ByteArray): Info? {
        if (b.size < 4) return null
        if (b.size >= 24 && u32be(b, 0) == 0x89504E47L && u32be(b, 4) == 0x0D0A1A0AL) {
            return Info("png", u32be(b, 16).toInt(), u32be(b, 20).toInt())
        }
        if (isJpegStart(b)) {
            val s = jpegSize(b) ?: (0 to 0)
            return Info("jpg", s.first, s.second)
        }
        if (b.size >= 10 && ascii(b, 0, 4) == "GIF8") return Info("gif", u16le(b, 6), u16le(b, 8))
        if (b.size >= 30 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 12) == "WEBP") {
            return when (ascii(b, 12, 16)) {
                "VP8X" -> Info("webp", 1 + (u16le(b, 24) or (u8(b, 26) shl 16)), 1 + (u16le(b, 27) or (u8(b, 29) shl 16)))
                "VP8 " -> Info("webp", u16le(b, 26) and 0x3FFF, u16le(b, 28) and 0x3FFF)
                "VP8L" -> {
                    val v = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                    Info("webp", (v and 0x3FFF) + 1, ((v shr 14) and 0x3FFF) + 1)
                }
                else -> Info("webp", 0, 0)
            }
        }
        if (b.size >= 22 && u16le(b, 0) == 0 && u16le(b, 2) == 1) {
            val n = u16le(b, 4)
            var best = 0
            var i = 0
            while (i < n && 6 + i * 16 + 16 <= b.size) {
                val w = u8(b, 6 + i * 16).let { if (it == 0) 256 else it }
                if (w > best) best = w
                i++
            }
            if (best > 0) return Info("ico", best, best)
        }
        return null
    }
}
