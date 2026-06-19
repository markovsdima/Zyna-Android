package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMediaGroupItem
import com.zyna.app.data.matrix.MatrixMediaGroupPresentation
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.messaging.CaptionMode
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupInfo
import com.zyna.app.data.messaging.normalizedMessageCaption

internal fun List<MatrixChatMessage>.withMediaGroupPresentation(
    hasNewerBoundary: Boolean,
    hasOlderBoundary: Boolean
): List<MatrixChatMessage> {
    if (isEmpty()) return this

    val result = mutableListOf<MatrixChatMessage>()
    var index = 0
    while (index < size) {
        val firstMessage = this[index]
        val firstGroup = firstMessage.zynaAttributes.mediaGroup
        if (firstMessage.contentType != MatrixMessageContentType.IMAGE || firstGroup == null) {
            result += firstMessage
            index += 1
            continue
        }

        val runStart = index
        var runEnd = index
        while (runEnd + 1 < size && sharesMediaGroup(this[runEnd], this[runEnd + 1])) {
            runEnd += 1
        }

        val sourceMessages = subList(runStart, runEnd + 1)
        val sharesWithNewerBoundary = runStart == 0 && hasNewerBoundary
        val sharesWithOlderBoundary = runEnd == lastIndex && hasOlderBoundary
        val captionCollapse = groupCaptionCollapse(sourceMessages.map { it.imageInfo?.caption })
        val canRenderComposite = canRenderCompleteMediaGroup(
            messages = sourceMessages,
            group = firstGroup,
            sharesWithNewerBoundary = sharesWithNewerBoundary,
            sharesWithOlderBoundary = sharesWithOlderBoundary,
            canCollapseCaption = captionCollapse.canCollapse
        )
        val canRenderIncomingPlaceholder = canRenderIncomingMediaGroupPlaceholder(
            messages = sourceMessages,
            group = firstGroup,
            sharesWithNewerBoundary = sharesWithNewerBoundary,
            sharesWithOlderBoundary = sharesWithOlderBoundary
        )
        val carrierOffset = if (firstGroup.captionPlacement == CaptionPlacement.TOP) {
            sourceMessages.lastIndex
        } else {
            0
        }

        when {
            canRenderComposite -> {
                val items = sourceMessages.mediaGroupItems()
                for (offset in sourceMessages.indices) {
                    if (offset == carrierOffset) {
                        val message = sourceMessages[offset]
                        result += message.copy(
                            mediaGroupPresentation = MatrixMediaGroupPresentation(
                                id = firstGroup.id,
                                totalHint = firstGroup.total,
                                caption = captionCollapse.caption,
                                captionPlacement = firstGroup.captionPlacement,
                                layoutOverride = firstGroup.layoutOverride,
                                suppressIndividualCaption = captionCollapse.caption != null,
                                items = items,
                                rendersCompositeBubble = true,
                                hidesStandaloneBubble = false
                            )
                        )
                    }
                }
            }
            canRenderIncomingPlaceholder -> {
                for (offset in sourceMessages.indices) {
                    if (offset == carrierOffset) {
                        val anchor = sourceMessages[offset]
                        result += MatrixChatMessage(
                            id = "incoming-assembly:${firstGroup.id}",
                            sender = anchor.sender,
                            senderDisplayName = anchor.senderDisplayName,
                            body = incomingAssemblyPlaceholderBody(
                                visibleCount = sourceMessages.size,
                                totalCount = firstGroup.total
                            ),
                            timestampMillis = anchor.timestampMillis,
                            isOwn = false,
                            contentType = MatrixMessageContentType.NOTICE,
                            deliveryState = MatrixMessageDeliveryState.SENT
                        )
                    }
                }
            }
            else -> {
                result += sourceMessages
            }
        }

        index = runEnd + 1
    }

    return result
}

