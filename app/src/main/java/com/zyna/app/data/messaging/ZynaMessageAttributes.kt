package com.zyna.app.data.messaging

data class ZynaMessageAttributes(
    val forwardedFrom: String? = null
) {
    val isEmpty: Boolean
        get() = forwardedFrom == null
}
