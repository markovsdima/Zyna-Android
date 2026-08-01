package com.zyna.app.ui.spaces

internal data class SpacePaginationStatus(
    val spaceId: String,
    val loadedRoomCount: Int,
    val isKnown: Boolean,
    val isPaginating: Boolean,
    val endReached: Boolean
) {
    val canLoadMore: Boolean
        get() = isKnown && !isPaginating && !endReached
}

/**
 * Allows at most one post-layout auto-pagination check for the same loaded page.
 */
internal class SpaceAutoPaginationGate {
    private data class PageKey(
        val spaceId: String,
        val loadedRoomCount: Int
    )

    private var scheduledPage: PageKey? = null

    fun shouldSchedule(status: SpacePaginationStatus): Boolean {
        if (!status.canLoadMore) {
            scheduledPage = null
            return false
        }
        val page = status.pageKey()
        if (scheduledPage == page) return false
        scheduledPage = page
        return true
    }

    fun shouldRequestAfterLayout(
        status: SpacePaginationStatus,
        canScrollForward: Boolean
    ): Boolean {
        return status.canLoadMore &&
            scheduledPage == status.pageKey() &&
            !canScrollForward
    }

    private fun SpacePaginationStatus.pageKey(): PageKey {
        return PageKey(
            spaceId = spaceId,
            loadedRoomCount = loadedRoomCount
        )
    }
}
