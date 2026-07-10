package com.zyna.app.data.matrix

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineMembership
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineNotification
import com.zyna.app.data.local.TimelineFlushSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem

data class MatrixTimelineUpdate(
    val messages: List<MatrixChatMessage>,
    val flushSummary: TimelineFlushSummary,
    val callNotifications: List<MatrixRtcCallTimelineNotification> = emptyList(),
    val callMemberships: List<MatrixRtcCallTimelineMembership> = emptyList()
)

data class MatrixTimelineMappedItem(
    val message: MatrixChatMessage? = null,
    val callNotification: MatrixRtcCallTimelineNotification? = null,
    val callMembership: MatrixRtcCallTimelineMembership? = null
)

internal class MatrixTimelineDiffBatcher(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val mapTimelineItem: (TimelineItem) -> MatrixTimelineMappedItem,
    private val onFlush: (MatrixTimelineUpdate) -> Unit
) {
    private class ShadowPosition

    private data class MutableTimelineFlushSummary(
        var appendCount: Int = 0,
        var pushBackCount: Int = 0,
        var pushFrontCount: Int = 0,
        var insertCount: Int = 0,
        var setCount: Int = 0,
        var removeCount: Int = 0,
        var resetCount: Int = 0,
        var truncateCount: Int = 0,
        var clearCount: Int = 0
    ) {
        fun toSummary(
            upsertCount: Int,
            redactedUpsertCount: Int
        ): TimelineFlushSummary {
            return TimelineFlushSummary(
                appendCount = appendCount,
                pushBackCount = pushBackCount,
                pushFrontCount = pushFrontCount,
                insertCount = insertCount,
                setCount = setCount,
                removeCount = removeCount,
                resetCount = resetCount,
                truncateCount = truncateCount,
                clearCount = clearCount,
                upsertCount = upsertCount,
                deleteCount = 0,
                redactedUpsertCount = redactedUpsertCount
            )
        }
    }

    private val lock = Any()
    private val shadowPositions = mutableListOf<ShadowPosition>()
    private val pendingUpserts = mutableListOf<MatrixChatMessage>()
    private val pendingCallNotifications = mutableListOf<MatrixRtcCallTimelineNotification>()
    private val pendingCallMemberships = mutableListOf<MatrixRtcCallTimelineMembership>()
    private var pendingSummary = MutableTimelineFlushSummary()
    private var hasPendingDiffs = false
    private var flushJob: Job? = null

    fun receive(diffs: List<TimelineDiff>) {
        if (diffs.isEmpty()) {
            return
        }

        synchronized(lock) {
            hasPendingDiffs = true
            diffs.forEach { diff -> enqueueDiff(diff) }
        }
        scheduleFlush()
    }

    fun cancel() {
        flushJob?.cancel()
        flushJob = null
        synchronized(lock) {
            shadowPositions.clear()
            pendingUpserts.clear()
            pendingCallNotifications.clear()
            pendingCallMemberships.clear()
            pendingSummary = MutableTimelineFlushSummary()
            hasPendingDiffs = false
        }
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(debounceMillis)
            flush()
        }
    }

    private fun flush() {
        val update = synchronized(lock) {
            if (!hasPendingDiffs) {
                null
            } else {
                hasPendingDiffs = false
                val rawUpsertCount = pendingUpserts.size
                val upserts = pendingUpserts.coalescedById()
                val summary = pendingSummary.toSummary(
                    upsertCount = rawUpsertCount,
                    redactedUpsertCount = upserts.count {
                        it.contentType == MatrixMessageContentType.REDACTED
                    }
                )
                pendingUpserts.clear()
                val callNotifications = pendingCallNotifications.coalescedCallNotificationsByEventId()
                val callMemberships = pendingCallMemberships.coalescedCallMembershipsByEventId()
                pendingCallNotifications.clear()
                pendingCallMemberships.clear()
                pendingSummary = MutableTimelineFlushSummary()
                MatrixTimelineUpdate(
                    messages = upserts,
                    flushSummary = summary,
                    callNotifications = callNotifications,
                    callMemberships = callMemberships
                )
            }
        } ?: return

        onFlush(update)
    }

    private fun enqueueDiff(diff: TimelineDiff) {
        recordSummary(diff)
        when (diff) {
            is TimelineDiff.Append -> diff.values.forEach { item -> appendItem(item) }
            is TimelineDiff.PushBack -> appendItem(diff.value)
            is TimelineDiff.PushFront -> insertItem(0, diff.value)
            is TimelineDiff.Insert -> insertItem(diff.index.toInt(), diff.value)
            is TimelineDiff.Set -> setItem(diff.index.toInt(), diff.value)
            is TimelineDiff.Remove -> removeAt(diff.index.toInt())
            TimelineDiff.PopBack -> removeLast()
            TimelineDiff.PopFront -> removeFirst()
            is TimelineDiff.Reset -> {
                shadowPositions.clear()
                diff.values.forEach { item -> appendItem(item) }
            }
            is TimelineDiff.Truncate -> truncate(diff.length.toInt())
            TimelineDiff.Clear -> shadowPositions.clear()
        }
    }

    private fun recordSummary(diff: TimelineDiff) {
        when (diff) {
            is TimelineDiff.Append -> pendingSummary.appendCount += 1
            is TimelineDiff.PushBack -> pendingSummary.pushBackCount += 1
            is TimelineDiff.PushFront -> pendingSummary.pushFrontCount += 1
            is TimelineDiff.Insert -> pendingSummary.insertCount += 1
            is TimelineDiff.Set -> pendingSummary.setCount += 1
            is TimelineDiff.Remove -> pendingSummary.removeCount += 1
            TimelineDiff.PopBack -> pendingSummary.removeCount += 1
            TimelineDiff.PopFront -> pendingSummary.removeCount += 1
            is TimelineDiff.Reset -> pendingSummary.resetCount += 1
            is TimelineDiff.Truncate -> pendingSummary.truncateCount += 1
            TimelineDiff.Clear -> pendingSummary.clearCount += 1
        }
    }

    private fun appendItem(item: TimelineItem) {
        val mappedItem = mapTimelineItem(item)
        shadowPositions.add(ShadowPosition())
        enqueueMappedItem(mappedItem)
    }

    private fun insertItem(index: Int, item: TimelineItem) {
        if (index !in 0..shadowPositions.size) {
            return
        }

        val mappedItem = mapTimelineItem(item)
        shadowPositions.add(index, ShadowPosition())
        enqueueMappedItem(mappedItem)
    }

    private fun setItem(index: Int, item: TimelineItem) {
        if (index !in shadowPositions.indices) {
            return
        }

        val mappedItem = mapTimelineItem(item)
        shadowPositions[index] = ShadowPosition()
        enqueueMappedItem(mappedItem)
    }

    private fun enqueueMappedItem(mappedItem: MatrixTimelineMappedItem) {
        mappedItem.message?.let { pendingUpserts.add(it) }
        mappedItem.callNotification?.let { pendingCallNotifications.add(it) }
        mappedItem.callMembership?.let { pendingCallMemberships.add(it) }
    }

    private fun removeAt(index: Int) {
        if (index in shadowPositions.indices) {
            shadowPositions.removeAt(index)
        }
    }

    private fun removeFirst() {
        if (shadowPositions.isNotEmpty()) {
            shadowPositions.removeAt(0)
        }
    }

    private fun removeLast() {
        if (shadowPositions.isNotEmpty()) {
            shadowPositions.removeAt(shadowPositions.lastIndex)
        }
    }

    private fun truncate(length: Int) {
        val targetLength = length.coerceAtLeast(0)
        while (shadowPositions.size > targetLength) {
            shadowPositions.removeAt(shadowPositions.lastIndex)
        }
    }

    private fun List<MatrixChatMessage>.coalescedById(): List<MatrixChatMessage> {
        val messagesById = LinkedHashMap<String, MatrixChatMessage>()
        forEach { message ->
            messagesById[message.id] = message
        }
        return messagesById.values.sortedWith(
            compareBy<MatrixChatMessage> { it.timestampMillis }.thenBy { it.id }
        )
    }

    private fun List<MatrixRtcCallTimelineNotification>.coalescedCallNotificationsByEventId():
        List<MatrixRtcCallTimelineNotification> {
        val callsByEventId = LinkedHashMap<String, MatrixRtcCallTimelineNotification>()
        forEach { notification ->
            callsByEventId[notification.eventId] = notification
        }
        return callsByEventId.values.sortedWith(
            compareBy<MatrixRtcCallTimelineNotification> { it.timestampMillis }
                .thenBy { it.eventId }
        )
    }

    private fun List<MatrixRtcCallTimelineMembership>.coalescedCallMembershipsByEventId():
        List<MatrixRtcCallTimelineMembership> {
        val membershipsByEventId = LinkedHashMap<String, MatrixRtcCallTimelineMembership>()
        forEach { membership ->
            membershipsByEventId[membership.eventId] = membership
        }
        return membershipsByEventId.values.sortedWith(
            compareBy<MatrixRtcCallTimelineMembership> { it.timestampMillis }
                .thenBy { it.eventId }
        )
    }
}
