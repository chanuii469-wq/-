package com.adlull.android.dns

/**
 * 업스트림 DNS 응답 캐시 (LRU + TTL + 낙관적 캐시).
 * - 같은 도메인을 반복 조회하면 네트워크 왕복 없이 바로 응답
 * - 만료된 항목도 1시간 동안은 일단 바로 돌려주고(TTL 10초) 뒤에서 새로 받아 옴 (낙관적 캐시)
 * - 돌려줄 때 TTL을 남은 시간으로 고쳐 써서 앱이 너무 오래 캐시하지 않게 함
 */
class DnsCache(private val maxEntries: Int = 8192) {
    private class Entry(val response: ByteArray, val expiresAt: Long, val ttlMs: Long) {
        var hits = 0
    }

    /** stale=true면 만료됐거나 곧 만료되므로 뒤에서 새로 받아 오는 게 좋음 */
    class Hit(val response: ByteArray, val stale: Boolean)

    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    var hits = 0L; private set
    var misses = 0L; private set

    /** 캐시된 응답을 질의 ID에 맞춰 돌려줌 */
    @Synchronized
    fun lookup(query: ByteArray, q: DnsQuery, scope: String, now: Long = System.currentTimeMillis()): Hit? {
        val k = key(q, scope)
        val e = map[k]
        if (e == null || now - e.expiresAt > STALE_MS) {
            if (e != null) map.remove(k)
            misses++
            return null
        }
        hits++
        e.hits++
        val remainingMs = e.expiresAt - now
        if (remainingMs <= 0) {
            // 만료됐지만 낙관적으로 일단 응답
            return Hit(withTtl(withId(e.response, query), STALE_TTL), stale = true)
        }
        // 두 번 이상 쓰였고 남은 시간이 TTL의 10% 미만이면 미리 갱신 대상
        val stale = e.hits >= 2 && remainingMs < e.ttlMs / 10
        return Hit(withTtl(withId(e.response, query), maxOf(1L, remainingMs / 1000)), stale)
    }

    fun get(query: ByteArray, q: DnsQuery, scope: String, now: Long = System.currentTimeMillis()): ByteArray? =
        lookup(query, q, scope, now)?.response

    @Synchronized
    fun put(q: DnsQuery, response: ByteArray, scope: String, now: Long = System.currentTimeMillis()) {
        val ttl = cacheTtl(response) ?: return
        if (ttl <= 0) return
        map[key(q, scope)] = Entry(response, now + ttl * 1000L, ttl * 1000L)
    }

    @Synchronized
    fun clear() = map.clear()

    companion object {
        private const val MAX_TTL = 3600L
        private const val STALE_MS = 3600 * 1000L // 만료 후 1시간까지만 (오래된 CDN 주소 방지)
        private const val STALE_TTL = 10L
        private const val TYPE_OPT = 41

        /** 답변·권한·추가 레코드의 TTL을 ttl로 바꿈 (EDNS OPT 레코드는 제외) */
        fun withTtl(msg: ByteArray, ttl: Long): ByteArray {
            if (msg.size < 12) return msg
            val qd = u16(msg, 4); val rr = u16(msg, 6) + u16(msg, 8) + u16(msg, 10)
            var pos = 12
            repeat(qd) { pos = skipName(msg, pos) + 4; if (pos > msg.size) return msg }
            repeat(rr) {
                pos = skipName(msg, pos)
                if (pos + 10 > msg.size) return msg
                if (u16(msg, pos) != TYPE_OPT) {
                    msg[pos + 4] = (ttl shr 24).toByte(); msg[pos + 5] = (ttl shr 16).toByte()
                    msg[pos + 6] = (ttl shr 8).toByte(); msg[pos + 7] = ttl.toByte()
                }
                pos += 10 + u16(msg, pos + 8)
            }
            return msg
        }

        fun key(q: DnsQuery, scope: String) = "$scope|${q.qtype}|${q.name}"

        /** 응답의 ID를 질의 ID로 바꾼 복사본 */
        fun withId(response: ByteArray, query: ByteArray): ByteArray {
            val r = response.copyOf()
            r[0] = query[0]; r[1] = query[1]
            return r
        }
        private const val NEGATIVE_TTL = 60L

        /**
         * 캐시해도 되는 응답이면 캐시 시간(초), 아니면 null.
         * 정상(NOERROR)·NXDOMAIN 응답만, 잘린(TC) 응답은 제외. 답변/권한 레코드 중 최소 TTL 사용.
         */
        fun cacheTtl(msg: ByteArray): Long? {
            if (msg.size < 12) return null
            if (msg[2].toInt() and 0x82 != 0x80) return null // QR=1, TC=0
            val rcode = msg[3].toInt() and 0x0F
            if (rcode != 0 && rcode != 3) return null
            val qd = u16(msg, 4); val an = u16(msg, 6); val ns = u16(msg, 8)
            var pos = 12
            repeat(qd) { pos = skipName(msg, pos) + 4; if (pos > msg.size) return null }
            var min = Long.MAX_VALUE
            repeat(an + ns) {
                pos = skipName(msg, pos)
                if (pos + 10 > msg.size) return null
                val ttl = ((msg[pos + 4].toLong() and 0xFF) shl 24) or ((msg[pos + 5].toLong() and 0xFF) shl 16) or
                    ((msg[pos + 6].toLong() and 0xFF) shl 8) or (msg[pos + 7].toLong() and 0xFF)
                if (ttl < min) min = ttl
                pos += 10 + u16(msg, pos + 8)
            }
            return when {
                min == Long.MAX_VALUE -> if (rcode == 3 || an == 0) NEGATIVE_TTL else null
                else -> minOf(min, MAX_TTL)
            }
        }

        private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        private fun skipName(msg: ByteArray, start: Int): Int {
            var pos = start
            while (pos < msg.size) {
                val l = msg[pos].toInt() and 0xFF
                if (l == 0) return pos + 1
                if (l and 0xC0 == 0xC0) return pos + 2 // 압축 포인터
                pos += l + 1
            }
            return Int.MAX_VALUE / 2
        }
    }
}
