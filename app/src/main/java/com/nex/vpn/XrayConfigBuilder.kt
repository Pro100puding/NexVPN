package com.nex.vpn

/**
 * Сборка JSON-конфига xray-core.
 *
 * Политика NexVPN:
 *  - DNS только DoH (Cloudflare 1.1.1.1, fallback Google 8.8.8.8), bootstrap по IP —
 *    провайдер не может ни подсунуть левый IP, ни увидеть, что мы резолвим
 *  - порт 80 жёстко в blacklist — HTTP-трафик через туннель не ходит вообще,
 *    поэтому «слить» данные в открытом виде невозможно по построению
 *  - allowInsecure=false всегда: самоподписанные/левые сертификаты не доверяем
 *  - routing: если bypass=false — весь трафик в туннель; если bypass=true —
 *    route только для заблокированных доменных множеств (geosite:category-ru-ads и т.п.)
 */
object XrayConfigBuilder {

    fun build(key: VlessKey, bypass: Boolean, allowLan: Boolean = false): String {
        val sb = StringBuilder(2048)
        sb.append('{')
        // ---- log ----
        sb.append("\"log\":{\"loglevel\":\"warning\"},")

        // ---- dns: только DoH ----
        sb.append("\"dns\":{")
        sb.append("\"servers\":[")
        sb.append("{\"address\":\"https://cloudflare-dns.com/dns-query\",\"domains\":[\"geosite:geolocation-!cn\"]},")
        sb.append("{\"address\":\"https://dns.google/dns-query\",\"domains\":[\"geosite:cn\"]},")
        sb.append("{\"address\":\"1.1.1.1\"},")
        sb.append("{\"address\":\"8.8.8.8\"}")
        sb.append("],")
        sb.append("\"queryStrategy\":\"UseIPv4\"")
        sb.append("},")

        // ---- routing ----
        sb.append("\"routing\":{")
        sb.append("\"domainStrategy\":\"IPIfNonMatch\",")
        sb.append("\"rules\":[")
        // 1. рез HTTP: порт 80 -> block. Это ключевая защита от утечек открытым текстом
        sb.append("{\"type\":\"field\",\"port\":\"80\",\"network\":\"tcp,udp\",\"outboundTag\":\"blocked\"},")
        // 2. реклама -> блок
        sb.append("{\"type\":\"field\",\"domain\":[\"geosite:category-ads-all\"],\"outboundTag\":\"blocked\"},")
        // 3. DNS-запросы -> внутренний dns outbound
        sb.append("{\"type\":\"field\",\"port\":53,\"network\":\"tcp,udp\",\"outboundTag\":\"dns-out\"},")
        // 4. локальный трафик приложения -> напрямую
        if (!allowLan) {
            sb.append("{\"type\":\"field\",\"ip\":[\"geoip:private\"],\"outboundTag\":\"direct\"},")
        }
        // 5. если bypass включён — «белые списки» блокируются, трафик идёт напрямую кроме нужных доменов
        if (bypass) {
            sb.append("{\"type\":\"field\",\"domain\":[\"geosite:category-gov-ru\",\"geosite:category-bank\"],\"outboundTag\":\"direct\"},")
        }
        // 6. всё остальное -> прокси (vless-ключ)
        sb.append("{\"type\":\"field\",\"network\":\"tcp,udp\",\"outboundTag\":\"proxy\"}")
        sb.append("]},")

        // ---- inbound: TUN от VpnService (xray читает fd из env xray.tun.fd) ----
        // Без protocol:"tun" xray вообще не читает tun-fd -> весь трафик в чёрную дыру.
        // name ОБЯЗАТЕЛЕН на Android: без него TunConfig.Build зовёт GetAvailableTunName()
        // -> net.Interfaces() -> netlink -> EPERM в untrusted_app.
        sb.append("\"inbounds\":[{")
        sb.append("\"port\":0,\"protocol\":\"tun\",")
        sb.append("\"settings\":{\"name\":\"tun0\",\"mtu\":1500},")
        sb.append("\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"]}")
        sb.append("}],")

