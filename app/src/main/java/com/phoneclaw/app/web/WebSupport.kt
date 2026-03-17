package com.phoneclaw.app.web

import java.net.URI

private val absoluteUrlRegex = Regex("""(?i)\bhttps?://[^\s<>\"']+""")
private val bareDomainRegex = Regex(
    """(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:com|net|org|io|ai|cn|dev|app|co|edu|gov|info|me|xyz|tech|site|top|cc|tv|online|cloud|store|blog|hk|jp|us|uk)(?:/[^\s<>\"']*)?""",
)
private val titleRegex = Regex("""(?is)<title[^>]*>(.*?)</title>""")

fun extractFirstWebTarget(text: String): String? {
    val absoluteUrl = absoluteUrlRegex.find(text)?.value
    if (!absoluteUrl.isNullOrBlank()) {
        return absoluteUrl
    }

    return bareDomainRegex.find(text)?.value
}

fun normalizeWebUrl(input: String): String? {
    val trimmed = input.trim().trimWebBoundaryPunctuation()
    if (trimmed.isBlank()) return null

    val withScheme = when {
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        bareDomainRegex.matches(trimmed) -> "https://$trimmed"
        else -> return null
    }

    return runCatching {
        val uri = URI(withScheme)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
            null
        } else {
            uri.toASCIIString()
        }
    }.getOrNull()
}

fun containsWebFetchIntent(text: String): Boolean {
    val normalized = text.normalizeForIntentMatch()
    val fetchKeywords = listOf(
        "fetch",
        "read",
        "extract",
        "summarize",
        "content",
        "webcontent",
        "pagecontent",
        "读取",
        "抓取",
        "获取",
        "总结",
        "摘要",
        "网页内容",
        "网页正文",
        "网站内容",
    )
    return fetchKeywords.any { keyword -> normalized.contains(keyword) }
}

fun extractHtmlTitle(html: String): String? {
    val title = titleRegex.find(html)?.groupValues?.getOrNull(1)?.decodeHtmlEntities()?.collapseWhitespace()
    return title?.takeIf { it.isNotBlank() }
}

fun extractReadableText(html: String, maxLength: Int = 1200): String {
    if (html.isBlank()) return ""

    val withoutScripts = html
        .replace(Regex("""(?is)<script[^>]*>.*?</script>"""), " ")
        .replace(Regex("""(?is)<style[^>]*>.*?</style>"""), " ")
        .replace(Regex("""(?is)<noscript[^>]*>.*?</noscript>"""), " ")
        .replace(Regex("""(?is)<!--.*?-->"""), " ")

    val text = withoutScripts
        .replace(Regex("""(?i)</(p|div|section|article|li|h1|h2|h3|h4|h5|h6|br|tr)>"""), "\n")
        .replace(Regex("""(?is)<[^>]+>"""), " ")
        .decodeHtmlEntities()
        .replace("\u00A0", " ")
        .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
        .replace(Regex("""\n\s*\n+"""), "\n")
        .trim()

    return if (text.length <= maxLength) {
        text
    } else {
        text.take(maxLength).trimEnd() + "..."
    }
}

private fun String.trimWebBoundaryPunctuation(): String {
    return trim()
        .trimStart('(', '[', '{', '<', '"', '\'')
        .trimEnd(')', ']', '}', '>', '.', ',', ';', ':', '!', '?', '。', '，', '；', '：', '！', '？', '"', '\'')
}

private fun String.normalizeForIntentMatch(): String {
    return lowercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
}

private fun String.decodeHtmlEntities(): String {
    return this
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

private fun String.collapseWhitespace(): String {
    return replace(Regex("""\s+"""), " ").trim()
}
