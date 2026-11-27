package com.adlull.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.adlull.android.Prefs
import com.adlull.android.UpstreamDns
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 차단되지 않은 DNS 질의를 실제 DNS 서버로 전달.
 *
 * 속도를 위해:
 * - 이 앱은 VPN에서 제외돼 있으므로 소켓마다 protect()(시스템 호출) 하지 않음
 * - UDP는 첫 서버가 0.15초 안에 답이 없으면 다음 서버에도 동시에 보내 먼저 온 답을 씀
 * - DoH는 동시 연결 수를 제한해 HTTPS 연결을 재사용(요청마다 TLS 핸드셰이크 방지)하고,
 *   실패하면 1분간 DoH를 쉬고 바로 UDP로 처리 (매 요청 타임아웃 대기 방지)
 */
class Upstream(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var sysDns: List<InetAddress> = emptyList()
    @Volatile private var sysDnsAt = 0L

    private val dohSlots = Semaphore(DOH_CONNECTIONS)
    @Volatile private var dohDownUntil = 0L

    /** 캐시를 구분하기 위한 현재 업스트림 이름 */
    fun scope(): String = Prefs.hot.let { "${it.upstream.name}:${it.useDoh}" }

    fun resolve(query: ByteArray): ByteArray? {
        val hot = Prefs.hot
        val u = hot.upstream
        if (u != UpstreamDns.SYSTEM && hot.useDoh && u.dohUrl != null &&
            System.currentTimeMillis() > dohDownUntil
        ) {
            doh(u.dohUrl, query)?.let { return it }
        }
        val servers = if (u == UpstreamDns.SYSTEM) systemDns() else u.addresses
        return udp(servers, query)
    }

    private fun udp(servers: List<InetAddress>, query: ByteArray): ByteArray? {
        val r = hedgedUdp(servers.map { InetSocketAddress(it, 53) }, query) ?: return null
        // 응답이 잘렸으면(TC) TCP로 다시 받아 온전한 응답을 전달
        return if (r.second[2].toInt() and 0x02 != 0) tcp(r.first, query) ?: r.second else r.second
    }

    private fun tcp(server: InetAddress, query: ByteArray): ByteArray? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(server, 53), 2000)
            s.soTimeout = 3000
            val out = DataOutputStream(s.getOutputStream())
            out.writeShort(query.size)
            out.write(query)
            out.flush()
            val input = DataInputStream(s.getInputStream())
            val len = input.readUnsignedShort()
            ByteArray(len).also { input.readFully(it) }
        }
    } catch (e: Exception) {
        null
    }

    // DNS-over-HTTPS (RFC 8484). 이 앱은 VPN에서 제외되어 있어 루프가 생기지 않음
    private fun doh(url: String, query: ByteArray): ByteArray? {
        if (!dohSlots.tryAcquire(DOH_WAIT_MS, TimeUnit.MILLISECONDS)) return null
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = 2000
                readTimeout = 2500
                setFixedLengthStreamingMode(query.size)
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode != 200) {
                conn.errorStream?.close()
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            // 응답을 끝까지 읽고 닫아야 연결이 재사용됨
            return conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(512)
                input.copyTo(out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            dohDownUntil = System.currentTimeMillis() + DOH_BACKOFF_MS
            return null
        } finally {
            dohSlots.release()
        }
    }

    // 와이파이↔모바일 전환 시 DNS 서버 목록을 바로 다시 읽음
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { sysDnsAt = 0 }
        override fun onLost(network: Network) { sysDnsAt = 0 }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { sysDnsAt = 0 }
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
    }

    fun close() {
        runCatching { cm.unregisterNetworkCallback(netCallback) }
    }

    /**
     * 지금 실제로 쓰는 네트워크(와이파이 또는 모바일)의 DNS 서버.
     * 이 앱은 VPN에서 제외돼 있어 activeNetwork가 곧 실제 네트워크다.
     * (예전처럼 모든 네트워크의 DNS를 합치면, 와이파이 사용 중에 닿지 않는 모바일 DNS가
     *  앞에 오는 경우 요청마다 기다리는 문제가 있었음)
     */
    @Suppress("DEPRECATION")
    private fun systemDns(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (now - sysDnsAt < 60_000 && sysDns.isNotEmpty()) return sysDns
        var list = cm.activeNetwork?.let { n ->
            val caps = cm.getNetworkCapabilities(n)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) null
            else cm.getLinkProperties(n)?.dnsServers
        }.orEmpty()
        if (list.isEmpty()) {
            list = cm.allNetworks.firstNotNullOfOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) cm.getLinkProperties(n)?.dnsServers?.takeIf { it.isNotEmpty() } else null
            }.orEmpty()
        }
        sysDns = list.ifEmpty { UpstreamDns.CLOUDFLARE.addresses }
        sysDnsAt = now
        return sysDns
    }

    companion object {
        /**
         * 여러 서버에 시간차(hedgeMs)를 두고 보내 가장 먼저 온 올바른 응답을 돌려줌.
         * @return (응답한 서버 주소, 응답)
         */
        fun hedgedUdp(
            servers: List<InetSocketAddress>,
            query: ByteArray,
            hedgeMs: Long = HEDGE_MS,
            totalMs: Long = UDP_TOTAL_MS,
        ): Pair<InetAddress, ByteArray>? {
            if (servers.isEmpty()) return null
            try {
                DatagramSocket().use { sock ->
                    val deadline = System.currentTimeMillis() + totalMs
                    val buf = ByteArray(4096)
                    var next = 0
                    var sendNext = true
                    while (true) {
                        if (sendNext && next < servers.size) {
                            runCatching { sock.send(DatagramPacket(query, query.size, servers[next])) }
                            next++
                        }
                        sendNext = false
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) return null
                        val wait = if (next < servers.size) minOf(hedgeMs, remaining) else remaining
                        sock.soTimeout = wait.toInt().coerceAtLeast(1)
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            // 다른 질의의 응답이나 잘못된 패킷은 무시하고 계속 기다림
                            if (p.length < 12 || buf[0] != query[0] || buf[1] != query[1]) continue
                            return p.address to buf.copyOf(p.length)
                        } catch (e: SocketTimeoutException) {
                            if (next >= servers.size) return null
                            sendNext = true
                        }
                    }
                }
            } catch (e: Exception) {
                return null
            }
        }

        private const val HEDGE_MS = 150L
        private const val UDP_TOTAL_MS = 3000L
        private const val DOH_CONNECTIONS = 4
        private const val DOH_WAIT_MS = 1500L
        private const val DOH_BACKOFF_MS = 60_000L
    }
}
