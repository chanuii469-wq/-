package com.adshield.android.filter

/** 도메인 차단/허용 목록. 상위 도메인까지 검사(서브도메인 포함 차단). */
class Blocklist(private val blocked: Set<String>, private val allowed: Set<String>) {
    val size get() = blocked.size

    fun isBlocked(domain: String): Boolean {
        val d = domain.trimEnd('.').lowercase()
        if (matches(allowed, d)) return false
        return matches(blocked, d)
    }

    private fun matches(set: Set<String>, domain: String): Boolean {
        if (set.isEmpty()) return false
        var s = domain
        while (true) {
            if (s in set) return true
            val i = s.indexOf('.')
            if (i < 0) return false
            s = s.substring(i + 1)
        }
    }

    companion object {
        val EMPTY = Blocklist(emptySet(), emptySet())

        private val DOMAIN = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
        private val HOST_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "::0", "0:0:0:0:0:0:0:0")
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")
        private val OK_OPTIONS = setOf("important", "all", "third-party", "3p")

        /**
         * 한 줄을 해석해 block/allow 집합에 추가. 지원 형식:
         *  hosts(0.0.0.0 example.com), 도메인만, ||example.com^, @@||example.com^, *.example.com
         * @return 추가된 규칙 수(0 또는 1)
         */
        fun parseLine(raw: String, block: MutableSet<String>, allow: MutableSet<String>): Int {
            var l = raw.trim()
            if (l.isEmpty() || l[0] == '!' || l[0] == '#' || l[0] == '[') return 0
            if ("##" in l || "#@#" in l || "#?#" in l || "#$#" in l) return 0
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
                        if (!opts.all { it in OK_OPTIONS }) return 0
                    }
                    if (l[end] == '/') return 0 // 경로 규칙은 DNS로 처리 불가
                    l = l.substring(0, end)
                }
            } else if (l.any { it.isWhitespace() }) {
                val parts = l.split(Regex("\\s+"))
                if (parts.size < 2 || parts[0] !in HOST_IPS) return 0
                l = parts[1]
            } else if (l.startsWith("|") || l.startsWith("/") || l.startsWith("-") || l.startsWith(".")) {
                return 0
            }

            if (l.startsWith("*.")) l = l.substring(2)
            l = l.lowercase().trimEnd('.')
            if (l in SKIP || !DOMAIN.matches(l)) return 0
            return if ((if (isAllow) allow else block).add(l)) 1 else 0
        }
    }
}
