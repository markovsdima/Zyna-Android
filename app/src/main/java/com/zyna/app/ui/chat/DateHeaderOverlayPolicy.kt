package com.zyna.app.ui.chat

/**
 * Pure, allocation-free sticky-header geometry.
 *
 * [Float.NaN] means that the real inline divider owns presentation and no floating copy is needed.
 */
internal fun resolveDateHeaderTranslationY(
    ownDividerStillVisible: Boolean,
    approachingDividerTop: Float,
    stickyTop: Float,
    overlayHeight: Float,
    pushSpacing: Float
): Float {
    if (ownDividerStillVisible || overlayHeight <= 0f) return Float.NaN
    if (!approachingDividerTop.isFinite()) return 0f
    return minOf(0f, approachingDividerTop - stickyTop - overlayHeight - pushSpacing)
}
