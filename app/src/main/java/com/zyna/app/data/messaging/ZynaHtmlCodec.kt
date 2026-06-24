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
        val root = parseJson(unescapeFromHtmlAttribute(raw)) ?: return ZynaMessageAttributes()

        return buildAttributes(root)
    }

    fun encodeAttributesJson(attributes: ZynaMessageAttributes): String? {
        return buildJson(attributes)
    }

    fun decodeAttributesJson(json: String?): ZynaMessageAttributes {
        val root = json
            ?.takeIf { it.isNotBlank() }
            ?.let(::parseJson)
            ?: return ZynaMessageAttributes()
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

        attributes.mediaGroup?.let { mediaGroup ->
            val groupJson = JSONObject()
                .put(JSON_MEDIA_GROUP_ID, mediaGroup.id)
                .put(JSON_MEDIA_GROUP_INDEX, mediaGroup.index)
                .put(JSON_MEDIA_GROUP_TOTAL, mediaGroup.total)
                .put(JSON_MEDIA_GROUP_CAPTION_MODE, mediaGroup.captionMode.wireValue)
                .put(JSON_MEDIA_GROUP_CAPTION_PLACEMENT, mediaGroup.captionPlacement.wireValue)
            mediaGroup.layoutOverride?.let { layout ->
                val layoutJson = JSONObject()
                    .put(JSON_MEDIA_GROUP_LAYOUT_PRIMARY, layout.primarySplitPermille)
                if (layout.secondarySplitPermille != null) {
                    layoutJson.put(JSON_MEDIA_GROUP_LAYOUT_SECONDARY, layout.secondarySplitPermille)
                }
                groupJson.put(JSON_MEDIA_GROUP_LAYOUT, layoutJson)
            }
            root.put(JSON_MEDIA_GROUP, groupJson)
        }

        if (root.length() <= 1) return null
        return root.toString()
    }

    private fun buildAttributes(root: JSONObject): ZynaMessageAttributes {
        return ZynaMessageAttributes(
            forwardedFrom = root.optStringOrNull(JSON_FORWARDED_FROM),
            mediaGroup = root.optJSONObject(JSON_MEDIA_GROUP)?.toMediaGroupInfoOrNull()
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

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()
    }

    private fun JSONObject.toMediaGroupInfoOrNull(): MediaGroupInfo? {
        val id = optStringOrNull(JSON_MEDIA_GROUP_ID) ?: return null
        val index = optIntOrNull(JSON_MEDIA_GROUP_INDEX) ?: return null
        val total = optIntOrNull(JSON_MEDIA_GROUP_TOTAL) ?: return null
        val captionMode = CaptionMode.fromWireValue(
            optStringOrNull(JSON_MEDIA_GROUP_CAPTION_MODE)
        ) ?: return null
        val captionPlacement = CaptionPlacement.fromWireValue(
            optStringOrNull(JSON_MEDIA_GROUP_CAPTION_PLACEMENT)
        ) ?: CaptionPlacement.BOTTOM
        val layoutOverride = optJSONObject(JSON_MEDIA_GROUP_LAYOUT)
            ?.toMediaGroupLayoutOverrideOrNull()

        return MediaGroupInfo(
            id = id,
            index = index,
            total = total,
            captionMode = captionMode,
            captionPlacement = captionPlacement,
            layoutOverride = layoutOverride
        )
    }

    private fun JSONObject.toMediaGroupLayoutOverrideOrNull(): MediaGroupLayoutOverride? {
        val primarySplit = optIntOrNull(JSON_MEDIA_GROUP_LAYOUT_PRIMARY) ?: return null
        return MediaGroupLayoutOverride(
            primarySplitPermille = primarySplit,
            secondarySplitPermille = optIntOrNull(JSON_MEDIA_GROUP_LAYOUT_SECONDARY)
        )
    }

    private fun parseJson(json: String): JSONObject? {
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private const val JSON_VERSION = "v"
    private const val JSON_FORWARDED_FROM = "fwd"
    private const val JSON_MEDIA_GROUP = "mg"
    private const val JSON_MEDIA_GROUP_ID = "id"
    private const val JSON_MEDIA_GROUP_INDEX = "index"
    private const val JSON_MEDIA_GROUP_TOTAL = "total"
    private const val JSON_MEDIA_GROUP_CAPTION_MODE = "captionMode"
    private const val JSON_MEDIA_GROUP_CAPTION_PLACEMENT = "captionPlacement"
    private const val JSON_MEDIA_GROUP_LAYOUT = "layout"
    private const val JSON_MEDIA_GROUP_LAYOUT_PRIMARY = "primarySplit"
    private const val JSON_MEDIA_GROUP_LAYOUT_SECONDARY = "secondarySplit"
    private val DATA_ZYNA_ATTRIBUTE_REGEX = Regex("""data-zyna\s*=\s*(?:"([^"]*)"|'([^']*)')""")
}
