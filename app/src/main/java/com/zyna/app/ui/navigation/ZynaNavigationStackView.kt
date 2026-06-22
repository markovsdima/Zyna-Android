package com.zyna.app.ui.navigation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.zyna.app.util.ZynaPerfLog
import kotlin.math.max

class ZynaNavigationStackView(context: Context) : FrameLayout(context) {
    private data class MountedEntry(
        val entry: ZynaScreenEntry,
        val view: View
    )

    private val mountedEntries = mutableListOf<MountedEntry>()
    private var rootGlassLayerOwnerDuringTransition: MountedEntry? = null
    private var transitionAnimator: ValueAnimator? = null
    private var isTransitionRunning = false
    private var pendingEntries: List<ZynaScreenEntry>? = null
    var onRootGlassLayerStateChanged: (ownerKey: String?, translationX: Float) -> Unit =
        { _, _ -> }

    fun topView(): View? {
        return mountedEntries.lastOrNull()?.view
    }

    fun setEntries(entries: List<ZynaScreenEntry>, animated: Boolean) {
        require(entries.isNotEmpty()) { "ZynaNavigationStackView requires at least one entry" }
        val start = ZynaPerfLog.start()
        val currentKeys = mountedEntries.map { it.entry.key }
        val nextKeys = entries.map { it.key }
        ZynaPerfLog.mark {
            "stack.setEntries.begin animated=$animated current=$currentKeys next=$nextKeys"
        }

        if (isTransitionRunning) {
            pendingEntries = entries
            ZynaPerfLog.end(start, "stack.setEntries.deferred") { "next=$nextKeys" }
            return
        }

        if (mountedEntries.isEmpty()) {
            mountInitial(entries)
            syncRootGlassLayerTranslation()
            ZynaPerfLog.end(start, "stack.setEntries.initial") { "next=$nextKeys" }
            return
        }

        updateCommonEntries(entries)

        if (currentKeys == nextKeys) {
            syncRootGlassLayerTranslation()
            ZynaPerfLog.end(start, "stack.setEntries.same") { "keys=$nextKeys" }
            return
        }

        val commonPrefix = commonPrefixLength(currentKeys, nextKeys)
        val isPurePush = commonPrefix == currentKeys.size && nextKeys.size > currentKeys.size
        val isPurePop = commonPrefix == nextKeys.size && currentKeys.size > nextKeys.size

        when {
            animated && isPurePush -> pushEntries(entries.drop(currentKeys.size))
            animated && isPurePop -> popToSize(nextKeys.size)
            else -> replaceAll(entries)
        }
        ZynaPerfLog.end(
            start,
            "stack.setEntries.done"
        ) {
            "animated=$animated purePush=$isPurePush purePop=$isPurePop"
        }
    }

    private fun mountInitial(entries: List<ZynaScreenEntry>) {
        entries.forEach { entry ->
            val createStart = ZynaPerfLog.start()
            val view = entry.createView(context)
            ZynaPerfLog.end(createStart, "stack.initial.createView") { "key=${entry.key}" }
            addFullSizeView(view)
            mountedEntries += MountedEntry(entry, view)
            val updateStart = ZynaPerfLog.start()
            entry.updateView(view)
            ZynaPerfLog.end(updateStart, "stack.initial.updateView") { "key=${entry.key}" }
        }
        syncRootGlassLayerTranslation()
    }

    private fun updateCommonEntries(entries: List<ZynaScreenEntry>) {
        val common = commonPrefixLength(
            mountedEntries.map { it.entry.key },
            entries.map { it.key }
        )
        if (common == 0) return

        val visibleCommonIndex = minOf(common - 1, entries.lastIndex)
        val mounted = mountedEntries[visibleCommonIndex]
        val next = entries[visibleCommonIndex]
        mountedEntries[visibleCommonIndex] = mounted.copy(entry = next)
        val updateStart = ZynaPerfLog.start()
        next.updateView(mounted.view)
        ZynaPerfLog.end(updateStart, "stack.common.updateView") { "key=${next.key}" }
        syncRootGlassLayerTranslation()
    }

    private fun pushEntries(entries: List<ZynaScreenEntry>) {
        if (entries.isEmpty()) return

        val entry = entries.first()
        val createStart = ZynaPerfLog.start()
        val view = entry.createView(context)
        ZynaPerfLog.end(createStart, "stack.push.createView") { "key=${entry.key}" }
        addFullSizeView(view)
        mountedEntries += MountedEntry(entry, view)
        val updateStart = ZynaPerfLog.start()
        entry.updateView(view)
        ZynaPerfLog.end(updateStart, "stack.push.updateView") { "key=${entry.key}" }

        val previousView = mountedEntries
            .getOrNull(mountedEntries.lastIndex - 1)
            ?.view
        runPushAnimation(view, previousView) {
            pushEntries(entries.drop(1))
        }
    }

    private fun popToSize(targetSize: Int) {
        if (mountedEntries.size <= targetSize) return

        val removed = mountedEntries.removeLast()
        val revealed = mountedEntries.lastOrNull()?.view
        rootGlassLayerOwnerDuringTransition = if (removed.entry.rootGlassOwnerKey != null) {
            removed
        } else {
            null
        }
        runPopAnimation(removed.view, revealed) {
            if (removed.entry.retainViewOnRemove) {
                parkRetainedView(removed.view)
            } else {
                removeView(removed.view)
            }
            removed.entry.onViewRemoved(removed.view)
            rootGlassLayerOwnerDuringTransition = null
            syncRootGlassLayerTranslation()
            popToSize(targetSize)
        }
    }

