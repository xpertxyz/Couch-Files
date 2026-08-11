package com.xpertxyz.sharetotv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** Finds TVs advertising _sharetotv._tcp on the local network. */
class TvDiscovery(context: Context) {

    data class Tv(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null

    val tvs = MutableStateFlow<List<Tv>>(emptyList())

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String?) {}
            override fun onDiscoveryStopped(type: String?) {}
            override fun onStartDiscoveryFailed(type: String?, err: Int) {}
            override fun onStopDiscoveryFailed(type: String?, err: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                // ponytail: concurrent resolves fail with ALREADY_ACTIVE; fine for the 1-TV common case,
                // manual IP entry covers the rest
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, err: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        val tv = Tv(i.serviceName, host, i.port)
                        tvs.value = tvs.value.filter { it.name != tv.name } + tv
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                tvs.value = tvs.value.filter { it.name != info.serviceName }
            }
        }
        listener = l
        nsd.discoverServices("_sharetotv._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        tvs.value = emptyList()
    }
}
