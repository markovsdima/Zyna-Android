package com.zyna.app.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.zyna.app.data.media.MatrixMediaLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MatrixAvatarView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var paletteBackground = Color.WHITE
    private var loadHandle: AutoCloseable? = null
    private var localDecodeJob: Job? = null
    private var loadedKey: String? = null
    private var currentLoader: MatrixMediaLoader? = null
    private var currentColorSeed: String? = null

    private val initialsText = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = INVISIBLE
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        addView(
            initialsText,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            imageView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onDetachedFromWindow() {
        loadHandle?.close()
        loadHandle = null
        localDecodeJob?.cancel()
        localDecodeJob = null
        super.onDetachedFromWindow()
    }

    fun setPaletteBackground(color: Int) {
        paletteBackground = color
        currentColorSeed?.let { seed -> setBackgroundColor(avatarColor(seed)) }
    }

    fun render(
        userId: String,
        displayName: String?,
        avatarUrl: String?,
        localAvatarPath: String?,
        matrixMediaLoader: MatrixMediaLoader?,
        sizePx: Int
    ) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: userId
        val colorSeed = userId.ifBlank { name }
        currentColorSeed = colorSeed
        initialsText.text = name.avatarInitial()
        initialsText.textSize = (sizePx / density / 2.65f).coerceAtLeast(18f)
        setBackgroundColor(avatarColor(colorSeed))

        val localPath = localAvatarPath?.takeIf { it.isNotBlank() }
        if (localPath != null) {
            loadHandle?.close()
            loadHandle = null
            currentLoader = null
            val key = "local:$localPath"
            if (loadedKey == key && imageView.drawable != null) {
                imageView.visibility = VISIBLE
                return
            }
            if (loadedKey == key && localDecodeJob?.isActive == true) {
                return
            }

            loadedKey = key
            imageView.setImageDrawable(null)
            imageView.visibility = INVISIBLE
            localDecodeJob?.cancel()
            localDecodeJob = decodeScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { decodeLocalAvatar(localPath, sizePx) }.getOrNull()
                }
                if (loadedKey == key) {
                    bindBitmap(bitmap)
                } else {
                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                }
            }
            return
        }

        localDecodeJob?.cancel()
        localDecodeJob = null
        val url = avatarUrl?.takeIf { it.isNotBlank() }
        if (url == null || matrixMediaLoader == null) {
            loadHandle?.close()
            loadHandle = null
            currentLoader = matrixMediaLoader
            loadedKey = null
            imageView.setImageDrawable(null)
            imageView.visibility = INVISIBLE
            return
        }

        val key = "remote:$sizePx:$url"
        if (loadedKey == key && currentLoader === matrixMediaLoader && imageView.drawable != null) {
            imageView.visibility = VISIBLE
            return
        }

        loadHandle?.close()
        currentLoader = matrixMediaLoader
        loadedKey = key
        matrixMediaLoader.cachedAvatar(url, sizePx)?.let { cached ->
            imageView.setImageBitmap(cached)
            imageView.visibility = VISIBLE
        } ?: run {
            imageView.setImageDrawable(null)
            imageView.visibility = INVISIBLE
        }
        loadHandle = matrixMediaLoader.loadAvatar(url, sizePx) { bitmap ->
            if (loadedKey == key && currentLoader === matrixMediaLoader) {
                bindBitmap(bitmap)
            }
        }
    }

    private fun bindBitmap(bitmap: Bitmap?) {
        val nextBitmap = bitmap?.takeIf { !it.isRecycled }
        imageView.setImageBitmap(nextBitmap)
        imageView.visibility = if (nextBitmap == null) INVISIBLE else VISIBLE
    }

    private fun decodeLocalAvatar(path: String, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        val sourceMax = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        val target = sizePx.coerceAtLeast(1)
        while (sourceMax / (sampleSize * 2) >= target) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize.coerceAtLeast(1)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun avatarColor(seed: String): Int {
        val colors = if (isDarkPalette()) DARK_AVATAR_COLORS else LIGHT_AVATAR_COLORS
        return colors[(seed.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun isDarkPalette(): Boolean {
        return Color.luminance(paletteBackground) < 0.5f
    }

    private fun String.avatarInitial(): String {
        return trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
    }

    private companion object {
        val LIGHT_AVATAR_COLORS = intArrayOf(
            Color.rgb(0, 122, 255),
            Color.rgb(52, 199, 89),
            Color.rgb(255, 149, 0),
            Color.rgb(175, 82, 222),
            Color.rgb(255, 45, 85)
        )
        val DARK_AVATAR_COLORS = intArrayOf(
            Color.rgb(10, 132, 255),
            Color.rgb(48, 209, 88),
            Color.rgb(255, 159, 10),
            Color.rgb(191, 90, 242),
            Color.rgb(255, 55, 95)
        )
    }
}
