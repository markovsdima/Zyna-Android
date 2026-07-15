package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixMessageReaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageActionStoreTest {
    @Test
    fun invalidReactionTargets_areRejected() {
        val recorder = RecordingMessageActionDriver()
        val store = ChatMessageActionStore(recorder.driver)

        assertNull(store.createReactionRequest(TARGET, message(), "   "))
        assertNull(
            store.createReactionRequest(
                TARGET,
                message().copy(eventId = null),
                REACTION_KEY
            )
        )
        assertNull(
            store.createReactionRequest(
                TARGET,
                message().copy(contentType = MatrixMessageContentType.REDACTED),
                REACTION_KEY
            )
        )
        assertNull(
            store.createReactionRequest(
                TARGET,
                message().copy(outgoingEnvelopeId = "outgoing"),
                REACTION_KEY
            )
        )
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun reactionRequest_selectsAddRemoveAndPendingRemovalBehavior() {
        val store = ChatMessageActionStore(RecordingMessageActionDriver().driver)
        val keyWithWhitespace = " $REACTION_KEY "

        assertEquals(
            ChatMessageActionRequest.AddReaction(TARGET, EVENT_ID, keyWithWhitespace),
            store.createReactionRequest(TARGET, message(), keyWithWhitespace)
        )
        assertEquals(
            ChatMessageActionRequest.RemoveReaction(TARGET, EVENT_ID, keyWithWhitespace),
            store.createReactionRequest(
                TARGET,
                message(reactions = listOf(reaction(keyWithWhitespace))),
                keyWithWhitespace
            )
        )
        assertEquals(
            ChatMessageActionRequest.AddReaction(TARGET, EVENT_ID, keyWithWhitespace),
            store.createReactionRequest(
                TARGET,
                message(
                    reactions = listOf(
                        reaction(keyWithWhitespace, isPendingRemoval = true)
                    )
                ),
                keyWithWhitespace
            )
        )
    }

    @Test
    fun addReaction_writesDurableActionBeforeKickingOutbox() = runBlocking {
        val recorder = RecordingMessageActionDriver(addResult = "reaction-id")
        val store = ChatMessageActionStore(recorder.driver)
        val request = ChatMessageActionRequest.AddReaction(TARGET, EVENT_ID, REACTION_KEY)

        val result = store.execute(request)

        assertEquals(ChatMessageActionResult.COMPLETED, result)
        assertEquals(
            listOf(PreparedReactionAdd(TARGET, EVENT_ID, REACTION_KEY, "transaction-1")),
            recorder.preparedAdds
        )
        assertEquals(
            listOf("transaction", "add", "kick:new-reaction:reaction-id"),
            recorder.events
        )
    }

    @Test
    fun rejectedAdd_doesNotKickOutbox() = runBlocking {
        val recorder = RecordingMessageActionDriver(addResult = null)
        val store = ChatMessageActionStore(recorder.driver)

        val result = store.execute(
            ChatMessageActionRequest.AddReaction(TARGET, EVENT_ID, REACTION_KEY)
        )

        assertEquals(ChatMessageActionResult.COMPLETED, result)
        assertEquals(listOf("transaction", "add"), recorder.events)
    }

    @Test
    fun removalKnownByCache_skipsSdkFallback() = runBlocking {
        val recorder = RecordingMessageActionDriver(
            removalResults = listOf("reaction-id")
        )
        val store = ChatMessageActionStore(recorder.driver)

        store.execute(ChatMessageActionRequest.RemoveReaction(TARGET, EVENT_ID, REACTION_KEY))

        assertEquals(
            listOf(
                PreparedReactionRemoval(
                    TARGET,
                    EVENT_ID,
                    REACTION_KEY,
                    reactionEventId = null,
                    transactionId = "transaction-1"
                )
            ),
            recorder.preparedRemovals
        )
        assertEquals(
            listOf("transaction", "remove:null", "kick:new-reaction-removal:reaction-id"),
            recorder.events
        )
    }

    @Test
    fun removalMissingFromCache_resolvesSdkEventAndReusesTransaction() = runBlocking {
        val recorder = RecordingMessageActionDriver(
            removalResults = listOf(null, "reaction-id"),
            foundReactionEventId = "reaction-event"
        )
        val store = ChatMessageActionStore(recorder.driver)

        store.execute(ChatMessageActionRequest.RemoveReaction(TARGET, EVENT_ID, REACTION_KEY))

        assertEquals(
            listOf(
                PreparedReactionRemoval(
                    TARGET,
                    EVENT_ID,
                    REACTION_KEY,
                    reactionEventId = null,
                    transactionId = "transaction-1"
                ),
                PreparedReactionRemoval(
                    TARGET,
                    EVENT_ID,
                    REACTION_KEY,
                    reactionEventId = "reaction-event",
                    transactionId = "transaction-1"
                )
            ),
            recorder.preparedRemovals
        )
        assertEquals(
            listOf(
                "transaction",
                "remove:null",
                "find",
                "remove:reaction-event",
                "kick:new-reaction-removal:reaction-id"
            ),
            recorder.events
        )
    }

    @Test
    fun unresolvedRemoval_doesNotRetryPrepareOrKick() = runBlocking {
        val recorder = RecordingMessageActionDriver(
            removalResults = listOf(null),
            foundReactionEventId = null
        )
        val store = ChatMessageActionStore(recorder.driver)

        val result = store.execute(
            ChatMessageActionRequest.RemoveReaction(TARGET, EVENT_ID, REACTION_KEY)
        )

        assertEquals(ChatMessageActionResult.COMPLETED, result)
        assertEquals(1, recorder.preparedRemovals.size)
        assertEquals(listOf("transaction", "remove:null", "find"), recorder.events)
    }

    @Test
    fun successfulRetry_updatesEnvelopeBeforeKickingOutbox() = runBlocking {
        val recorder = RecordingMessageActionDriver(retryResult = true)
        val store = ChatMessageActionStore(recorder.driver)

        val result = store.execute(
            ChatMessageActionRequest.RetryOutgoing(TARGET, "envelope-id")
        )

        assertEquals(ChatMessageActionResult.COMPLETED, result)
        assertEquals(
            listOf(OutgoingEnvelopeAction(TARGET, "envelope-id")),
            recorder.retriedEnvelopes
        )
        assertEquals(
            listOf("retry", "kick:manual-retry:envelope-id"),
            recorder.events
        )
    }

    @Test
    fun rejectedRetry_returnsNotAppliedAndDoesNotKick() = runBlocking {
        val recorder = RecordingMessageActionDriver(retryResult = false)
        val store = ChatMessageActionStore(recorder.driver)

        val result = store.execute(
            ChatMessageActionRequest.RetryOutgoing(TARGET, "envelope-id")
        )

        assertEquals(ChatMessageActionResult.NOT_APPLIED, result)
        assertEquals(listOf("retry"), recorder.events)
    }

    @Test
    fun discard_reportsWhetherFailedEnvelopeWasDeleted() = runBlocking {
        val appliedRecorder = RecordingMessageActionDriver(discardResult = true)
        val rejectedRecorder = RecordingMessageActionDriver(discardResult = false)

        val applied = ChatMessageActionStore(appliedRecorder.driver).execute(
            ChatMessageActionRequest.DiscardOutgoing(TARGET, "applied-envelope")
        )
        val rejected = ChatMessageActionStore(rejectedRecorder.driver).execute(
            ChatMessageActionRequest.DiscardOutgoing(TARGET, "missing-envelope")
        )

        assertEquals(ChatMessageActionResult.COMPLETED, applied)
        assertEquals(ChatMessageActionResult.NOT_APPLIED, rejected)
        assertEquals(
            listOf(OutgoingEnvelopeAction(TARGET, "applied-envelope")),
            appliedRecorder.discardedEnvelopes
        )
        assertEquals(listOf("discard"), appliedRecorder.events)
        assertEquals(listOf("discard"), rejectedRecorder.events)
    }

    @Test
    fun redactionRequest_normalizesIdsAndKeepsOnlyEligibleMessages() {
        val recorder = RecordingMessageActionDriver()
        val store = ChatMessageActionStore(recorder.driver)
        val eligible = message(id = "eligible", isOwn = true)
        val blankEvent = message(id = "blank-event", eventId = "", isOwn = true)
        val notOwn = message(id = "not-own", isOwn = false)
        val noEvent = message(id = "no-event", eventId = null, isOwn = true)
        val redacted = message(
            id = "redacted",
            isOwn = true,
            contentType = MatrixMessageContentType.REDACTED
        )
        val failed = message(
            id = "failed",
            isOwn = true,
            deliveryState = MatrixMessageDeliveryState.FAILED
        )
        val available = listOf(eligible, blankEvent, notOwn, noEvent, redacted, failed)

        val request = store.createRedactionRequest(
            target = TARGET,
            messageIds = listOf(
                " eligible ",
                "eligible",
                "",
                "missing",
                "not-own",
                "no-event",
                "redacted",
                "failed",
                "blank-event"
            ),
            availableMessages = available
        )

        assertEquals(
            ChatMessageActionRequest.RedactMessages(
                TARGET,
                listOf(eligible, blankEvent)
            ),
            request
        )
        assertNull(
            store.createRedactionRequest(
                target = TARGET,
                messageIds = listOf(" ", "missing", "not-own"),
                availableMessages = available
            )
        )
        assertEquals(0, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun singleRedaction_writesEnvelopeBeforeTargetedKick() = runBlocking {
        val recorder = RecordingMessageActionDriver(redactionResults = listOf(true))
        val store = ChatMessageActionStore(recorder.driver)
        val targetMessage = message(id = "message-a", isOwn = true)

        val result = store.execute(
            ChatMessageActionRequest.RedactMessages(TARGET, listOf(targetMessage))
        )

        assertEquals(ChatMessageActionResult.COMPLETED, result)
        assertEquals(
            listOf(
                RedactionAttempt(
                    target = TARGET,
                    envelopeId = "redaction:id-1",
                    transactionId = "transaction-1",
                    targetMessage = targetMessage
                )
            ),
            recorder.redactionAttempts
        )
        assertEquals(
            listOf(
                "id",
                "transaction",
                "redaction:redaction:id-1:message-a",
                "kick:new-redaction:redaction:id-1"
            ),
            recorder.events
        )
    }

    @Test
    fun batchRedaction_kicksOnceWithoutEnvelopeFilter() = runBlocking {
        val recorder = RecordingMessageActionDriver(
            redactionResults = listOf(true, true)
        )
        val store = ChatMessageActionStore(recorder.driver)

        val result = store.execute(
            ChatMessageActionRequest.RedactMessages(
                TARGET,
                listOf(
                    message(id = "message-a", isOwn = true),
                    message(id = "message-b", isOwn = true)
                )
            )
        )

        assertEquals(ChatMessageActionResult.COMPLETED, result)
        assertEquals(2, recorder.redactionAttempts.size)
        assertEquals("kick:new-redactions:null", recorder.events.last())
    }

    @Test
    fun partialRedaction_targetsOnlyCreatedEnvelope() = runBlocking {
        val recorder = RecordingMessageActionDriver(
            redactionResults = listOf(false, true)
        )
        val store = ChatMessageActionStore(recorder.driver)

        val result = store.execute(
            ChatMessageActionRequest.RedactMessages(
                TARGET,
                listOf(
                    message(id = "rejected", isOwn = true),
                    message(id = "created", isOwn = true)
                )
            )
        )

        assertEquals(ChatMessageActionResult.COMPLETED, result)
        assertEquals(
            "kick:new-redaction:redaction:id-2",
            recorder.events.last()
        )
    }

    @Test
    fun rejectedRedactions_returnNotAppliedWithoutKick() = runBlocking {
        val recorder = RecordingMessageActionDriver(
            redactionResults = listOf(false, false)
        )
        val store = ChatMessageActionStore(recorder.driver)

        val result = store.execute(
            ChatMessageActionRequest.RedactMessages(
                TARGET,
                listOf(
                    message(id = "message-a", isOwn = true),
                    message(id = "message-b", isOwn = true)
                )
            )
        )

        assertEquals(ChatMessageActionResult.NOT_APPLIED, result)
        assertEquals(2, recorder.redactionAttempts.size)
        assertFalse(recorder.events.any { it.startsWith("kick:") })
    }

    private companion object {
        val TARGET = ChatMessageActionTarget(
            userId = "@me:example.org",
            roomId = "!room:example.org"
        )
        const val EVENT_ID = "event-id"
        const val REACTION_KEY = "👍"

        fun message(
            id: String = "message-id",
            eventId: String? = EVENT_ID,
            isOwn: Boolean = false,
            contentType: MatrixMessageContentType = MatrixMessageContentType.TEXT,
            deliveryState: MatrixMessageDeliveryState = MatrixMessageDeliveryState.SENT,
            reactions: List<MatrixMessageReaction> = emptyList()
        ): MatrixChatMessage {
            return MatrixChatMessage(
                id = id,
                eventId = eventId,
                sender = "@alice:example.org",
                body = "Hello",
                timestampMillis = 1L,
                isOwn = isOwn,
                contentType = contentType,
                deliveryState = deliveryState,
                reactions = reactions
            )
        }

        fun reaction(
            key: String,
            isPendingRemoval: Boolean = false
        ): MatrixMessageReaction {
            return MatrixMessageReaction(
                key = key,
                senders = emptyList(),
                isOwn = true,
                isPendingRemoval = isPendingRemoval
            )
        }
    }
}

private class RecordingMessageActionDriver(
    private val addResult: String? = "add-id",
    private val removalResults: List<String?> = listOf("remove-id"),
    private val foundReactionEventId: String? = null,
    private val retryResult: Boolean = true,
    private val discardResult: Boolean = true,
    private val redactionResults: List<Boolean> = listOf(true)
) {
    var idCallCount = 0
        private set
    var transactionCallCount = 0
        private set
    val preparedAdds = mutableListOf<PreparedReactionAdd>()
    val preparedRemovals = mutableListOf<PreparedReactionRemoval>()
    val retriedEnvelopes = mutableListOf<OutgoingEnvelopeAction>()
    val discardedEnvelopes = mutableListOf<OutgoingEnvelopeAction>()
    val redactionAttempts = mutableListOf<RedactionAttempt>()
    val events = mutableListOf<String>()

    val driver = ChatMessageActionDriver(
        nextId = {
            idCallCount += 1
            events += "id"
            "id-$idCallCount"
        },
        prepareTransactionId = {
            transactionCallCount += 1
            events += "transaction"
            "transaction-$transactionCallCount"
        },
        prepareReactionAdd = { target, targetEventId, reactionKey, transactionId ->
            preparedAdds += PreparedReactionAdd(
                target,
                targetEventId,
                reactionKey,
                transactionId
            )
            events += "add"
            addResult
        },
        prepareReactionRemoval = {
                target, targetEventId, reactionKey, reactionEventId, transactionId ->
            preparedRemovals += PreparedReactionRemoval(
                target,
                targetEventId,
                reactionKey,
                reactionEventId,
                transactionId
            )
            events += "remove:$reactionEventId"
            removalResults.getOrNull(preparedRemovals.lastIndex)
        },
        findOwnReactionEventId = { _, _, _ ->
            events += "find"
            foundReactionEventId
        },
        retryOutgoing = { target, envelopeId ->
            retriedEnvelopes += OutgoingEnvelopeAction(target, envelopeId)
            events += "retry"
            retryResult
        },
        discardOutgoing = { target, envelopeId ->
            discardedEnvelopes += OutgoingEnvelopeAction(target, envelopeId)
            events += "discard"
            discardResult
        },
        createRedactionEnvelope = {
                target, envelopeId, transactionId, targetMessage ->
            redactionAttempts += RedactionAttempt(
                target,
                envelopeId,
                transactionId,
                targetMessage
            )
            events += "redaction:$envelopeId:${targetMessage.id}"
            redactionResults.getOrNull(redactionAttempts.lastIndex) ?: false
        },
        kickOutbox = { reason, envelopeId ->
            events += "kick:$reason:$envelopeId"
        }
    )
}

private data class PreparedReactionAdd(
    val target: ChatMessageActionTarget,
    val targetEventId: String,
    val reactionKey: String,
    val transactionId: String
)

private data class PreparedReactionRemoval(
    val target: ChatMessageActionTarget,
    val targetEventId: String,
    val reactionKey: String,
    val reactionEventId: String?,
    val transactionId: String
)

private data class OutgoingEnvelopeAction(
    val target: ChatMessageActionTarget,
    val envelopeId: String
)

private data class RedactionAttempt(
    val target: ChatMessageActionTarget,
    val envelopeId: String,
    val transactionId: String,
    val targetMessage: MatrixChatMessage
)
