package com.adshield.android.filter

/**
 * 도메인 차단/허용 목록. 상위 도메인까지 검사(서브도메인 포함 차단).
 * 각 도메인은 어느 분류(광고/추적기/사용자 규칙)에서 왔는지 비트 플래그로 기억한다.
 */
class Blocklist(private val blocked: Map<String, Int>, private val allowed: Set<String>) {
    val size get() = blocked.size

    /** 일치한 분류 플래그(0이면 차단 대상 아님). 허용 규칙이 우선한다. */
    fun match(domain: String): Int {
        val d = domain.trimEnd('.').lowercase()
        if (blocked.isEmpty() || isAllowed(d)) return 0
        var flags = 0
        var s = d
        while (true) {
            blocked[s]?.let { flags = flags or it }
            val i = s.indexOf('.')
            if (i < 0) return flags
            s = s.substring(i + 1)
        }
    }

    fun isBlocked(domain: String) = match(domain) != 0

    private fun isAllowed(domain: String): Boolean {
        if (allowed.isEmpty()) return false
        var s = domain
        while (true) {
            if (s in allowed) return true
            val i = s.indexOf('.')
            if (i < 0) return false
            s = s.substring(i + 1)
        }
    }

    companion object {
        const val ADS = 1
        const val TRACKERS = 2
        const val USER = 4

        val EMPTY = Blocklist(emptyMap(), emptySet())

        private val DOMAIN = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
        private val HOST_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "::0", "0:0:0:0:0:0:0:0")
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")
        private val OK_OPTIONS = setOf("important", "all", "third-party", "3p")

        /** 한 줄을 해석. @return (도메인, 허용규칙 여부) 또는 null */
        fun parseRule(raw: String): Pair<String, Boolean>? {
            var l = raw.trim()
            if (l.isEmpty() || l[0] == '!' || l[0] == '#' || l[0] == '[') return null
            if ("##" in l || "#@#" in l || "#?#" in l || "#$#" in l) return null
            val c = l.indexOf(" #")
            if (c >= 0) l = l.substring(0, c).trim()

            var isAllow = false
            if (l.startsWith("@@")) { isAllow = true; l = l.substring(2) }

            if (l.startsWith("||")) {
                l = l.substring(2)
                val end = l.indexOfFirst { it == '^' || it == '$' || it == '/' || it == '|' }
                if (end >= 0) {
                    val dollar = l.indexOf('$')
                    if (dollar >= 0) {
                        val opts = l.substring(dollar + 1).split(',').map { it.trim().lowercase() }
                        if (!opts.all { it in OK_OPTIONS }) return null
                    }
                    if (l[end] == '/') return null // 경로 규칙은 DNS로 처리 불가
                    l = l.substring(0, end)
                }
            } else if (l.any { it.isWhitespace() }) {
                val parts = l.split(Regex("\\s+"))
                if (parts.size < 2 || parts[0] !in HOST_IPS) return null
                l = parts[1]
            } else if (l.startsWith("|") || l.startsWith("/") || l.startsWith("-") || l.startsWith(".")) {
                return null
            }

            if (l.startsWith("*.")) l = l.substring(2)
            l = l.lowercase().trimEnd('.')
            if (l in SKIP || !DOMAIN.matches(l)) return null
            return l to isAllow
        }

        /**
         * 한 줄을 해석해 block/allow에 추가. 지원 형식:
         *  hosts(0.0.0.0 example.com), 도메인만, ||example.com^, @@||example.com^, *.example.com
         * @return 추가된 규칙 수(0 또는 1)
         */
        fun parseLine(raw: String, block: MutableMap<String, Int>, allow: MutableSet<String>, flag: Int = ADS): Int {
            val (domain, isAllow) = parseRule(raw) ?: return 0
            if (isAllow) return if (allow.add(domain)) 1 else 0
            val prev = block[domain]
            block[domain] = (prev ?: 0) or flag
            return if (prev == null) 1 else 0
        }
    }
}
