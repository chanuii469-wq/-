package com.adshield.android.vpn

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
import com.adshield.android.AppRule
import com.adshield.android.AppRules
import com.adshield.android.Prefs
import com.adshield.android.R
import com.adshield.android.Stats
import com.adshield.android.dns.DnsPackets
import com.adshield.android.dns.IpPackets
import com.adshield.android.dns.UdpPacket
import com.adshield.android.filter.Blocklist
import com.adshield.android.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 로컬 VPN으로 기기의 DNS 질의만 가로채서 광고 도메인을 차단한다.
 * 가상 DNS 서버(10.111.222.1)로 가는 경로만 터널에 넣으므로 일반 트래픽은 그대로 통과한다.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpn"
        const val ACTION_START = "com.adshield.START"
        const val ACTION_STOP = "com.adshield.STOP"
        const val ACTION_RESTART = "com.adshield.RESTART"
        private const val VPN_ADDRESS = "10.111.222.2"
        private const val VPN_DNS = "10.111.222.1"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun restartIfRunning(ctx: Context) { if (Stats.running.value) send(ctx, ACTION_RESTART) }

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, AdBlockVpnService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ContextCompat.startForegroundService(ctx, i)
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var out: FileOutputStream? = null
    private lateinit var pool: ExecutorService
    private lateinit var upstream: Upstream
    private class Owner(val pkg: String?, val label: String)
    private val owners = ConcurrentHashMap<Int, Owner>()
    private var lastNotify = 0L

    override fun onCreate() {
        super.onCreate()
        pool = Executors.newFixedThreadPool(8)
        upstream = Upstream(this)
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
            else -> startVpn()
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
        stopVpn()
        pool.shutdownNow()
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
        QuickTileService.refresh(this)
    }

    private fun stopVpn() {
        reader?.interrupt()
        reader = null
        runCatching { tun?.close() }
        tun = null
        out = null
        Stats.running.value = false
        Stats.save()
        QuickTileService.refresh(this)
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n <= 0) continue
                val pkt = IpPackets.parseUdp(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                pool.execute { handleDns(pkt) }
            }
        } catch (e: Exception) {
            // tun이 닫히면 여기로 옴
        }
    }

    private fun handleDns(pkt: UdpPacket) {
        val query = DnsPackets.parse(pkt.payload) ?: return
        val mask = Stats.blocklist.match(query.name)
        // 차단 후보이거나 기록이 켜져 있을 때만 요청한 앱을 조회 (binder 호출 비용 절약)
        val owner = if (mask != 0 || Prefs.logQueries) ownerApp(pkt) else null
        val rule = owner?.pkg?.let { AppRules[it] } ?: AppRule()
        val blocked = (mask and (Blocklist.ADS or Blocklist.USER) != 0 && rule.ads) ||
            (mask and Blocklist.TRACKERS != 0 && rule.trackers)

        val response = if (blocked) {
            DnsPackets.blockedResponse(pkt.payload, query, Prefs.nxdomain)
        } else {
            upstream.resolve(pkt.payload) ?: return
        }
        write(IpPackets.buildUdp(pkt.dstIp, pkt.srcIp, pkt.dstPort, pkt.srcPort, response))
        Stats.record(query.name, query.qtype, blocked, owner?.label, owner?.pkg, rule.log)
        if (blocked) updateNotification()
    }

    private fun write(packet: ByteArray) {
        val o = out ?: return
        try {
            synchronized(o) { o.write(packet) }
        } catch (_: Exception) {
        }
    }

    /** 어떤 앱이 요청했는지 (Android 10 이상) */
    private fun ownerApp(pkt: UdpPacket): Owner? {
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

    private fun ownerFor(uid: Int): Owner {
        val pkgs = packageManager.getPackagesForUid(uid)
        if (pkgs.isNullOrEmpty()) return Owner(null, if (uid == 1000) "Android 시스템" else "UID $uid")
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkgs[0], 0)).toString()
        } catch (e: Exception) {
            pkgs[0]
        }
        return Owner(pkgs[0], label)
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
            .setSmallIcon(R.drawable.ic_shield)
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
        if (now - lastNotify < 3000) return
        lastNotify = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }
}
