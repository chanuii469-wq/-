package com.adshield.android

import com.adshield.android.filter.Blocklist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LogEntry(
    val time: Long,
    val domain: String,
    val qtype: Int,
    val blocked: Boolean,
    val app: String?,
)

/** 서비스와 UI가 공유하는 실행 상태/통계 */
object Stats {
    const val LOG_SIZE = 1000

    val running = MutableStateFlow(false)

    @Volatile
    var blocklist: Blocklist = Blocklist.EMPTY

    private val _totalQueries = MutableStateFlow(0L)
    private val _totalBlocked = MutableStateFlow(0L)
    val totalQueries: StateFlow<Long> = _totalQueries
    val totalBlocked: StateFlow<Long> = _totalBlocked

    private val logBuf = ArrayDeque<LogEntry>()
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    private val domainCounts = HashMap<String, Int>()
    private val appCounts = HashMap<String, Int>()
    private val _topDomains = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    private val _topApps = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val topDomains: StateFlow<List<Pair<String, Int>>> = _topDomains
    val topApps: StateFlow<List<Pair<String, Int>>> = _topApps

    private var unsaved = 0

    fun load() {
        _totalQueries.value = Prefs.totalQueries
        _totalBlocked.value = Prefs.totalBlocked
    }

    @Synchronized
    fun record(domain: String, qtype: Int, blocked: Boolean, app: String?) {
        _totalQueries.value++
        if (blocked) {
            _totalBlocked.value++
            domainCounts.merge(domain, 1, Int::plus)
            if (app != null) appCounts.merge(app, 1, Int::plus)
            _topDomains.value = domainCounts.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
            _topApps.value = appCounts.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
        }
        if (Prefs.logQueries) {
            logBuf.addFirst(LogEntry(System.currentTimeMillis(), domain, qtype, blocked, app))
            while (logBuf.size > LOG_SIZE) logBuf.removeLast()
            _log.value = logBuf.toList()
        }
        if (++unsaved >= 50) save()
    }

    @Synchronized
    fun save() {
        unsaved = 0
        Prefs.totalQueries = _totalQueries.value
        Prefs.totalBlocked = _totalBlocked.value
    }

    @Synchronized
    fun reset() {
        _totalQueries.value = 0
        _totalBlocked.value = 0
        domainCounts.clear(); appCounts.clear()
        _topDomains.value = emptyList(); _topApps.value = emptyList()
        logBuf.clear(); _log.value = emptyList()
        save()
    }

    @Synchronized
    fun clearLog() {
        logBuf.clear(); _log.value = emptyList()
    }
}
