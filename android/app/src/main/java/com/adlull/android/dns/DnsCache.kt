package com.adlull.android.dns

/**
 * 업스트림 DNS 응답 캐시 (LRU + TTL).
 * 같은 도메인을 반복 조회할 때 네트워크 왕복 없이 바로 응답해 지연과 배터리를 줄인다.
 */
class DnsCache(private val maxEntries: Int = 2048) {
    private class Entry(val response: ByteArray, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    var hits = 0L; private set
    var misses = 0L; private set

    private fun key(q: DnsQuery, scope: String) = "$scope|${q.qtype}|${q.name}"

    /** 캐시된 응답을 질의 ID에 맞춰 돌려줌 */
    @Synchronized
    fun get(query: ByteArray, q: DnsQuery, scope: String, now: Long = System.currentTimeMillis()): ByteArray? {
        val k = key(q, scope)
        val e = map[k]
        if (e == null || e.expiresAt <= now) {
            if (e != null) map.remove(k)
            misses++
            return null
        }
        hits++
        val r = e.response.copyOf()
        r[0] = query[0]; r[1] = query[1]
        return r
    }

    @Synchronized
    fun put(q: DnsQuery, response: ByteArray, scope: String, now: Long = System.currentTimeMillis()) {
        val ttl = cacheTtl(response) ?: return
        if (ttl <= 0) return
        map[key(q, scope)] = Entry(response, now + ttl * 1000L)
    }

    @Synchronized
    fun clear() = map.clear()

    companion object {
        private const val MAX_TTL = 3600L
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
