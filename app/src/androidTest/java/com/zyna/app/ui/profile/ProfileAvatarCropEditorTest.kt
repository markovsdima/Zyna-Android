package com.zyna.app.ui.profile

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.zyna.app.data.profile.ProfileAvatarCropSpec
import com.zyna.app.ui.theme.ZynaAndroidTheme
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileAvatarCropEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var bitmap: Bitmap? = null

    @After
    fun tearDown() {
        bitmap?.takeUnless { it.isRecycled }?.recycle()
    }

    @Test
    fun dragOutsideTheCropCircleChangesTheExportedCrop() {
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.MAGENTA)
        }
        bitmap = source
        var confirmedCrop: ProfileAvatarCropSpec? = null
        composeRule.setContent {
            ZynaAndroidTheme(darkTheme = true, dynamicColor = false) {
                ProfileAvatarCropEditor(
                    bitmap = source,
                    isProcessing = false,
                    errorMessage = null,
                    onDismiss = {},
                    onConfirm = { confirmedCrop = it }
                )
            }
        }

        composeRule.onNodeWithTag(PROFILE_AVATAR_CROP_VIEWPORT_TEST_TAG)
            .performTouchInput {
                val outsideCircleY = 4f
                swipe(
                    start = Offset(center.x - 80f, outsideCircleY),
                    end = Offset(center.x + 80f, outsideCircleY),
                    durationMillis = 300
                )
            }
        composeRule.onNodeWithTag(PROFILE_AVATAR_CROP_DONE_TEST_TAG)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertNotNull(confirmedCrop)
            assertTrue(requireNotNull(confirmedCrop).centerX < 0.5f)
        }
    }
}
