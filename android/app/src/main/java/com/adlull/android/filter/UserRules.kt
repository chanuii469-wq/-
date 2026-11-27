package com.adlull.android.filter

/** 사용자 규칙 한 줄. 꺼진 규칙은 "!- " 주석으로 저장해 텍스트 편집과 호환된다. */
data class UserRule(val rule: String, val enabled: Boolean) {
    val allow get() = rule.startsWith("@@")
    val domain get() = Blocklist.parseRule(rule)?.first ?: rule
}

object UserRules {
    private const val OFF = "!- "

    fun parse(text: String): List<UserRule> = text.lines().mapNotNull { raw ->
        val t = raw.trim()
        when {
            t.startsWith(OFF.trim()) -> UserRule(t.removePrefix(OFF.trim()).trim(), false)
                .takeIf { Blocklist.parseRule(it.rule) != null }
            t.isEmpty() || t.startsWith("!") || t.startsWith("#") -> null
            else -> UserRule(t, true)
        }
    }

    fun format(rules: List<UserRule>): String =
        rules.joinToString("\n") { if (it.enabled) it.rule else OFF + it.rule } + if (rules.isEmpty()) "" else "\n"

    /** 적용 중인 규칙만 담은 텍스트 (필터 빌드용) */
    fun activeLines(text: String): List<String> = parse(text).filter { it.enabled }.map { it.rule }

    /** 사용자가 입력한 값을 규칙으로 정규화. 도메인만 입력하면 ||domain^ 형태로 바꾼다. */
    fun normalize(input: String, allow: Boolean): String? {
        var v = input.trim().lowercase()
        if (v.isEmpty()) return null
        v = v.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val rule = if (v.startsWith("||") || v.startsWith("@@") || v.contains(' ')) v
        else (if (allow) "@@||" else "||") + v + "^"
        val parsed = Blocklist.parseRule(rule) ?: return null
        return (if (parsed.second) "@@||" else "||") + parsed.first + "^"
    }
}
