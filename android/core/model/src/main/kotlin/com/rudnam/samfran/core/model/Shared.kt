package com.rudnam.samfran.core.model

import java.net.URI

/**
 * Adding a place from Google Maps (web/src/share.ts). Maps shares text like
 * "Hanabi Grill\nhttps://maps.app.goo.gl/AbC123", sometimes with the
 * address on a line of its own, sometimes only the link. The Add form reads
 * a pasted link the same way.
 */
data class Shared(val name: String, val url: String) {
    companion object {
        private val link = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
        private val leftover = Regex("""^(check out|see|shared)\b.*:$""", RegexOption.IGNORE_CASE)

        /** The place's name and link in text shared from Maps (or anywhere). */
        fun parse(vararg parts: String?): Shared {
            val text = parts.filterNot { it.isNullOrEmpty() }.joinToString("\n")
            val url = link.find(text)?.value.orEmpty()
            val name = text.split('\n')
                .map { it.replace(link, "").trim() }
                // Not a leftover like "Check out this place:" or a bare address.
                .firstOrNull { it.isNotEmpty() && !leftover.matches(it) }
                .orEmpty()
            // Maps sometimes writes "Name · rating · type": keep the name.
            return Shared(name.split(" · ").first().trim().take(200), url)
        }

        /** Android's share intent: some apps put the name in the subject and only the link in the text. */
        fun fromIntent(text: String?, subject: String?): String? {
            if (text.isNullOrEmpty()) return null
            return if (!subject.isNullOrEmpty() && !text.contains(subject)) "$subject\n$text" else text
        }

        /** True for a Google Maps address the server can read a name from. */
        fun isMapsLink(url: String): Boolean {
            val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            if (u.scheme?.lowercase() !in setOf("http", "https")) return false
            val host = u.host?.lowercase() ?: return false
            return host == "maps.app.goo.gl" ||
                (host == "goo.gl" && u.path.orEmpty().startsWith("/maps")) ||
                Regex("""^(www\.|maps\.)?google\.(com|[a-z]{2}|com?\.[a-z]{2})$""").matches(host)
        }
    }
}
