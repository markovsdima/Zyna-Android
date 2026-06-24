package com.zyna.app.data.messaging

internal fun String?.normalizedMessageCaption(): String? {
    val trimmed = this?.trimCaptionIgnorable() ?: return null
    return trimmed.takeIf { text ->
        text.any { char -> !char.isCaptionIgnorable() }
    }
}

private fun String.trimCaptionIgnorable(): String {
    var start = 0
    var end = length
    while (start < end && this[start].isCaptionIgnorable()) {
        start += 1
    }
    while (end > start && this[end - 1].isCaptionIgnorable()) {
        end -= 1
    }
    return substring(start, end)
}

private fun Char.isCaptionIgnorable(): Boolean {
    return isWhitespace() ||
        Character.isSpaceChar(this) ||
        when (Character.getType(code)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt() -> true
            else -> false
        }
}
