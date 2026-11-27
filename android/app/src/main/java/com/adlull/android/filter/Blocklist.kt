package com.adlull.android.filter

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 도메인 차단/허용 목록 (메모리 절약형).
 *
 * 도메인 문자열 대신 64비트 해시를 분류별 정렬 배열에 저장한다.
 * 30만 개 기준 HashMap<String> 약 30MB → 약 2.4MB. 64비트 해시라 충돌 확률은 사실상 0.
 * 조회는 도메인과 모든 상위 도메인(서브도메인 포함 차단)을 이진 탐색한다.
 */
class Blocklist private constructor(
    private val ads: LongArray,
    private val trackers: LongArray,
    private val user: LongArray,
    private val allow: LongArray,
    /** 중복을 뺀 차단 도메인 수 */
    val size: Int,
) {
    /** 일치한 분류 플래그(0이면 차단 대상 아님). 허용 규칙이 우선한다. */
    fun match(domain: String): Int {
        var end = domain.length
        while (end > 0 && domain[end - 1] == '.') end--
        if (end == 0 || size == 0) return 0
        var flags = 0
        var start = 0
        while (true) {
            val h = hash(domain, start, end)
            if (allow.isNotEmpty() && allow.binarySearch(h) >= 0) return 0
            if (ads.binarySearch(h) >= 0) flags = flags or ADS
            if (trackers.binarySearch(h) >= 0) flags = flags or TRACKERS
            if (user.binarySearch(h) >= 0) flags = flags or USER
            val dot = domain.indexOf('.', start)
            if (dot < 0 || dot >= end) return flags
            start = dot + 1
        }
    }

    fun isBlocked(domain: String) = match(domain) != 0

    fun writeTo(out: DataOutputStream) {
        out.writeInt(size)
        for (a in arrayOf(ads, trackers, user, allow)) {
            out.writeInt(a.size)
            for (v in a) out.writeLong(v)
        }
    }

    /** 규칙을 모아 Blocklist를 만든다. */
    class Builder {
        private val lists = Array(4) { LongList() }

        fun add(domain: String, flag: Int) {
            val h = hash(domain, 0, domain.length)
            when (flag) {
                TRACKERS -> lists[1].add(h)
                USER -> lists[2].add(h)
                else -> lists[0].add(h)
            }
        }

        fun allow(domain: String) = lists[3].add(hash(domain, 0, domain.length))

        fun build(): Blocklist {
            val (a, t, u, w) = lists.map { it.sortedUnique() }
            return Blocklist(a, t, u, w, unionSize(a, t, u))
        }
    }

    companion object {
        const val ADS = 1
        const val TRACKERS = 2
        const val USER = 4

        val EMPTY = Builder().build()

        fun readFrom(input: DataInputStream): Blocklist {
            val size = input.readInt()
            val arrays = Array(4) { LongArray(input.readInt()).also { a -> for (i in a.indices) a[i] = input.readLong() } }
            return Blocklist(arrays[0], arrays[1], arrays[2], arrays[3], size)
        }

        /** FNV-1a 64비트. 대문자는 소문자로 취급 (문자열 할당 없이 부분 문자열 해시) */
        fun hash(s: String, start: Int, end: Int): Long {
            var h = -0x340d631b7bdddcdbL
            for (i in start until end) {
                var c = s[i].code
                if (c in 65..90) c += 32
                h = (h xor c.toLong()) * 0x100000001b3L
            }
            return h
        }

        private fun unionSize(vararg arrays: LongArray): Int {
            val idx = IntArray(arrays.size)
            var count = 0
            while (true) {
                var min = Long.MAX_VALUE
                var any = false
                for (k in arrays.indices) if (idx[k] < arrays[k].size) {
                    any = true
                    if (arrays[k][idx[k]] < min) min = arrays[k][idx[k]]
                }
                if (!any) return count
                count++
                for (k in arrays.indices) if (idx[k] < arrays[k].size && arrays[k][idx[k]] == min) idx[k]++
            }
        }

        private val HOST_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "::0", "0:0:0:0:0:0:0:0")
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")
        private val OK_OPTIONS = setOf("important", "all", "third-party", "3p")

        /** 도메인 형식 검사 (정규식보다 빠른 수동 검사): 영문·숫자·_- 라벨 2개 이상 */
        private fun isDomain(s: String): Boolean {
            if (s.isEmpty() || s.length > 253) return false
            var labelLen = 0
            var dots = 0
            for (c in s) {
                when {
                    c == '.' -> { if (labelLen == 0) return false; dots++; labelLen = 0 }
                    c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> labelLen++
                    else -> return false
                }
            }
            return dots > 0 && labelLen > 0
        }

        /** 한 줄을 해석. @return (소문자 도메인, 허용규칙 여부) 또는 null */
        fun parseRule(raw: String): Pair<String, Boolean>? {
            var l = raw.trim()
            if (l.isEmpty()) return null
            val first = l[0]
            if (first == '!' || first == '#' || first == '[') return null
            if (l.indexOf('#') >= 0) {
                if ("##" in l || "#@#" in l || "#?#" in l || "#$#" in l) return null
                val c = l.indexOf(" #")
                if (c >= 0) l = l.substring(0, c).trim()
            }

            var isAllow = false
            if (l.startsWith("@@")) { isAllow = true; l = l.substring(2) }

            if (l.startsWith("||")) {
                l = l.substring(2)
                val end = l.indexOfFirst { it == '^' || it == '$' || it == '/' || it == '|' }
                if (end >= 0) {
                    val dollar = l.indexOf('$')
                    if (dollar >= 0) {
                        val opts = l.substring(dollar + 1).split(',')
                        if (!opts.all { it.trim().lowercase() in OK_OPTIONS }) return null
                    }
                    if (l[end] == '/') return null // 경로 규칙은 DNS로 처리 불가
                    l = l.substring(0, end)
                }
            } else {
                val ws = l.indexOfFirst { it == ' ' || it == '\t' }
                if (ws >= 0) { // hosts 형식: "0.0.0.0 domain"
                    if (l.substring(0, ws) !in HOST_IPS) return null
                    l = l.substring(ws).trim()
                    val ws2 = l.indexOfFirst { it == ' ' || it == '\t' }
                    if (ws2 >= 0) l = l.substring(0, ws2)
                } else if (first == '|' || first == '/' || first == '-' || first == '.') {
                    return null
                }
            }

            if (l.startsWith("*.")) l = l.substring(2)
            l = l.lowercase().trimEnd('.')
            if (l in SKIP || !isDomain(l)) return null
            return l to isAllow
        }

        /** 한 줄을 해석해 builder에 추가. @return 규칙이면 1, 아니면 0 */
        fun parseLine(raw: String, builder: Builder, flag: Int = ADS): Int {
            val (domain, isAllow) = parseRule(raw) ?: return 0
            if (isAllow) builder.allow(domain) else builder.add(domain, flag)
            return 1
        }
    }
}

/** 박싱 없는 Long 가변 배열 */
private class LongList {
    private var a = LongArray(1024)
    private var n = 0

    fun add(v: Long) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }

    fun sortedUnique(): LongArray {
        val s = a.copyOf(n)
        s.sort()
        var w = 0
        for (i in s.indices) if (i == 0 || s[i] != s[i - 1]) s[w++] = s[i]
        return s.copyOf(w)
    }
}
