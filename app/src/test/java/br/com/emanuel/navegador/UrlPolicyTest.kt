package br.com.emanuel.navegador

import org.junit.Assert.*
import org.junit.Test

class UrlPolicyTest {
    @Test fun opensSecureAddressByDefault() { assertEquals("https://example.com", UrlPolicy.resolve("example.com")) }
    @Test fun preservesExplicitHttpAndPorts() { assertEquals("http://localhost:8080/path?q=a", UrlPolicy.resolve("http://localhost:8080/path?q=a")) }
    @Test fun rejectsScriptFileContentAndIntentSchemes() {
        listOf("javascript:alert(1)", "file:///data/data/app", "content://other/private", "intent://test#Intent;end", "data:text/html,x", "about:blank").forEach { assertNull(it, UrlPolicy.resolve(it)) }
    }
    @Test fun rejectsJavascriptWithLeadingWhitespace() { assertNull(UrlPolicy.resolve(" javascript:alert(1) ")) }
    @Test fun rejectsControlCharacters() { assertNull(UrlPolicy.resolve("https://example.com\n.evil")); assertNull(UrlPolicy.resolve("https://examp\u0000le.com")) }
    @Test fun rejectsCredentialsAndMisleadingAuthorities() { assertNull(UrlPolicy.resolve("https://trusted.com@evil.com")); assertNull(UrlPolicy.resolve("https://trusted.com\\@evil.com")) }
    @Test fun rejectsInvalidPorts() { assertNull(UrlPolicy.resolve("https://example.com:999999")); assertNull(UrlPolicy.resolve("https://example.com:bad")) }
    @Test fun searchesWithEscapedQuery() { assertEquals("https://duckduckgo.com/?q=an%C3%A1lise+de+solo", UrlPolicy.resolve("análise de solo")) }
    @Test fun supportsSearchProviderSelection() { assertEquals("https://www.google.com/search?q=hello+world", UrlPolicy.resolve("hello world", "google")) }
    @Test fun rejectsBlankOrExcessiveInputs() { assertNull(UrlPolicy.resolve("  ")); assertNull(UrlPolicy.resolve("a".repeat(9000))) }
    @Test fun normalizesOriginsAndDefaultPorts() { assertEquals("https://example.com", UrlPolicy.origin("https://example.com:443/path")); assertEquals("http://example.com:8080", UrlPolicy.origin("http://example.com:8080/")) }
    @Test fun displaysAsciiHostForInternationalDomains() { assertEquals("https://xn--bcher-kva.de", UrlPolicy.resolve("bücher.de")); assertEquals("xn--bcher-kva.de", UrlPolicy.displayHost(UrlPolicy.resolve("bücher.de"))) }
    @Test fun validatesEveryNetworkEntryPoint() { assertFalse(UrlPolicy.isWebUrl("https://")); assertFalse(UrlPolicy.isWebUrl("file:///etc/passwd")); assertTrue(UrlPolicy.isWebUrl("https://example.com/path?q=1#top")) }
    @Test fun supportsIpv6Literals() { assertEquals("http://[::1]:8080/", UrlPolicy.resolve("http://[::1]:8080/")) }
}
