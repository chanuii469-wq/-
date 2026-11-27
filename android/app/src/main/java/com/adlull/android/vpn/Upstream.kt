package com.adlull.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.adlull.android.Diagnostics
import com.adlull.android.Prefs
import com.adlull.android.UpstreamDns
import java.net.InetSocketAddress

/**
 * 어느 DNS 서버로 물어볼지 정한다 (실제 송수신은 DnsEngine이 함).
 * 결과를 기억해 두고, 와이파이↔모바일 전환 등 네트워크가 바뀔 때만 다시 읽는다.
 */
class Upstream(context: Context, private val onNetworkChanged: () -> Unit = {}) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var sysServers: List<InetSocketAddress> = emptyList()
    @Volatile private var sysAt = 0L
    private val fixed = HashMap<UpstreamDns, List<InetSocketAddress>>()

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            sysAt = 0
            onNetworkChanged()
        }
        override fun onLost(network: Network) { sysAt = 0 }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { sysAt = 0 }
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
    }

    fun close() {
        runCatching { cm.unregisterNetworkCallback(netCallback) }
    }

    fun scope(): String = Prefs.hot.let { "${it.upstream.name}:${it.useDoh}" }

    fun dohUrl(): String? = Prefs.hot.let {
        if (it.upstream != UpstreamDns.SYSTEM && it.useDoh) it.upstream.dohUrl else null
    }

    fun servers(): List<InetSocketAddress> {
        val u = Prefs.hot.upstream
        val list = if (u == UpstreamDns.SYSTEM) systemServers() else synchronized(fixed) {
            fixed.getOrPut(u) { u.addresses.map { InetSocketAddress(it, 53) } }
        }
        if (Diagnostics.dnsServers.size != list.size) {
            Diagnostics.dnsServers = list.map { it.address?.hostAddress ?: it.toString() }
        }
        return list
    }

    /**
     * 지금 실제로 쓰는 네트워크(와이파이 또는 모바일)의 DNS 서버.
     * 이 앱은 VPN에서 제외돼 있어 activeNetwork가 곧 실제 네트워크다.
     */
    @Suppress("DEPRECATION")
    private fun systemServers(): List<InetSocketAddress> {
        val now = System.currentTimeMillis()
        if (now - sysAt < 60_000 && sysServers.isNotEmpty()) return sysServers
        var dns = cm.activeNetwork?.let { n ->
            val caps = cm.getNetworkCapabilities(n)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) null
            else cm.getLinkProperties(n)?.dnsServers
        }.orEmpty()
        if (dns.isEmpty()) {
            dns = cm.allNetworks.firstNotNullOfOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) cm.getLinkProperties(n)?.dnsServers?.takeIf { it.isNotEmpty() } else null
            }.orEmpty()
        }
        val list = dns.map { InetSocketAddress(it, 53) }
            .ifEmpty { UpstreamDns.CLOUDFLARE.addresses.map { InetSocketAddress(it, 53) } }
        sysServers = list
        sysAt = now
        Diagnostics.dnsServers = list.map { it.address?.hostAddress ?: it.toString() }
        return list
    }
}
