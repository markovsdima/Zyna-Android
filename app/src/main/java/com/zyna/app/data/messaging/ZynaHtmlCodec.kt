package com.zyna.app.data.messaging

import org.json.JSONObject

object ZynaHtmlCodec {
    const val CURRENT_VERSION = 1
    const val DATA_ATTRIBUTE_NAME = "data-zyna"

    fun encode(
        userHtml: String,
        attributes: ZynaMessageAttributes
    ): String {
        if (attributes.isEmpty) return userHtml
        val json = buildJson(attributes) ?: return userHtml
        val escaped = escapeForHtmlAttribute(json)
        return "$userHtml<span $DATA_ATTRIBUTE_NAME=\"$escaped\"></span>"
    }

    fun decode(htmlBody: String): ZynaMessageAttributes {
        val raw = extractDataZynaValue(htmlBody) ?: return ZynaMessageAttributes()
        val root = runCatching {
            JSONObject(unescapeFromHtmlAttribute(raw))
        }.getOrNull() ?: return ZynaMessageAttributes()

        return buildAttributes(root)
    }

    fun escapeForHtmlAttribute(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    fun unescapeFromHtmlAttribute(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun buildJson(attributes: ZynaMessageAttributes): String? {
        val root = JSONObject()
            .put(JSON_VERSION, CURRENT_VERSION)

        attributes.forwardedFrom
            ?.takeIf { it.isNotBlank() }
            ?.let { root.put(JSON_FORWARDED_FROM, it) }

        if (root.length() <= 1) return null
        return root.toString()
    }

    private fun buildAttributes(root: JSONObject): ZynaMessageAttributes {
        return ZynaMessageAttributes(
            forwardedFrom = root.optStringOrNull(JSON_FORWARDED_FROM)
        )
    }

    private fun extractDataZynaValue(htmlBody: String): String? {
        val match = DATA_ZYNA_ATTRIBUTE_REGEX.find(htmlBody) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return opt(key)
            ?.let { it as? String }
            ?.takeIf { it.isNotBlank() }
    }

    private const val JSON_VERSION = "v"
    private const val JSON_FORWARDED_FROM = "fwd"
    private val DATA_ZYNA_ATTRIBUTE_REGEX = Regex("""data-zyna\s*=\s*(?:"([^"]*)"|'([^']*)')""")
}
