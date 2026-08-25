package com.zyna.app.ui.createroom

/**
 * Keeps a cursor or selection endpoint stable when live normalization replaces part of a value.
 *
 * Case-only changes are treated as unchanged. Positions after a removed prefix/suffix move by the
 * size of that edit, while positions inside the changed range stay as close as possible.
 */
internal fun remapSelectionAfterNormalization(
    oldValue: String,
    newValue: String,
    selection: Int
): Int {
    if (oldValue.isEmpty()) return newValue.length
    val normalizedSelection = selection.coerceIn(0, oldValue.length)
    var commonPrefixLength = 0
    val maxPrefixLength = minOf(oldValue.length, newValue.length)
    while (
        commonPrefixLength < maxPrefixLength &&
        oldValue[commonPrefixLength].equals(newValue[commonPrefixLength], ignoreCase = true)
    ) {
        commonPrefixLength += 1
    }

    var commonSuffixLength = 0
    val maxSuffixLength = minOf(
        oldValue.length - commonPrefixLength,
        newValue.length - commonPrefixLength
    )
    while (
        commonSuffixLength < maxSuffixLength &&
        oldValue[oldValue.lastIndex - commonSuffixLength].equals(
            newValue[newValue.lastIndex - commonSuffixLength],
            ignoreCase = true
        )
    ) {
        commonSuffixLength += 1
    }

    val oldChangedEnd = oldValue.length - commonSuffixLength
    val newChangedEnd = newValue.length - commonSuffixLength
    return when {
        normalizedSelection <= commonPrefixLength -> normalizedSelection
        normalizedSelection >= oldChangedEnd ->
            normalizedSelection + newChangedEnd - oldChangedEnd
        else -> commonPrefixLength + minOf(
            normalizedSelection - commonPrefixLength,
            newChangedEnd - commonPrefixLength
        )
    }.coerceIn(0, newValue.length)
}
