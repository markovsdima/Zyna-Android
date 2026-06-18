package com.zyna.app.data.local

data class TimelineWindowAnchor(
    val timestampMillis: Long,
    val id: String
)

data class TimelineWindowSnapshot<T>(
    val anchor: TimelineWindowAnchor?,
    val messages: List<T>,
    val newestAnchor: TimelineWindowAnchor? = null,
    val hasOlderInDb: Boolean = false,
    val hasNewerInDb: Boolean = false
)

enum class TimelineWindowChangeOrigin {
    INITIAL_LOAD,
    TIMELINE_FLUSH,
    DATABASE_PAGINATION,
    LOCAL_MUTATION
}

data class TimelineFlushSummary(
    val appendCount: Int = 0,
    val pushBackCount: Int = 0,
    val pushFrontCount: Int = 0,
    val insertCount: Int = 0,
    val setCount: Int = 0,
    val removeCount: Int = 0,
    val resetCount: Int = 0,
    val truncateCount: Int = 0,
    val clearCount: Int = 0,
    val readReceiptCount: Int = 0,
    val upsertCount: Int = 0,
    val deleteCount: Int = 0,
    val redactedUpsertCount: Int = 0
) {
    val hasHistoryOrResetShape: Boolean
        get() = resetCount > 0 ||
            pushFrontCount > 0 ||
            appendCount > 0 ||
            insertCount > 0 ||
            clearCount > 0

    val allowsRemoteRedactionAnimation: Boolean
        get() = setCount > 0 && !hasHistoryOrResetShape
}

data class TimelineWindowUpdate<T>(
    val messages: List<T>,
    val origin: TimelineWindowChangeOrigin,
    val hasOlderInDb: Boolean,
    val hasNewerInDb: Boolean,
    val flushSummary: TimelineFlushSummary? = null
)
