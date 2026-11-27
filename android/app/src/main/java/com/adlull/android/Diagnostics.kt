package com.adlull.android

import android.os.Process
import android.os.SystemClock

/**
 * 속도·발열 원인을 찾기 위한 진단 값. 서비스가 채우고 진단 화면이 읽는다.
 * 기기 밖으로 보내지 않음.
 */
object Diagnostics {
    @Volatile var dnsServers: List<String> = emptyList()
    @Volatile var vpnStarts = 0
    @Volatile var tunnelEnds = 0
    @Volatile var autoReconnects = 0
    @Volatile var poolInfo: () -> String = { "-" }

    private val lat = LongArray(256)
    private var latN = 0
    private var failures = 0L
    private var slow = 0L

    @Synchronized
    fun latency(ms: Long) {
        lat[latN % lat.size] = ms
        latN++
        if (ms > 300) slow++
    }

    @Synchronized
    fun failure() { failures++ }

    /** 최근 업스트림 응답 시간: (중앙값, 상위 5%) */
    @Synchronized
    fun percentiles(): Pair<Long, Long>? {
        val n = minOf(latN, lat.size)
        if (n == 0) return null
        val s = lat.copyOf(n).sorted()
        return s[n / 2] to s[minOf(n - 1, n * 95 / 100)]
    }

    @Synchronized fun failures() = failures
    @Synchronized fun slow() = slow

    /** 앱 시작 이후 평균 CPU 사용률(%, 코어 1개 기준) */
    fun cpuSinceStart(): Double {
        val wall = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        return if (wall <= 0) 0.0 else Process.getElapsedCpuTime() * 100.0 / wall
    }
}
