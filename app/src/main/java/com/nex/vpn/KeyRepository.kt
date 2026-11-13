package com.nex.vpn

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Тянет список ключей из публичных репозиториев ( MyAppVPN-subs и подобные ),
 * парсит vless:// строки, считает живые.
 */
object KeyRepository {

    // Список публичных источников (ветка output — обновляется автоматически)
    private val SOURCES = listOf(
        "https://cdn.jsdelivr.net/gh/indelingDanil/MyAppVPN-subs@output/vless.txt",
        "https://cdn.jsdelivr.net/gh/indelingDanil/MyAppVPN-subs@output/reality.txt"
    )

    var bypassMode: Boolean = false
    var bestByPing: Boolean = false

    private var cache: List<VlessKey> = emptyList()
    private var cacheTime: Long = 0

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Главный метод: получить валидные ключи (с кэшем на 30 минут) */
    suspend fun fetchKeys(force: Boolean = false): List<VlessKey> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && cache.isNotEmpty() && now - cacheTime < 30 * 60 * 1000) {
            return@withContext cache
        }
        val result = mutableListOf<VlessKey>()
        for (url in SOURCES) {
            try {
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    result += parseVlessList(body)
                }
            } catch (_: Exception) { /* источник недоступен — идём к следующему */ }
        }
        // дедупликация по uuid+host
        val dedup = result.distinctBy { it.uuid + it.host + it.port }
        cache = dedup
        cacheTime = now
        dedup
    }

    /** Выбрать один ключ под текущий режим */
    fun pickKey(bypass: Boolean): VlessKey? {
        if (cache.isEmpty()) return null
        return if (bypass) {
            // режим обхода: prefer reality (тоньше в DPI)
            cache.firstOrNull { it.protocol == "reality" } ?: cache.random()
        } else {
            cache.random()
        }
    }

    /** Сколько живых ключей сейчас доступно под режим */
    suspend fun countActive(bypass: Boolean): Int = withContext(Dispatchers.IO) {
        val keys = fetchKeys()
        if (bypass) keys.count { it.protocol == "reality" }.takeIf { it > 0 } ?: keys.size
        else keys.size
    }

    /**
     * Выбрать ключ, который РЕАЛЬНО отвечает: грузим список, перебираем кандидатов
     * и проверяем на уровне транспорта (TLS-handshake / WS-upgrade), а не только
     * TCP-коннектом — нода может слушать порт, но резать соединение (429, битый SNI).
     */
    suspend fun pickKeyLive(bypass: Boolean, maxTry: Int = 8, budgetMs: Long = 20_000L): VlessKey? =
        withContext(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(budgetMs) { pickKeyLiveInner(bypass, maxTry) }
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun pickKeyLiveInner(bypass: Boolean, maxTry: Int): VlessKey? =
        withContext(Dispatchers.IO) {
            val keys = try {
                fetchKeys()
            } catch (e: Exception) {
                emptyList()
            }
            if (keys.isEmpty()) return@withContext null

            val pool = if (bypass) {
                val reality = keys.filter { it.protocol == "reality" }
                if (reality.isNotEmpty()) reality.shuffled() else keys.shuffled()
            } else {
                keys.shuffled()
            }

            if (bestByPing) {
                // режим «лучшие по пингу»: меряем всех кандидатов, берём быстрейший
                var bestKey: VlessKey? = null
                var bestMs = Long.MAX_VALUE
                for (k in pool.take(maxTry)) {
                    val ms = measureKey(k, 1200)
                    if (ms in 0 until bestMs) {
                        bestMs = ms
                        bestKey = k
                    }
                }
                bestKey ?: pool.firstOrNull()
            } else {
                for (k in pool.take(maxTry)) {
                    if (measureKey(k, 1200) >= 0) return@withContext k
                }
                // ни одна не прошла probe — отдаём хоть что-то, xray попробует сам
                pool.firstOrNull()
            }
        }

    /**
     * Проверка живости ключа на уровне его транспорта.
     * Возвращает латентность в мс (для сравнения в режиме best-by-ping),
     * или -1, если ключ мёртв/не отвечает.
     */
    private fun measureKey(k: VlessKey, timeoutMs: Int): Long = try {
        val t0 = System.nanoTime()
        val alive = when {
            k.protocol == "reality" -> tcpPing(k.host, k.port, timeoutMs) >= 0
            k.transport == "ws" -> probeWs(k, timeoutMs)
            k.transport == "xhttp" -> probeXhttp(k, timeoutMs)
            else -> probeTls(k, timeoutMs)
        }
        if (alive) (System.nanoTime() - t0) / 1_000_000 else -1L
    } catch (_: Exception) {
        -1L
    }

    /** TCP-connect в мс; -1 если недоступен. Используется и для пинга в UI. */
    fun tcpPing(host: String, port: Int, timeoutMs: Int = 3000): Long {
        val sock = java.net.Socket()
        return try {
            val t0 = System.nanoTime()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000
        } catch (_: Exception) {
            -1L
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** TLS-соединение с SNI из ключа; бросает исключение при ошибке handshake */
    private fun openTls(k: VlessKey, timeoutMs: Int): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, null, null)
        val sock = ctx.socketFactory.createSocket() as SSLSocket
        val sni = k.sni ?: k.hostHeader
        if (!sni.isNullOrBlank()) {
            try {
                sock.sslParameters = sock.sslParameters.apply {
                    serverNames = listOf(SNIHostName(sni))
                }
            } catch (_: Exception) { /* невалидный SNI — handshake покажет */ }
        }
        sock.soTimeout = timeoutMs
        sock.connect(InetSocketAddress(k.host, k.port), timeoutMs)
        sock.startHandshake()
        return sock
    }

    /** tls+tcp/grpc: успешного TLS-handshake достаточно */
    private fun probeTls(k: VlessKey, timeoutMs: Int): Boolean {
        val sock = openTls(k, timeoutMs)
        try {
            return true
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** tls+ws: TLS + Upgrade: websocket — сервер обязан ответить 101 (429/403 = мёртв) */
    private fun probeWs(k: VlessKey, timeoutMs: Int): Boolean {
        val sock = openTls(k, timeoutMs)
        try {
            val path = k.path ?: "/"
            val host = k.hostHeader ?: k.sni ?: k.host
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            sock.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            sock.outputStream.flush()
            val line = BufferedReader(InputStreamReader(sock.inputStream, Charsets.US_ASCII))
                .readLine() ?: return false
            return line.split(" ").getOrNull(1) == "101"
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** tls+xhttp: TLS + обычный GET; жив, если сервер отвечает HTTP и не 429/403 */
    private fun probeXhttp(k: VlessKey, timeoutMs: Int): Boolean {
        val sock = openTls(k, timeoutMs)
        try {
            val path = k.path ?: "/"
            val host = k.hostHeader ?: k.sni ?: k.host
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            sock.outputStream.flush()
            val line = BufferedReader(InputStreamReader(sock.inputStream, Charsets.US_ASCII))
                .readLine() ?: return false
            val code = line.split(" ").getOrNull(1) ?: return false
            return code != "429" && code != "403"
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** Парсер строк vless:// */
    fun parseVlessList(body: String): List<VlessKey> {
        val out = mutableListOf<VlessKey>()
        // поддержка base64-обёртки
        val text = if (!body.contains("vless://")) {
            try {
                String(Base64.decode(body.trim(), Base64.DEFAULT))
            } catch (_: Exception) { body }
        } else body

        for (line in text.lines()) {
            val key = parseVless(line.trim()) ?: continue
            out += key
            // формат имени источника: "🇩🇪 DE reality-0012" — страну достаёт parseVless
        }
        return out
    }

    /** Разбор одной строки vless://uuid@host:port?params#name */
    fun parseVless(line: String): VlessKey? {
        if (!line.startsWith("vless://")) return null
        return try {
            val uri = URI(line)
            val userInfo = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port in 1..65535) uri.port else return null
            val params = uri.query?.split('&')
                ?.mapNotNull { p ->
                    val i = p.indexOf('=')
                    if (i > 0) p.substring(0, i) to p.substring(i + 1) else null
                }?.toMap() ?: emptyMap()

            val name = line.substringAfterLast('#', "").takeIf { it.isNotEmpty() }
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

            val security = when {
                params["security"] == "reality" -> "reality"
                else -> "tls"
            }
            // type: tcp/raw (по умолчанию), ws, xhttp, grpc
            val transport = when (params["type"]?.lowercase()) {
                "ws", "websocket" -> "ws"
                "xhttp", "splithttp" -> "xhttp"
                "grpc" -> "grpc"
                else -> "tcp"
            }

            // страна: сначала из имени (флаг или код), иначе unknown
            val cc = extractCountry(name) ?: "??"

            VlessKey(
                raw = line,
                protocol = security,
                transport = transport,
                country = cc,
                host = host,
                port = port,
                uuid = userInfo,
                sni = params["sni"],
                flow = params["flow"],
                publicKey = params["pbk"],
                shortId = params["sid"],
                path = params["path"] ?: params["serviceName"],
                hostHeader = params["host"],
                mode = params["mode"],
                extra = params["extra"],
                fingerprint = params["fp"],
                name = name
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCountry(name: String?): String? {
        if (name == null) return null
        // ищем ISO-код в имени: "DE reality-0012"
        val m = Regex("\\b([A-Z]{2})\\b").find(name)
        if (m != null) return m.groupValues[1]
        // флаги-эмодзи: региональные индикаторы
        val flag = Regex("[\\uD83C\\uDDE6-\\uD83C\\uDDFF]{2}").find(name)
        if (flag != null) {
            val chars = flag.value.codePoints().toArray()
            if (chars.size == 2) {
                return String(charArrayOf(
                    (chars[0] - 0x1F1E6 + 'A'.code).toChar(),
                    (chars[1] - 0x1F1E6 + 'A'.code).toChar()
                ))
            }
        }
        return null
    }
}
