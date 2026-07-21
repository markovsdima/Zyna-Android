package com.zyna.app.data.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAvatarPreprocessorInstrumentedTest {
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        testDirectory = File(
            context.cacheDir,
            "profile-avatar-preprocessor-${UUID.randomUUID()}"
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun processExportsTheRequestedSquareCrop() {
        val source = File(testDirectory, "two-colors.png")
        Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also { bitmap ->
            val pixels = IntArray(400 * 200) { index ->
                if (index % 400 < 200) Color.RED else Color.BLUE
            }
            bitmap.setPixels(pixels, 0, 400, 0, 0, 400, 200)
            source.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }

        val leftDraft = ProfileAvatarPreprocessor.process(
            sourceFile = source,
            crop = ProfileAvatarCropSpec(centerX = 0.25f),
            outputDir = File(testDirectory, "output")
        )
        val rightDraft = ProfileAvatarPreprocessor.process(
            sourceFile = source,
            crop = ProfileAvatarCropSpec(centerX = 0.75f),
            outputDir = File(testDirectory, "output")
        )

        val left = requireNotNull(BitmapFactory.decodeFile(leftDraft.localPath))
        val right = requireNotNull(BitmapFactory.decodeFile(rightDraft.localPath))
        try {
            assertEquals(768, left.width)
            assertEquals(768, left.height)
            assertEquals(768, right.width)
            assertEquals(768, right.height)
            assertTrue(Color.red(left.getPixel(384, 384)) > 200)
            assertTrue(Color.blue(left.getPixel(384, 384)) < 40)
            assertTrue(Color.blue(right.getPixel(384, 384)) > 200)
            assertTrue(Color.red(right.getPixel(384, 384)) < 40)
        } finally {
            left.recycle()
            right.recycle()
        }
    }

    @Test
    fun loadPreviewAppliesExifRotation() {
        val source = File(testDirectory, "rotated.jpg")
        Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.GREEN)
            source.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
            bitmap.recycle()
        }
        ExifInterface(source.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString()
            )
            saveAttributes()
        }

        val preview = ProfileAvatarPreprocessor.loadPreview(source)

        try {
            assertEquals(40, preview.width)
            assertEquals(80, preview.height)
        } finally {
            preview.recycle()
        }
    }
}