private fun canRenderCompleteMediaGroup(
    messages: List<MatrixChatMessage>,
    group: MediaGroupInfo,
    sharesWithNewerBoundary: Boolean,
    sharesWithOlderBoundary: Boolean,
    canCollapseCaption: Boolean
): Boolean {
    if (messages.size <= 1 || group.total != messages.size) return false
    if (sharesWithNewerBoundary || sharesWithOlderBoundary || !canCollapseCaption) return false
    val seenIndices = mediaGroupMemberIndicesOrNull(messages, group) ?: return false
    return seenIndices.size == group.total &&
        seenIndices.minOrNull() == 0 &&
        seenIndices.maxOrNull() == group.total - 1
}

private fun canRenderIncomingMediaGroupPlaceholder(
    messages: List<MatrixChatMessage>,
    group: MediaGroupInfo,
    sharesWithNewerBoundary: Boolean,
    sharesWithOlderBoundary: Boolean
): Boolean {
    if (messages.any { it.isOwn }) return false
    if (group.total <= 1 || messages.size >= group.total) return false
    if (sharesWithNewerBoundary || sharesWithOlderBoundary) return false
    return mediaGroupMemberIndicesOrNull(messages, group) != null
}

private fun mediaGroupMemberIndicesOrNull(
    messages: List<MatrixChatMessage>,
    expectedGroup: MediaGroupInfo
): Set<Int>? {
    val seenIndices = mutableSetOf<Int>()
    for (message in messages) {
        val group = message.zynaAttributes.mediaGroup ?: return null
        if (
            message.contentType != MatrixMessageContentType.IMAGE ||
            group.id != expectedGroup.id ||
            group.total != expectedGroup.total ||
            group.captionMode != CaptionMode.REPLICATED ||
            group.captionMode != expectedGroup.captionMode ||
            group.captionPlacement != expectedGroup.captionPlacement ||
            group.layoutOverride != expectedGroup.layoutOverride ||
            group.index !in 0 until group.total ||
            !seenIndices.add(group.index)
        ) {
            return null
        }
    }
    return seenIndices
}

private fun List<MatrixChatMessage>.mediaGroupItems(): List<MatrixMediaGroupItem> {
    return sortedWith(
        compareBy<MatrixChatMessage> { it.zynaAttributes.mediaGroup?.index ?: Int.MAX_VALUE }
            .thenBy { it.timestampMillis }
            .thenBy { it.id }
    ).mapNotNull { message ->
        val imageInfo = message.imageInfo ?: return@mapNotNull null
        MatrixMediaGroupItem(
            messageId = message.id,
            eventId = message.eventId,
            transactionId = message.transactionId,
            imageInfo = imageInfo,
            deliveryState = message.deliveryState
        )
    }
}

private fun sharesMediaGroup(lhs: MatrixChatMessage, rhs: MatrixChatMessage): Boolean {
    val lhsGroup = lhs.zynaAttributes.mediaGroup ?: return false
    val rhsGroup = rhs.zynaAttributes.mediaGroup ?: return false
    return lhs.contentType == MatrixMessageContentType.IMAGE &&
        rhs.contentType == MatrixMessageContentType.IMAGE &&
        lhs.sender == rhs.sender &&
        lhsGroup.id == rhsGroup.id
}

private data class CaptionCollapse(
    val caption: String?,
    val canCollapse: Boolean
)

private fun groupCaptionCollapse(captions: List<String?>): CaptionCollapse {
    val first = captions.firstOrNull()
    val normalizedCaption = first.normalizedMessageCaption()
    for (caption in captions.drop(1)) {
        if (caption.normalizedMessageCaption() != normalizedCaption) {
            return CaptionCollapse(caption = null, canCollapse = false)
        }
    }
    return CaptionCollapse(caption = normalizedCaption, canCollapse = true)
}

private fun incomingAssemblyPlaceholderBody(
    visibleCount: Int,
    totalCount: Int
): String {
    return "Receiving photo $visibleCount of $totalCount"
}
