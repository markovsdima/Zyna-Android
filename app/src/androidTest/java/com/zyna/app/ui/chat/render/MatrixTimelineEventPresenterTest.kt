package com.zyna.app.ui.chat.render

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMembershipEventChange
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixRoomStateChange
import com.zyna.app.data.matrix.MatrixRtcCallEventDetails
import com.zyna.app.data.matrix.MatrixSystemEventDetails
import com.zyna.app.ui.time.TimeTextFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatrixTimelineEventPresenterTest {
    private lateinit var englishContext: Context

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(targetContext.resources.configuration).apply {
            setLocales(LocaleList(Locale.ENGLISH))
        }
        englishContext = targetContext.createConfigurationContext(configuration)
    }

    @Test
    fun kickedCurrentUserWithReason_usesTargetYouWording() {
        val message = systemMessage(
            sender = "@moderator:example.org",
            senderDisplayName = "Moderator",
            details = MatrixSystemEventDetails.Membership(
                userId = CURRENT_USER_ID,
                userDisplayName = "Current User",
                change = MatrixMembershipEventChange.KICKED,
                reason = "room rules"
            )
        )

        val result = presenter().present(message, currentUserId = CURRENT_USER_ID)

        assertEquals("Moderator removed you: room rules", result.text.toString())
        assertEquals(SystemEventRenderKind.SYSTEM_EVENT, result.kind)
    }

    @Test
    fun ownThirdPartyInvite_usesInviteeInsteadOfSender() {
        val message = systemMessage(
            sender = CURRENT_USER_ID,
            senderDisplayName = "Current User",
            isOwn = true,
            details = MatrixSystemEventDetails.RoomState(
                stateKey = "invite-token",
                change = MatrixRoomStateChange.ThirdPartyInvite(displayName = "Guest")
            )
        )

        val result = presenter().present(message, currentUserId = CURRENT_USER_ID)

        assertEquals("You invited Guest", result.text.toString())
    }

    @Test
    fun declinedVideoCall_usesNegativeVideoIconAndInjectedLocalizedTime() {
        val message = MatrixChatMessage(
            id = "call-event",
            eventId = "call-event",
            sender = "@caller:example.org",
            body = "",
            timestampMillis = 0L,
            isOwn = false,
            contentType = MatrixMessageContentType.MATRIX_RTC_CALL,
            matrixRtcCallDetails = MatrixRtcCallEventDetails(
                parentEventId = null,
                callIntent = "m.video",
                notificationType = MatrixRtcCallNotificationType.RING,
                expiresAtMillis = null,
                declinedBy = emptyList(),
                outcome = MatrixRtcCallHistoryOutcome.DECLINED
            )
        )

        val result = presenter().present(message, currentUserId = CURRENT_USER_ID)

        assertEquals("Call declined · 12:00 AM", result.text.toString())
        assertEquals("Call declined · 12:00 AM", result.accessibilityText.toString())
        assertEquals(SystemEventRenderKind.CALL_EVENT, result.kind)
        assertEquals(SystemEventLeadingIcon.VIDEO_NEGATIVE, result.leadingIcon)
    }

    @Test
    fun legacyCallWithoutIntent_usesAudioIcon() {
        val message = MatrixChatMessage(
            id = "legacy-call-event",
            eventId = "legacy-call-event",
            sender = "@caller:example.org",
            body = "",
            timestampMillis = 0L,
            isOwn = false,
            contentType = MatrixMessageContentType.MATRIX_RTC_CALL,
            matrixRtcCallDetails = MatrixRtcCallEventDetails(
                parentEventId = null,
                callIntent = null,
                notificationType = MatrixRtcCallNotificationType.RING,
                expiresAtMillis = null,
                declinedBy = emptyList(),
                outcome = MatrixRtcCallHistoryOutcome.STARTED
            )
        )

        val result = presenter().present(message, currentUserId = CURRENT_USER_ID)

        assertEquals("Call started · 12:00 AM", result.text.toString())
        assertEquals(SystemEventRenderKind.CALL_EVENT, result.kind)
        assertEquals(SystemEventLeadingIcon.PHONE, result.leadingIcon)
    }

    private fun presenter(): MatrixTimelineEventPresenter {
        return MatrixTimelineEventPresenter(
            context = englishContext,
            timeTextFormatter = TimeTextFormatter { timestampMillis ->
                assertEquals(0L, timestampMillis)
                "12:00 AM"
            }
        )
    }

    private fun systemMessage(
        sender: String,
        senderDisplayName: String?,
        details: MatrixSystemEventDetails,
        isOwn: Boolean = false
    ): MatrixChatMessage {
        return MatrixChatMessage(
            id = "system-event",
            eventId = "system-event",
            sender = sender,
            senderDisplayName = senderDisplayName,
            body = "",
            timestampMillis = 0L,
            isOwn = isOwn,
            contentType = MatrixMessageContentType.SYSTEM_EVENT,
            systemEventDetails = details
        )
    }

    private companion object {
        const val CURRENT_USER_ID = "@me:example.org"
    }
}
