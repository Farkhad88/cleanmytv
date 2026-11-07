package dev.cleanmytv.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredTv(val name: String, val host: String, val port: Int, val pairing: Boolean, val wireless: Boolean)

/** Discovery only reads advertisements. It never selects a TV or sends an ADB authentication packet. */
class Discovery(context: Context, private val found: (DiscoveredTv) -> Unit, private val error: (String) -> Unit) : Closeable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("CleanMyTV discovery")
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    @Volatile private var stopped = false
    fun start() {
        lock.setReferenceCounted(false); lock.acquire()
        listOf("_androidtvremote2._tcp.", "_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.", "_adb._tcp.").forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(serviceType: String, code: Int) { error("Discovery is unavailable ($code). Manual IP connection is still available.") }
                override fun onStopDiscoveryFailed(serviceType: String, code: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) { if(!stopped && queue.size < 64) { queue.add(info); resolve() } }
            }
            listeners.add(listener); manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }
    @Suppress("DEPRECATION") private fun resolve() {
        if(stopped || !resolving.compareAndSet(false, true)) return
        val info = queue.poll() ?: run { resolving.set(false); return }
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolving.set(false); resolve() }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if(!stopped && host != null) {
                    val tls = info.serviceType.contains("adb-tls")
                    found(DiscoveredTv(serviceInfo.serviceName.take(80), host, if(tls || info.serviceType.startsWith("_adb.")) serviceInfo.port else 5555,
                        info.serviceType.contains("pairing"), tls))
                }
                resolving.set(false); resolve()
            }
        })
    }
    override fun close() { stopped = true; queue.clear(); listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }; listeners.clear(); if(lock.isHeld) lock.release() }
}
