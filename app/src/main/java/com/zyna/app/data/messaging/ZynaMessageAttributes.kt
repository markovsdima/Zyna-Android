package com.zyna.app.data.messaging

data class ZynaMessageAttributes(
    val forwardedFrom: String? = null,
    val mediaGroup: MediaGroupInfo? = null
) {
    val isEmpty: Boolean
        get() = forwardedFrom == null && mediaGroup == null
}

data class MediaGroupInfo(
    val id: String,
    val index: Int,
    val total: Int,
    val captionMode: CaptionMode,
    val captionPlacement: CaptionPlacement,
    val layoutOverride: MediaGroupLayoutOverride? = null
)

data class MediaGroupLayoutOverride(
    val primarySplitPermille: Int,
    val secondarySplitPermille: Int? = null
)

enum class CaptionMode(val wireValue: String) {
    REPLICATED("replicated");

    companion object {
        fun fromWireValue(value: String?): CaptionMode? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class CaptionPlacement(val wireValue: String) {
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun fromWireValue(value: String?): CaptionPlacement? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}
