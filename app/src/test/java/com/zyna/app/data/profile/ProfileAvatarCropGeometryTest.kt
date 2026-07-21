package com.zyna.app.data.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileAvatarCropGeometryTest {
    @Test
    fun `default landscape crop is centered square`() {
        val rect = ProfileAvatarCropGeometry.sourceRect(
            crop = ProfileAvatarCropSpec(),
            sourceWidth = 2000,
            sourceHeight = 1000
        )

        assertEquals(500f, rect.left, DELTA)
        assertEquals(0f, rect.top, DELTA)
        assertEquals(1000f, rect.size, DELTA)
    }

    @Test
    fun `default portrait crop is centered square`() {
        val rect = ProfileAvatarCropGeometry.sourceRect(
            crop = ProfileAvatarCropSpec(),
            sourceWidth = 1000,
            sourceHeight = 2000
        )

        assertEquals(0f, rect.left, DELTA)
        assertEquals(500f, rect.top, DELTA)
        assertEquals(1000f, rect.size, DELTA)
    }

    @Test
    fun `zoom keeps gesture focus anchored`() {
        val crop = ProfileAvatarCropGeometry.transform(
            crop = ProfileAvatarCropSpec(),
            sourceWidth = 2000,
            sourceHeight = 1000,
            viewportSize = 500f,
            centroidX = 500f,
            centroidY = 250f,
            panX = 0f,
            panY = 0f,
            zoomFactor = 2f
        )
        val rect = ProfileAvatarCropGeometry.sourceRect(crop, 2000, 1000)

        assertEquals(1000f, rect.left, DELTA)
        assertEquals(1500f, rect.right, DELTA)
        assertEquals(2f, crop.zoom, DELTA)
    }

    @Test
    fun `dragging image right moves crop toward source left`() {
        val crop = ProfileAvatarCropGeometry.transform(
            crop = ProfileAvatarCropSpec(),
            sourceWidth = 2000,
            sourceHeight = 1000,
            viewportSize = 500f,
            centroidX = 250f,
            centroidY = 250f,
            panX = 100f,
            panY = 0f,
            zoomFactor = 1f
        )
        val rect = ProfileAvatarCropGeometry.sourceRect(crop, 2000, 1000)

        assertEquals(300f, rect.left, DELTA)
        assertEquals(0f, rect.top, DELTA)
    }

    @Test
    fun `crop center is clamped so viewport never exposes empty space`() {
        val clamped = ProfileAvatarCropGeometry.clamp(
            crop = ProfileAvatarCropSpec(centerX = -10f, centerY = 10f, zoom = 2f),
            sourceWidth = 1000,
            sourceHeight = 2000
        )
        val rect = ProfileAvatarCropGeometry.sourceRect(clamped, 1000, 2000)

        assertEquals(0f, rect.left, DELTA)
        assertEquals(1500f, rect.top, DELTA)
        assertEquals(2000f, rect.bottom, DELTA)
        assertEquals(500f, rect.size, DELTA)
    }

    @Test
    fun `invalid and excessive zoom values are normalized`() {
        val invalid = ProfileAvatarCropGeometry.clamp(
            crop = ProfileAvatarCropSpec(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN),
            sourceWidth = 1000,
            sourceHeight = 1000
        )
        val excessive = ProfileAvatarCropGeometry.clamp(
            crop = ProfileAvatarCropSpec(0.5f, 0.5f, 100f),
            sourceWidth = 1000,
            sourceHeight = 1000
        )

        assertEquals(ProfileAvatarCropSpec(), invalid)
        assertEquals(MAX_PROFILE_AVATAR_ZOOM, excessive.zoom, DELTA)
    }

    private companion object {
        const val DELTA = 0.001f
    }
}
