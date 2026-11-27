package com.adlull.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.adlull.android.AppRule
import com.adlull.android.AppRules
import com.adlull.android.Diagnostics
import com.adlull.android.Prefs
import com.adlull.android.R
import com.adlull.android.Stats
import com.adlull.android.dns.AppOwner
import com.adlull.android.dns.DnsCache
import com.adlull.android.dns.DnsEngine
import com.adlull.android.dns.UdpPacket
import com.adlull.android.filter.Blocklist
import com.adlull.android.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 로컬 VPN으로 기기의 DNS 질의만 가로채서 광고 도메인을 차단한다.
 * 가상 DNS 서버(10.111.222.1)로 가는 경로만 터널에 넣으므로 일반 트래픽은 그대로 통과한다.
 * DNS 처리 자체는 DnsEngine이 맡고, 이 서비스는 VPN 연결·알림·앱 조회만 담당한다.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpn"
        const val ACTION_START = "com.adlull.START"
        const val ACTION_STOP = "com.adlull.STOP"
        const val ACTION_RESTART = "com.adlull.RESTART"
        private const val VPN_ADDRESS = "10.111.222.2"
        private const val VPN_DNS = "10.111.222.1"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun restartIfRunning(ctx: Context) { if (Stats.running.value) send(ctx, ACTION_RESTART) }

        /** VPN을 다시 연결해도 DNS 캐시는 유지 */
        private val sharedCache = DnsCache()
        private const val SAVE_INTERVAL_MS = 10 * 60 * 1000L

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, AdBlockVpnService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ContextCompat.startForegroundService(ctx, i)
        }
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var out: FileOutputStream? = null
    private lateinit var upstream: Upstream
    private lateinit var engine: DnsEngine
    private val owners = ConcurrentHashMap<Int, AppOwner>()
    @Volatile private var lastNotify = 0L

    override fun onCreate() {
        super.onCreate()
        engine = DnsEngine(EngineHost(), cache = sharedCache)
        upstream = Upstream(this) { if (::engine.isInitialized) engine.requestRebind() }
        engine.start()
        if (sharedCache.size() == 0) Thread({ loadCache() }, "dns-cache-load").start()
        Diagnostics.poolInfo = {
            "대기 중 질의 ${engine.pendingCount()}개 · 앱 조회 ${engine.ownerLookups}회 · SERVFAIL ${engine.servfails}회"
        }
    }

    /** 엔진이 VPN·설정·통계에 접근하는 통로 */
    private inner class EngineHost : DnsEngine.Host {
        override fun writeToTun(packet: ByteArray) = write(packet)
        override fun match(name: String) = Stats.blocklist.match(name)
        override fun appRulesActive() = AppRules.rules.value.isNotEmpty()
        override fun isBlocked(mask: Int, owner: AppOwner?): Boolean {
            val rule = owner?.pkg?.let { AppRules[it] } ?: AppRule()
            return (mask and (Blocklist.ADS or Blocklist.USER) != 0 && rule.ads) ||
                (mask and Blocklist.TRACKERS != 0 && rule.trackers)
        }
        override fun nxdomain() = Prefs.hot.nxdomain
        override fun logEnabled() = Prefs.hot.logQueries
        override fun lookupOwner(packet: UdpPacket) = ownerApp(packet)
        override fun record(name: String, qtype: Int, blocked: Boolean, owner: AppOwner?) {
            val log = owner?.pkg?.let { AppRules[it].log } ?: true
            Stats.record(name, qtype, blocked, owner?.label, owner?.pkg, log)
            if (blocked) updateNotification()
        }
        override fun servers(): List<InetSocketAddress> = upstream.servers()
        override fun raceServers(): List<InetSocketAddress> = upstream.raceServers()
        override fun cacheScope() = upstream.scope()
        override fun dohUrl() = upstream.dohUrl()
        override fun onResolved(ms: Long, fromCache: Boolean) {
            Stats.recordResolve(ms, fromCache)
            if (!fromCache) Diagnostics.latency(ms)
        }
        override fun onFailure() = Diagnostics.failure()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.wasRunning = false
                stopVpn()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopVpn()
                startVpn()
            }
            // ACTION_START, 또는 시스템의 '상시 VPN'이 시작한 경우
            else -> {
                restarts = 0
                startVpn()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // 다른 VPN이 켜지거나 사용자가 시스템 설정에서 끊음
        Prefs.wasRunning = false
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(periodicSave)
        stopVpn()
        engine.stop()
        upstream.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------

    private fun startVpn() {
        if (tun != null) return
        startForegroundCompat()
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            // 중요: IPv6 주소를 추가하지 않은 VPN은 기본적으로 '모든 IPv6 통신을 차단'한다(안드로이드 규칙).
            // 그러면 IPv6가 기본인 모바일 데이터에서 모든 통신이 IPv4 변환(CLAT)을 거쳐 느려지고 열이 난다.
            // IPv6는 터널로 받지 않고 원래 네트워크로 그대로 나가도록 허용한다.
            .allowFamily(OsConstants.AF_INET6)
            .allowFamily(OsConstants.AF_INET)
            .setMtu(1500)
            .setBlocking(true)
            .setConfigureIntent(mainIntent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        // 이 앱 자신(필터 다운로드, DoH)과 사용자가 제외한 앱은 VPN을 거치지 않음
        (Prefs.excludedApps + packageName).forEach {
            try { builder.addDisallowedApplication(it) } catch (_: PackageManager.NameNotFoundException) {}
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (pfd == null) { // VPN 권한 없음
            Stats.running.value = false
            stopSelf()
            return
        }
        tun = pfd
        out = FileOutputStream(pfd.fileDescriptor)
        Stats.running.value = true
        Prefs.wasRunning = true
        reader = Thread({ readLoop(pfd) }, "vpn-reader").also { it.start() }
        mainHandler.removeCallbacks(periodicSave)
        mainHandler.postDelayed(periodicSave, SAVE_INTERVAL_MS)
        Diagnostics.vpnStarts++
        QuickTileService.refresh(this)
    }

    private fun stopVpn() {
        if (tun != null) saveCacheAsync()
        reader?.interrupt()
        reader = null
        runCatching { tun?.close() }
        tun = null
        out = null
        Stats.running.value = false
        Stats.save()
        QuickTileService.refresh(this)
    }

    /**
     * 터널에서 패킷을 읽는 루프.
     * 주의: read()가 -1(스트림 끝)을 돌려주면 다음 read()도 즉시 -1을 돌려주므로
     * 그냥 continue하면 CPU를 100% 쓰며 헛도는 문제가 생긴다(발열 원인).
     * 끝이면 루프를 빠져나와 VPN을 다시 연결하고, 0이면 잠깐 쉰다.
     */
    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        var empty = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) {
                    if (++empty > 3) Thread.sleep(20)
                    continue
                }
                empty = 0
                engine.onPacket(buf, n)
            }
        } catch (e: Exception) {
            // tun이 닫히면 여기로 옴
        }
        // 직접 끈 게 아닌데 터널이 끊겼으면(네트워크 전환 등) 잠시 뒤 다시 연결
        if (!Thread.currentThread().isInterrupted && tun === pfd) {
            Diagnostics.tunnelEnds++
            mainHandler.postDelayed({
                if (tun === pfd && restarts++ < 5) {
                    Log.w(TAG, "tunnel ended, reconnecting")
                    Diagnostics.autoReconnects++
                    stopVpn()
                    startVpn()
                }
            }, 1000)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------------- DNS 캐시 저장 (앱이 다시 시작돼도 처음부터 빠르게) ----------------

    private val cacheFile by lazy { java.io.File(filesDir, "dns-cache.bin") }

    private val periodicSave = object : Runnable {
        override fun run() {
            saveCacheAsync()
            mainHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private fun loadCache() {
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(cacheFile.inputStream())).use { sharedCache.load(it) }
        }
    }

    private fun saveCacheAsync() {
        Thread({
            runCatching {
                val tmp = java.io.File(filesDir, "dns-cache.tmp")
                java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream())).use { sharedCache.save(it) }
                tmp.renameTo(cacheFile)
            }
        }, "dns-cache-save").start()
    }
    private var restarts = 0

    private fun write(packet: ByteArray) {
        val o = out ?: return
        try {
            synchronized(o) { o.write(packet) }
        } catch (_: Exception) {
        }
    }

    /** 어떤 앱이 요청했는지 (Android 10 이상) */
    private fun ownerApp(pkt: UdpPacket): AppOwner? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val uid = cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(pkt.srcIp), pkt.srcPort),
                InetSocketAddress(InetAddress.getByAddress(pkt.dstIp), pkt.dstPort),
            )
            if (uid < 0) null else owners.getOrPut(uid) { ownerFor(uid) }
        } catch (e: Exception) {
            null
        }
    }

    private fun ownerFor(uid: Int): AppOwner {
        val pkgs = packageManager.getPackagesForUid(uid)
        if (pkgs.isNullOrEmpty()) return AppOwner(null, if (uid == 1000) "Android 시스템" else "UID $uid")
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkgs[0], 0)).toString()
        } catch (e: Exception) {
            pkgs[0]
        }
        return AppOwner(pkgs[0], label)
    }

    // ---------------- 알림 ----------------

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "광고 차단 상태", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("광고 차단 중")
            .setContentText("지금까지 ${"%,d".format(Stats.totalBlocked.value)}개 차단")
            .setContentIntent(mainIntent())
            .addAction(0, "중지", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 30_000) return // 알림 갱신은 30초에 한 번만
        lastNotify = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }
}
