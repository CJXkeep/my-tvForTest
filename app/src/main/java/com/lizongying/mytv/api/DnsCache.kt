package com.lizongying.mytv.api

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class DnsCache : Dns {
    private data class Entry(val addresses: List<InetAddress>, val expireAt: Long)

    private val dnsCache: MutableMap<String, Entry> = ConcurrentHashMap()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[hostname]?.let {
            if (it.expireAt > now) {
                return it.addresses
            }
        }

        val addresses = InetAddress.getAllByName(hostname).toList()

        if (addresses.isNotEmpty()) {
            dnsCache[hostname] = Entry(addresses, now + TTL_MS)
        }

        return addresses
    }

    companion object {
        /** 缓存有效期：直播源 CDN 换 IP 后可自愈，避免永久固定旧地址 */
        private const val TTL_MS = 60_000L
    }
}