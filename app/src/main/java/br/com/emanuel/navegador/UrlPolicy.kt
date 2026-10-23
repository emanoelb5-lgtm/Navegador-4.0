package br.com.emanuel.navegador

import java.net.IDN
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** All entry points share this policy; website JS, files and Android intents are never address-bar URLs. */
object UrlPolicy {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val bareHost = Regex("(?i)^(localhost|[^ /?#]+\\.[^ /?#]+|\\[[0-9a-f:]+])(?::[0-9]{1,5})?([/?#].*)?$")
    private val localHost = Regex("(?i)^localhost:[0-9]{1,5}([/?#].*)?$")

    fun resolve(input: String, provider: String = "duckduckgo"): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 8192 || text.any { it.isISOControl() }) return null
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) return canonicalWebUrl(text)
        if (scheme.containsMatchIn(text) && !localHost.matches(text)) return null
        if (!text.any { it.isWhitespace() } && (bareHost.matches(text) || localHost.matches(text))) {
            return canonicalWebUrl("https://$text")
        }
        val base = when (provider) {
            "google" -> "https://www.google.com/search?q="
            "bing" -> "https://www.bing.com/search?q="
            else -> "https://duckduckgo.com/?q="
        }
        return base + URLEncoder.encode(text, "UTF-8")
    }

    fun canonicalWebUrl(value: String): String? = runCatching {
        val uri = URI(value)
        val protocol = uri.scheme?.lowercase(Locale.ROOT)
        if (protocol !in setOf("http", "https") || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null || value.contains('\\')) return null
        val authority = uri.rawAuthority
        if ('@' in authority || '%' in authority) return null
        val host = uri.host ?: authority.substringBefore(':')
        if (host.isBlank()) return null
        val ascii = if (host.startsWith('[')) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        val port = uri.port
        if (port !in -1..65535) return null
        // An authority with a non-numeric port must not be mistaken for a valid host.
        if (uri.host == null && authority.contains(':')) return null
        val normalized = "$protocol://$ascii" + (if (port >= 0) ":$port" else "") + (uri.rawPath.orEmpty()) + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        URI(normalized).toASCIIString()
    }.getOrNull()

    fun isWebUrl(value: String?): Boolean = value != null && canonicalWebUrl(value) != null
    fun origin(value: String?): String? = value?.let { canonicalWebUrl(it) }?.let {
        val uri = URI(it)
        val port = if (uri.port < 0 || uri.port == (if (uri.scheme == "https") 443 else 80)) "" else ":${uri.port}"
        "${uri.scheme}://${uri.host}$port"
    }
    fun displayHost(value: String?): String = value?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty()
}
