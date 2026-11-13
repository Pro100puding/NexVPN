package com.nex.vpn

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Встроенный DNS-over-HTTPS резолвер.
 * Работает ВНУТРИ туннеля — подменить ответы снаружи нельзя.
 * Кэширует ответы, неутолимые запросы не роняют соединение.
 */
object DohResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // беру два независимых резолвера — primary + fallback
    private val primary: DnsOverHttps = DnsOverHttps.Builder()
        .client(client)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1")
        )
        .build()

    private val fallback: DnsOverHttps = DnsOverHttps.Builder()
        .client(client)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("8.8.4.4")
        )
        .build()

    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    fun resolve(host: String): List<InetAddress> {
        cache[host]?.let { return it }
        val result = try {
            primary.lookup(host)
        } catch (_: Exception) {
            fallback.lookup(host)
        }
        if (result.isNotEmpty()) cache[host] = result
        return result
    }

    fun clearCache() = cache.clear()
}
