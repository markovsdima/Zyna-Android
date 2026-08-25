package com.zyna.app.data.calls.matrixrtc

/** MatrixRTC events a regular room member must be able to publish to join and ring a call. */
internal object MatrixRtcRoomPowerLevelPermissions {
    val participantEventOverrides: Map<String, Int> = listOf(
        MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
        MatrixRtcRawMembershipEvent.RTC_MEMBER_EVENT_TYPE,
        MatrixRtcCallNotificationContent.EVENT_TYPE,
        MatrixRtcLegacyCallNotifyContent.EVENT_TYPE
    ).associateWith { DEFAULT_MEMBER_POWER_LEVEL }

    private const val DEFAULT_MEMBER_POWER_LEVEL = 0
}
