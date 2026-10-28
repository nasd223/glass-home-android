package com.glasshome.tv.web

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Passwords, tokens and login throttling, ported from service/auth.js. */
object Auth {
    private const val KEY_BITS = 256
    const val MAX_PASSWORD_LEN = 256
    private val rng = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun randomToken(bytes: Int = 32): String =
        Base64.encodeToString(randomBytes(bytes), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun randomHex(bytes: Int) = randomBytes(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    fun sha256hex(s: String) = sha256(s).joinToString("") { "%02x".format(it) }

    /** "maple-otter-sky-47": three different words and two digits. */
    fun generatePassword(): String {
        val picked = LinkedHashSet<String>()
        while (picked.size < 3) picked += WORDS[rng.nextInt(WORDS.size)]
        return picked.joinToString("-") + "-" + (10 + rng.nextInt(90))
    }

    /**
     * Generated passwords are compared case-insensitively and with any separator,
     * because phones capitalise and autocorrect. Chosen ones are compared exactly.
     */
    fun canonicalGenerated(pw: String) = pw.trim().lowercase().replace(Regex("[\\s._\\u2013\\u2014-]+"), "-")

    fun timingSafeEqual(a: String, b: String) =
        MessageDigest.isEqual(sha256(a), sha256(b)) && a.length == b.length

    private fun pbkdf2(input: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(input.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hashPassword(plain: String, iterations: Int, generated: Boolean): Password {
        val salt = randomBytes(16)
        val input = if (generated) canonicalGenerated(plain) else plain
        return Password(
            iter = iterations,
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            hash = Base64.encodeToString(pbkdf2(input, salt, iterations), Base64.NO_WRAP),
            generated = generated,
            id = randomHex(8),
            changedAt = System.currentTimeMillis(),
        )
    }

    fun verifyPassword(attempt: String, rec: Password?): Boolean {
        if (rec == null || attempt.isEmpty() || attempt.length > MAX_PASSWORD_LEN) return false
        val input = if (rec.generated) canonicalGenerated(attempt) else attempt
        val key = pbkdf2(input, Base64.decode(rec.salt, Base64.NO_WRAP), rec.iter)
        return MessageDigest.isEqual(key, Base64.decode(rec.hash, Base64.NO_WRAP))
    }

    // Short, easy-to-type words for generated passwords (from service/words.js).
    private val WORDS = (
        "acorn amber anchor apple apron arrow aspen atlas autumn badge bagel baker " +
            "bamboo banjo barley basil beach beacon bean berry birch biscuit blaze bloom " +
            "blossom bonfire branch breeze brick bridge brook bubble bucket cabin cactus " +
            "camel candle canoe canyon carrot castle cedar cello cherry chess cider cinema " +
            "citrus clover cobalt cocoa comet compass copper coral cotton cougar crane " +
            "crayon creek cricket crystal cuckoo daisy delta denim desert diamond dingo " +
            "dolphin donut dragon drum dune eagle echo ember emerald falcon feather fern " +
            "fiddle fig finch fjord flame flint forest fossil fox galaxy garden gecko " +
            "ginger glacier globe goose granite grape gravel guitar gull harbor hazel " +
            "heron hickory honey hornet husky iceberg igloo indigo island ivory jaguar " +
            "jasmine jelly jungle kayak kettle kiwi koala lagoon lantern lava lemon " +
            "lily lime linen lion llama lobster lotus lunar lychee magnet mango maple " +
            "marble meadow melon meteor mint mocha moose mosaic moss muffin nectar nest " +
            "nickel noodle nutmeg oak oasis ocean olive onyx opal orbit orchid otter " +
            "owl oyster paddle panda papaya parrot pasta peach peanut pebble pecan " +
            "pelican penguin pepper piano pickle pine pixel planet plum polar pony " +
            "poppy prairie pretzel puffin pumpkin quartz quill quokka rabbit radish " +
            "rainbow raven reef ribbon river robin rocket ruby saffron sage salmon " +
            "sandal sapphire satin scarf seal sequoia shell sierra silver sketch sloth " +
            "snow sparrow spice spruce squid star stone summit sunset swan tango teal " +
            "thistle thunder tiger timber toast topaz tulip tundra turtle twig umber " +
            "valley vanilla velvet violet volcano waffle walnut walrus wasabi willow " +
            "window winter wombat yogurt zebra zephyr zinc"
        ).split(' ')
}

/** A stored password: PBKDF2-SHA256 hash only. */
class Password(
    val iter: Int,
    val salt: String,
    val hash: String,
    val generated: Boolean,
    val id: String,
    val changedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("alg", "pbkdf2-sha256").put("iter", iter).put("salt", salt).put("hash", hash)
        .put("generated", generated).put("id", id).put("changedAt", changedAt)

    companion object {
        fun fromJson(o: JSONObject?): Password? {
            if (o == null || o.optString("alg") != "pbkdf2-sha256") return null
            return Password(o.getInt("iter"), o.getString("salt"), o.getString("hash"),
                o.optBoolean("generated"), o.getString("id"), o.optLong("changedAt"))
        }
    }
}

class Busy(val reason: String) : Exception("busy")

/**
 * Login throttling, as in auth.js:
 *  - per address: [failures] wrong passwords within [windowMs] lock it out for [lockMs];
 *  - global: one check at a time, and recent failures from anywhere add a growing delay;
 *  - a fair queue: one device can neither fill the queue nor lock the owner's phone out.
 */
class Limiter(
    private val failures: Int = 5,
    private val lockMs: Long = 5 * 60 * 1000L,
    private val windowMs: Long = 15 * 60 * 1000L,
    private val globalWindowMs: Long = 10 * 60 * 1000L,
    private val globalStepMs: Long = 400,
    private val globalMaxMs: Long = 8000,
    private val maxQueue: Int = 20,
    private val perIp: Int = 2,
    private val reserve: Int = 1,
) {
    private class Rec(var count: Int, val first: Long, var lockedUntil: Long)

    class Status(val locked: Boolean, val retryAfter: Int = 0, val remaining: Int = 0)

    private val byIp = HashMap<String, Rec>()
    private val inflight = HashMap<String, Int>()
    private val globalFails = ArrayDeque<Long>()
    private var queued = 0
    private val checks = java.util.concurrent.locks.ReentrantLock(true)

    @Synchronized
    private fun peek(ip: String): Rec {
        val now = System.currentTimeMillis()
        var r = byIp[ip]
        if (r != null && r.lockedUntil != 0L && r.lockedUntil <= now) r = null
        if (r != null && r.lockedUntil == 0L && now - r.first > windowMs) r = null
        return r ?: Rec(0, now, 0)
    }

    @Synchronized
    fun status(ip: String): Status {
        val r = peek(ip)
        val now = System.currentTimeMillis()
        if (r.lockedUntil > now) return Status(true, retryAfter = ((r.lockedUntil - now + 999) / 1000).toInt())
        return Status(false, remaining = failures - r.count)
    }

    @Synchronized
    fun fail(ip: String): Status {
        val now = System.currentTimeMillis()
        val r = peek(ip).also { byIp[ip] = it }
        r.count += 1
        globalFails.addLast(now)
        if (r.count >= failures) {
            r.lockedUntil = now + lockMs
            return Status(true, retryAfter = (lockMs / 1000).toInt())
        }
        return Status(false, remaining = failures - r.count)
    }

    @Synchronized
    fun success(ip: String) { byIp.remove(ip) }

    @Synchronized
    fun globalDelay(): Long {
        val cutoff = System.currentTimeMillis() - globalWindowMs
        while (globalFails.isNotEmpty() && globalFails.first() < cutoff) globalFails.removeFirst()
        val n = globalFails.size
        if (n <= 2) return 0
        return minOf(globalMaxMs, (n - 2) * globalStepMs)
    }

    @Synchronized
    private fun admit(ip: String) {
        val r = peek(ip)
        val mine = inflight[ip] ?: 0
        val free = maxQueue - queued
        val reason = when {
            mine >= perIp -> "address-busy"
            r.count + mine >= failures -> "address-limit"
            free <= 0 -> "queue-full"
            free <= reserve && (mine > 0 || r.count > 0) -> "queue-reserved"
            else -> null
        }
        if (reason != null) throw Busy(reason)
        queued += 1
        inflight[ip] = mine + 1
    }

    @Synchronized
    private fun release(ip: String) {
        queued -= 1
        val left = (inflight[ip] ?: 1) - 1
        if (left > 0) inflight[ip] = left else inflight.remove(ip)
    }

    /** Runs [fn] after every earlier check has finished. Throws [Busy] if [ip] isn't admitted. */
    fun <T> serial(ip: String, fn: () -> T): T {
        admit(ip)
        try {
            checks.lock()
            try { return fn() } finally { checks.unlock() }
        } finally {
            release(ip)
        }
    }

    /** Housekeeping so the map can't grow without bound. */
    @Synchronized
    fun sweep() {
        val now = System.currentTimeMillis()
        byIp.entries.removeAll { (_, r) ->
            (r.lockedUntil != 0L && r.lockedUntil <= now) || (r.lockedUntil == 0L && now - r.first > windowMs)
        }
        globalDelay()
    }
}