    private fun replaceAll(entries: List<ZynaScreenEntry>) {
        transitionAnimator?.cancel()
        transitionAnimator = null
        rootGlassLayerOwnerDuringTransition = null
        val removedEntries = mountedEntries.toList()
        removedEntries.forEach { mounted ->
            if (mounted.entry.retainViewOnRemove) {
                parkRetainedView(mounted.view)
            } else {
                removeView(mounted.view)
            }
            mounted.entry.onViewRemoved(mounted.view)
        }
        mountedEntries.clear()
        mountInitial(entries)
        syncRootGlassLayerTranslation()
    }

    private fun runPushAnimation(incoming: View, previous: View?, onEnd: () -> Unit) {
        val animationStart = ZynaPerfLog.start()
        val width = max(width, resources.displayMetrics.widthPixels).toFloat()
        val previousStartTranslationX = previous?.translationX ?: 0f
        val previousTargetTranslationX = -width * PARALLAX_RATIO
        incoming.translationX = width
        syncRootGlassLayerTranslation()
        runTransition(
            targetTranslationX = 0f,
            onFrame = { progress ->
                incoming.translationX = lerp(width, 0f, progress)
                if (previous != null) {
                    previous.translationX = lerp(
                        previousStartTranslationX,
                        previousTargetTranslationX,
                        progress
                    )
                }
                syncRootGlassLayerTranslation()
            },
            onEnd = {
                incoming.translationX = 0f
                previous?.translationX = 0f
                syncRootGlassLayerTranslation()
                ZynaPerfLog.end(animationStart, "stack.push.animation") { "width=$width" }
                onEnd()
            }
        )
    }

    private fun runPopAnimation(outgoing: View, revealed: View?, onEnd: () -> Unit) {
        val animationStart = ZynaPerfLog.start()
        val width = max(width, resources.displayMetrics.widthPixels).toFloat()
        val outgoingStartTranslationX = outgoing.translationX
        val revealedStartTranslationX = -width * PARALLAX_RATIO
        revealed?.translationX = revealedStartTranslationX
        syncRootGlassLayerTranslation()
        runTransition(
            targetTranslationX = width,
            onFrame = { progress ->
                outgoing.translationX = lerp(outgoingStartTranslationX, width, progress)
                if (revealed != null) {
                    revealed.translationX = lerp(revealedStartTranslationX, 0f, progress)
                }
                syncRootGlassLayerTranslation()
            },
            onEnd = {
                outgoing.translationX = width
                revealed?.translationX = 0f
                ZynaPerfLog.end(animationStart, "stack.pop.animation") { "width=$width" }
                onEnd()
            }
        )
    }

    private fun runTransition(
        targetTranslationX: Float,
        onFrame: (Float) -> Unit,
        onEnd: () -> Unit
    ) {
        isTransitionRunning = true
        ZynaPerfLog.mark {
            "stack.transition.start duration=$TRANSITION_MS " +
                "durationScale=${ValueAnimator.getDurationScale()} targetX=$targetTranslationX"
        }
        transitionAnimator?.cancel()
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                onFrame(animator.animatedValue as Float)
            }
            var didCancel = false
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (didCancel) {
                        return
                    }
                    transitionAnimator = null
                    isTransitionRunning = false
                    onEnd()
                    flushPendingEntries()
                }

                override fun onAnimationCancel(animation: Animator) {
                    didCancel = true
                    transitionAnimator = null
                    rootGlassLayerOwnerDuringTransition = null
                    isTransitionRunning = false
                    syncRootGlassLayerTranslation()
                    flushPendingEntries()
                }
            })
        }
        transitionAnimator?.start()
    }

    private fun syncRootGlassLayerTranslation() {
        val owner = rootGlassLayerOwnerDuringTransition
            ?: mountedEntries
                .lastOrNull()
                ?.takeIf { it.entry.rootGlassOwnerKey != null }
        onRootGlassLayerStateChanged(
            owner?.entry?.rootGlassOwnerKey,
            owner?.view?.translationX ?: 0f
        )
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress
    }

    private fun flushPendingEntries() {
        val entries = pendingEntries ?: return
        pendingEntries = null
        setEntries(entries, animated = false)
    }

    private fun addFullSizeView(view: View) {
        if (view.parent === this) {
            view.layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            view.visibility = View.VISIBLE
            view.isEnabled = true
            bringChildToFront(view)
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        view.visibility = View.VISIBLE
        view.isEnabled = true
        addView(
            view,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun removeRetainedView(view: View) {
        if (mountedEntries.any { it.view === view }) {
            return
        }
        if (view.parent === this) {
            removeView(view)
        }
    }

    private fun parkRetainedView(view: View) {
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
        view.visibility = View.INVISIBLE
        view.isEnabled = false
    }

    private fun commonPrefixLength(left: List<String>, right: List<String>): Int {
        val max = minOf(left.size, right.size)
        var index = 0
        while (index < max && left[index] == right[index]) {
            index++
        }
        return index
    }

    private companion object {
        const val TRANSITION_MS = 260L
        const val PARALLAX_RATIO = 0.28f
    }
}
