package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageReaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private companion object {
        val TARGET = ChatMessageActionTarget(
            userId = "@me:example.org",
            roomId = "!room:example.org"
        )
        const val EVENT_ID = "event-id"
        const val REACTION_KEY = "👍"

        fun message(
            reactions: List<MatrixMessageReaction> = emptyList()
        ): MatrixChatMessage {
            return MatrixChatMessage(
                id = "message-id",
                eventId = EVENT_ID,
                sender = "@alice:example.org",
                body = "Hello",
                timestampMillis = 1L,
                isOwn = false,
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
    private val discardResult: Boolean = true
) {
    var transactionCallCount = 0
        private set
    val preparedAdds = mutableListOf<PreparedReactionAdd>()
    val preparedRemovals = mutableListOf<PreparedReactionRemoval>()
    val retriedEnvelopes = mutableListOf<OutgoingEnvelopeAction>()
    val discardedEnvelopes = mutableListOf<OutgoingEnvelopeAction>()
    val events = mutableListOf<String>()

    val driver = ChatMessageActionDriver(
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
