package com.zyna.app.ui.glass

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.zyna.app.ui.chat.render.PaintSplashTarget

/**
 * Single-writer gate for root-owned chat glass resources.
 *
 * Chat layouts provide normal Android views and geometry; the root coordinator
 * decides which registered owner may attach foreground layers and write into
 * the shared Vulkan overlay.
 */
internal class RootGlassLayerCoordinator(
    private val vulkanOverlay: VulkanChatOverlayView,
    private val foregroundHost: FrameLayout
) {
    private data class Owner(
        val key: String,
        val rootView: View,
        val inputBar: View,
        val contextMenuLayer: View,
        val onBackdropStats: (VulkanGlassBackdropStats) -> Unit,
        val onActiveChanged: (Boolean) -> Unit
    )

    private data class GeometrySnapshot(
        val ownerKey: String,
        val rootView: View,
        val rootToHostX: Int,
        val rootToHostY: Int,
        val rootToOverlayX: Int,
        val rootToOverlayY: Int
    )

    private val owners = LinkedHashMap<String, Owner>()
    private val ownerLocationOnScreen = IntArray(2)
    private val hostLocationOnScreen = IntArray(2)
    private val overlayLocationOnScreen = IntArray(2)
    private var activeOwner: Owner? = null
    private var presentedOwnerKey: String? = null
    private var presentedTranslationX = 0f
    private var geometrySnapshot: GeometrySnapshot? = null

    init {
        foregroundHost.visibility = View.GONE
        vulkanOverlay.setPresentationSuppressed(true)
        vulkanOverlay.onBackdropStats = { stats ->
            activeOwner?.onBackdropStats?.invoke(stats)
        }
    }

    fun registerOwner(
        key: String,
        rootView: View,
        inputBar: View,
        contextMenuLayer: View,
        onBackdropStats: (VulkanGlassBackdropStats) -> Unit,
        onActiveChanged: (Boolean) -> Unit
    ) {
        owners[key]?.let { previous ->
            if (previous.rootView !== rootView) {
                if (activeOwner === previous) {
                    setActiveOwner(null)
                }
                detachForegroundViews(previous)
            }
        }
        invalidateGeometry(key)
        owners[key] = Owner(
            key = key,
            rootView = rootView,
            inputBar = inputBar,
            contextMenuLayer = contextMenuLayer,
            onBackdropStats = onBackdropStats,
            onActiveChanged = onActiveChanged
        )
        applyPresentation()
    }

    fun unregisterOwner(key: String, rootView: View) {
        val owner = owners[key] ?: return
        if (owner.rootView !== rootView) {
            return
        }
        owners.remove(key)
        if (activeOwner === owner) {
            setActiveOwner(null)
        } else {
            detachForegroundViews(owner)
        }
        invalidateGeometry(key)
        applyPresentation()
    }

    fun invalidateGeometry(ownerKey: String) {
        if (geometrySnapshot?.ownerKey == ownerKey) {
            geometrySnapshot = null
        }
    }

    fun setPresentedOwner(ownerKey: String?, translationX: Float) {
        presentedOwnerKey = ownerKey
        presentedTranslationX = translationX
        applyPresentation()
    }

    fun setOverlayEnabled(isEnabled: Boolean) {
        vulkanOverlay.setOverlayEnabled(isEnabled)
    }

    fun warmSurface() {
        vulkanOverlay.warmSurface()
    }

    fun isOwnerActive(ownerKey: String): Boolean {
        return activeOwner?.key == ownerKey
    }

    fun syncInputBarLayout(
        ownerKey: String,
        localLeft: Int,
        localTop: Int,
        localRight: Int,
        localBottom: Int
    ): Boolean {
        val owner = activeOwnerFor(ownerKey) ?: return false
        val localWidth = localRight - localLeft
        val localHeight = localBottom - localTop
        if (localWidth <= 0 || localHeight <= 0) {
            return false
        }

        if (!attachInputBarIfNeeded(owner, localWidth, localHeight)) {
            return false
        }

        val geometry = geometryFor(owner)
        val hostLeft = geometry.rootToHostX + localLeft
        val hostTop = geometry.rootToHostY + localTop
        val hostRight = hostLeft + localWidth
        val hostBottom = hostTop + localHeight
        val nextWidth = (hostRight - hostLeft).coerceAtLeast(0)
        val nextHeight = (hostBottom - hostTop).coerceAtLeast(0)

        val params = (owner.inputBar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        if (
            params.width != nextWidth ||
            params.height != nextHeight ||
            params.leftMargin != hostLeft ||
            params.topMargin != hostTop
        ) {
            params.width = nextWidth
            params.height = nextHeight
            params.leftMargin = hostLeft
            params.topMargin = hostTop
            params.gravity = Gravity.START or Gravity.TOP
            owner.inputBar.layoutParams = params
        }
        if (
            owner.inputBar.measuredWidth != nextWidth ||
            owner.inputBar.measuredHeight != nextHeight ||
            owner.inputBar.width != nextWidth ||
            owner.inputBar.height != nextHeight ||
            owner.inputBar.isLayoutRequested
        ) {
            owner.inputBar.forceLayout()
            owner.inputBar.measure(
                View.MeasureSpec.makeMeasureSpec(nextWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(nextHeight, View.MeasureSpec.EXACTLY)
            )
        }
        owner.inputBar.layout(hostLeft, hostTop, hostRight, hostBottom)
        bringForegroundChildrenToFront(owner)
        return true
    }

    fun syncContextMenuLayerLayout(ownerKey: String, width: Int, height: Int): Boolean {
        val owner = activeOwnerFor(ownerKey) ?: return false
        if (width <= 0 || height <= 0) {
            return false
        }
        if (!attachContextMenuLayerIfNeeded(owner, width, height)) {
            return false
        }

        val geometry = geometryFor(owner)
        val hostLeft = geometry.rootToHostX
        val hostTop = geometry.rootToHostY
        val hostRight = hostLeft + width
        val hostBottom = hostTop + height

        val params = (owner.contextMenuLayer.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        if (
            params.width != width ||
            params.height != height ||
            params.leftMargin != hostLeft ||
            params.topMargin != hostTop
        ) {
            params.width = width
            params.height = height
            params.leftMargin = hostLeft
            params.topMargin = hostTop
            params.gravity = Gravity.START or Gravity.TOP
            owner.contextMenuLayer.layoutParams = params
        }
        if (
            owner.contextMenuLayer.measuredWidth != width ||
            owner.contextMenuLayer.measuredHeight != height ||
            owner.contextMenuLayer.width != width ||
            owner.contextMenuLayer.height != height ||
            owner.contextMenuLayer.isLayoutRequested
        ) {
            owner.contextMenuLayer.forceLayout()
            owner.contextMenuLayer.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
        }
        owner.contextMenuLayer.layout(hostLeft, hostTop, hostRight, hostBottom)
        bringForegroundChildrenToFront(owner)
        return true
    }

    fun overlayOffset(ownerKey: String, rootView: View, outOffset: IntArray): Boolean {
        val owner = activeOwnerFor(ownerKey) ?: return false
        if (owner.rootView !== rootView) {
            return false
        }
        val geometry = geometryFor(owner)
        outOffset[0] = geometry.rootToOverlayX
        outOffset[1] = geometry.rootToOverlayY
        return true
    }

    fun setInputBarBounds(ownerKey: String, left: Int, top: Int, right: Int, bottom: Int) {
        if (!isOwnerActive(ownerKey)) {
            return
        }
        vulkanOverlay.setInputBarBounds(left, top, right, bottom)
    }

    fun setBackdropFrame(
        ownerKey: String,
        frame: HardwareBufferChatCapture.CapturedFrame,
        rects: List<VulkanChatGlassRect>,
        textureLeft: Float,
        textureTop: Float
    ): BackdropFrameResult {
        if (!isOwnerActive(ownerKey)) {
            frame.closeCapturedFrame()
            return BackdropFrameResult(imported = false)
        }
        return vulkanOverlay.setBackdropFrame(
            frame = frame,
            rects = rects,
            textureLeft = textureLeft,
            textureTop = textureTop
        )
    }

    fun updateBackdropRects(
        ownerKey: String,
        rects: List<VulkanChatGlassRect>,
        textureLeft: Float,
        textureTop: Float
    ): BackdropFrameResult {
        if (!isOwnerActive(ownerKey)) {
            return BackdropFrameResult(imported = false)
        }
        return vulkanOverlay.updateBackdropRects(
            rects = rects,
            textureLeft = textureLeft,
            textureTop = textureTop
        )
    }

    fun setTeleportScene(
        ownerKey: String,
        oldFrame: HardwareBufferChatCapture.CapturedFrame,
        newFrame: HardwareBufferChatCapture.CapturedFrame,
        viewportLeft: Float,
        viewportTop: Float,
        viewportRight: Float,
        viewportBottom: Float,
        captureLeft: Float,
        captureTop: Float,
        captureWidth: Int,
        captureHeight: Int,
        directionSign: Float,
        onReady: (Boolean) -> Unit
    ): Boolean {
        if (!isOwnerActive(ownerKey)) {
            oldFrame.closeCapturedFrame()
            newFrame.closeCapturedFrame()
            onReady(false)
            return false
        }
        return vulkanOverlay.setTeleportScene(
            oldFrame = oldFrame,
            newFrame = newFrame,
            viewportLeft = viewportLeft,
            viewportTop = viewportTop,
            viewportRight = viewportRight,
            viewportBottom = viewportBottom,
            captureLeft = captureLeft,
            captureTop = captureTop,
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            directionSign = directionSign,
            onReady = onReady
        )
    }

    fun updateTeleportProgress(ownerKey: String, progress: Float): Boolean {
        return isOwnerActive(ownerKey) && vulkanOverlay.updateTeleportProgress(progress)
    }

    fun clearTeleportScene(ownerKey: String) {
        if (isOwnerActive(ownerKey)) {
            vulkanOverlay.clearTeleportScene()
        }
    }

    fun addPaintSplash(ownerKey: String, target: PaintSplashTarget) {
        if (!isOwnerActive(ownerKey)) {
            target.bitmap.recycle()
            return
        }
        vulkanOverlay.addPaintSplash(target)
    }

    fun clearBackdropFrame(ownerKey: String) {
        if (!isOwnerActive(ownerKey)) {
            return
        }
        vulkanOverlay.clearBackdropFrame()
    }

    private fun applyPresentation() {
        val nextOwner = presentedOwnerKey?.let(owners::get)
        val isPresented = nextOwner != null
        vulkanOverlay.translationX = presentedTranslationX
        foregroundHost.translationX = presentedTranslationX
        if (isPresented) {
            foregroundHost.visibility = View.VISIBLE
        }
        setActiveOwner(nextOwner)
        vulkanOverlay.setPresentationSuppressed(!isPresented)
        if (!isPresented) {
            foregroundHost.visibility = View.GONE
        }
    }

    private fun setActiveOwner(nextOwner: Owner?) {
        val previousOwner = activeOwner
        if (previousOwner === nextOwner) {
            return
        }

        if (previousOwner != null) {
            vulkanOverlay.clearTeleportScene()
            vulkanOverlay.discardBackdropFrame()
        }
        geometrySnapshot = null
        previousOwner?.onActiveChanged?.invoke(false)
        previousOwner?.let(::detachForegroundViews)

        activeOwner = nextOwner

        nextOwner?.onActiveChanged?.invoke(true)
    }

    private fun activeOwnerFor(ownerKey: String): Owner? {
        return activeOwner?.takeIf { it.key == ownerKey }
    }

    private fun geometryFor(owner: Owner): GeometrySnapshot {
        geometrySnapshot?.let { snapshot ->
            if (snapshot.ownerKey == owner.key && snapshot.rootView === owner.rootView) {
                return snapshot
            }
        }

        owner.rootView.getLocationOnScreen(ownerLocationOnScreen)
        foregroundHost.getLocationOnScreen(hostLocationOnScreen)
        vulkanOverlay.getLocationOnScreen(overlayLocationOnScreen)
        return GeometrySnapshot(
            ownerKey = owner.key,
            rootView = owner.rootView,
            rootToHostX = ownerLocationOnScreen[0] - hostLocationOnScreen[0],
            rootToHostY = ownerLocationOnScreen[1] - hostLocationOnScreen[1],
            rootToOverlayX = ownerLocationOnScreen[0] - overlayLocationOnScreen[0],
            rootToOverlayY = ownerLocationOnScreen[1] - overlayLocationOnScreen[1]
        ).also { snapshot ->
            geometrySnapshot = snapshot
        }
    }

    private fun attachInputBarIfNeeded(owner: Owner, width: Int, height: Int): Boolean {
        if (owner.inputBar.parent === foregroundHost) {
            bringForegroundChildrenToFront(owner)
            return true
        }
        (owner.inputBar.parent as? ViewGroup)?.removeView(owner.inputBar)
        foregroundHost.addView(
            owner.inputBar,
            FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.START or Gravity.TOP
            }
        )
        bringForegroundChildrenToFront(owner)
        return true
    }

    private fun attachContextMenuLayerIfNeeded(owner: Owner, width: Int, height: Int): Boolean {
        if (owner.contextMenuLayer.parent === foregroundHost) {
            bringForegroundChildrenToFront(owner)
            return true
        }
        (owner.contextMenuLayer.parent as? ViewGroup)?.removeView(owner.contextMenuLayer)
        foregroundHost.addView(
            owner.contextMenuLayer,
            FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.START or Gravity.TOP
            }
        )
        bringForegroundChildrenToFront(owner)
        return true
    }

    private fun detachForegroundViews(owner: Owner) {
        if (owner.contextMenuLayer.parent === foregroundHost) {
            foregroundHost.removeView(owner.contextMenuLayer)
        }
        if (owner.inputBar.parent === foregroundHost) {
            foregroundHost.removeView(owner.inputBar)
        }
    }

    private fun bringForegroundChildrenToFront(owner: Owner) {
        if (
            owner.contextMenuLayer.parent === foregroundHost &&
            owner.contextMenuLayer.visibility == View.VISIBLE
        ) {
            bringChildToFrontIfNeeded(owner.contextMenuLayer)
            return
        }
        if (owner.inputBar.parent === foregroundHost) {
            bringChildToFrontIfNeeded(owner.inputBar)
        }
    }

    private fun bringChildToFrontIfNeeded(child: View) {
        val childIndex = foregroundHost.indexOfChild(child)
        if (childIndex == -1 || childIndex == foregroundHost.childCount - 1) {
            return
        }
        child.bringToFront()
    }
}
