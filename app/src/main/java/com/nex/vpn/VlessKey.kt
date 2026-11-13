package com.nex.vpn

/**
 * Один vless-ключ из публичного репозитория.
 */
data class VlessKey(
    val raw: String,          // полная строка vless://
    val protocol: String,     // reality / tls (security из URI)
    val transport: String,    // tcp / ws / xhttp / grpc (type из URI)
    val country: String,      // ISO-код страны, напр. "DE"
    val host: String,
    val port: Int,
    val uuid: String,
    val sni: String?,
    val flow: String?,
    val publicKey: String?,   // для reality
    val shortId: String?,
    val path: String?,        // ws/xhttp path, grpc serviceName
    val hostHeader: String?,  // ws/xhttp Host header
    val mode: String?,        // xhttp mode / grpc mode
    val extra: String?,       // xhttp extra (raw JSON)
    val fingerprint: String?, // tls fingerprint (fp)
    val name: String?
) {
    val countryEmoji: String
        get() = if (country.length == 2 && country.all { it in 'A'..'Z' }) {
            // ВАЖНО: toChars() для символа вне BMP возвращает пару суррогатов из 2 char —
            // брать [0] нельзя (теряется половина -> «ромб с ?» вместо флага)
            country.map { codeChar ->
                Character.toChars(0x1F1E6 + (codeChar - 'A')).concatToString()
            }.joinToString("")
        } else "🌐"

    val protocolLabel: String
        get() = when {
            protocol == "reality" -> "REALITY"
            transport == "tcp" -> "TLS"
            else -> transport.uppercase()
        }
}
