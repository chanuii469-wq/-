package com.adshield.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.adshield.android.Prefs
import com.adshield.android.UpstreamDns
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** 차단되지 않은 DNS 질의를 실제 DNS 서버로 전달 */
class Upstream(private val service: VpnService) {
    private val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var sysDns: List<InetAddress> = emptyList()
    @Volatile private var sysDnsAt = 0L

    fun resolve(query: ByteArray): ByteArray? {
        val u = Prefs.upstream
        if (u != UpstreamDns.SYSTEM && Prefs.useDoh && u.dohUrl != null) {
            doh(u.dohUrl, query)?.let { return it }
        }
        val servers = if (u == UpstreamDns.SYSTEM) systemDns() else listOf(InetAddress.getByName(u.ip))
        for (s in servers) udp(s, query)?.let { return it }
        return null
    }

    private fun udp(server: InetAddress, query: ByteArray): ByteArray? = try {
        DatagramSocket().use { sock ->
            service.protect(sock)
            sock.soTimeout = 5000
            sock.send(DatagramPacket(query, query.size, server, 53))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            buf.copyOf(p.length)
        }
    } catch (e: Exception) {
        null
    }

    // DNS-over-HTTPS (RFC 8484). 이 앱은 VPN에서 제외되어 있어 루프가 생기지 않음
    private fun doh(url: String, query: ByteArray): ByteArray? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Content-Type", "application/dns-message")
            setRequestProperty("Accept", "application/dns-message")
        }
        conn.outputStream.use { it.write(query) }
        if (conn.responseCode == 200) {
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            }
        } else null
    } catch (e: Exception) {
        null
    }

    /** VPN이 아닌 실제 네트워크(와이파이/모바일)의 DNS 서버 */
    @Suppress("DEPRECATION")
    private fun systemDns(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (now - sysDnsAt < 30_000 && sysDns.isNotEmpty()) return sysDns
        val list = cm.allNetworks.flatMap { n ->
            val caps = cm.getNetworkCapabilities(n)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) cm.getLinkProperties(n)?.dnsServers.orEmpty() else emptyList()
        }.distinct()
        sysDns = list.ifEmpty { listOf(InetAddress.getByName("1.1.1.1")) }
        sysDnsAt = now
        return sysDns
    }
}