        // ---- outbounds ----
        sb.append("\"outbounds\":[")
        // proxy: vless/reality
        sb.append(buildProxyOutbound(key)).append(',')
        // direct
        sb.append("{\"protocol\":\"freedom\",\"tag\":\"direct\",\"settings\":{\"domainStrategy\":\"UseIP\"}},")
        // block
        sb.append("{\"protocol\":\"blackhole\",\"tag\":\"blocked\"},")
        // dns-out
        sb.append("{\"protocol\":\"dns\",\"tag\":\"dns-out\"}")
        sb.append("]}")

        return sb.toString()
    }

    private fun jsonEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private fun buildProxyOutbound(k: VlessKey): String {
        val sb = StringBuilder(2048)
        val security = if (k.protocol == "reality") "reality" else "tls"
        // REALITY поддерживает только raw/xhttp/grpc — ws под неё недопустим
        val transport = if (security == "reality" && k.transport == "ws") "tcp" else k.transport

        sb.append("{")
        sb.append("\"protocol\":\"vless\",\"tag\":\"proxy\",")
        sb.append("\"settings\":{\"vnext\":[{")
        sb.append("\"address\":\"").append(k.host).append("\",")
        sb.append("\"port\":").append(k.port).append(',')
        sb.append("\"users\":[{")
        sb.append("\"id\":\"").append(k.uuid).append("\",")
        sb.append("\"encryption\":\"none\",")
        sb.append("\"flow\":\"").append(k.flow ?: "").append("\"")
        sb.append("}]")
        sb.append("}]},")
        // streamSettings
        sb.append("\"streamSettings\":{")
        sb.append("\"network\":\"").append(transport).append("\",")
        sb.append("\"security\":\"").append(security).append("\",")
        when (security) {
            "reality" -> {
                sb.append("\"realitySettings\":{")
                sb.append("\"serverName\":\"").append(jsonEscape(k.sni ?: "")).append("\",")
                sb.append("\"fingerprint\":\"chrome\",")
                sb.append("\"publicKey\":\"").append(k.publicKey ?: "").append("\",")
                sb.append("\"shortId\":\"").append(k.shortId ?: "").append("\",")
                sb.append("\"spiderX\":\"\"")
                sb.append("},")
            }
            else -> {
                sb.append("\"tlsSettings\":{")
                sb.append("\"serverName\":\"").append(jsonEscape(k.sni ?: k.hostHeader ?: k.host)).append("\",")
                if (k.fingerprint != null && k.fingerprint != "unsafe") {
                    sb.append("\"fingerprint\":\"").append(jsonEscape(k.fingerprint)).append("\",")
                }
                sb.append("\"allowInsecure\":false") // жёстко: никаких самоподписанных
                sb.append("},")
            }
        }
        when (transport) {
            "ws" -> {
                sb.append("\"wsSettings\":{")
                sb.append("\"path\":\"").append(jsonEscape(k.path ?: "/")).append("\"")
                if (!k.hostHeader.isNullOrBlank()) {
                    sb.append(",\"headers\":{\"Host\":\"").append(jsonEscape(k.hostHeader)).append("\"}")
                }
                sb.append("},")
            }
            "xhttp" -> {
                sb.append("\"xhttpSettings\":{")
                sb.append("\"path\":\"").append(jsonEscape(k.path ?: "/")).append("\",")
                if (!k.hostHeader.isNullOrBlank()) {
                    sb.append("\"host\":\"").append(jsonEscape(k.hostHeader)).append("\",")
                }
                sb.append("\"mode\":\"").append(if (k.mode.isNullOrBlank()) "auto" else jsonEscape(k.mode)).append('"')
                if (!k.extra.isNullOrBlank()) {
                    // extra — готовый JSON из ключа (scMax*, xmux, ...)
                    sb.append(",\"extra\":").append(k.extra)
                }
                sb.append("},")
            }
            "grpc" -> {
                sb.append("\"grpcSettings\":{")
                sb.append("\"serviceName\":\"").append(jsonEscape(k.path ?: "")).append("\"")
                if (k.mode == "multi") sb.append(",\"multiMode\":true")
                sb.append("},")
            }
        }
        sb.append("\"sockopt\":{\"tcpKeepAliveIdle\":100,\"mark\":0}")
        sb.append("}")
        sb.append("}")
        return sb.toString()
    }
}
