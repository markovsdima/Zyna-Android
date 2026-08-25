#define VK_USE_PLATFORM_ANDROID_KHR 1

#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <ctime>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "ZynaVulkanChat";
constexpr int64_t kPaintSplashDurationNs = 1200000000LL;
constexpr float kPaintSplashReferenceArea = 8000.0f;
constexpr float kPaintSplashBaseParticleCount = 300.0f;
constexpr float kPaintSplashBlobScale = 2.5f;
constexpr uint32_t kMaxSplashItems = 8;
constexpr uint32_t kMinParticlesPerSplash = 200;
constexpr uint32_t kMaxParticlesPerSplash = 1200;
constexpr bool kEnableVulkanFrameTrace = true;
constexpr uint32_t kMaxFrameTraceQueries = 32;
constexpr float kFrameTraceSlowThresholdMs = 8.33f;
constexpr float kPaintSplashBlobSurfaceScale = 0.5f;
constexpr float kPaintSplashGlassSurfaceScale = 0.5f;
constexpr float kPaintSplashGlassSphSurfaceScale = 1.0f;
constexpr uint32_t kMaxPaintSplashGlassHitTargets = 16;
constexpr uint32_t kMaxPaintSplashGlassDroplets = 160;
constexpr uint32_t kPaintSplashGlassCellCols = 12;
constexpr uint32_t kPaintSplashGlassCellRows = 8;
constexpr uint32_t kPaintSplashGlassCellsPerTarget =
    kPaintSplashGlassCellCols * kPaintSplashGlassCellRows;
constexpr uint32_t kPaintSplashGlassCellCount =
    kMaxPaintSplashGlassHitTargets * kPaintSplashGlassCellsPerTarget;
constexpr uint32_t kPaintSplashGlassNozzleSlotsPerTarget = 8;
constexpr uint32_t kPaintSplashGlassSphParticlesPerNozzle = 4;
constexpr uint32_t kPaintSplashGlassSphParticlesPerTarget =
    kPaintSplashGlassNozzleSlotsPerTarget * kPaintSplashGlassSphParticlesPerNozzle;
constexpr uint32_t kMaxPaintSplashGlassSphParticles =
    kMaxPaintSplashGlassHitTargets * kPaintSplashGlassSphParticlesPerTarget;
constexpr int64_t kPaintSplashGlassDripDurationNs = 3200000000LL;
constexpr uint32_t kMaxBackdropRects = 16;
constexpr uint32_t kBackdropRectFloatCount = 11;
constexpr size_t kMaxBackdropImportCacheEntries = 6;
constexpr VkDeviceSize kBackdropStatsBufferSize = sizeof(float) * 8;

alignas(uint32_t) constexpr uint32_t kPaintSplashVertSpv[] =
#include "paint_splash_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashFragSpv[] =
#include "paint_splash_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashInitCompSpv[] =
#include "paint_splash_init_comp_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashUpdateCompSpv[] =
#include "paint_splash_update_comp_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashGlassImpactVertSpv[] =
#include "paint_splash_glass_impact_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashGlassImpactFragSpv[] =
#include "paint_splash_glass_impact_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashGlassSurfaceCompSpv[] =
#include "paint_splash_glass_surface_comp_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashGlassSphUpdateCompSpv[] =
#include "paint_splash_glass_sph_update_comp_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashGlassSphVertSpv[] =
#include "paint_splash_glass_sph_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashGlassSphFragSpv[] =
#include "paint_splash_glass_sph_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashGlassSphCompositeFragSpv[] =
#include "paint_splash_glass_sph_composite_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashCompositeVertSpv[] =
#include "paint_splash_composite_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashCompositeFragSpv[] =
#include "paint_splash_composite_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatBackdropVertSpv[] =
#include "chat_backdrop_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatBackdropFragSpv[] =
#include "chat_backdrop_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatBackdropOverlayFragSpv[] =
#include "chat_backdrop_overlay_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatBackdropBlurVertSpv[] =
#include "chat_backdrop_blur_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatBackdropBlurFragSpv[] =
#include "chat_backdrop_blur_frag_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatBackdropStatsCompSpv[] =
#include "chat_backdrop_stats_comp_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatTeleportVertSpv[] =
#include "chat_teleport_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatTeleportFragSpv[] =
#include "chat_teleport_frag_spv.inc"
;

struct GpuDroplet {
    float position[2];
    float velocity[2];
    float color[4];
    float srcUv[2];
    float baseSize;
    float flightAge;
    float rotation;
    float lifetime;
    float dragFactor;
    uint32_t phase;
};

struct ParticlePushConstants {
    float viewportSize[2];
    float itemOrigin[2];
    float screenToTargetScale[2];
    float blobScale;
    float _padding0;
};

struct CompositePushConstants {
    float viewportSize[2];
    float blobTextureSize[2];
};

struct SplashInitPushConstants {
    float itemSize[2];
    uint32_t dropletCount;
    uint32_t _padding0;
};

struct SplashUpdatePushConstants {
    float timeStep;
    uint32_t dropletCount;
    uint32_t glassHitTargetCount;
    uint32_t glassEventCapacity;
    float itemOrigin[2];
};

struct GpuGlassHitTarget {
    float rect[4];
    float params[4];
};

struct GpuGlassDroplet {
    float position[2];
    float velocity[2];
    float color[4];
    float radius;
    float age;
    float lifetime;
    float seed;
    float stretch;
    float active;
    float impact;
    float pad;
};

struct GlassImpactPushConstants {
    float viewportSize[2];
};

struct GlassSurfacePushConstants {
    float timeStep;
    uint32_t hitTargetCount;
    float viewportSize[2];
    int32_t dispatchOrigin[2];
};

struct GpuGlassSphParticle {
    float positionVelocity[4];
    float color[4];
    float radiusMassDensityPressure[4];
    float ageLifetimeSeedActive[4];
    float anchorStrengthAnchorState[4];
    float normalCurvatureProfilePad[4];
};

struct GlassSphUpdatePushConstants {
    float timeStep;
    uint32_t hitTargetCount;
    uint32_t particleCapacity;
    uint32_t _padding0;
    float viewportSize[2];
    float _padding1[2];
};

struct GlassSphRenderPushConstants {
    float viewportSize[2];
    float visibleFade;
    float _padding0;
};

static_assert(sizeof(GpuDroplet) == sizeof(float) * 16);
static_assert(offsetof(GpuDroplet, position) == 0);
static_assert(offsetof(GpuDroplet, velocity) == 8);
static_assert(offsetof(GpuDroplet, color) == 16);
static_assert(offsetof(GpuDroplet, srcUv) == 32);
static_assert(offsetof(GpuDroplet, baseSize) == 40);
static_assert(offsetof(GpuDroplet, phase) == 60);
static_assert(sizeof(GpuGlassHitTarget) == sizeof(float) * 8);
static_assert(sizeof(GpuGlassDroplet) == sizeof(float) * 16);
static_assert(sizeof(GpuGlassSphParticle) == sizeof(float) * 24);
static_assert(offsetof(SplashUpdatePushConstants, itemOrigin) == 16);
static_assert(offsetof(GlassSurfacePushConstants, dispatchOrigin) == 16);
static_assert(sizeof(GlassSurfacePushConstants) == 24);
static_assert(offsetof(GlassSphUpdatePushConstants, viewportSize) == 16);
static_assert(sizeof(GlassSphUpdatePushConstants) == 32);
static_assert(sizeof(GlassSphRenderPushConstants) == 16);

struct BackdropPushConstants {
    float viewportSize[2];
    float textureSize[2];
    float rect[4];
    float opacity;
    float cornerRadius;
    float textureOrigin[2];
    float bezelWidth;
    float glassThickness;
    float adaptiveAppearance;
    float adaptiveContrast;
    float splashSurfaceIntensity;
    float splashSurfaceAge;
};

struct BlurPushConstants {
    float texelStep[2];
};

struct BackdropOverlayPushConstants {
    float viewportSize[2];
    float textureSize[2];
    float textureOrigin[2];
    float overlayAlpha;
    float _padding0;
};

struct BackdropStatsPushConstants {
    float textureSize[2];
    float _padding0[2];
    float rect[4];
    float textureOrigin[2];
    float cornerRadius;
};

struct TeleportPushConstants {
    float outputSize[2];
    float outputOrigin[2];
    float viewportRect[4];
    float sceneTextureSize[2];
    float progress;
    float directionSign;
};

static_assert(sizeof(TeleportPushConstants) == sizeof(float) * 12);
static_assert(offsetof(TeleportPushConstants, viewportRect) == 16);
static_assert(offsetof(TeleportPushConstants, progress) == 40);

static_assert(offsetof(BackdropStatsPushConstants, rect) == 16);
static_assert(offsetof(BackdropStatsPushConstants, textureOrigin) == 32);
static_assert(offsetof(BackdropStatsPushConstants, cornerRadius) == 40);

struct BackdropRect {
    float left;
    float top;
    float right;
    float bottom;
    float cornerRadius;
    float opacity;
    float bezelWidth;
    float glassThickness;
    float adaptiveAppearance;
    float adaptiveContrast;
    float shapeKind;
};

struct BackdropImportEntry {
    AHardwareBuffer* hardwareBuffer = nullptr;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    int width = 0;
    int height = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
};

struct BackdropStatsResult {
    float meanLuma;
    float variance;
    float brightFraction;
    float darkFraction;
};

struct SplashStagingBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize capacity = 0;
    void* mapped = nullptr;
    bool inUse = false;
};

struct PaintSplashItem {
    float left;
    float top;
    float right;
    float bottom;
    int64_t startTimeNs;
    int64_t lastUpdateNs;
    uint32_t dropletCount;
    bool initialized;
    VkImage snapshotImage = VK_NULL_HANDLE;
    VkDeviceMemory snapshotMemory = VK_NULL_HANDLE;
    VkImageView snapshotImageView = VK_NULL_HANDLE;
    SplashStagingBuffer* snapshotStaging = nullptr;
    uint32_t snapshotWidth = 0;
    uint32_t snapshotHeight = 0;
    bool snapshotUploadPending = false;
    VkBuffer dropletBuffer = VK_NULL_HANDLE;
    VkDeviceMemory dropletMemory = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
};

struct FrameTraceCpuTiming {
    int64_t totalNs = 0;
    int64_t fenceNs = 0;
    int64_t acquireNs = 0;
    int64_t recordNs = 0;
    int64_t submitNs = 0;
    int64_t presentNs = 0;
};

struct FrameTraceFlags {
    bool hasSplash = false;
    bool hasWetSurface = false;
    bool hasBackdrop = false;
    bool hasBackdropComposite = false;
    uint32_t splashItemCount = 0;
    uint32_t backdropRectCount = 0;
    uint32_t glassTargetCount = 0;
};

void logWarn(const char* message, VkResult result = VK_SUCCESS) {
    if (result == VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s", message);
    } else {
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s result=%d", message, result);
    }
}

void logDebug(const char* message) {
    __android_log_print(ANDROID_LOG_DEBUG, kTag, "%s", message);
}

void logDebugf(const char* format, uint32_t value) {
    __android_log_print(ANDROID_LOG_DEBUG, kTag, format, value);
}

constexpr bool kLogBackdropImportTiming = false;

bool isOk(VkResult result, const char* operation) {
    if (result == VK_SUCCESS) {
        return true;
    }
    logWarn(operation, result);
    return false;
}

uint32_t clampUint(uint32_t value, uint32_t minValue, uint32_t maxValue) {
    return std::max(minValue, std::min(value, maxValue));
}

class VulkanChatRenderer {
public:
    VulkanChatRenderer() {
        createInstance();
    }

    ~VulkanChatRenderer() {
        std::lock_guard<std::mutex> lock(mutex_);
        destroySurfaceLocked();
        destroyDeviceLocked();
        if (instance_ != VK_NULL_HANDLE) {
            vkDestroyInstance(instance_, nullptr);
            instance_ = VK_NULL_HANDLE;
        }
    }

    bool isReady() const {
        return instance_ != VK_NULL_HANDLE;
    }

    void setClearColor(float red, float green, float blue, float alpha) {
        std::lock_guard<std::mutex> lock(mutex_);
        clearColor_ = {red, green, blue, alpha};
    }

    void setInputBarBounds(float left, float top, float right, float bottom) {
        std::lock_guard<std::mutex> lock(mutex_);
        inputBarBounds_ = {left, top, right, bottom};
        hasInputBarBounds_ = right > left && bottom > top;
    }

    bool setBackdropHardwareBuffer(
        JNIEnv* env,
        jobject hardwareBuffer,
        std::vector<BackdropRect> rects,
        float textureLeft,
        float textureTop
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        backdropRects_ = std::move(rects);
        backdropTextureOrigin_[0] = textureLeft;
        backdropTextureOrigin_[1] = textureTop;
        if (backdropRects_.empty()) {
            clearBackdropLocked();
            return false;
        }
        const bool imported = importBackdropHardwareBufferLocked(env, hardwareBuffer);
        if (imported) {
            if (teleportActive_ && backdropCompositeImageView_ != VK_NULL_HANDLE) {
                updateBackdropSourceDescriptorsLocked(backdropCompositeImageView_);
            }
            backdropNeedsRender_ = true;
        }
        return imported;
    }

    bool setTeleportHardwareBuffers(
        JNIEnv* env,
        jobject oldHardwareBuffer,
        jobject newHardwareBuffer,
        float viewportLeft,
        float viewportTop,
        float viewportRight,
        float viewportBottom,
        float captureLeft,
        float captureTop,
        int captureWidth,
        int captureHeight,
        float directionSign
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (oldHardwareBuffer == nullptr ||
            newHardwareBuffer == nullptr ||
            viewportRight <= viewportLeft ||
            viewportBottom <= viewportTop ||
            captureWidth <= 0 ||
            captureHeight <= 0) {
            return false;
        }

        waitForFrameFenceLocked();
        destroyTeleportSceneLocked(false);
        if (!importTeleportHardwareBufferLocked(env, oldHardwareBuffer, teleportOldEntry_) ||
            !importTeleportHardwareBufferLocked(env, newHardwareBuffer, teleportNewEntry_)) {
            destroyTeleportSceneLocked(true);
            return false;
        }
        if (teleportOldEntry_.width != teleportNewEntry_.width ||
            teleportOldEntry_.height != teleportNewEntry_.height) {
            logWarn("Teleport scene dimensions do not match");
            destroyTeleportSceneLocked(true);
            return false;
        }
        if (!ensureBackdropBlurSizeResourcesLocked(
                static_cast<uint32_t>(captureWidth),
                static_cast<uint32_t>(captureHeight)
            )) {
            destroyTeleportSceneLocked(true);
            return false;
        }

        teleportViewportRect_ = {
            viewportLeft,
            viewportTop,
            viewportRight,
            viewportBottom
        };
        teleportCaptureOrigin_ = {captureLeft, captureTop};
        teleportDirectionSign_ = directionSign >= 0.0f ? 1.0f : -1.0f;
        teleportProgress_ = 0.0f;
        teleportNeedsTransition_ = true;
        teleportActive_ = true;
        backdropWidth_ = captureWidth;
        backdropHeight_ = captureHeight;
        backdropTextureOrigin_ = {captureLeft, captureTop};
        updateTeleportDescriptorLocked();
        updateBackdropSourceDescriptorsLocked(backdropCompositeImageView_);
        backdropNeedsRender_ = true;
        return true;
    }

    bool updateTeleportProgress(float progress) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!teleportActive_) {
            return false;
        }
        teleportProgress_ = std::clamp(progress, 0.0f, 1.0f);
        backdropNeedsRender_ = true;
        return true;
    }

    void clearTeleport() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!teleportActive_ &&
            teleportOldEntry_.hardwareBuffer == nullptr &&
            teleportNewEntry_.hardwareBuffer == nullptr) {
            return;
        }
        waitForFrameFenceLocked();
        destroyTeleportSceneLocked(true);
        backdropNeedsRender_ = true;
    }

    bool updateBackdropRects(
        std::vector<BackdropRect> rects,
        float textureLeft,
        float textureTop
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (
            rects.empty() ||
            activeBackdropEntry_ == nullptr ||
            activeBackdropEntry_->image == VK_NULL_HANDLE ||
            activeBackdropEntry_->imageView == VK_NULL_HANDLE
        ) {
            return false;
        }
        backdropRects_ = std::move(rects);
        backdropTextureOrigin_[0] = textureLeft;
        backdropTextureOrigin_[1] = textureTop;
        const bool hasBackdrop = hasBackdropImageLocked();
        if (hasBackdrop) {
            backdropNeedsRender_ = true;
        }
        return hasBackdrop;
    }

    bool pollBackdropStats(BackdropStatsResult& outStats) {
        std::lock_guard<std::mutex> lock(mutex_);
        consumeCompletedBackdropStatsIfReadyLocked();
        if (!hasUnreadBackdropStats_) {
            return false;
        }
        outStats = latestBackdropStats_;
        hasUnreadBackdropStats_ = false;
        return true;
    }

    void clearBackdropHardwareBuffer() {
        std::lock_guard<std::mutex> lock(mutex_);
        clearBackdropLocked();
    }

    void addPaintSplashBitmap(
        float left,
        float top,
        float right,
        float bottom,
        const AndroidBitmapInfo& bitmapInfo,
        const void* bitmapPixels
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (right <= left || bottom <= top) {
            return;
        }
        waitForFrameFenceLocked();
        const bool wasPaintSplashIdle =
            splashItems_.empty() && !hasActivePaintSplashGlassSurfaceLocked(nowNanos());
        if (splashItems_.size() >= kMaxSplashItems) {
            destroyPaintSplashItemLocked(splashItems_.front());
            splashItems_.erase(splashItems_.begin());
        }
        PaintSplashItem item{};
        item.left = left;
        item.top = top;
        item.right = right;
        item.bottom = bottom;
        item.startTimeNs = nowNanos();
        item.lastUpdateNs = item.startTimeNs;
        if (!createGpuPaintSplashItemLocked(item, bitmapInfo, bitmapPixels)) {
            destroyPaintSplashItemLocked(item);
            return;
        }
        splashItems_.push_back(std::move(item));
        if (wasPaintSplashIdle) {
            paintSplashGlassNeedsReset_ = true;
            paintSplashGlassDripEndNs_ = 0;
            paintSplashGlassSurfaceAge_ = 0.0f;
        }
        backdropNeedsRender_ = true;
        didLogParticleDraw_ = false;
        logDebugf("native splash queued particles=%u", splashItems_.back().dropletCount);
    }

    void setSurface(JNIEnv* env, jobject surfaceObject, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (instance_ == VK_NULL_HANDLE) {
            return;
        }

        width_ = std::max(1, width);
        height_ = std::max(1, height);
        destroySurfaceLocked();

        if (surfaceObject == nullptr) {
            return;
        }

        window_ = ANativeWindow_fromSurface(env, surfaceObject);
        if (window_ == nullptr) {
            logWarn("ANativeWindow_fromSurface failed");
            return;
        }

        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window_;
        if (!isOk(vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_),
                  "vkCreateAndroidSurfaceKHR failed")) {
            destroySurfaceLocked();
            return;
        }

        if (device_ == VK_NULL_HANDLE) {
            if (!createDeviceForSurfaceLocked()) {
                destroySurfaceLocked();
                return;
            }
        } else if (!queueSupportsPresentLocked()) {
            destroyDeviceLocked();
            if (!createDeviceForSurfaceLocked()) {
                destroySurfaceLocked();
                return;
            }
        }

        createSwapchainLocked();
    }

    bool renderFrame() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (swapchain_ == VK_NULL_HANDLE || device_ == VK_NULL_HANDLE) {
            return false;
        }

        const int64_t frameCpuStartNs = nowNanos();
        FrameTraceCpuTiming cpuTiming{};

        const int64_t fenceStartNs = nowNanos();
        VkResult fenceResult = vkGetFenceStatus(device_, inFlightFence_);
        cpuTiming.fenceNs = nowNanos() - fenceStartNs;
        if (fenceResult == VK_NOT_READY) {
            const int64_t skipTimeNs = nowNanos();
            const bool hasWork = hasRenderableWorkLocked(skipTimeNs);
            logFrameSkipLocked(skipTimeNs, hasWork);
            return hasWork;
        }
        if (fenceResult != VK_SUCCESS) {
            logWarn("vkGetFenceStatus failed", fenceResult);
            return false;
        }
        consumeCompletedFrameTraceLocked();
        consumeCompletedBackdropStatsLocked();
        releaseCompletedSplashUploadStagingLocked();
        vkResetFences(device_, 1, &inFlightFence_);

        uint32_t imageIndex = 0;
        const int64_t acquireStartNs = nowNanos();
        VkResult acquireResult = vkAcquireNextImageKHR(
            device_,
            swapchain_,
            std::numeric_limits<uint64_t>::max(),
            imageAvailableSemaphore_,
            VK_NULL_HANDLE,
            &imageIndex
        );
        cpuTiming.acquireNs = nowNanos() - acquireStartNs;
        if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
            recreateSwapchainLocked();
            vkResetFences(device_, 1, &inFlightFence_);
            return hasActivePaintSplashesLocked();
        }
        if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
            logWarn("vkAcquireNextImageKHR failed", acquireResult);
            vkResetFences(device_, 1, &inFlightFence_);
            return false;
        }
        if (imageIndex >= commandBuffers_.size()) {
            logWarn("swapchain image index out of bounds");
            vkResetFences(device_, 1, &inFlightFence_);
            return false;
        }

        VkCommandBuffer commandBuffer = commandBuffers_[imageIndex];
        vkResetCommandBuffer(commandBuffer, 0);
        const int64_t recordStartNs = nowNanos();
        recordClearPassLocked(commandBuffer, imageIndex);
        cpuTiming.recordNs = nowNanos() - recordStartNs;

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.waitSemaphoreCount = 1;
        submitInfo.pWaitSemaphores = &imageAvailableSemaphore_;
        submitInfo.pWaitDstStageMask = &waitStage;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &renderFinishedSemaphore_;

        const int64_t submitStartNs = nowNanos();
        if (!isOk(vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_),
                  "vkQueueSubmit failed")) {
            backdropStatsPending_ = false;
            vkResetFences(device_, 1, &inFlightFence_);
            return false;
        }
        cpuTiming.submitNs = nowNanos() - submitStartNs;

        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &renderFinishedSemaphore_;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain_;
        presentInfo.pImageIndices = &imageIndex;

        const int64_t presentStartNs = nowNanos();
        VkResult presentResult = vkQueuePresentKHR(graphicsQueue_, &presentInfo);
        cpuTiming.presentNs = nowNanos() - presentStartNs;
        cpuTiming.totalNs = nowNanos() - frameCpuStartNs;
        queuePendingFrameTraceLocked(cpuTiming);
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
            recreateSwapchainLocked();
        } else if (presentResult != VK_SUCCESS) {
            logWarn("vkQueuePresentKHR failed", presentResult);
            return false;
        }

        backdropNeedsRender_ = false;
        return hasRenderableWorkLocked(nowNanos());
    }

private:
    void createInstance() {
        const std::array<const char*, 2> extensions = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };

        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "Zyna Chat";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = "Zyna";
        appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &appInfo;
        instanceInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
        instanceInfo.ppEnabledExtensionNames = extensions.data();

        isOk(vkCreateInstance(&instanceInfo, nullptr, &instance_), "vkCreateInstance failed");
    }

    bool createDeviceForSurfaceLocked() {
        uint32_t physicalDeviceCount = 0;
        vkEnumeratePhysicalDevices(instance_, &physicalDeviceCount, nullptr);
        if (physicalDeviceCount == 0) {
            logWarn("No Vulkan physical devices");
            return false;
        }

        std::vector<VkPhysicalDevice> physicalDevices(physicalDeviceCount);
        vkEnumeratePhysicalDevices(instance_, &physicalDeviceCount, physicalDevices.data());

        for (VkPhysicalDevice candidate : physicalDevices) {
            uint32_t queueFamilyIndex = 0;
            if (findQueueFamily(candidate, queueFamilyIndex)) {
                physicalDevice_ = candidate;
                queueFamilyIndex_ = queueFamilyIndex;
                break;
            }
        }

        if (physicalDevice_ == VK_NULL_HANDLE) {
            logWarn("No suitable Vulkan queue family");
            return false;
        }

        const float queuePriority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{};
        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamilyIndex_;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &queuePriority;

        const std::array<const char*, 7> hardwareBufferImportExtensions = {
            VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
            VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
            VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
            VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
            VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME
        };
        const bool canImportHardwareBuffers =
            supportsDeviceExtensions(physicalDevice_, hardwareBufferImportExtensions);
        std::vector<const char*> deviceExtensions = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
        };
        if (canImportHardwareBuffers) {
            deviceExtensions.insert(
                deviceExtensions.end(),
                hardwareBufferImportExtensions.begin(),
                hardwareBufferImportExtensions.end()
            );
        }

        VkDeviceCreateInfo deviceInfo{};
        deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
        deviceInfo.ppEnabledExtensionNames = deviceExtensions.data();

        if (!isOk(vkCreateDevice(physicalDevice_, &deviceInfo, nullptr, &device_),
                  "vkCreateDevice failed")) {
            physicalDevice_ = VK_NULL_HANDLE;
            queueFamilyIndex_ = 0;
            return false;
        }

        getAndroidHardwareBufferProperties_ =
            reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID")
            );
        hardwareBufferImportSupported_ =
            canImportHardwareBuffers && getAndroidHardwareBufferProperties_ != nullptr;
        logDebug(
            hardwareBufferImportSupported_
                ? "Vulkan AHardwareBuffer import extensions enabled"
                : "Vulkan AHardwareBuffer import extensions unavailable"
        );

        vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &graphicsQueue_);

        VkCommandPoolCreateInfo commandPoolInfo{};
        commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        commandPoolInfo.queueFamilyIndex = queueFamilyIndex_;
        if (!isOk(vkCreateCommandPool(device_, &commandPoolInfo, nullptr, &commandPool_),
                  "vkCreateCommandPool failed")) {
            destroyDeviceLocked();
            return false;
        }

        VkSemaphoreCreateInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        if (!isOk(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &imageAvailableSemaphore_),
                  "vkCreateSemaphore image failed") ||
            !isOk(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &renderFinishedSemaphore_),
                  "vkCreateSemaphore render failed")) {
            destroyDeviceLocked();
            return false;
        }

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (!isOk(vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_),
                  "vkCreateFence failed")) {
            destroyDeviceLocked();
            return false;
        }

        createFrameTraceResourcesLocked();
        return true;
    }

    template <size_t Count>
    bool supportsDeviceExtensions(
        VkPhysicalDevice candidate,
        const std::array<const char*, Count>& requiredExtensions
    ) const {
        uint32_t extensionCount = 0;
        VkResult countResult = vkEnumerateDeviceExtensionProperties(
            candidate,
            nullptr,
            &extensionCount,
            nullptr
        );
        if (countResult != VK_SUCCESS || extensionCount == 0) {
            return false;
        }

        std::vector<VkExtensionProperties> supportedExtensions(extensionCount);
        VkResult propertiesResult = vkEnumerateDeviceExtensionProperties(
            candidate,
            nullptr,
            &extensionCount,
            supportedExtensions.data()
        );
        if (propertiesResult != VK_SUCCESS) {
            return false;
        }

        for (const char* requiredName : requiredExtensions) {
            bool didFind = false;
            for (const VkExtensionProperties& supported : supportedExtensions) {
                if (std::strcmp(supported.extensionName, requiredName) == 0) {
                    didFind = true;
                    break;
                }
            }
            if (!didFind) {
                return false;
            }
        }
        return true;
    }

    bool findQueueFamily(VkPhysicalDevice candidate, uint32_t& outIndex) const {
        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, nullptr);
        if (queueFamilyCount == 0) {
            return false;
        }

        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, queueFamilies.data());

        for (uint32_t index = 0; index < queueFamilyCount; ++index) {
            const VkQueueFlags requiredFlags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
            if ((queueFamilies[index].queueFlags & requiredFlags) != requiredFlags) {
                continue;
            }
            VkBool32 presentSupported = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, surface_, &presentSupported);
            if (presentSupported == VK_TRUE) {
                outIndex = index;
                return true;
            }
        }
        return false;
    }

    bool queueSupportsPresentLocked() const {
        if (physicalDevice_ == VK_NULL_HANDLE || surface_ == VK_NULL_HANDLE) {
            return false;
        }
        VkBool32 presentSupported = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(
            physicalDevice_,
            queueFamilyIndex_,
            surface_,
            &presentSupported
        );
        return presentSupported == VK_TRUE;
    }

    void createFrameTraceResourcesLocked() {
        if (!kEnableVulkanFrameTrace ||
            physicalDevice_ == VK_NULL_HANDLE ||
            device_ == VK_NULL_HANDLE ||
            queueFamilyIndex_ == UINT32_MAX) {
            return;
        }

        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice_, &queueFamilyCount, nullptr);
        if (queueFamilyCount <= queueFamilyIndex_) {
            return;
        }
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(
            physicalDevice_,
            &queueFamilyCount,
            queueFamilies.data()
        );
        frameTraceTimestampValidBits_ = queueFamilies[queueFamilyIndex_].timestampValidBits;
        if (frameTraceTimestampValidBits_ == 0) {
            logDebug("frameTrace disabled: queue has no timestamp support");
            return;
        }

        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice_, &properties);
        frameTraceTimestampPeriodNs_ = properties.limits.timestampPeriod;

        VkQueryPoolCreateInfo queryPoolInfo{};
        queryPoolInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        queryPoolInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
        queryPoolInfo.queryCount = kMaxFrameTraceQueries;
        if (!isOk(
                vkCreateQueryPool(device_, &queryPoolInfo, nullptr, &frameTraceQueryPool_),
                "vkCreateQueryPool frameTrace failed"
            )) {
            frameTraceQueryPool_ = VK_NULL_HANDLE;
            return;
        }
        logDebug("frameTrace enabled");
    }

    void destroyFrameTraceResourcesLocked() {
        if (device_ != VK_NULL_HANDLE && frameTraceQueryPool_ != VK_NULL_HANDLE) {
            vkDestroyQueryPool(device_, frameTraceQueryPool_, nullptr);
        }
        frameTraceQueryPool_ = VK_NULL_HANDLE;
        frameTraceQueryCount_ = 0;
        pendingFrameTraceQueryCount_ = 0;
        pendingFrameTraceActive_ = false;
    }

    void beginFrameTraceLocked(VkCommandBuffer commandBuffer) {
        frameTraceQueryCount_ = 0;
        currentFrameTraceHasEffects_ = false;
        currentFrameTraceFlags_ = {};
        if (frameTraceQueryPool_ == VK_NULL_HANDLE) {
            return;
        }
        vkCmdResetQueryPool(commandBuffer, frameTraceQueryPool_, 0, kMaxFrameTraceQueries);
        writeFrameTraceTimestampLocked(commandBuffer, "start", VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
    }

    void writeFrameTraceTimestampLocked(
        VkCommandBuffer commandBuffer,
        const char* name,
        VkPipelineStageFlagBits stage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
    ) {
        if (frameTraceQueryPool_ == VK_NULL_HANDLE ||
            frameTraceQueryCount_ >= kMaxFrameTraceQueries) {
            return;
        }
        frameTraceNames_[frameTraceQueryCount_] = name;
        vkCmdWriteTimestamp(commandBuffer, stage, frameTraceQueryPool_, frameTraceQueryCount_);
        ++frameTraceQueryCount_;
    }

    void queuePendingFrameTraceLocked(const FrameTraceCpuTiming& cpuTiming) {
        if (frameTraceQueryPool_ == VK_NULL_HANDLE || frameTraceQueryCount_ < 2) {
            return;
        }
        pendingFrameTraceQueryCount_ = frameTraceQueryCount_;
        for (uint32_t index = 0; index < frameTraceQueryCount_; ++index) {
            pendingFrameTraceNames_[index] = frameTraceNames_[index];
        }
        pendingFrameTraceCpuTiming_ = cpuTiming;
        pendingFrameTraceHasEffects_ = currentFrameTraceHasEffects_;
        pendingFrameTraceFlags_ = currentFrameTraceFlags_;
        pendingFrameTraceActive_ = true;
    }

    uint64_t frameTraceTimestampDeltaLocked(uint64_t start, uint64_t end) const {
        if (frameTraceTimestampValidBits_ == 0) {
            return 0;
        }
        if (frameTraceTimestampValidBits_ >= 64) {
            return end >= start ? end - start : 0;
        }
        const uint64_t mask = (1ULL << frameTraceTimestampValidBits_) - 1ULL;
        return (end - start) & mask;
    }

    float frameTraceTicksToMsLocked(uint64_t ticks) const {
        return static_cast<float>(
            static_cast<double>(ticks) *
                static_cast<double>(frameTraceTimestampPeriodNs_) /
                1000000.0
        );
    }

    static float nsToMs(int64_t nanos) {
        return static_cast<float>(static_cast<double>(nanos) / 1000000.0);
    }

    void appendFrameTracePart(std::string& line, const char* name, float valueMs) const {
        char part[64];
        std::snprintf(part, sizeof(part), " %s=%.2f", name, valueMs);
        line += part;
    }

    void appendFrameTraceUint(std::string& line, const char* name, uint32_t value) const {
        char part[64];
        std::snprintf(part, sizeof(part), " %s=%u", name, value);
        line += part;
    }

    void appendFrameTraceFlags(std::string& line, const FrameTraceFlags& flags) const {
        appendFrameTraceUint(line, "splash", flags.hasSplash ? 1u : 0u);
        appendFrameTraceUint(line, "wet", flags.hasWetSurface ? 1u : 0u);
        appendFrameTraceUint(line, "backdrop", flags.hasBackdrop ? 1u : 0u);
        appendFrameTraceUint(line, "glass", flags.hasBackdropComposite ? 1u : 0u);
        appendFrameTraceUint(line, "items", flags.splashItemCount);
        appendFrameTraceUint(line, "rects", flags.backdropRectCount);
        appendFrameTraceUint(line, "targets", flags.glassTargetCount);
    }

    void logFrameSkipLocked(int64_t nowNs, bool hasWork) {
        if (!hasWork || frameSkipLogsRemaining_ == 0) {
            return;
        }
        --frameSkipLogsRemaining_;

        FrameTraceFlags flags{};
        flags.hasSplash = hasLivePaintSplashesLocked(nowNs);
        flags.hasWetSurface = hasActivePaintSplashGlassSurfaceLocked(nowNs);
        flags.hasBackdrop = hasBackdropImageLocked();
        flags.hasBackdropComposite = flags.hasBackdrop && !backdropRects_.empty();
        flags.splashItemCount = static_cast<uint32_t>(splashItems_.size());
        flags.backdropRectCount = static_cast<uint32_t>(backdropRects_.size());
        flags.glassTargetCount = paintSplashGlassTargetCount_;

        std::string line;
        line.reserve(256);
        appendFrameTraceFlags(line, flags);
        __android_log_print(ANDROID_LOG_DEBUG, kTag, "frameSkip reason=fenceBusy%s", line.c_str());
    }

    void consumeCompletedFrameTraceLocked() {
        if (!pendingFrameTraceActive_ ||
            frameTraceQueryPool_ == VK_NULL_HANDLE ||
            pendingFrameTraceQueryCount_ < 2) {
            return;
        }

        std::array<uint64_t, kMaxFrameTraceQueries> timestamps{};
        VkResult result = vkGetQueryPoolResults(
            device_,
            frameTraceQueryPool_,
            0,
            pendingFrameTraceQueryCount_,
            sizeof(uint64_t) * pendingFrameTraceQueryCount_,
            timestamps.data(),
            sizeof(uint64_t),
            VK_QUERY_RESULT_64_BIT
        );
        if (result != VK_SUCCESS) {
            return;
        }

        const float gpuTotalMs = frameTraceTicksToMsLocked(
            frameTraceTimestampDeltaLocked(
                timestamps[0],
                timestamps[pendingFrameTraceQueryCount_ - 1]
            )
        );
        const float cpuTotalMs = nsToMs(pendingFrameTraceCpuTiming_.totalNs);
        const bool isSlow =
            gpuTotalMs >= kFrameTraceSlowThresholdMs ||
            cpuTotalMs >= kFrameTraceSlowThresholdMs;
        if (pendingFrameTraceHasEffects_ && isSlow && frameTraceLogsRemaining_ > 0) {
            --frameTraceLogsRemaining_;

            std::string line;
            line.reserve(1024);
            appendFrameTracePart(line, "cpu", cpuTotalMs);
            appendFrameTracePart(line, "fence", nsToMs(pendingFrameTraceCpuTiming_.fenceNs));
            appendFrameTracePart(line, "acquire", nsToMs(pendingFrameTraceCpuTiming_.acquireNs));
            appendFrameTracePart(line, "record", nsToMs(pendingFrameTraceCpuTiming_.recordNs));
            appendFrameTracePart(line, "submit", nsToMs(pendingFrameTraceCpuTiming_.submitNs));
            appendFrameTracePart(line, "present", nsToMs(pendingFrameTraceCpuTiming_.presentNs));
            appendFrameTracePart(line, "gpu", gpuTotalMs);
            appendFrameTraceFlags(line, pendingFrameTraceFlags_);

            for (uint32_t index = 1; index < pendingFrameTraceQueryCount_; ++index) {
                const float segmentMs = frameTraceTicksToMsLocked(
                    frameTraceTimestampDeltaLocked(timestamps[index - 1], timestamps[index])
                );
                appendFrameTracePart(line, pendingFrameTraceNames_[index], segmentMs);
            }
            __android_log_print(ANDROID_LOG_DEBUG, kTag, "frameTrace%s", line.c_str());
        }
        pendingFrameTraceActive_ = false;
    }

    bool createSwapchainLocked() {
        if (surface_ == VK_NULL_HANDLE || device_ == VK_NULL_HANDLE) {
            return false;
        }

        VkSurfaceCapabilitiesKHR capabilities{};
        if (!isOk(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &capabilities),
                  "vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed")) {
            return false;
        }

        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
        if (formatCount == 0) {
            logWarn("No Vulkan surface formats");
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());
        VkSurfaceFormatKHR surfaceFormat = chooseSurfaceFormat(formats);

        VkExtent2D extent = chooseExtent(capabilities);
        uint32_t imageCount = capabilities.minImageCount + 1;
        if (capabilities.maxImageCount > 0) {
            imageCount = std::min(imageCount, capabilities.maxImageCount);
        }

        VkCompositeAlphaFlagBitsKHR compositeAlpha = chooseCompositeAlpha(capabilities);

        VkSwapchainCreateInfoKHR swapchainInfo{};
        swapchainInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        swapchainInfo.surface = surface_;
        swapchainInfo.minImageCount = imageCount;
        swapchainInfo.imageFormat = surfaceFormat.format;
        swapchainInfo.imageColorSpace = surfaceFormat.colorSpace;
        swapchainInfo.imageExtent = extent;
        swapchainInfo.imageArrayLayers = 1;
        swapchainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        swapchainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchainInfo.preTransform = capabilities.currentTransform;
        swapchainInfo.compositeAlpha = compositeAlpha;
        swapchainInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapchainInfo.clipped = VK_TRUE;
        swapchainInfo.oldSwapchain = VK_NULL_HANDLE;

        if (!isOk(vkCreateSwapchainKHR(device_, &swapchainInfo, nullptr, &swapchain_),
                  "vkCreateSwapchainKHR failed")) {
            return false;
        }

        swapchainFormat_ = surfaceFormat.format;
        swapchainExtent_ = extent;

        uint32_t actualImageCount = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &actualImageCount, nullptr);
        swapchainImages_.resize(actualImageCount);
        vkGetSwapchainImagesKHR(device_, swapchain_, &actualImageCount, swapchainImages_.data());

        return createImageViewsLocked() &&
            createRenderPassLocked() &&
            createBlobResourcesLocked() &&
            createSplashDescriptorResourcesLocked() &&
            createBackdropDescriptorResourcesLocked() &&
            createTeleportDescriptorResourcesLocked() &&
            createBackdropStatsResourcesLocked() &&
            createBackdropBlurResourcesLocked() &&
            createBackdropOverlayResourcesLocked() &&
            createSplashInitPipelineLocked() &&
            createSplashUpdatePipelineLocked() &&
            createParticlePipelineLocked() &&
            createPaintSplashGlassImpactPipelineLocked() &&
            createPaintSplashGlassSurfacePipelineLocked() &&
            createPaintSplashGlassSphUpdatePipelineLocked() &&
            createPaintSplashGlassSphFieldPipelineLocked() &&
            createPaintSplashGlassSphCompositePipelineLocked() &&
            createBackdropStatsPipelineLocked() &&
            createBackdropOverlayPipelineLocked() &&
            createBackdropBlurPipelineLocked() &&
            createTeleportPipelinesLocked() &&
            createBackdropPipelineLocked() &&
            createCompositePipelineLocked() &&
            createFramebuffersLocked() &&
            allocateCommandBuffersLocked();
    }

    VkSurfaceFormatKHR chooseSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) const {
        for (const VkSurfaceFormatKHR& format : formats) {
            if (format.format == VK_FORMAT_R8G8B8A8_UNORM ||
                format.format == VK_FORMAT_B8G8R8A8_UNORM) {
                return format;
            }
        }
        return formats.front();
    }

    VkExtent2D chooseExtent(const VkSurfaceCapabilitiesKHR& capabilities) const {
        if (capabilities.currentExtent.width != std::numeric_limits<uint32_t>::max()) {
            return capabilities.currentExtent;
        }
        return VkExtent2D{
            clampUint(static_cast<uint32_t>(width_), capabilities.minImageExtent.width,
                      capabilities.maxImageExtent.width),
            clampUint(static_cast<uint32_t>(height_), capabilities.minImageExtent.height,
                      capabilities.maxImageExtent.height)
        };
    }

    VkCompositeAlphaFlagBitsKHR chooseCompositeAlpha(
        const VkSurfaceCapabilitiesKHR& capabilities
    ) const {
        const std::array<VkCompositeAlphaFlagBitsKHR, 4> preferred = {
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
        };
        for (VkCompositeAlphaFlagBitsKHR candidate : preferred) {
            if ((capabilities.supportedCompositeAlpha & candidate) != 0) {
                return candidate;
            }
        }
        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }

    bool createImageViewsLocked() {
        swapchainImageViews_.clear();
        swapchainImageViews_.reserve(swapchainImages_.size());

        for (VkImage image : swapchainImages_) {
            VkImageViewCreateInfo viewInfo{};
            viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            viewInfo.image = image;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = swapchainFormat_;
            viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.baseMipLevel = 0;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.baseArrayLayer = 0;
            viewInfo.subresourceRange.layerCount = 1;

            VkImageView imageView = VK_NULL_HANDLE;
            if (!isOk(vkCreateImageView(device_, &viewInfo, nullptr, &imageView),
                      "vkCreateImageView failed")) {
                return false;
            }
            swapchainImageViews_.push_back(imageView);
        }
        return true;
    }

    bool createRenderPassLocked() {
        if (renderPass_ != VK_NULL_HANDLE) {
            return true;
        }

        VkAttachmentDescription colorAttachment{};
        colorAttachment.format = swapchainFormat_;
        colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorAttachmentRef{};
        colorAttachmentRef.attachment = 0;
        colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorAttachmentRef;

        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask =
            VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &colorAttachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = 1;
        renderPassInfo.pDependencies = &dependency;

        return isOk(vkCreateRenderPass(device_, &renderPassInfo, nullptr, &renderPass_),
                    "vkCreateRenderPass failed");
    }

    bool createFramebuffersLocked() {
        framebuffers_.clear();
        framebuffers_.reserve(swapchainImageViews_.size());

        for (VkImageView imageView : swapchainImageViews_) {
            VkFramebufferCreateInfo framebufferInfo{};
            framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            framebufferInfo.renderPass = renderPass_;
            framebufferInfo.attachmentCount = 1;
            framebufferInfo.pAttachments = &imageView;
            framebufferInfo.width = swapchainExtent_.width;
            framebufferInfo.height = swapchainExtent_.height;
            framebufferInfo.layers = 1;

            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            if (!isOk(vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffer),
                      "vkCreateFramebuffer failed")) {
                return false;
            }
            framebuffers_.push_back(framebuffer);
        }
        return true;
    }

    bool allocateCommandBuffersLocked() {
        commandBuffers_.resize(framebuffers_.size());
        if (commandBuffers_.empty()) {
            return false;
        }

        VkCommandBufferAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocateInfo.commandPool = commandPool_;
        allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocateInfo.commandBufferCount = static_cast<uint32_t>(commandBuffers_.size());

        return isOk(vkAllocateCommandBuffers(device_, &allocateInfo, commandBuffers_.data()),
                    "vkAllocateCommandBuffers failed");
    }

    bool createBlobResourcesLocked() {
        if (!createBlobRenderPassLocked() ||
            !createBlobImageLocked() ||
            !createBlobFramebufferLocked() ||
            !createBlobDescriptorResourcesLocked() ||
            !createPaintSplashGlassRenderPassLocked() ||
            !createPaintSplashGlassImagesLocked() ||
            !createPaintSplashGlassImpactFramebufferLocked() ||
            !createPaintSplashGlassSphFramebufferLocked()) {
            return false;
        }
        return true;
    }

    bool createBlobRenderPassLocked() {
        VkAttachmentDescription colorAttachment{};
        colorAttachment.format = blobFormat_;
        colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAttachment.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkAttachmentReference colorAttachmentRef{};
        colorAttachmentRef.attachment = 0;
        colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorAttachmentRef;

        std::array<VkSubpassDependency, 2> dependencies{};
        dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[0].dstSubpass = 0;
        dependencies[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].srcSubpass = 0;
        dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[1].dstStageMask =
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &colorAttachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = static_cast<uint32_t>(dependencies.size());
        renderPassInfo.pDependencies = dependencies.data();

        return isOk(
            vkCreateRenderPass(device_, &renderPassInfo, nullptr, &blobRenderPass_),
            "vkCreateRenderPass blob failed"
        );
    }

    bool createBlobImageLocked() {
        blobExtent_ = paintSplashBlobExtentLocked();
        if (blobExtent_.width == 0 || blobExtent_.height == 0) {
            return false;
        }

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = blobExtent_.width;
        imageInfo.extent.height = blobExtent_.height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = blobFormat_;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(vkCreateImage(device_, &imageInfo, nullptr, &blobImage_),
                  "vkCreateImage blob failed")) {
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetImageMemoryRequirements(device_, blobImage_, &memoryRequirements);
        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(
                memoryRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                memoryTypeIndex
            )) {
            logWarn("No device local memory for blob image");
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(vkAllocateMemory(device_, &allocateInfo, nullptr, &blobImageMemory_),
                  "vkAllocateMemory blob failed")) {
            return false;
        }
        if (!isOk(vkBindImageMemory(device_, blobImage_, blobImageMemory_, 0),
                  "vkBindImageMemory blob failed")) {
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = blobImage_;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = blobFormat_;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        return isOk(vkCreateImageView(device_, &viewInfo, nullptr, &blobImageView_),
                    "vkCreateImageView blob failed");
    }

    bool createBlobFramebufferLocked() {
        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = blobRenderPass_;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = &blobImageView_;
        framebufferInfo.width = blobExtent_.width;
        framebufferInfo.height = blobExtent_.height;
        framebufferInfo.layers = 1;
        return isOk(vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &blobFramebuffer_),
                    "vkCreateFramebuffer blob failed");
    }

    bool createBlobDescriptorResourcesLocked() {
        if (blobSampler_ == VK_NULL_HANDLE) {
            VkSamplerCreateInfo samplerInfo{};
            samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
            samplerInfo.magFilter = VK_FILTER_LINEAR;
            samplerInfo.minFilter = VK_FILTER_LINEAR;
            samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
            samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.maxLod = 1.0f;
            if (!isOk(vkCreateSampler(device_, &samplerInfo, nullptr, &blobSampler_),
                      "vkCreateSampler blob failed")) {
                return false;
            }
        }

        if (compositeDescriptorSetLayout_ == VK_NULL_HANDLE) {
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = 0;
            binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            binding.descriptorCount = 1;
            binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = 1;
            layoutInfo.pBindings = &binding;
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &compositeDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout composite failed"
                )) {
                return false;
            }
        }

        if (compositeDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSize.descriptorCount = 1;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &compositeDescriptorPool_),
                      "vkCreateDescriptorPool composite failed")) {
                return false;
            }
        }

        if (compositeDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = compositeDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &compositeDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(device_, &allocateInfo, &compositeDescriptorSet_),
                    "vkAllocateDescriptorSets composite failed"
                )) {
                return false;
            }
        }

        VkDescriptorImageInfo imageInfo{};
        imageInfo.sampler = blobSampler_;
        imageInfo.imageView = blobImageView_;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = compositeDescriptorSet_;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageInfo;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
        return true;
    }

    bool createPaintSplashGlassRenderPassLocked() {
        if (paintSplashGlassImpactRenderPass_ != VK_NULL_HANDLE) {
            return true;
        }

        std::array<VkAttachmentDescription, 2> attachments{};
        for (VkAttachmentDescription& attachment : attachments) {
            attachment.format = paintSplashGlassFormat_;
            attachment.samples = VK_SAMPLE_COUNT_1_BIT;
            attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
            attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            attachment.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }

        std::array<VkAttachmentReference, 2> colorAttachmentRefs{};
        colorAttachmentRefs[0].attachment = 0;
        colorAttachmentRefs[0].layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        colorAttachmentRefs[1].attachment = 1;
        colorAttachmentRefs[1].layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = static_cast<uint32_t>(colorAttachmentRefs.size());
        subpass.pColorAttachments = colorAttachmentRefs.data();

        std::array<VkSubpassDependency, 2> dependencies{};
        dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[0].dstSubpass = 0;
        dependencies[0].srcStageMask =
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].srcSubpass = 0;
        dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[1].dstStageMask =
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = static_cast<uint32_t>(attachments.size());
        renderPassInfo.pAttachments = attachments.data();
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = static_cast<uint32_t>(dependencies.size());
        renderPassInfo.pDependencies = dependencies.data();

        return isOk(
            vkCreateRenderPass(
                device_,
                &renderPassInfo,
                nullptr,
                &paintSplashGlassImpactRenderPass_
            ),
            "vkCreateRenderPass paint splash glass impact failed"
        );
    }

    bool createPaintSplashGlassImageWithExtentLocked(
        const VkExtent2D& imageExtent,
        VkImage& image,
        VkDeviceMemory& memory,
        VkImageView& imageView
    ) {
        if (imageExtent.width == 0 || imageExtent.height == 0) {
            return false;
        }

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = imageExtent.width;
        imageInfo.extent.height = imageExtent.height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = paintSplashGlassFormat_;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage =
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
            VK_IMAGE_USAGE_SAMPLED_BIT |
            VK_IMAGE_USAGE_STORAGE_BIT |
            VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(
                vkCreateImage(device_, &imageInfo, nullptr, &image),
                "vkCreateImage paint splash glass failed"
            )) {
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetImageMemoryRequirements(device_, image, &memoryRequirements);
        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(
                memoryRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                memoryTypeIndex
            )) {
            logWarn("No device local memory for paint splash glass image");
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(
                vkAllocateMemory(device_, &allocateInfo, nullptr, &memory),
                "vkAllocateMemory paint splash glass failed"
            ) ||
            !isOk(
                vkBindImageMemory(device_, image, memory, 0),
                "vkBindImageMemory paint splash glass failed"
            )) {
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = paintSplashGlassFormat_;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        return isOk(
            vkCreateImageView(device_, &viewInfo, nullptr, &imageView),
            "vkCreateImageView paint splash glass failed"
        );
    }

    bool createPaintSplashGlassImageLocked(
        VkImage& image,
        VkDeviceMemory& memory,
        VkImageView& imageView
    ) {
        return createPaintSplashGlassImageWithExtentLocked(
            paintSplashGlassSurfaceExtentLocked(),
            image,
            memory,
            imageView
        );
    }

    void destroyPaintSplashGlassImageLocked(
        VkImage& image,
        VkDeviceMemory& memory,
        VkImageView& imageView
    ) {
        if (device_ == VK_NULL_HANDLE) {
            image = VK_NULL_HANDLE;
            memory = VK_NULL_HANDLE;
            imageView = VK_NULL_HANDLE;
            return;
        }
        if (imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, imageView, nullptr);
            imageView = VK_NULL_HANDLE;
        }
        if (image != VK_NULL_HANDLE) {
            vkDestroyImage(device_, image, nullptr);
            image = VK_NULL_HANDLE;
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, memory, nullptr);
            memory = VK_NULL_HANDLE;
        }
    }

    void destroyPaintSplashGlassImagesLocked() {
        if (device_ == VK_NULL_HANDLE) {
            paintSplashGlassImpactFramebuffer_ = VK_NULL_HANDLE;
            paintSplashGlassSphFramebuffer_ = VK_NULL_HANDLE;
        } else if (paintSplashGlassImpactFramebuffer_ != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, paintSplashGlassImpactFramebuffer_, nullptr);
            paintSplashGlassImpactFramebuffer_ = VK_NULL_HANDLE;
        }
        if (device_ != VK_NULL_HANDLE && paintSplashGlassSphFramebuffer_ != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, paintSplashGlassSphFramebuffer_, nullptr);
            paintSplashGlassSphFramebuffer_ = VK_NULL_HANDLE;
        }

        destroyPaintSplashGlassImageLocked(
            paintSplashGlassSurfaceImage_,
            paintSplashGlassSurfaceMemory_,
            paintSplashGlassSurfaceImageView_
        );
        destroyPaintSplashGlassImageLocked(
            paintSplashGlassSurfaceWorkImage_,
            paintSplashGlassSurfaceWorkMemory_,
            paintSplashGlassSurfaceWorkImageView_
        );
        destroyPaintSplashGlassImageLocked(
            paintSplashGlassVelocityImage_,
            paintSplashGlassVelocityMemory_,
            paintSplashGlassVelocityImageView_
        );
        destroyPaintSplashGlassImageLocked(
            paintSplashGlassVelocityWorkImage_,
            paintSplashGlassVelocityWorkMemory_,
            paintSplashGlassVelocityWorkImageView_
        );
        destroyPaintSplashGlassImageLocked(
            paintSplashGlassImpactImage_,
            paintSplashGlassImpactMemory_,
            paintSplashGlassImpactImageView_
        );
        destroyPaintSplashGlassImageLocked(
            paintSplashGlassImpactVelocityImage_,
            paintSplashGlassImpactVelocityMemory_,
            paintSplashGlassImpactVelocityImageView_
        );
        destroyPaintSplashGlassImageLocked(
            paintSplashGlassSphImage_,
            paintSplashGlassSphMemory_,
            paintSplashGlassSphImageView_
        );

        paintSplashGlassSurfaceLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassSurfaceWorkLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassVelocityLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassVelocityWorkLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassImpactLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassImpactVelocityLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassSphLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassNeedsReset_ = true;
        paintSplashGlassDripEndNs_ = 0;
        paintSplashGlassLastSurfaceUpdateNs_ = 0;
        paintSplashGlassLastSphUpdateNs_ = 0;
        paintSplashGlassSurfaceAge_ = 0.0f;
    }

    bool createPaintSplashGlassImagesLocked() {
        if (paintSplashGlassSurfaceImageView_ != VK_NULL_HANDLE &&
            paintSplashGlassSurfaceWorkImageView_ != VK_NULL_HANDLE &&
            paintSplashGlassVelocityImageView_ != VK_NULL_HANDLE &&
            paintSplashGlassVelocityWorkImageView_ != VK_NULL_HANDLE &&
            paintSplashGlassImpactImageView_ != VK_NULL_HANDLE &&
            paintSplashGlassImpactVelocityImageView_ != VK_NULL_HANDLE &&
            paintSplashGlassSphImageView_ != VK_NULL_HANDLE) {
            return true;
        }

        destroyPaintSplashGlassImagesLocked();
        const bool ok =
            createPaintSplashGlassImageLocked(
                paintSplashGlassSurfaceImage_,
                paintSplashGlassSurfaceMemory_,
                paintSplashGlassSurfaceImageView_
            ) &&
            createPaintSplashGlassImageLocked(
                paintSplashGlassSurfaceWorkImage_,
                paintSplashGlassSurfaceWorkMemory_,
                paintSplashGlassSurfaceWorkImageView_
            ) &&
            createPaintSplashGlassImageLocked(
                paintSplashGlassVelocityImage_,
                paintSplashGlassVelocityMemory_,
                paintSplashGlassVelocityImageView_
            ) &&
            createPaintSplashGlassImageLocked(
                paintSplashGlassVelocityWorkImage_,
                paintSplashGlassVelocityWorkMemory_,
                paintSplashGlassVelocityWorkImageView_
            ) &&
            createPaintSplashGlassImageLocked(
                paintSplashGlassImpactImage_,
                paintSplashGlassImpactMemory_,
                paintSplashGlassImpactImageView_
            ) &&
            createPaintSplashGlassImageLocked(
                paintSplashGlassImpactVelocityImage_,
                paintSplashGlassImpactVelocityMemory_,
                paintSplashGlassImpactVelocityImageView_
            ) &&
            createPaintSplashGlassImageWithExtentLocked(
                paintSplashGlassSphExtentLocked(),
                paintSplashGlassSphImage_,
                paintSplashGlassSphMemory_,
                paintSplashGlassSphImageView_
            );
        if (!ok) {
            destroyPaintSplashGlassImagesLocked();
            return false;
        }

        paintSplashGlassSurfaceLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassSurfaceWorkLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassVelocityLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassVelocityWorkLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassImpactLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassImpactVelocityLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassSphLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        paintSplashGlassNeedsReset_ = true;
        return true;
    }

    bool createPaintSplashGlassImpactFramebufferLocked() {
        if (paintSplashGlassImpactFramebuffer_ != VK_NULL_HANDLE) {
            return true;
        }
        if (paintSplashGlassImpactRenderPass_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactVelocityImageView_ == VK_NULL_HANDLE) {
            return false;
        }

        std::array<VkImageView, 2> attachments = {
            paintSplashGlassImpactImageView_,
            paintSplashGlassImpactVelocityImageView_
        };
        const VkExtent2D imageExtent = paintSplashGlassSurfaceExtentLocked();
        if (imageExtent.width == 0 || imageExtent.height == 0) {
            return false;
        }

        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = paintSplashGlassImpactRenderPass_;
        framebufferInfo.attachmentCount = static_cast<uint32_t>(attachments.size());
        framebufferInfo.pAttachments = attachments.data();
        framebufferInfo.width = imageExtent.width;
        framebufferInfo.height = imageExtent.height;
        framebufferInfo.layers = 1;
        return isOk(
            vkCreateFramebuffer(
                device_,
                &framebufferInfo,
                nullptr,
                &paintSplashGlassImpactFramebuffer_
            ),
            "vkCreateFramebuffer paint splash glass impact failed"
        );
    }

    bool createPaintSplashGlassSphFramebufferLocked() {
        if (paintSplashGlassSphFramebuffer_ != VK_NULL_HANDLE) {
            return true;
        }
        if (blobRenderPass_ == VK_NULL_HANDLE ||
            paintSplashGlassSphImageView_ == VK_NULL_HANDLE) {
            return false;
        }

        const VkExtent2D imageExtent = paintSplashGlassSphExtentLocked();
        if (imageExtent.width == 0 || imageExtent.height == 0) {
            return false;
        }

        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = blobRenderPass_;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = &paintSplashGlassSphImageView_;
        framebufferInfo.width = imageExtent.width;
        framebufferInfo.height = imageExtent.height;
        framebufferInfo.layers = 1;
        return isOk(
            vkCreateFramebuffer(
                device_,
                &framebufferInfo,
                nullptr,
                &paintSplashGlassSphFramebuffer_
            ),
            "vkCreateFramebuffer paint splash glass sph failed"
        );
    }

    bool createSplashDescriptorResourcesLocked() {
        if (!ensurePaintSplashGlassTargetBufferLocked() ||
            !ensurePaintSplashGlassEventBuffersLocked() ||
            !ensurePaintSplashGlassSphBuffersLocked()) {
            return false;
        }

        if (splashDescriptorSetLayout_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayoutBinding, 6> bindings{};
            bindings[0].binding = 0;
            bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[0].descriptorCount = 1;
            bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_VERTEX_BIT;
            bindings[1].binding = 1;
            bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[1].descriptorCount = 1;
            bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings[2].binding = 2;
            bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[2].descriptorCount = 1;
            bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings[3].binding = 3;
            bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[3].descriptorCount = 1;
            bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings[4].binding = 4;
            bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[4].descriptorCount = 1;
            bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings[5].binding = 5;
            bindings[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[5].descriptorCount = 1;
            bindings[5].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
            layoutInfo.pBindings = bindings.data();
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &splashDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout splash failed"
                )) {
                return false;
            }
        }

        if (splashDescriptorPool_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorPoolSize, 2> poolSizes{};
            poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            poolSizes[0].descriptorCount = kMaxSplashItems * 5;
            poolSizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSizes[1].descriptorCount = kMaxSplashItems;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
            poolInfo.maxSets = kMaxSplashItems;
            poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
            poolInfo.pPoolSizes = poolSizes.data();
            if (!isOk(
                    vkCreateDescriptorPool(device_, &poolInfo, nullptr, &splashDescriptorPool_),
                    "vkCreateDescriptorPool splash failed"
                )) {
                return false;
            }
        }
        return createPaintSplashGlassImpactDescriptorResourcesLocked() &&
            createPaintSplashGlassSurfaceDescriptorResourcesLocked() &&
            createPaintSplashGlassSphUpdateDescriptorResourcesLocked() &&
            createPaintSplashGlassSphFieldDescriptorResourcesLocked() &&
            createPaintSplashGlassSphCompositeDescriptorResourcesLocked();
    }

    bool createPaintSplashGlassImpactDescriptorResourcesLocked() {
        if (paintSplashGlassImpactDescriptorSetLayout_ == VK_NULL_HANDLE) {
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = 0;
            binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            binding.descriptorCount = 1;
            binding.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = 1;
            layoutInfo.pBindings = &binding;
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &paintSplashGlassImpactDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout paint splash glass impact failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassImpactDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            poolSize.descriptorCount = 1;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(
                    vkCreateDescriptorPool(
                        device_,
                        &poolInfo,
                        nullptr,
                        &paintSplashGlassImpactDescriptorPool_
                    ),
                    "vkCreateDescriptorPool paint splash glass impact failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassImpactDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = paintSplashGlassImpactDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &paintSplashGlassImpactDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(
                        device_,
                        &allocateInfo,
                        &paintSplashGlassImpactDescriptorSet_
                    ),
                    "vkAllocateDescriptorSets paint splash glass impact failed"
                )) {
                return false;
            }
        }

        VkDescriptorBufferInfo eventBufferInfo{};
        eventBufferInfo.buffer = paintSplashGlassEventBuffer_;
        eventBufferInfo.offset = 0;
        eventBufferInfo.range = sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = paintSplashGlassImpactDescriptorSet_;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &eventBufferInfo;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
        return true;
    }

    bool createPaintSplashGlassSurfaceDescriptorResourcesLocked() {
        if (paintSplashGlassSurfaceDescriptorSetLayout_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayoutBinding, 7> bindings{};
            for (uint32_t index = 0; index < 4; ++index) {
                bindings[index].binding = index;
                bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                bindings[index].descriptorCount = 1;
                bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            }
            for (uint32_t index = 4; index < 6; ++index) {
                bindings[index].binding = index;
                bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                bindings[index].descriptorCount = 1;
                bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            }
            bindings[6].binding = 6;
            bindings[6].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[6].descriptorCount = 1;
            bindings[6].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
            layoutInfo.pBindings = bindings.data();
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &paintSplashGlassSurfaceDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout paint splash glass surface failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSurfaceDescriptorPool_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorPoolSize, 3> poolSizes{};
            poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSizes[0].descriptorCount = 4;
            poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            poolSizes[1].descriptorCount = 2;
            poolSizes[2].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            poolSizes[2].descriptorCount = 1;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
            poolInfo.pPoolSizes = poolSizes.data();
            if (!isOk(
                    vkCreateDescriptorPool(
                        device_,
                        &poolInfo,
                        nullptr,
                        &paintSplashGlassSurfaceDescriptorPool_
                    ),
                    "vkCreateDescriptorPool paint splash glass surface failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSurfaceDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = paintSplashGlassSurfaceDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &paintSplashGlassSurfaceDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(
                        device_,
                        &allocateInfo,
                        &paintSplashGlassSurfaceDescriptorSet_
                    ),
                    "vkAllocateDescriptorSets paint splash glass surface failed"
                )) {
                return false;
            }
        }

        updatePaintSplashGlassSurfaceDescriptorLocked();
        return true;
    }

    void updatePaintSplashGlassSurfaceDescriptorLocked() {
        if (paintSplashGlassSurfaceDescriptorSet_ == VK_NULL_HANDLE ||
            blobSampler_ == VK_NULL_HANDLE ||
            paintSplashGlassTargetBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfaceImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassVelocityImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfaceWorkImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassVelocityWorkImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactVelocityImageView_ == VK_NULL_HANDLE) {
            return;
        }

        std::array<VkDescriptorImageInfo, 4> sampledImages{};
        sampledImages[0].sampler = blobSampler_;
        sampledImages[0].imageView = paintSplashGlassSurfaceImageView_;
        sampledImages[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        sampledImages[1].sampler = blobSampler_;
        sampledImages[1].imageView = paintSplashGlassVelocityImageView_;
        sampledImages[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        sampledImages[2].sampler = blobSampler_;
        sampledImages[2].imageView = paintSplashGlassImpactImageView_;
        sampledImages[2].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        sampledImages[3].sampler = blobSampler_;
        sampledImages[3].imageView = paintSplashGlassImpactVelocityImageView_;
        sampledImages[3].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkDescriptorImageInfo, 2> storageImages{};
        storageImages[0].imageView = paintSplashGlassSurfaceWorkImageView_;
        storageImages[0].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        storageImages[1].imageView = paintSplashGlassVelocityWorkImageView_;
        storageImages[1].imageLayout = VK_IMAGE_LAYOUT_GENERAL;

        VkDescriptorBufferInfo targetBufferInfo{};
        targetBufferInfo.buffer = paintSplashGlassTargetBuffer_;
        targetBufferInfo.offset = 0;
        targetBufferInfo.range = sizeof(GpuGlassHitTarget) * kMaxPaintSplashGlassHitTargets;

        std::array<VkWriteDescriptorSet, 7> writes{};
        for (uint32_t index = 0; index < 4; ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = paintSplashGlassSurfaceDescriptorSet_;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[index].pImageInfo = &sampledImages[index];
        }
        for (uint32_t index = 0; index < 2; ++index) {
            const uint32_t writeIndex = index + 4;
            writes[writeIndex].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[writeIndex].dstSet = paintSplashGlassSurfaceDescriptorSet_;
            writes[writeIndex].dstBinding = writeIndex;
            writes[writeIndex].descriptorCount = 1;
            writes[writeIndex].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[writeIndex].pImageInfo = &storageImages[index];
        }
        writes[6].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[6].dstSet = paintSplashGlassSurfaceDescriptorSet_;
        writes[6].dstBinding = 6;
        writes[6].descriptorCount = 1;
        writes[6].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[6].pBufferInfo = &targetBufferInfo;

        vkUpdateDescriptorSets(
            device_,
            static_cast<uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
    }

    bool createPaintSplashGlassSphUpdateDescriptorResourcesLocked() {
        if (paintSplashGlassSphUpdateDescriptorSetLayout_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayoutBinding, 5> bindings{};
            for (uint32_t index = 0; index < 3; ++index) {
                bindings[index].binding = index;
                bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                bindings[index].descriptorCount = 1;
                bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            }
            for (uint32_t index = 3; index < 5; ++index) {
                bindings[index].binding = index;
                bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                bindings[index].descriptorCount = 1;
                bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
            layoutInfo.pBindings = bindings.data();
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &paintSplashGlassSphUpdateDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout paint splash glass sph update failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSphUpdateDescriptorPool_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorPoolSize, 2> poolSizes{};
            poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            poolSizes[0].descriptorCount = 3;
            poolSizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSizes[1].descriptorCount = 2;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
            poolInfo.pPoolSizes = poolSizes.data();
            if (!isOk(
                    vkCreateDescriptorPool(
                        device_,
                        &poolInfo,
                        nullptr,
                        &paintSplashGlassSphUpdateDescriptorPool_
                    ),
                    "vkCreateDescriptorPool paint splash glass sph update failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSphUpdateDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = paintSplashGlassSphUpdateDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &paintSplashGlassSphUpdateDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(
                        device_,
                        &allocateInfo,
                        &paintSplashGlassSphUpdateDescriptorSet_
                    ),
                    "vkAllocateDescriptorSets paint splash glass sph update failed"
                )) {
                return false;
            }
        }

        updatePaintSplashGlassSphUpdateDescriptorLocked();
        return true;
    }

    void updatePaintSplashGlassSphUpdateDescriptorLocked() {
        if (paintSplashGlassSphUpdateDescriptorSet_ == VK_NULL_HANDLE ||
            blobSampler_ == VK_NULL_HANDLE ||
            paintSplashGlassSphParticleBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSphParticleWorkBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassTargetBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfaceImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassVelocityImageView_ == VK_NULL_HANDLE) {
            return;
        }

        std::array<VkDescriptorBufferInfo, 3> buffers{};
        buffers[0].buffer = paintSplashGlassSphParticleBuffer_;
        buffers[0].range = sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles;
        buffers[1].buffer = paintSplashGlassSphParticleWorkBuffer_;
        buffers[1].range = sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles;
        buffers[2].buffer = paintSplashGlassTargetBuffer_;
        buffers[2].range = sizeof(GpuGlassHitTarget) * kMaxPaintSplashGlassHitTargets;

        std::array<VkDescriptorImageInfo, 2> images{};
        images[0].sampler = blobSampler_;
        images[0].imageView = paintSplashGlassSurfaceImageView_;
        images[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        images[1].sampler = blobSampler_;
        images[1].imageView = paintSplashGlassVelocityImageView_;
        images[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkWriteDescriptorSet, 5> writes{};
        for (uint32_t index = 0; index < 3; ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = paintSplashGlassSphUpdateDescriptorSet_;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[index].pBufferInfo = &buffers[index];
        }
        for (uint32_t index = 0; index < 2; ++index) {
            const uint32_t writeIndex = index + 3;
            writes[writeIndex].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[writeIndex].dstSet = paintSplashGlassSphUpdateDescriptorSet_;
            writes[writeIndex].dstBinding = writeIndex;
            writes[writeIndex].descriptorCount = 1;
            writes[writeIndex].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[writeIndex].pImageInfo = &images[index];
        }

        vkUpdateDescriptorSets(
            device_,
            static_cast<uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
    }

    bool createPaintSplashGlassSphFieldDescriptorResourcesLocked() {
        if (paintSplashGlassSphFieldDescriptorSetLayout_ == VK_NULL_HANDLE) {
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = 0;
            binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            binding.descriptorCount = 1;
            binding.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = 1;
            layoutInfo.pBindings = &binding;
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &paintSplashGlassSphFieldDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout paint splash glass sph field failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSphFieldDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            poolSize.descriptorCount = 1;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(
                    vkCreateDescriptorPool(
                        device_,
                        &poolInfo,
                        nullptr,
                        &paintSplashGlassSphFieldDescriptorPool_
                    ),
                    "vkCreateDescriptorPool paint splash glass sph field failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSphFieldDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = paintSplashGlassSphFieldDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &paintSplashGlassSphFieldDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(
                        device_,
                        &allocateInfo,
                        &paintSplashGlassSphFieldDescriptorSet_
                    ),
                    "vkAllocateDescriptorSets paint splash glass sph field failed"
                )) {
                return false;
            }
        }

        updatePaintSplashGlassSphFieldDescriptorLocked();
        return true;
    }

    void updatePaintSplashGlassSphFieldDescriptorLocked() {
        if (paintSplashGlassSphFieldDescriptorSet_ == VK_NULL_HANDLE ||
            paintSplashGlassSphParticleBuffer_ == VK_NULL_HANDLE) {
            return;
        }

        VkDescriptorBufferInfo buffer{};
        buffer.buffer = paintSplashGlassSphParticleBuffer_;
        buffer.offset = 0;
        buffer.range = sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = paintSplashGlassSphFieldDescriptorSet_;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &buffer;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    }

    bool createPaintSplashGlassSphCompositeDescriptorResourcesLocked() {
        if (paintSplashGlassSphCompositeDescriptorSetLayout_ == VK_NULL_HANDLE) {
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = 0;
            binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            binding.descriptorCount = 1;
            binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = 1;
            layoutInfo.pBindings = &binding;
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &paintSplashGlassSphCompositeDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout paint splash glass sph composite failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSphCompositeDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSize.descriptorCount = 1;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(
                    vkCreateDescriptorPool(
                        device_,
                        &poolInfo,
                        nullptr,
                        &paintSplashGlassSphCompositeDescriptorPool_
                    ),
                    "vkCreateDescriptorPool paint splash glass sph composite failed"
                )) {
                return false;
            }
        }

        if (paintSplashGlassSphCompositeDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = paintSplashGlassSphCompositeDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &paintSplashGlassSphCompositeDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(
                        device_,
                        &allocateInfo,
                        &paintSplashGlassSphCompositeDescriptorSet_
                    ),
                    "vkAllocateDescriptorSets paint splash glass sph composite failed"
                )) {
                return false;
            }
        }

        updatePaintSplashGlassSphCompositeDescriptorLocked();
        return true;
    }

    void updatePaintSplashGlassSphCompositeDescriptorLocked() {
        if (paintSplashGlassSphCompositeDescriptorSet_ == VK_NULL_HANDLE ||
            blobSampler_ == VK_NULL_HANDLE ||
            paintSplashGlassSphImageView_ == VK_NULL_HANDLE) {
            return;
        }

        VkDescriptorImageInfo image{};
        image.sampler = blobSampler_;
        image.imageView = paintSplashGlassSphImageView_;
        image.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = paintSplashGlassSphCompositeDescriptorSet_;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &image;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    }

    bool ensurePaintSplashGlassEventBuffersLocked() {
        if (paintSplashGlassEventBuffer_ != VK_NULL_HANDLE &&
            paintSplashGlassEventMemory_ != VK_NULL_HANDLE &&
            paintSplashGlassCursorBuffer_ != VK_NULL_HANDLE &&
            paintSplashGlassCursorMemory_ != VK_NULL_HANDLE &&
            paintSplashGlassCellBuffer_ != VK_NULL_HANDLE &&
            paintSplashGlassCellMemory_ != VK_NULL_HANDLE) {
            return true;
        }

        destroyPaintSplashGlassEventBuffersLocked();

        if (!createBufferLocked(
                sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                paintSplashGlassEventBuffer_,
                paintSplashGlassEventMemory_
            ) ||
            !createBufferLocked(
                sizeof(uint32_t),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                paintSplashGlassCursorBuffer_,
                paintSplashGlassCursorMemory_
            ) ||
            !createBufferLocked(
                sizeof(uint32_t) * kPaintSplashGlassCellCount,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                paintSplashGlassCellBuffer_,
                paintSplashGlassCellMemory_
            )) {
            destroyPaintSplashGlassEventBuffersLocked();
            return false;
        }
        paintSplashGlassNeedsReset_ = true;
        return true;
    }

    bool ensurePaintSplashGlassSphBuffersLocked() {
        if (paintSplashGlassSphParticleBuffer_ != VK_NULL_HANDLE &&
            paintSplashGlassSphParticleMemory_ != VK_NULL_HANDLE &&
            paintSplashGlassSphParticleWorkBuffer_ != VK_NULL_HANDLE &&
            paintSplashGlassSphParticleWorkMemory_ != VK_NULL_HANDLE) {
            return true;
        }

        destroyPaintSplashGlassSphBuffersLocked();
        const VkDeviceSize bufferSize =
            sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles;
        if (!createBufferLocked(
                bufferSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                paintSplashGlassSphParticleBuffer_,
                paintSplashGlassSphParticleMemory_
            ) ||
            !createBufferLocked(
                bufferSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                paintSplashGlassSphParticleWorkBuffer_,
                paintSplashGlassSphParticleWorkMemory_
            )) {
            destroyPaintSplashGlassSphBuffersLocked();
            return false;
        }
        paintSplashGlassNeedsReset_ = true;
        return true;
    }

    bool ensurePaintSplashGlassTargetBufferLocked() {
        if (paintSplashGlassTargetBuffer_ != VK_NULL_HANDLE &&
            paintSplashGlassTargetMemory_ != VK_NULL_HANDLE &&
            paintSplashGlassTargetMapped_ != nullptr) {
            return true;
        }

        const VkDeviceSize bufferSize =
            sizeof(GpuGlassHitTarget) * kMaxPaintSplashGlassHitTargets;
        if (!createBufferLocked(
                bufferSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                paintSplashGlassTargetBuffer_,
                paintSplashGlassTargetMemory_
            )) {
            return false;
        }

        if (!isOk(
                vkMapMemory(
                    device_,
                    paintSplashGlassTargetMemory_,
                    0,
                    bufferSize,
                    0,
                    reinterpret_cast<void**>(&paintSplashGlassTargetMapped_)
                ),
                "vkMapMemory paint splash glass targets failed"
            )) {
            destroyPaintSplashGlassTargetBufferLocked();
            return false;
        }

        std::fill_n(
            paintSplashGlassTargetMapped_,
            kMaxPaintSplashGlassHitTargets,
            GpuGlassHitTarget{}
        );
        return true;
    }

    void updatePaintSplashGlassTargetsLocked() {
        paintSplashGlassTargetCount_ = 0;
        if (paintSplashGlassTargetMapped_ == nullptr) {
            return;
        }

        for (uint32_t index = 0;
             index < static_cast<uint32_t>(backdropRects_.size()) &&
                 paintSplashGlassTargetCount_ < kMaxPaintSplashGlassHitTargets;
            ++index) {
            const BackdropRect& rect = backdropRects_[index];
            const float width = rect.right - rect.left;
            const float height = rect.bottom - rect.top;
            if (width <= 0.0f || height <= 0.0f || rect.opacity <= 0.0f) {
                continue;
            }

            GpuGlassHitTarget& target =
                paintSplashGlassTargetMapped_[paintSplashGlassTargetCount_];
            target.rect[0] = rect.left;
            target.rect[1] = rect.top;
            target.rect[2] = width;
            target.rect[3] = height;
            target.params[0] = std::max(0.0f, rect.cornerRadius);
            target.params[1] = 0.16f * std::clamp(rect.opacity, 0.0f, 1.0f);
            target.params[2] = rect.shapeKind;
            target.params[3] =
                static_cast<float>(paintSplashGlassTargetCount_) * 0.91f +
                rect.left * 0.017f +
                rect.top * 0.031f;
            paintSplashGlassTargetCount_ += 1;
        }
    }

    void destroyPaintSplashGlassTargetBufferLocked() {
        paintSplashGlassTargetCount_ = 0;
        if (device_ == VK_NULL_HANDLE) {
            paintSplashGlassTargetBuffer_ = VK_NULL_HANDLE;
            paintSplashGlassTargetMemory_ = VK_NULL_HANDLE;
            paintSplashGlassTargetMapped_ = nullptr;
            return;
        }
        if (paintSplashGlassTargetMapped_ != nullptr) {
            vkUnmapMemory(device_, paintSplashGlassTargetMemory_);
            paintSplashGlassTargetMapped_ = nullptr;
        }
        if (paintSplashGlassTargetBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, paintSplashGlassTargetBuffer_, nullptr);
            paintSplashGlassTargetBuffer_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassTargetMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, paintSplashGlassTargetMemory_, nullptr);
            paintSplashGlassTargetMemory_ = VK_NULL_HANDLE;
        }
    }

    void destroyPaintSplashGlassEventBuffersLocked() {
        if (device_ == VK_NULL_HANDLE) {
            paintSplashGlassEventBuffer_ = VK_NULL_HANDLE;
            paintSplashGlassEventMemory_ = VK_NULL_HANDLE;
            paintSplashGlassCursorBuffer_ = VK_NULL_HANDLE;
            paintSplashGlassCursorMemory_ = VK_NULL_HANDLE;
            paintSplashGlassCellBuffer_ = VK_NULL_HANDLE;
            paintSplashGlassCellMemory_ = VK_NULL_HANDLE;
            return;
        }
        if (paintSplashGlassEventBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, paintSplashGlassEventBuffer_, nullptr);
            paintSplashGlassEventBuffer_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassEventMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, paintSplashGlassEventMemory_, nullptr);
            paintSplashGlassEventMemory_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassCursorBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, paintSplashGlassCursorBuffer_, nullptr);
            paintSplashGlassCursorBuffer_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassCursorMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, paintSplashGlassCursorMemory_, nullptr);
            paintSplashGlassCursorMemory_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassCellBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, paintSplashGlassCellBuffer_, nullptr);
            paintSplashGlassCellBuffer_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassCellMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, paintSplashGlassCellMemory_, nullptr);
            paintSplashGlassCellMemory_ = VK_NULL_HANDLE;
        }
    }

    void destroyPaintSplashGlassSphBuffersLocked() {
        if (device_ == VK_NULL_HANDLE) {
            paintSplashGlassSphParticleBuffer_ = VK_NULL_HANDLE;
            paintSplashGlassSphParticleMemory_ = VK_NULL_HANDLE;
            paintSplashGlassSphParticleWorkBuffer_ = VK_NULL_HANDLE;
            paintSplashGlassSphParticleWorkMemory_ = VK_NULL_HANDLE;
            return;
        }
        if (paintSplashGlassSphParticleBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, paintSplashGlassSphParticleBuffer_, nullptr);
            paintSplashGlassSphParticleBuffer_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphParticleMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, paintSplashGlassSphParticleMemory_, nullptr);
            paintSplashGlassSphParticleMemory_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphParticleWorkBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, paintSplashGlassSphParticleWorkBuffer_, nullptr);
            paintSplashGlassSphParticleWorkBuffer_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphParticleWorkMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, paintSplashGlassSphParticleWorkMemory_, nullptr);
            paintSplashGlassSphParticleWorkMemory_ = VK_NULL_HANDLE;
        }
    }

    bool createBackdropDescriptorResourcesLocked() {
        if (backdropSampler_ == VK_NULL_HANDLE) {
            VkSamplerCreateInfo samplerInfo{};
            samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
            samplerInfo.magFilter = VK_FILTER_LINEAR;
            samplerInfo.minFilter = VK_FILTER_LINEAR;
            samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
            samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.maxLod = 1.0f;
            if (!isOk(vkCreateSampler(device_, &samplerInfo, nullptr, &backdropSampler_),
                      "vkCreateSampler backdrop failed")) {
                return false;
            }
        }

        if (backdropDescriptorSetLayout_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
            bindings[0].binding = 0;
            bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[0].descriptorCount = 1;
            bindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
            bindings[1].binding = 1;
            bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[1].descriptorCount = 1;
            bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
            bindings[2].binding = 2;
            bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[2].descriptorCount = 1;
            bindings[2].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
            layoutInfo.pBindings = bindings.data();
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &backdropDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout backdrop failed"
                )) {
                return false;
            }
        }

        if (backdropDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSize.descriptorCount = 3;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &backdropDescriptorPool_),
                      "vkCreateDescriptorPool backdrop failed")) {
                return false;
            }
        }

        if (backdropDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = backdropDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &backdropDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(device_, &allocateInfo, &backdropDescriptorSet_),
                    "vkAllocateDescriptorSets backdrop failed"
                )) {
                return false;
            }
        }

        if (activeBackdropEntry_ != nullptr &&
            activeBackdropEntry_->imageView != VK_NULL_HANDLE &&
            backdropBlurImageView_ != VK_NULL_HANDLE) {
            updateBackdropDescriptorLocked();
        }
        return true;
    }

    void updateBackdropDescriptorLocked() {
        if (activeBackdropEntry_ == nullptr) {
            return;
        }
        updateBackdropDescriptorLocked(activeBackdropEntry_->imageView);
    }

    void updateBackdropDescriptorLocked(VkImageView clearImageView) {
        if (backdropDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            clearImageView == VK_NULL_HANDLE ||
            backdropBlurImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfaceImageView_ == VK_NULL_HANDLE) {
            return;
        }

        std::array<VkDescriptorImageInfo, 3> imageInfos{};
        imageInfos[0].sampler = backdropSampler_;
        imageInfos[0].imageView = clearImageView;
        imageInfos[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageInfos[1].sampler = backdropSampler_;
        imageInfos[1].imageView = backdropBlurImageView_;
        imageInfos[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageInfos[2].sampler = blobSampler_ != VK_NULL_HANDLE ? blobSampler_ : backdropSampler_;
        imageInfos[2].imageView = paintSplashGlassSurfaceImageView_;
        imageInfos[2].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkWriteDescriptorSet, 3> writes{};
        for (uint32_t index = 0; index < static_cast<uint32_t>(writes.size()); ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = backdropDescriptorSet_;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[index].pImageInfo = &imageInfos[index];
        }
        vkUpdateDescriptorSets(
            device_,
            static_cast<uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
    }

    bool createTeleportDescriptorResourcesLocked() {
        if (teleportDescriptorSetLayout_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
            for (uint32_t index = 0; index < bindings.size(); ++index) {
                bindings[index].binding = index;
                bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                bindings[index].descriptorCount = 1;
                bindings[index].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
            layoutInfo.pBindings = bindings.data();
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &teleportDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout teleport failed"
                )) {
                return false;
            }
        }

        if (teleportDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSize.descriptorCount = 2;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(
                    vkCreateDescriptorPool(device_, &poolInfo, nullptr, &teleportDescriptorPool_),
                    "vkCreateDescriptorPool teleport failed"
                )) {
                return false;
            }
        }

        if (teleportDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = teleportDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &teleportDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(device_, &allocateInfo, &teleportDescriptorSet_),
                    "vkAllocateDescriptorSets teleport failed"
                )) {
                return false;
            }
        }
        updateTeleportDescriptorLocked();
        return true;
    }

    void updateTeleportDescriptorLocked() {
        if (teleportDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            teleportOldEntry_.imageView == VK_NULL_HANDLE ||
            teleportNewEntry_.imageView == VK_NULL_HANDLE) {
            return;
        }

        std::array<VkDescriptorImageInfo, 2> imageInfos{};
        imageInfos[0].sampler = backdropSampler_;
        imageInfos[0].imageView = teleportOldEntry_.imageView;
        imageInfos[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageInfos[1].sampler = backdropSampler_;
        imageInfos[1].imageView = teleportNewEntry_.imageView;
        imageInfos[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkWriteDescriptorSet, 2> writes{};
        for (uint32_t index = 0; index < writes.size(); ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = teleportDescriptorSet_;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[index].pImageInfo = &imageInfos[index];
        }
        vkUpdateDescriptorSets(
            device_,
            static_cast<uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
    }

    bool createBackdropStatsResourcesLocked() {
        if (!createBackdropStatsBufferLocked()) {
            return false;
        }

        if (backdropStatsDescriptorSetLayout_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
            bindings[0].binding = 0;
            bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[0].descriptorCount = 1;
            bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings[1].binding = 1;
            bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[1].descriptorCount = 1;
            bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
            layoutInfo.pBindings = bindings.data();
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &backdropStatsDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout backdrop stats failed"
                )) {
                return false;
            }
        }

        if (backdropStatsDescriptorPool_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorPoolSize, 2> poolSizes{};
            poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSizes[0].descriptorCount = 1;
            poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            poolSizes[1].descriptorCount = 1;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
            poolInfo.pPoolSizes = poolSizes.data();
            if (!isOk(
                    vkCreateDescriptorPool(
                        device_,
                        &poolInfo,
                        nullptr,
                        &backdropStatsDescriptorPool_
                    ),
                    "vkCreateDescriptorPool backdrop stats failed"
                )) {
                return false;
            }
        }

        if (backdropStatsDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = backdropStatsDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &backdropStatsDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(
                        device_,
                        &allocateInfo,
                        &backdropStatsDescriptorSet_
                    ),
                    "vkAllocateDescriptorSets backdrop stats failed"
                )) {
                return false;
            }
        }

        updateBackdropStatsDescriptorLocked();
        return true;
    }

    bool createBackdropStatsBufferLocked() {
        if (backdropStatsBuffer_ != VK_NULL_HANDLE &&
            backdropStatsBufferMemory_ != VK_NULL_HANDLE &&
            backdropStatsMapped_ != nullptr) {
            return true;
        }

        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = kBackdropStatsBufferSize;
        bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(
                vkCreateBuffer(device_, &bufferInfo, nullptr, &backdropStatsBuffer_),
                "vkCreateBuffer backdrop stats failed"
            )) {
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetBufferMemoryRequirements(device_, backdropStatsBuffer_, &memoryRequirements);

        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(
                memoryRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                memoryTypeIndex
            )) {
            logWarn("No host coherent memory for backdrop stats buffer");
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(
                vkAllocateMemory(device_, &allocateInfo, nullptr, &backdropStatsBufferMemory_),
                "vkAllocateMemory backdrop stats failed"
            )) {
            return false;
        }
        if (!isOk(
                vkBindBufferMemory(device_, backdropStatsBuffer_, backdropStatsBufferMemory_, 0),
                "vkBindBufferMemory backdrop stats failed"
            )) {
            return false;
        }
        if (!isOk(
                vkMapMemory(
                    device_,
                    backdropStatsBufferMemory_,
                    0,
                    kBackdropStatsBufferSize,
                    0,
                    reinterpret_cast<void**>(&backdropStatsMapped_)
                ),
                "vkMapMemory backdrop stats failed"
            )) {
            return false;
        }
        std::fill(
            backdropStatsMapped_,
            backdropStatsMapped_ + kBackdropStatsBufferSize / sizeof(float),
            0.0f
        );
        return true;
    }

    void updateBackdropStatsDescriptorLocked() {
        if (activeBackdropEntry_ == nullptr) {
            return;
        }
        updateBackdropStatsDescriptorLocked(activeBackdropEntry_->imageView);
    }

    void updateBackdropStatsDescriptorLocked(VkImageView sourceImageView) {
        if (backdropStatsDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            backdropStatsBuffer_ == VK_NULL_HANDLE ||
            sourceImageView == VK_NULL_HANDLE) {
            return;
        }

        VkDescriptorImageInfo imageInfo{};
        imageInfo.sampler = backdropSampler_;
        imageInfo.imageView = sourceImageView;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkDescriptorBufferInfo bufferInfo{};
        bufferInfo.buffer = backdropStatsBuffer_;
        bufferInfo.offset = 0;
        bufferInfo.range = kBackdropStatsBufferSize;

        std::array<VkWriteDescriptorSet, 2> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = backdropStatsDescriptorSet_;
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[0].pImageInfo = &imageInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = backdropStatsDescriptorSet_;
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[1].pBufferInfo = &bufferInfo;

        vkUpdateDescriptorSets(
            device_,
            static_cast<uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
    }

    bool createBackdropOverlayResourcesLocked() {
        if (backdropOverlayDescriptorSetLayout_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
            bindings[0].binding = 0;
            bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[0].descriptorCount = 1;
            bindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
            bindings[1].binding = 1;
            bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[1].descriptorCount = 1;
            bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
            layoutInfo.pBindings = bindings.data();
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &backdropOverlayDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout backdrop overlay failed"
                )) {
                return false;
            }
        }

        if (backdropOverlayDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSize.descriptorCount = 2;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(
                    vkCreateDescriptorPool(
                        device_,
                        &poolInfo,
                        nullptr,
                        &backdropOverlayDescriptorPool_
                    ),
                    "vkCreateDescriptorPool backdrop overlay failed"
                )) {
                return false;
            }
        }

        if (backdropOverlayDescriptorSet_ == VK_NULL_HANDLE) {
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = backdropOverlayDescriptorPool_;
            allocateInfo.descriptorSetCount = 1;
            allocateInfo.pSetLayouts = &backdropOverlayDescriptorSetLayout_;
            if (!isOk(
                    vkAllocateDescriptorSets(
                        device_,
                        &allocateInfo,
                        &backdropOverlayDescriptorSet_
                    ),
                    "vkAllocateDescriptorSets backdrop overlay failed"
                )) {
                return false;
            }
        }

        updateBackdropOverlayDescriptorLocked();
        return true;
    }

    void updateBackdropOverlayDescriptorLocked() {
        if (backdropOverlayDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            blobSampler_ == VK_NULL_HANDLE ||
            activeBackdropEntry_ == nullptr ||
            activeBackdropEntry_->imageView == VK_NULL_HANDLE ||
            blobImageView_ == VK_NULL_HANDLE) {
            return;
        }

        std::array<VkDescriptorImageInfo, 2> imageInfos{};
        imageInfos[0].sampler = backdropSampler_;
        imageInfos[0].imageView = activeBackdropEntry_->imageView;
        imageInfos[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageInfos[1].sampler = blobSampler_;
        imageInfos[1].imageView = blobImageView_;
        imageInfos[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkWriteDescriptorSet, 2> writes{};
        for (uint32_t index = 0; index < static_cast<uint32_t>(writes.size()); ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = backdropOverlayDescriptorSet_;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[index].pImageInfo = &imageInfos[index];
        }

        vkUpdateDescriptorSets(
            device_,
            static_cast<uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
    }

    bool createBackdropBlurResourcesLocked() {
        return createBackdropBlurDescriptorResourcesLocked() &&
            createBackdropBlurRenderPassLocked();
    }

    bool createBackdropBlurDescriptorResourcesLocked() {
        if (backdropBlurDescriptorSetLayout_ == VK_NULL_HANDLE) {
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = 0;
            binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            binding.descriptorCount = 1;
            binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = 1;
            layoutInfo.pBindings = &binding;
            if (!isOk(
                    vkCreateDescriptorSetLayout(
                        device_,
                        &layoutInfo,
                        nullptr,
                        &backdropBlurDescriptorSetLayout_
                    ),
                    "vkCreateDescriptorSetLayout backdrop blur failed"
                )) {
                return false;
            }
        }

        if (backdropBlurDescriptorPool_ == VK_NULL_HANDLE) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            poolSize.descriptorCount = 2;

            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 2;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!isOk(
                    vkCreateDescriptorPool(device_, &poolInfo, nullptr, &backdropBlurDescriptorPool_),
                    "vkCreateDescriptorPool backdrop blur failed"
                )) {
                return false;
            }
        }

        if (backdropBlurSourceDescriptorSet_ == VK_NULL_HANDLE ||
            backdropBlurTempDescriptorSet_ == VK_NULL_HANDLE) {
            std::array<VkDescriptorSetLayout, 2> layouts = {
                backdropBlurDescriptorSetLayout_,
                backdropBlurDescriptorSetLayout_
            };
            std::array<VkDescriptorSet, 2> sets{};
            VkDescriptorSetAllocateInfo allocateInfo{};
            allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocateInfo.descriptorPool = backdropBlurDescriptorPool_;
            allocateInfo.descriptorSetCount = static_cast<uint32_t>(sets.size());
            allocateInfo.pSetLayouts = layouts.data();
            if (!isOk(
                    vkAllocateDescriptorSets(device_, &allocateInfo, sets.data()),
                    "vkAllocateDescriptorSets backdrop blur failed"
                )) {
                return false;
            }
            backdropBlurSourceDescriptorSet_ = sets[0];
            backdropBlurTempDescriptorSet_ = sets[1];
        }
        return true;
    }

    bool createBackdropBlurRenderPassLocked() {
        if (backdropBlurRenderPass_ != VK_NULL_HANDLE) {
            return true;
        }

        VkAttachmentDescription colorAttachment{};
        colorAttachment.format = backdropBlurFormat_;
        colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAttachment.initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        colorAttachment.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkAttachmentReference colorAttachmentRef{};
        colorAttachmentRef.attachment = 0;
        colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorAttachmentRef;

        std::array<VkSubpassDependency, 2> dependencies{};
        dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[0].dstSubpass = 0;
        dependencies[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].srcSubpass = 0;
        dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &colorAttachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = static_cast<uint32_t>(dependencies.size());
        renderPassInfo.pDependencies = dependencies.data();

        return isOk(
            vkCreateRenderPass(device_, &renderPassInfo, nullptr, &backdropBlurRenderPass_),
            "vkCreateRenderPass backdrop blur failed"
        );
    }

    bool createBackdropOverlayPipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(BackdropOverlayPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &backdropOverlayDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(
                    device_,
                    &layoutInfo,
                    nullptr,
                    &backdropOverlayPipelineLayout_
                ),
                "vkCreatePipelineLayout backdrop overlay failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kChatBackdropBlurVertSpv,
            sizeof(kChatBackdropBlurVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kChatBackdropOverlayFragSpv,
            sizeof(kChatBackdropOverlayFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, backdropOverlayPipelineLayout_, nullptr);
            backdropOverlayPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_FALSE;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = backdropOverlayPipelineLayout_;
        pipelineInfo.renderPass = backdropBlurRenderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &backdropOverlayPipeline_
            ),
            "vkCreateGraphicsPipelines backdrop overlay failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, backdropOverlayPipelineLayout_, nullptr);
            backdropOverlayPipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool ensureBackdropBlurSizeResourcesLocked(uint32_t width, uint32_t height) {
        if (width == 0 || height == 0) {
            return false;
        }
        if (backdropBlurWidth_ == width &&
            backdropBlurHeight_ == height &&
            backdropCompositeImageView_ != VK_NULL_HANDLE &&
            backdropCompositeFramebuffer_ != VK_NULL_HANDLE &&
            backdropBlurTempImageView_ != VK_NULL_HANDLE &&
            backdropBlurImageView_ != VK_NULL_HANDLE &&
            backdropBlurTempFramebuffer_ != VK_NULL_HANDLE &&
            backdropBlurFramebuffer_ != VK_NULL_HANDLE) {
            return true;
        }

        destroyBackdropBlurSizeResourcesLocked();
        backdropBlurWidth_ = width;
        backdropBlurHeight_ = height;
        if (!createBackdropBlurImageLocked(
                width,
                height,
                backdropCompositeImage_,
                backdropCompositeImageMemory_,
                backdropCompositeImageView_
            ) ||
            !createBackdropBlurImageLocked(
                width,
                height,
                backdropBlurTempImage_,
                backdropBlurTempImageMemory_,
                backdropBlurTempImageView_
            ) ||
            !createBackdropBlurImageLocked(
                width,
                height,
                backdropBlurImage_,
                backdropBlurImageMemory_,
                backdropBlurImageView_
            ) ||
            !createBackdropBlurFramebufferLocked(
                width,
                height,
                backdropBlurTempImageView_,
                backdropBlurTempFramebuffer_
            ) ||
            !createBackdropBlurFramebufferLocked(
                width,
                height,
                backdropCompositeImageView_,
                backdropCompositeFramebuffer_
            ) ||
            !createBackdropBlurFramebufferLocked(
                width,
                height,
                backdropBlurImageView_,
                backdropBlurFramebuffer_
            )) {
            destroyBackdropBlurSizeResourcesLocked();
            return false;
        }

        backdropCompositeLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        backdropBlurTempLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        backdropBlurLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        updateBackdropBlurTempDescriptorLocked();
        updateBackdropDescriptorLocked();
        return true;
    }

    bool createBackdropBlurImageLocked(
        uint32_t width,
        uint32_t height,
        VkImage& image,
        VkDeviceMemory& memory,
        VkImageView& imageView
    ) {
        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = width;
        imageInfo.extent.height = height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = backdropBlurFormat_;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(vkCreateImage(device_, &imageInfo, nullptr, &image),
                  "vkCreateImage backdrop blur failed")) {
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetImageMemoryRequirements(device_, image, &memoryRequirements);
        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(
                memoryRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                memoryTypeIndex
            )) {
            logWarn("No device local memory for backdrop blur image");
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(vkAllocateMemory(device_, &allocateInfo, nullptr, &memory),
                  "vkAllocateMemory backdrop blur failed")) {
            return false;
        }
        if (!isOk(vkBindImageMemory(device_, image, memory, 0),
                  "vkBindImageMemory backdrop blur failed")) {
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = backdropBlurFormat_;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        return isOk(
            vkCreateImageView(device_, &viewInfo, nullptr, &imageView),
            "vkCreateImageView backdrop blur failed"
        );
    }

    bool createBackdropBlurFramebufferLocked(
        uint32_t width,
        uint32_t height,
        VkImageView imageView,
        VkFramebuffer& framebuffer
    ) {
        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = backdropBlurRenderPass_;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = &imageView;
        framebufferInfo.width = width;
        framebufferInfo.height = height;
        framebufferInfo.layers = 1;
        return isOk(
            vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffer),
            "vkCreateFramebuffer backdrop blur failed"
        );
    }

    void updateBackdropBlurSourceDescriptorLocked() {
        if (activeBackdropEntry_ == nullptr) {
            return;
        }
        updateBackdropBlurSourceDescriptorLocked(activeBackdropEntry_->imageView);
    }

    void updateBackdropBlurSourceDescriptorLocked(VkImageView sourceImageView) {
        if (backdropBlurSourceDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            sourceImageView == VK_NULL_HANDLE) {
            return;
        }
        updateSingleImageDescriptorLocked(
            backdropBlurSourceDescriptorSet_,
            sourceImageView
        );
    }

    void updateBackdropSourceDescriptorsLocked(VkImageView sourceImageView) {
        if (sourceImageView == VK_NULL_HANDLE) {
            return;
        }
        updateBackdropStatsDescriptorLocked(sourceImageView);
        updateBackdropBlurSourceDescriptorLocked(sourceImageView);
        updateBackdropDescriptorLocked(sourceImageView);
    }

    void updateBackdropBlurTempDescriptorLocked() {
        if (backdropBlurTempDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            backdropBlurTempImageView_ == VK_NULL_HANDLE) {
            return;
        }
        updateSingleImageDescriptorLocked(
            backdropBlurTempDescriptorSet_,
            backdropBlurTempImageView_
        );
    }

    void updateSingleImageDescriptorLocked(VkDescriptorSet descriptorSet, VkImageView imageView) {
        VkDescriptorImageInfo imageInfo{};
        imageInfo.sampler = backdropSampler_;
        imageInfo.imageView = imageView;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descriptorSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageInfo;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    }

    bool findMemoryTypeLocked(
        uint32_t typeFilter,
        VkMemoryPropertyFlags properties,
        uint32_t& outIndex
    ) const {
        VkPhysicalDeviceMemoryProperties memoryProperties{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memoryProperties);

        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
            if ((typeFilter & (1u << index)) == 0) {
                continue;
            }
            if ((memoryProperties.memoryTypes[index].propertyFlags & properties) == properties) {
                outIndex = index;
                return true;
            }
        }
        return false;
    }

    bool importBackdropHardwareBufferLocked(JNIEnv* env, jobject hardwareBuffer) {
        const int64_t importStartNs = nowNanos();
        if (device_ == VK_NULL_HANDLE ||
            !hardwareBufferImportSupported_ ||
            getAndroidHardwareBufferProperties_ == nullptr ||
            hardwareBuffer == nullptr) {
            logWarn("Backdrop AHB import unavailable");
            clearBackdropLocked();
            return false;
        }

        const int64_t fromJavaStartNs = nowNanos();
        AHardwareBuffer* nativeBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
        const int64_t fromJavaNs = nowNanos() - fromJavaStartNs;
        if (nativeBuffer == nullptr) {
            logWarn("Backdrop AHardwareBuffer_fromHardwareBuffer failed");
            clearBackdropLocked();
            return false;
        }

        const int64_t fenceStartNs = nowNanos();
        waitForFrameFenceLocked();
        const int64_t fenceNs = nowNanos() - fenceStartNs;

        const int64_t cacheStartNs = nowNanos();
        if (BackdropImportEntry* cachedEntry = findBackdropImportEntryLocked(nativeBuffer)) {
            activeBackdropEntry_ = cachedEntry;
            backdropWidth_ = cachedEntry->width;
            backdropHeight_ = cachedEntry->height;
            backdropNeedsTransition_ = true;
            if (!ensureBackdropBlurSizeResourcesLocked(
                    static_cast<uint32_t>(cachedEntry->width),
                    static_cast<uint32_t>(cachedEntry->height)
                )) {
                clearBackdropLocked();
                return false;
            }
            const int64_t descriptorStartNs = nowNanos();
            updateBackdropSourceDescriptorsLocked(cachedEntry->imageView);
            updateBackdropOverlayDescriptorLocked();
            const int64_t descriptorNs = nowNanos() - descriptorStartNs;
            const int64_t cacheNs = nowNanos() - cacheStartNs;
            const int64_t importTotalNs = nowNanos() - importStartNs;
            if (kLogBackdropImportTiming) {
                __android_log_print(
                    ANDROID_LOG_DEBUG,
                    kTag,
                    "Backdrop AHB cache hit width=%d height=%d totalMs=%.3f "
                    "fromJavaMs=%.3f fenceMs=%.3f cacheMs=%.3f descMs=%.3f",
                    cachedEntry->width,
                    cachedEntry->height,
                    nanosToMillis(importTotalNs),
                    nanosToMillis(fromJavaNs),
                    nanosToMillis(fenceNs),
                    nanosToMillis(cacheNs),
                    nanosToMillis(descriptorNs)
                );
            }
            return true;
        }
        const int64_t cacheNs = nowNanos() - cacheStartNs;

        evictBackdropImportCacheEntryIfNeededLocked();
        AHardwareBuffer_acquire(nativeBuffer);

        const int64_t describeStartNs = nowNanos();
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(nativeBuffer, &desc);
        const int64_t describeNs = nowNanos() - describeStartNs;
        if (desc.width == 0 || desc.height == 0 || desc.layers != 1) {
            logWarn("Unsupported backdrop AHB dimensions");
            AHardwareBuffer_release(nativeBuffer);
            backdropRects_.clear();
            return false;
        }

        VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{};
        formatProperties.sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID bufferProperties{};
        bufferProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        bufferProperties.pNext = &formatProperties;
        const int64_t propertiesStartNs = nowNanos();
        if (!isOk(
                getAndroidHardwareBufferProperties_(
                    device_,
                    nativeBuffer,
                    &bufferProperties
                ),
                "vkGetAndroidHardwareBufferPropertiesANDROID backdrop failed"
            )) {
            AHardwareBuffer_release(nativeBuffer);
            backdropRects_.clear();
            return false;
        }
        const int64_t propertiesNs = nowNanos() - propertiesStartNs;

        if (formatProperties.format == VK_FORMAT_UNDEFINED) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "Unsupported external-only backdrop format format=%d external=0x%llx",
                formatProperties.format,
                static_cast<unsigned long long>(formatProperties.externalFormat)
            );
            AHardwareBuffer_release(nativeBuffer);
            backdropRects_.clear();
            return false;
        }

        VkImage importedImage = VK_NULL_HANDLE;
        VkDeviceMemory importedMemory = VK_NULL_HANDLE;
        VkImageView importedView = VK_NULL_HANDLE;
        auto cleanupFailedImport = [&]() {
            if (device_ != VK_NULL_HANDLE) {
                if (importedView != VK_NULL_HANDLE) {
                    vkDestroyImageView(device_, importedView, nullptr);
                    importedView = VK_NULL_HANDLE;
                }
                if (importedImage != VK_NULL_HANDLE) {
                    vkDestroyImage(device_, importedImage, nullptr);
                    importedImage = VK_NULL_HANDLE;
                }
                if (importedMemory != VK_NULL_HANDLE) {
                    vkFreeMemory(device_, importedMemory, nullptr);
                    importedMemory = VK_NULL_HANDLE;
                }
            }
            AHardwareBuffer_release(nativeBuffer);
        };

        VkExternalMemoryImageCreateInfo externalImageInfo{};
        externalImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        externalImageInfo.handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.pNext = &externalImageInfo;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = desc.width;
        imageInfo.extent.height = desc.height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = formatProperties.format;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        const int64_t createImageStartNs = nowNanos();
        if (!isOk(vkCreateImage(device_, &imageInfo, nullptr, &importedImage),
                  "vkCreateImage backdrop import failed")) {
            cleanupFailedImport();
            backdropRects_.clear();
            return false;
        }
        const int64_t createImageNs = nowNanos() - createImageStartNs;

        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(bufferProperties.memoryTypeBits, 0, memoryTypeIndex)) {
            logWarn("No memory type for backdrop AHB");
            cleanupFailedImport();
            backdropRects_.clear();
            return false;
        }

        VkMemoryDedicatedAllocateInfo dedicatedInfo{};
        dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
        dedicatedInfo.image = importedImage;

        VkImportAndroidHardwareBufferInfoANDROID importInfo{};
        importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        importInfo.pNext = &dedicatedInfo;
        importInfo.buffer = nativeBuffer;

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.pNext = &importInfo;
        allocateInfo.allocationSize = bufferProperties.allocationSize;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        const int64_t allocateStartNs = nowNanos();
        if (!isOk(vkAllocateMemory(device_, &allocateInfo, nullptr, &importedMemory),
                  "vkAllocateMemory backdrop import failed")) {
            cleanupFailedImport();
            backdropRects_.clear();
            return false;
        }
        const int64_t allocateNs = nowNanos() - allocateStartNs;
        const int64_t bindStartNs = nowNanos();
        if (!isOk(vkBindImageMemory(device_, importedImage, importedMemory, 0),
                  "vkBindImageMemory backdrop failed")) {
            cleanupFailedImport();
            backdropRects_.clear();
            return false;
        }
        const int64_t bindNs = nowNanos() - bindStartNs;

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = importedImage;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = formatProperties.format;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        const int64_t viewStartNs = nowNanos();
        if (!isOk(vkCreateImageView(device_, &viewInfo, nullptr, &importedView),
                  "vkCreateImageView backdrop failed")) {
            cleanupFailedImport();
            backdropRects_.clear();
            return false;
        }
        const int64_t viewNs = nowNanos() - viewStartNs;

        auto entry = std::make_unique<BackdropImportEntry>();
        entry->hardwareBuffer = nativeBuffer;
        entry->image = importedImage;
        entry->memory = importedMemory;
        entry->imageView = importedView;
        entry->width = static_cast<int>(desc.width);
        entry->height = static_cast<int>(desc.height);
        entry->format = formatProperties.format;
        BackdropImportEntry* entryPtr = entry.get();
        backdropImportCache_.push_back(std::move(entry));

        activeBackdropEntry_ = entryPtr;
        backdropWidth_ = entryPtr->width;
        backdropHeight_ = entryPtr->height;
        backdropNeedsTransition_ = true;
        if (!ensureBackdropBlurSizeResourcesLocked(
                static_cast<uint32_t>(entryPtr->width),
                static_cast<uint32_t>(entryPtr->height)
            )) {
            clearBackdropLocked();
            return false;
        }
        const int64_t descriptorStartNs = nowNanos();
        updateBackdropSourceDescriptorsLocked(entryPtr->imageView);
        updateBackdropOverlayDescriptorLocked();
        const int64_t descriptorNs = nowNanos() - descriptorStartNs;
        const int64_t importTotalNs = nowNanos() - importStartNs;
        if (kLogBackdropImportTiming) {
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kTag,
                "Backdrop AHB imported width=%u height=%u stride=%u format=%d usage=0x%llx "
                "vkFormat=%d totalMs=%.3f fromJavaMs=%.3f fenceMs=%.3f cacheMs=%.3f "
                "describeMs=%.3f propsMs=%.3f createImageMs=%.3f allocMs=%.3f bindMs=%.3f "
                "viewMs=%.3f descMs=%.3f",
                desc.width,
                desc.height,
                desc.stride,
                desc.format,
                static_cast<unsigned long long>(desc.usage),
                formatProperties.format,
                nanosToMillis(importTotalNs),
                nanosToMillis(fromJavaNs),
                nanosToMillis(fenceNs),
                nanosToMillis(cacheNs),
                nanosToMillis(describeNs),
                nanosToMillis(propertiesNs),
                nanosToMillis(createImageNs),
                nanosToMillis(allocateNs),
                nanosToMillis(bindNs),
                nanosToMillis(viewNs),
                nanosToMillis(descriptorNs)
            );
        }
        return true;
    }

    bool importTeleportHardwareBufferLocked(
        JNIEnv* env,
        jobject hardwareBuffer,
        BackdropImportEntry& outEntry
    ) {
        if (device_ == VK_NULL_HANDLE ||
            !hardwareBufferImportSupported_ ||
            getAndroidHardwareBufferProperties_ == nullptr ||
            hardwareBuffer == nullptr) {
            logWarn("Teleport AHB import unavailable");
            return false;
        }

        AHardwareBuffer* nativeBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
        if (nativeBuffer == nullptr) {
            logWarn("Teleport AHardwareBuffer_fromHardwareBuffer failed");
            return false;
        }
        AHardwareBuffer_acquire(nativeBuffer);

        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(nativeBuffer, &desc);
        if (desc.width == 0 || desc.height == 0 || desc.layers != 1) {
            logWarn("Unsupported teleport AHB dimensions");
            AHardwareBuffer_release(nativeBuffer);
            return false;
        }

        VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{};
        formatProperties.sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID bufferProperties{};
        bufferProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        bufferProperties.pNext = &formatProperties;
        if (!isOk(
                getAndroidHardwareBufferProperties_(device_, nativeBuffer, &bufferProperties),
                "vkGetAndroidHardwareBufferPropertiesANDROID teleport failed"
            )) {
            AHardwareBuffer_release(nativeBuffer);
            return false;
        }
        if (formatProperties.format == VK_FORMAT_UNDEFINED) {
            logWarn("Unsupported external-only teleport format");
            AHardwareBuffer_release(nativeBuffer);
            return false;
        }

        VkImage importedImage = VK_NULL_HANDLE;
        VkDeviceMemory importedMemory = VK_NULL_HANDLE;
        VkImageView importedView = VK_NULL_HANDLE;
        auto cleanupFailedImport = [&]() {
            if (importedView != VK_NULL_HANDLE) {
                vkDestroyImageView(device_, importedView, nullptr);
            }
            if (importedImage != VK_NULL_HANDLE) {
                vkDestroyImage(device_, importedImage, nullptr);
            }
            if (importedMemory != VK_NULL_HANDLE) {
                vkFreeMemory(device_, importedMemory, nullptr);
            }
            AHardwareBuffer_release(nativeBuffer);
        };

        VkExternalMemoryImageCreateInfo externalImageInfo{};
        externalImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        externalImageInfo.handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.pNext = &externalImageInfo;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent = {desc.width, desc.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = formatProperties.format;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(
                vkCreateImage(device_, &imageInfo, nullptr, &importedImage),
                "vkCreateImage teleport import failed"
            )) {
            cleanupFailedImport();
            return false;
        }

        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(bufferProperties.memoryTypeBits, 0, memoryTypeIndex)) {
            logWarn("No memory type for teleport AHB");
            cleanupFailedImport();
            return false;
        }

        VkMemoryDedicatedAllocateInfo dedicatedInfo{};
        dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
        dedicatedInfo.image = importedImage;
        VkImportAndroidHardwareBufferInfoANDROID importInfo{};
        importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        importInfo.pNext = &dedicatedInfo;
        importInfo.buffer = nativeBuffer;
        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.pNext = &importInfo;
        allocateInfo.allocationSize = bufferProperties.allocationSize;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(
                vkAllocateMemory(device_, &allocateInfo, nullptr, &importedMemory),
                "vkAllocateMemory teleport import failed"
            ) ||
            !isOk(
                vkBindImageMemory(device_, importedImage, importedMemory, 0),
                "vkBindImageMemory teleport failed"
            )) {
            cleanupFailedImport();
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = importedImage;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = formatProperties.format;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        if (!isOk(
                vkCreateImageView(device_, &viewInfo, nullptr, &importedView),
                "vkCreateImageView teleport failed"
            )) {
            cleanupFailedImport();
            return false;
        }

        outEntry.hardwareBuffer = nativeBuffer;
        outEntry.image = importedImage;
        outEntry.memory = importedMemory;
        outEntry.imageView = importedView;
        outEntry.width = static_cast<int>(desc.width);
        outEntry.height = static_cast<int>(desc.height);
        outEntry.format = formatProperties.format;
        return true;
    }

    BackdropImportEntry* findBackdropImportEntryLocked(AHardwareBuffer* hardwareBuffer) const {
        if (hardwareBuffer == nullptr) {
            return nullptr;
        }
        for (const auto& entry : backdropImportCache_) {
            if (entry != nullptr && entry->hardwareBuffer == hardwareBuffer) {
                return entry.get();
            }
        }
        return nullptr;
    }

    void evictBackdropImportCacheEntryIfNeededLocked() {
        if (backdropImportCache_.size() < kMaxBackdropImportCacheEntries) {
            return;
        }

        for (auto it = backdropImportCache_.begin(); it != backdropImportCache_.end(); ++it) {
            BackdropImportEntry* entry = it->get();
            if (entry == nullptr || entry == activeBackdropEntry_) {
                continue;
            }
            destroyBackdropImportEntryLocked(*entry);
            backdropImportCache_.erase(it);
            return;
        }

        if (!backdropImportCache_.empty() && backdropImportCache_.front() != nullptr) {
            BackdropImportEntry* entry = backdropImportCache_.front().get();
            if (entry == activeBackdropEntry_) {
                activeBackdropEntry_ = nullptr;
                backdropWidth_ = 0;
                backdropHeight_ = 0;
                backdropNeedsTransition_ = false;
                backdropNeedsRender_ = false;
            }
            destroyBackdropImportEntryLocked(*entry);
            backdropImportCache_.erase(backdropImportCache_.begin());
        }
    }

    void waitForFrameFenceLocked() {
        if (device_ != VK_NULL_HANDLE && inFlightFence_ != VK_NULL_HANDLE) {
            if (vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, 100000000) == VK_SUCCESS) {
                consumeCompletedFrameTraceLocked();
                consumeCompletedBackdropStatsLocked();
                releaseCompletedSplashUploadStagingLocked();
            }
        }
    }

    void consumeCompletedBackdropStatsIfReadyLocked() {
        if (!backdropStatsPending_ ||
            device_ == VK_NULL_HANDLE ||
            inFlightFence_ == VK_NULL_HANDLE) {
            return;
        }
        const VkResult fenceStatus = vkGetFenceStatus(device_, inFlightFence_);
        if (fenceStatus == VK_SUCCESS) {
            consumeCompletedBackdropStatsLocked();
            releaseCompletedSplashUploadStagingLocked();
        }
    }

    void consumeCompletedBackdropStatsLocked() {
        if (!backdropStatsPending_) {
            return;
        }
        backdropStatsPending_ = false;
        if (backdropStatsMapped_ == nullptr) {
            return;
        }

        const float sum = backdropStatsMapped_[0];
        const float sumSq = backdropStatsMapped_[1];
        const float bright = backdropStatsMapped_[2];
        const float dark = backdropStatsMapped_[3];
        const float count = backdropStatsMapped_[4];
        if (count <= 0.5f) {
            return;
        }

        const float mean = sum / count;
        const float variance = std::max(0.0f, sumSq / count - mean * mean);
        latestBackdropStats_ = BackdropStatsResult{
            mean,
            variance,
            bright / count,
            dark / count
        };
        hasUnreadBackdropStats_ = true;
    }

    void clearBackdropLocked() {
        backdropRects_.clear();
        backdropTextureOrigin_ = {0.0f, 0.0f};
        activeBackdropEntry_ = nullptr;
        backdropWidth_ = 0;
        backdropHeight_ = 0;
        backdropNeedsTransition_ = false;
        backdropNeedsRender_ = false;
        backdropStatsPending_ = false;
        hasUnreadBackdropStats_ = false;
        destroyBackdropImportCacheLocked();
    }

    void destroyBackdropBlurSizeResourcesLocked() {
        if (device_ == VK_NULL_HANDLE) {
            backdropBlurWidth_ = 0;
            backdropBlurHeight_ = 0;
            backdropCompositeLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
            backdropBlurTempLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
            backdropBlurLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
            return;
        }

        if (backdropCompositeFramebuffer_ != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, backdropCompositeFramebuffer_, nullptr);
            backdropCompositeFramebuffer_ = VK_NULL_HANDLE;
        }
        if (backdropBlurTempFramebuffer_ != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, backdropBlurTempFramebuffer_, nullptr);
            backdropBlurTempFramebuffer_ = VK_NULL_HANDLE;
        }
        if (backdropBlurFramebuffer_ != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, backdropBlurFramebuffer_, nullptr);
            backdropBlurFramebuffer_ = VK_NULL_HANDLE;
        }
        if (backdropBlurTempImageView_ != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, backdropBlurTempImageView_, nullptr);
            backdropBlurTempImageView_ = VK_NULL_HANDLE;
        }
        if (backdropBlurImageView_ != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, backdropBlurImageView_, nullptr);
            backdropBlurImageView_ = VK_NULL_HANDLE;
        }
        if (backdropCompositeImageView_ != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, backdropCompositeImageView_, nullptr);
            backdropCompositeImageView_ = VK_NULL_HANDLE;
        }
        if (backdropBlurTempImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, backdropBlurTempImage_, nullptr);
            backdropBlurTempImage_ = VK_NULL_HANDLE;
        }
        if (backdropBlurImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, backdropBlurImage_, nullptr);
            backdropBlurImage_ = VK_NULL_HANDLE;
        }
        if (backdropCompositeImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, backdropCompositeImage_, nullptr);
            backdropCompositeImage_ = VK_NULL_HANDLE;
        }
        if (backdropBlurTempImageMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, backdropBlurTempImageMemory_, nullptr);
            backdropBlurTempImageMemory_ = VK_NULL_HANDLE;
        }
        if (backdropBlurImageMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, backdropBlurImageMemory_, nullptr);
            backdropBlurImageMemory_ = VK_NULL_HANDLE;
        }
        if (backdropCompositeImageMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, backdropCompositeImageMemory_, nullptr);
            backdropCompositeImageMemory_ = VK_NULL_HANDLE;
        }
        backdropBlurWidth_ = 0;
        backdropBlurHeight_ = 0;
        backdropCompositeLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        backdropBlurTempLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        backdropBlurLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    }

    void destroyBackdropStatsResourcesLocked() {
        backdropStatsPending_ = false;
        hasUnreadBackdropStats_ = false;
        if (device_ == VK_NULL_HANDLE) {
            backdropStatsPipeline_ = VK_NULL_HANDLE;
            backdropStatsPipelineLayout_ = VK_NULL_HANDLE;
            backdropStatsDescriptorPool_ = VK_NULL_HANDLE;
            backdropStatsDescriptorSet_ = VK_NULL_HANDLE;
            backdropStatsDescriptorSetLayout_ = VK_NULL_HANDLE;
            backdropStatsBuffer_ = VK_NULL_HANDLE;
            backdropStatsBufferMemory_ = VK_NULL_HANDLE;
            backdropStatsMapped_ = nullptr;
            return;
        }

        if (backdropStatsPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, backdropStatsPipeline_, nullptr);
            backdropStatsPipeline_ = VK_NULL_HANDLE;
        }
        if (backdropStatsPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, backdropStatsPipelineLayout_, nullptr);
            backdropStatsPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (backdropStatsDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, backdropStatsDescriptorPool_, nullptr);
            backdropStatsDescriptorPool_ = VK_NULL_HANDLE;
            backdropStatsDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (backdropStatsDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, backdropStatsDescriptorSetLayout_, nullptr);
            backdropStatsDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (backdropStatsMapped_ != nullptr) {
            vkUnmapMemory(device_, backdropStatsBufferMemory_);
            backdropStatsMapped_ = nullptr;
        }
        if (backdropStatsBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, backdropStatsBuffer_, nullptr);
            backdropStatsBuffer_ = VK_NULL_HANDLE;
        }
        if (backdropStatsBufferMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, backdropStatsBufferMemory_, nullptr);
            backdropStatsBufferMemory_ = VK_NULL_HANDLE;
        }
    }

    void destroyBackdropImportEntryLocked(BackdropImportEntry& entry) {
        if (device_ != VK_NULL_HANDLE) {
            if (entry.imageView != VK_NULL_HANDLE) {
                vkDestroyImageView(device_, entry.imageView, nullptr);
                entry.imageView = VK_NULL_HANDLE;
            }
            if (entry.image != VK_NULL_HANDLE) {
                vkDestroyImage(device_, entry.image, nullptr);
                entry.image = VK_NULL_HANDLE;
            }
            if (entry.memory != VK_NULL_HANDLE) {
                vkFreeMemory(device_, entry.memory, nullptr);
                entry.memory = VK_NULL_HANDLE;
            }
        }
        if (entry.hardwareBuffer != nullptr) {
            AHardwareBuffer_release(entry.hardwareBuffer);
            entry.hardwareBuffer = nullptr;
        }
        entry.width = 0;
        entry.height = 0;
        entry.format = VK_FORMAT_UNDEFINED;
    }

    void destroyBackdropImportCacheLocked() {
        if (device_ != VK_NULL_HANDLE && inFlightFence_ != VK_NULL_HANDLE) {
            vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, 100000000);
        }
        for (auto& entry : backdropImportCache_) {
            if (entry != nullptr) {
                destroyBackdropImportEntryLocked(*entry);
            }
        }
        backdropImportCache_.clear();
        activeBackdropEntry_ = nullptr;
        backdropWidth_ = 0;
        backdropHeight_ = 0;
        backdropNeedsTransition_ = false;
        backdropNeedsRender_ = false;
    }

    void destroyTeleportSceneLocked(bool restoreBackdropDescriptors) {
        destroyBackdropImportEntryLocked(teleportOldEntry_);
        destroyBackdropImportEntryLocked(teleportNewEntry_);
        teleportActive_ = false;
        teleportNeedsTransition_ = false;
        teleportProgress_ = 0.0f;
        teleportDirectionSign_ = 1.0f;
        teleportViewportRect_ = {};
        teleportCaptureOrigin_ = {};
        if (restoreBackdropDescriptors &&
            activeBackdropEntry_ != nullptr &&
            activeBackdropEntry_->imageView != VK_NULL_HANDLE) {
            backdropWidth_ = activeBackdropEntry_->width;
            backdropHeight_ = activeBackdropEntry_->height;
            updateBackdropSourceDescriptorsLocked(activeBackdropEntry_->imageView);
        }
    }

    bool hasTeleportSceneLocked() const {
        return teleportActive_ &&
            teleportOldEntry_.image != VK_NULL_HANDLE &&
            teleportOldEntry_.imageView != VK_NULL_HANDLE &&
            teleportNewEntry_.image != VK_NULL_HANDLE &&
            teleportNewEntry_.imageView != VK_NULL_HANDLE &&
            teleportDescriptorSet_ != VK_NULL_HANDLE &&
            teleportOldEntry_.width > 0 &&
            teleportOldEntry_.height > 0;
    }

    bool hasBackdropImageLocked() const {
        const bool hasSource = hasTeleportSceneLocked() ||
            (activeBackdropEntry_ != nullptr &&
                activeBackdropEntry_->image != VK_NULL_HANDLE &&
                activeBackdropEntry_->imageView != VK_NULL_HANDLE);
        return !backdropRects_.empty() &&
            hasSource &&
            backdropDescriptorSet_ != VK_NULL_HANDLE &&
            backdropBlurImageView_ != VK_NULL_HANDLE &&
            backdropWidth_ > 0 &&
            backdropHeight_ > 0;
    }

    void transitionBackdropImageForSamplingLocked(VkCommandBuffer commandBuffer) {
        if (teleportActive_ || !hasBackdropImageLocked() || !backdropNeedsTransition_) {
            return;
        }

        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        barrier.dstQueueFamilyIndex = queueFamilyIndex_;
        barrier.image = activeBackdropEntry_->image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;

        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &barrier
        );
        backdropNeedsTransition_ = false;
    }

    void transitionTeleportImagesForSamplingLocked(VkCommandBuffer commandBuffer) {
        if (!hasTeleportSceneLocked() || !teleportNeedsTransition_) {
            return;
        }

        std::array<VkImageMemoryBarrier, 2> barriers{};
        const std::array<VkImage, 2> images = {
            teleportOldEntry_.image,
            teleportNewEntry_.image
        };
        for (uint32_t index = 0; index < barriers.size(); ++index) {
            barriers[index].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barriers[index].srcAccessMask = 0;
            barriers[index].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barriers[index].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            barriers[index].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barriers[index].srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            barriers[index].dstQueueFamilyIndex = queueFamilyIndex_;
            barriers[index].image = images[index];
            barriers[index].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            barriers[index].subresourceRange.baseMipLevel = 0;
            barriers[index].subresourceRange.levelCount = 1;
            barriers[index].subresourceRange.baseArrayLayer = 0;
            barriers[index].subresourceRange.layerCount = 1;
        }
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            static_cast<uint32_t>(barriers.size()),
            barriers.data()
        );
        teleportNeedsTransition_ = false;
    }

    bool hasBackdropStatsResourcesLocked() const {
        return backdropStatsPipeline_ != VK_NULL_HANDLE &&
            backdropStatsPipelineLayout_ != VK_NULL_HANDLE &&
            backdropStatsDescriptorSet_ != VK_NULL_HANDLE &&
            backdropStatsBuffer_ != VK_NULL_HANDLE &&
            backdropStatsMapped_ != nullptr;
    }

    const BackdropRect* primaryBackdropRectLocked() const {
        const BackdropRect* primaryRect = nullptr;
        float primaryArea = 0.0f;
        for (const BackdropRect& rect : backdropRects_) {
            const float area = std::max(0.0f, rect.right - rect.left) *
                std::max(0.0f, rect.bottom - rect.top);
            if (area > primaryArea) {
                primaryArea = area;
                primaryRect = &rect;
            }
        }
        return primaryRect;
    }

    void recordBackdropStatsLocked(VkCommandBuffer commandBuffer) {
        if (!hasBackdropImageLocked() || !hasBackdropStatsResourcesLocked()) {
            return;
        }
        const BackdropRect* rect = primaryBackdropRectLocked();
        if (rect == nullptr) {
            return;
        }

        BackdropStatsPushConstants pushConstants{};
        pushConstants.textureSize[0] = static_cast<float>(backdropWidth_);
        pushConstants.textureSize[1] = static_cast<float>(backdropHeight_);
        pushConstants.rect[0] = rect->left;
        pushConstants.rect[1] = rect->top;
        pushConstants.rect[2] = rect->right;
        pushConstants.rect[3] = rect->bottom;
        pushConstants.textureOrigin[0] = backdropTextureOrigin_[0];
        pushConstants.textureOrigin[1] = backdropTextureOrigin_[1];
        pushConstants.cornerRadius = rect->cornerRadius;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, backdropStatsPipeline_);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            backdropStatsPipelineLayout_,
            0,
            1,
            &backdropStatsDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            backdropStatsPipelineLayout_,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(BackdropStatsPushConstants),
            &pushConstants
        );
        hasUnreadBackdropStats_ = false;
        vkCmdDispatch(commandBuffer, 1, 1, 1);

        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = backdropStatsBuffer_;
        barrier.offset = 0;
        barrier.size = kBackdropStatsBufferSize;
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0,
            0,
            nullptr,
            1,
            &barrier,
            0,
            nullptr
        );

        backdropStatsPending_ = true;
    }

    bool createSplashInitPipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(SplashInitPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &splashDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &splashInitPipelineLayout_),
                "vkCreatePipelineLayout splash init failed"
            )) {
            return false;
        }

        VkShaderModule computeShader = createShaderModuleLocked(
            kPaintSplashInitCompSpv,
            sizeof(kPaintSplashInitCompSpv)
        );
        if (computeShader == VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, splashInitPipelineLayout_, nullptr);
            splashInitPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo computeStage{};
        computeStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        computeStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        computeStage.module = computeShader;
        computeStage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipelineInfo.stage = computeStage;
        pipelineInfo.layout = splashInitPipelineLayout_;

        const bool didCreatePipeline = isOk(
            vkCreateComputePipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &splashInitPipeline_
            ),
            "vkCreateComputePipelines splash init failed"
        );

        vkDestroyShaderModule(device_, computeShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, splashInitPipelineLayout_, nullptr);
            splashInitPipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createSplashUpdatePipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(SplashUpdatePushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &splashDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &splashUpdatePipelineLayout_),
                "vkCreatePipelineLayout splash update failed"
            )) {
            return false;
        }

        VkShaderModule computeShader = createShaderModuleLocked(
            kPaintSplashUpdateCompSpv,
            sizeof(kPaintSplashUpdateCompSpv)
        );
        if (computeShader == VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, splashUpdatePipelineLayout_, nullptr);
            splashUpdatePipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo computeStage{};
        computeStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        computeStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        computeStage.module = computeShader;
        computeStage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipelineInfo.stage = computeStage;
        pipelineInfo.layout = splashUpdatePipelineLayout_;

        const bool didCreatePipeline = isOk(
            vkCreateComputePipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &splashUpdatePipeline_
            ),
            "vkCreateComputePipelines splash update failed"
        );

        vkDestroyShaderModule(device_, computeShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, splashUpdatePipelineLayout_, nullptr);
            splashUpdatePipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createParticlePipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(ParticlePushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &splashDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &particlePipelineLayout_),
                  "vkCreatePipelineLayout particle failed")) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kPaintSplashVertSpv,
            sizeof(kPaintSplashVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kPaintSplashFragSpv,
            sizeof(kPaintSplashFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, particlePipelineLayout_, nullptr);
            particlePipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_TRUE;
        blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = particlePipelineLayout_;
        pipelineInfo.renderPass = blobRenderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &particlePipeline_
            ),
            "vkCreateGraphicsPipelines particle failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, particlePipelineLayout_, nullptr);
            particlePipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createPaintSplashGlassImpactPipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(GlassImpactPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &paintSplashGlassImpactDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(
                    device_,
                    &layoutInfo,
                    nullptr,
                    &paintSplashGlassImpactPipelineLayout_
                ),
                "vkCreatePipelineLayout paint splash glass impact failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kPaintSplashGlassImpactVertSpv,
            sizeof(kPaintSplashGlassImpactVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kPaintSplashGlassImpactFragSpv,
            sizeof(kPaintSplashGlassImpactFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, paintSplashGlassImpactPipelineLayout_, nullptr);
            paintSplashGlassImpactPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        std::array<VkPipelineColorBlendAttachmentState, 2> blendAttachments{};
        for (VkPipelineColorBlendAttachmentState& attachment : blendAttachments) {
            attachment.blendEnable = VK_TRUE;
            attachment.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
            attachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
            attachment.colorBlendOp = VK_BLEND_OP_ADD;
            attachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            attachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            attachment.alphaBlendOp = VK_BLEND_OP_ADD;
            attachment.colorWriteMask =
                VK_COLOR_COMPONENT_R_BIT |
                VK_COLOR_COMPONENT_G_BIT |
                VK_COLOR_COMPONENT_B_BIT |
                VK_COLOR_COMPONENT_A_BIT;
        }

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = static_cast<uint32_t>(blendAttachments.size());
        colorBlending.pAttachments = blendAttachments.data();

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = paintSplashGlassImpactPipelineLayout_;
        pipelineInfo.renderPass = paintSplashGlassImpactRenderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &paintSplashGlassImpactPipeline_
            ),
            "vkCreateGraphicsPipelines paint splash glass impact failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, paintSplashGlassImpactPipelineLayout_, nullptr);
            paintSplashGlassImpactPipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createPaintSplashGlassSurfacePipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(GlassSurfacePushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &paintSplashGlassSurfaceDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(
                    device_,
                    &layoutInfo,
                    nullptr,
                    &paintSplashGlassSurfacePipelineLayout_
                ),
                "vkCreatePipelineLayout paint splash glass surface failed"
            )) {
            return false;
        }

        VkShaderModule computeShader = createShaderModuleLocked(
            kPaintSplashGlassSurfaceCompSpv,
            sizeof(kPaintSplashGlassSurfaceCompSpv)
        );
        if (computeShader == VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSurfacePipelineLayout_, nullptr);
            paintSplashGlassSurfacePipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo computeStage{};
        computeStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        computeStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        computeStage.module = computeShader;
        computeStage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipelineInfo.stage = computeStage;
        pipelineInfo.layout = paintSplashGlassSurfacePipelineLayout_;

        const bool didCreatePipeline = isOk(
            vkCreateComputePipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &paintSplashGlassSurfacePipeline_
            ),
            "vkCreateComputePipelines paint splash glass surface failed"
        );

        vkDestroyShaderModule(device_, computeShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSurfacePipelineLayout_, nullptr);
            paintSplashGlassSurfacePipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createPaintSplashGlassSphUpdatePipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(GlassSphUpdatePushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &paintSplashGlassSphUpdateDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(
                    device_,
                    &layoutInfo,
                    nullptr,
                    &paintSplashGlassSphUpdatePipelineLayout_
                ),
                "vkCreatePipelineLayout paint splash glass sph update failed"
            )) {
            return false;
        }

        VkShaderModule computeShader = createShaderModuleLocked(
            kPaintSplashGlassSphUpdateCompSpv,
            sizeof(kPaintSplashGlassSphUpdateCompSpv)
        );
        if (computeShader == VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSphUpdatePipelineLayout_, nullptr);
            paintSplashGlassSphUpdatePipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo computeStage{};
        computeStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        computeStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        computeStage.module = computeShader;
        computeStage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipelineInfo.stage = computeStage;
        pipelineInfo.layout = paintSplashGlassSphUpdatePipelineLayout_;

        const bool didCreatePipeline = isOk(
            vkCreateComputePipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &paintSplashGlassSphUpdatePipeline_
            ),
            "vkCreateComputePipelines paint splash glass sph update failed"
        );

        vkDestroyShaderModule(device_, computeShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSphUpdatePipelineLayout_, nullptr);
            paintSplashGlassSphUpdatePipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createPaintSplashGlassSphFieldPipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(GlassSphRenderPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &paintSplashGlassSphFieldDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(
                    device_,
                    &layoutInfo,
                    nullptr,
                    &paintSplashGlassSphFieldPipelineLayout_
                ),
                "vkCreatePipelineLayout paint splash glass sph field failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kPaintSplashGlassSphVertSpv,
            sizeof(kPaintSplashGlassSphVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kPaintSplashGlassSphFragSpv,
            sizeof(kPaintSplashGlassSphFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, paintSplashGlassSphFieldPipelineLayout_, nullptr);
            paintSplashGlassSphFieldPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_TRUE;
        blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = paintSplashGlassSphFieldPipelineLayout_;
        pipelineInfo.renderPass = blobRenderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &paintSplashGlassSphFieldPipeline_
            ),
            "vkCreateGraphicsPipelines paint splash glass sph field failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSphFieldPipelineLayout_, nullptr);
            paintSplashGlassSphFieldPipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createPaintSplashGlassSphCompositePipelineLocked() {
        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &paintSplashGlassSphCompositeDescriptorSetLayout_;
        if (!isOk(
                vkCreatePipelineLayout(
                    device_,
                    &layoutInfo,
                    nullptr,
                    &paintSplashGlassSphCompositePipelineLayout_
                ),
                "vkCreatePipelineLayout paint splash glass sph composite failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kPaintSplashCompositeVertSpv,
            sizeof(kPaintSplashCompositeVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kPaintSplashGlassSphCompositeFragSpv,
            sizeof(kPaintSplashGlassSphCompositeFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, paintSplashGlassSphCompositePipelineLayout_, nullptr);
            paintSplashGlassSphCompositePipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_TRUE;
        blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = paintSplashGlassSphCompositePipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &paintSplashGlassSphCompositePipeline_
            ),
            "vkCreateGraphicsPipelines paint splash glass sph composite failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSphCompositePipelineLayout_, nullptr);
            paintSplashGlassSphCompositePipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    VkShaderModule createShaderModuleLocked(const uint32_t* code, size_t byteSize) const {
        VkShaderModuleCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        createInfo.codeSize = byteSize;
        createInfo.pCode = code;

        VkShaderModule shaderModule = VK_NULL_HANDLE;
        if (!isOk(vkCreateShaderModule(device_, &createInfo, nullptr, &shaderModule),
                  "vkCreateShaderModule failed")) {
            return VK_NULL_HANDLE;
        }
        return shaderModule;
    }

    bool createBackdropStatsPipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(BackdropStatsPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &backdropStatsDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &backdropStatsPipelineLayout_),
                "vkCreatePipelineLayout backdrop stats failed"
            )) {
            return false;
        }

        VkShaderModule computeShader = createShaderModuleLocked(
            kChatBackdropStatsCompSpv,
            sizeof(kChatBackdropStatsCompSpv)
        );
        if (computeShader == VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, backdropStatsPipelineLayout_, nullptr);
            backdropStatsPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo computeStage{};
        computeStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        computeStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        computeStage.module = computeShader;
        computeStage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipelineInfo.stage = computeStage;
        pipelineInfo.layout = backdropStatsPipelineLayout_;

        const bool didCreatePipeline = isOk(
            vkCreateComputePipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &backdropStatsPipeline_
            ),
            "vkCreateComputePipelines backdrop stats failed"
        );

        vkDestroyShaderModule(device_, computeShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, backdropStatsPipelineLayout_, nullptr);
            backdropStatsPipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createBackdropBlurPipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(BlurPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &backdropBlurDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &backdropBlurPipelineLayout_),
                "vkCreatePipelineLayout backdrop blur failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kChatBackdropBlurVertSpv,
            sizeof(kChatBackdropBlurVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kChatBackdropBlurFragSpv,
            sizeof(kChatBackdropBlurFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, backdropBlurPipelineLayout_, nullptr);
            backdropBlurPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_FALSE;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = backdropBlurPipelineLayout_;
        pipelineInfo.renderPass = backdropBlurRenderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &backdropBlurPipeline_
            ),
            "vkCreateGraphicsPipelines backdrop blur failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, backdropBlurPipelineLayout_, nullptr);
            backdropBlurPipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createTeleportPipelinesLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(TeleportPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &teleportDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &teleportPipelineLayout_),
                "vkCreatePipelineLayout teleport failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kChatTeleportVertSpv,
            sizeof(kChatTeleportVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kChatTeleportFragSpv,
            sizeof(kChatTeleportFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, teleportPipelineLayout_, nullptr);
            teleportPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages{};
        shaderStages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shaderStages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        shaderStages[0].module = vertexShader;
        shaderStages[0].pName = "main";
        shaderStages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shaderStages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        shaderStages[1].module = fragmentShader;
        shaderStages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_FALSE;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;
        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = teleportPipelineLayout_;
        pipelineInfo.subpass = 0;

        pipelineInfo.renderPass = backdropBlurRenderPass_;
        const bool didCreateGlassPipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &teleportGlassPipeline_
            ),
            "vkCreateGraphicsPipelines teleport glass failed"
        );
        if (didCreateGlassPipeline) {
            pipelineInfo.renderPass = renderPass_;
            isOk(
                vkCreateGraphicsPipelines(
                    device_,
                    VK_NULL_HANDLE,
                    1,
                    &pipelineInfo,
                    nullptr,
                    &teleportPresentPipeline_
                ),
                "vkCreateGraphicsPipelines teleport present failed"
            );
        }

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreateGlassPipeline || teleportPresentPipeline_ == VK_NULL_HANDLE) {
            if (teleportGlassPipeline_ != VK_NULL_HANDLE) {
                vkDestroyPipeline(device_, teleportGlassPipeline_, nullptr);
                teleportGlassPipeline_ = VK_NULL_HANDLE;
            }
            if (teleportPresentPipeline_ != VK_NULL_HANDLE) {
                vkDestroyPipeline(device_, teleportPresentPipeline_, nullptr);
                teleportPresentPipeline_ = VK_NULL_HANDLE;
            }
            vkDestroyPipelineLayout(device_, teleportPipelineLayout_, nullptr);
            teleportPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }
        return true;
    }

    bool createBackdropPipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(BackdropPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &backdropDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &backdropPipelineLayout_),
                "vkCreatePipelineLayout backdrop failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kChatBackdropVertSpv,
            sizeof(kChatBackdropVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kChatBackdropFragSpv,
            sizeof(kChatBackdropFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, backdropPipelineLayout_, nullptr);
            backdropPipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_TRUE;
        blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = backdropPipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &backdropPipeline_
            ),
            "vkCreateGraphicsPipelines backdrop failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, backdropPipelineLayout_, nullptr);
            backdropPipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createCompositePipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(CompositePushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &compositeDescriptorSetLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushConstantRange;
        if (!isOk(
                vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &compositePipelineLayout_),
                "vkCreatePipelineLayout composite failed"
            )) {
            return false;
        }

        VkShaderModule vertexShader = createShaderModuleLocked(
            kPaintSplashCompositeVertSpv,
            sizeof(kPaintSplashCompositeVertSpv)
        );
        VkShaderModule fragmentShader = createShaderModuleLocked(
            kPaintSplashCompositeFragSpv,
            sizeof(kPaintSplashCompositeFragSpv)
        );
        if (vertexShader == VK_NULL_HANDLE || fragmentShader == VK_NULL_HANDLE) {
            if (vertexShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexShader, nullptr);
            }
            if (fragmentShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentShader, nullptr);
            }
            vkDestroyPipelineLayout(device_, compositePipelineLayout_, nullptr);
            compositePipelineLayout_ = VK_NULL_HANDLE;
            return false;
        }

        VkPipelineShaderStageCreateInfo vertexStage{};
        vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertexStage.module = vertexShader;
        vertexStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragmentStage{};
        fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragmentStage.module = fragmentShader;
        fragmentStage.pName = "main";

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages = {
            vertexStage,
            fragmentStage
        };

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterization{};
        rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        rasterization.cullMode = VK_CULL_MODE_NONE;
        rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterization.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = VK_TRUE;
        blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        const std::array<VkDynamicState, 2> dynamicStates = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
        };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInputInfo;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = compositePipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;

        const bool didCreatePipeline = isOk(
            vkCreateGraphicsPipelines(
                device_,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &compositePipeline_
            ),
            "vkCreateGraphicsPipelines composite failed"
        );

        vkDestroyShaderModule(device_, fragmentShader, nullptr);
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        if (!didCreatePipeline) {
            vkDestroyPipelineLayout(device_, compositePipelineLayout_, nullptr);
            compositePipelineLayout_ = VK_NULL_HANDLE;
        }
        return didCreatePipeline;
    }

    bool createGpuPaintSplashItemLocked(
        PaintSplashItem& item,
        const AndroidBitmapInfo& bitmapInfo,
        const void* bitmapPixels
    ) {
        if (device_ == VK_NULL_HANDLE ||
            commandPool_ == VK_NULL_HANDLE ||
            graphicsQueue_ == VK_NULL_HANDLE ||
            splashDescriptorSetLayout_ == VK_NULL_HANDLE ||
            splashDescriptorPool_ == VK_NULL_HANDLE ||
            blobSampler_ == VK_NULL_HANDLE ||
            bitmapPixels == nullptr ||
            bitmapInfo.width == 0 ||
            bitmapInfo.height == 0 ||
            bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            return false;
        }

        const float width = std::max(1.0f, item.right - item.left);
        const float height = std::max(1.0f, item.bottom - item.top);
        const float area = width * height;
        const float scaledCount = kPaintSplashBaseParticleCount *
            std::pow(area / kPaintSplashReferenceArea, 0.6f);
        item.dropletCount = clampUint(
            static_cast<uint32_t>(scaledCount),
            kMinParticlesPerSplash,
            kMaxParticlesPerSplash
        );

        if (!createBufferLocked(
                sizeof(GpuDroplet) * item.dropletCount,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                item.dropletBuffer,
                item.dropletMemory
        )) {
            return false;
        }
        if (!uploadSnapshotTextureLocked(item, bitmapInfo, bitmapPixels)) {
            return false;
        }
        if (!allocateSplashDescriptorSetLocked(item)) {
            return false;
        }
        item.initialized = false;
        return true;
    }

    bool createBufferLocked(
        VkDeviceSize size,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags properties,
        VkBuffer& buffer,
        VkDeviceMemory& memory
    ) {
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(vkCreateBuffer(device_, &bufferInfo, nullptr, &buffer),
                  "vkCreateBuffer failed")) {
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetBufferMemoryRequirements(device_, buffer, &memoryRequirements);
        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(memoryRequirements.memoryTypeBits, properties, memoryTypeIndex)) {
            logWarn("No matching buffer memory type");
            vkDestroyBuffer(device_, buffer, nullptr);
            buffer = VK_NULL_HANDLE;
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(vkAllocateMemory(device_, &allocateInfo, nullptr, &memory),
                  "vkAllocateMemory buffer failed")) {
            vkDestroyBuffer(device_, buffer, nullptr);
            buffer = VK_NULL_HANDLE;
            return false;
        }

        if (!isOk(vkBindBufferMemory(device_, buffer, memory, 0),
                  "vkBindBufferMemory failed")) {
            vkDestroyBuffer(device_, buffer, nullptr);
            vkFreeMemory(device_, memory, nullptr);
            buffer = VK_NULL_HANDLE;
            memory = VK_NULL_HANDLE;
            return false;
        }
        return true;
    }

    SplashStagingBuffer* acquireSplashStagingBufferLocked(VkDeviceSize requiredBytes) {
        for (const auto& staging : splashStagingPool_) {
            if (!staging->inUse && staging->capacity >= requiredBytes) {
                staging->inUse = true;
                return staging.get();
            }
        }

        auto staging = std::make_unique<SplashStagingBuffer>();
        staging->capacity = requiredBytes;
        if (!createBufferLocked(
                requiredBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                staging->buffer,
                staging->memory
        )) {
            return nullptr;
        }

        if (!isOk(
                vkMapMemory(device_, staging->memory, 0, requiredBytes, 0, &staging->mapped),
                "vkMapMemory splash staging failed"
            )) {
            vkDestroyBuffer(device_, staging->buffer, nullptr);
            vkFreeMemory(device_, staging->memory, nullptr);
            return nullptr;
        }

        staging->inUse = true;
        SplashStagingBuffer* result = staging.get();
        splashStagingPool_.push_back(std::move(staging));
        return result;
    }

    void releaseSplashStagingBufferLocked(SplashStagingBuffer* staging) {
        if (staging != nullptr) {
            staging->inUse = false;
        }
    }

    void releaseCompletedSplashUploadStagingLocked() {
        for (SplashStagingBuffer* staging : pendingSplashStagingRelease_) {
            releaseSplashStagingBufferLocked(staging);
        }
        pendingSplashStagingRelease_.clear();
    }

    void destroySplashStagingPoolLocked() {
        pendingSplashStagingRelease_.clear();
        for (const auto& staging : splashStagingPool_) {
            if (staging->mapped != nullptr) {
                vkUnmapMemory(device_, staging->memory);
                staging->mapped = nullptr;
            }
            if (staging->buffer != VK_NULL_HANDLE) {
                vkDestroyBuffer(device_, staging->buffer, nullptr);
                staging->buffer = VK_NULL_HANDLE;
            }
            if (staging->memory != VK_NULL_HANDLE) {
                vkFreeMemory(device_, staging->memory, nullptr);
                staging->memory = VK_NULL_HANDLE;
            }
        }
        splashStagingPool_.clear();
    }

    bool markSnapshotUploadPendingLocked(
        PaintSplashItem& item,
        uint32_t width,
        uint32_t height
    ) {
        item.snapshotWidth = width;
        item.snapshotHeight = height;
        item.snapshotUploadPending = item.snapshotStaging != nullptr;
        return item.snapshotUploadPending;
    }

    bool uploadSnapshotTextureLocked(
        PaintSplashItem& item,
        const AndroidBitmapInfo& bitmapInfo,
        const void* bitmapPixels
    ) {
        const VkDeviceSize rowBytes = static_cast<VkDeviceSize>(bitmapInfo.width) * 4;
        const VkDeviceSize imageBytes = rowBytes * static_cast<VkDeviceSize>(bitmapInfo.height);
        SplashStagingBuffer* staging = acquireSplashStagingBufferLocked(imageBytes);
        if (staging == nullptr || staging->mapped == nullptr) {
            return false;
        }
        item.snapshotStaging = staging;

        auto* dst = static_cast<uint8_t*>(staging->mapped);
        const auto* src = static_cast<const uint8_t*>(bitmapPixels);
        for (uint32_t y = 0; y < bitmapInfo.height; ++y) {
            std::memcpy(dst + y * rowBytes, src + y * bitmapInfo.stride, rowBytes);
        }

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = bitmapInfo.width;
        imageInfo.extent.height = bitmapInfo.height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(vkCreateImage(device_, &imageInfo, nullptr, &item.snapshotImage),
                  "vkCreateImage splash snapshot failed")) {
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetImageMemoryRequirements(device_, item.snapshotImage, &memoryRequirements);
        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(
                memoryRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                memoryTypeIndex
            )) {
            logWarn("No device local memory for splash snapshot");
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(vkAllocateMemory(device_, &allocateInfo, nullptr, &item.snapshotMemory),
                  "vkAllocateMemory splash snapshot failed") ||
            !isOk(vkBindImageMemory(device_, item.snapshotImage, item.snapshotMemory, 0),
                  "vkBindImageMemory splash snapshot failed")) {
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = item.snapshotImage;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        const bool viewCreated = isOk(
            vkCreateImageView(device_, &viewInfo, nullptr, &item.snapshotImageView),
            "vkCreateImageView splash snapshot failed"
        );
        return viewCreated && markSnapshotUploadPendingLocked(item, bitmapInfo.width, bitmapInfo.height);
    }

    bool allocateSplashDescriptorSetLocked(PaintSplashItem& item) {
        if (paintSplashGlassTargetBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassEventBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassCursorBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassCellBuffer_ == VK_NULL_HANDLE) {
            return false;
        }

        VkDescriptorSetAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocateInfo.descriptorPool = splashDescriptorPool_;
        allocateInfo.descriptorSetCount = 1;
        allocateInfo.pSetLayouts = &splashDescriptorSetLayout_;
        if (!isOk(
                vkAllocateDescriptorSets(device_, &allocateInfo, &item.descriptorSet),
                "vkAllocateDescriptorSets splash failed"
            )) {
            return false;
        }

        VkDescriptorBufferInfo bufferInfo{};
        bufferInfo.buffer = item.dropletBuffer;
        bufferInfo.offset = 0;
        bufferInfo.range = sizeof(GpuDroplet) * item.dropletCount;

        VkDescriptorBufferInfo glassTargetBufferInfo{};
        glassTargetBufferInfo.buffer = paintSplashGlassTargetBuffer_;
        glassTargetBufferInfo.offset = 0;
        glassTargetBufferInfo.range = sizeof(GpuGlassHitTarget) * kMaxPaintSplashGlassHitTargets;

        VkDescriptorBufferInfo glassEventBufferInfo{};
        glassEventBufferInfo.buffer = paintSplashGlassEventBuffer_;
        glassEventBufferInfo.offset = 0;
        glassEventBufferInfo.range = sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets;

        VkDescriptorBufferInfo glassCursorBufferInfo{};
        glassCursorBufferInfo.buffer = paintSplashGlassCursorBuffer_;
        glassCursorBufferInfo.offset = 0;
        glassCursorBufferInfo.range = sizeof(uint32_t);

        VkDescriptorBufferInfo glassCellBufferInfo{};
        glassCellBufferInfo.buffer = paintSplashGlassCellBuffer_;
        glassCellBufferInfo.offset = 0;
        glassCellBufferInfo.range = sizeof(uint32_t) * kPaintSplashGlassCellCount;

        VkDescriptorImageInfo imageInfo{};
        imageInfo.sampler = blobSampler_;
        imageInfo.imageView = item.snapshotImageView;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkWriteDescriptorSet, 6> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = item.descriptorSet;
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[0].pBufferInfo = &bufferInfo;
        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = item.descriptorSet;
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[1].pImageInfo = &imageInfo;
        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = item.descriptorSet;
        writes[2].dstBinding = 2;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[2].pBufferInfo = &glassTargetBufferInfo;
        writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[3].dstSet = item.descriptorSet;
        writes[3].dstBinding = 3;
        writes[3].descriptorCount = 1;
        writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[3].pBufferInfo = &glassEventBufferInfo;
        writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[4].dstSet = item.descriptorSet;
        writes[4].dstBinding = 4;
        writes[4].descriptorCount = 1;
        writes[4].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[4].pBufferInfo = &glassCursorBufferInfo;
        writes[5].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[5].dstSet = item.descriptorSet;
        writes[5].dstBinding = 5;
        writes[5].descriptorCount = 1;
        writes[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[5].pBufferInfo = &glassCellBufferInfo;
        vkUpdateDescriptorSets(
            device_,
            static_cast<uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
        return true;
    }

    void destroyPaintSplashItemLocked(PaintSplashItem& item) {
        if (device_ == VK_NULL_HANDLE) {
            item = PaintSplashItem{};
            return;
        }
        if (item.descriptorSet != VK_NULL_HANDLE && splashDescriptorPool_ != VK_NULL_HANDLE) {
            vkFreeDescriptorSets(device_, splashDescriptorPool_, 1, &item.descriptorSet);
            item.descriptorSet = VK_NULL_HANDLE;
        }
        if (item.dropletBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, item.dropletBuffer, nullptr);
            item.dropletBuffer = VK_NULL_HANDLE;
        }
        if (item.dropletMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, item.dropletMemory, nullptr);
            item.dropletMemory = VK_NULL_HANDLE;
        }
        if (item.snapshotImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, item.snapshotImageView, nullptr);
            item.snapshotImageView = VK_NULL_HANDLE;
        }
        if (item.snapshotStaging != nullptr) {
            releaseSplashStagingBufferLocked(item.snapshotStaging);
            item.snapshotStaging = nullptr;
            item.snapshotUploadPending = false;
        }
        if (item.snapshotImage != VK_NULL_HANDLE) {
            vkDestroyImage(device_, item.snapshotImage, nullptr);
            item.snapshotImage = VK_NULL_HANDLE;
        }
        if (item.snapshotMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, item.snapshotMemory, nullptr);
            item.snapshotMemory = VK_NULL_HANDLE;
        }
    }

    void destroyPaintSplashItemsLocked() {
        for (PaintSplashItem& item : splashItems_) {
            destroyPaintSplashItemLocked(item);
        }
        splashItems_.clear();
        didLogParticleDraw_ = false;
    }

    void recordClearPassLocked(VkCommandBuffer commandBuffer, uint32_t imageIndex) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (!isOk(vkBeginCommandBuffer(commandBuffer, &beginInfo), "vkBeginCommandBuffer failed")) {
            return;
        }

        beginFrameTraceLocked(commandBuffer);
        const int64_t frameTimeNs = nowNanos();
        const bool hasPaintSplashes = hasActivePaintSplashesLocked(frameTimeNs);
        const bool hasPaintSplashGlassSurface =
            hasPaintSplashes || hasActivePaintSplashGlassSurfaceLocked(frameTimeNs);
        const VkRect2D paintSplashWorkRect = hasPaintSplashes
            ? paintSplashWorkRectLocked(frameTimeNs)
            : VkRect2D{};
        currentFrameTraceHasEffects_ =
            hasPaintSplashGlassSurface || hasTeleportSceneLocked();
        currentFrameTraceFlags_.hasSplash = hasPaintSplashes;
        currentFrameTraceFlags_.hasWetSurface = hasPaintSplashGlassSurface;
        currentFrameTraceFlags_.hasBackdrop = hasBackdropImageLocked();
        currentFrameTraceFlags_.splashItemCount = static_cast<uint32_t>(splashItems_.size());
        currentFrameTraceFlags_.backdropRectCount = static_cast<uint32_t>(backdropRects_.size());
        if (hasPaintSplashes) {
            recordPendingPaintSplashUploadsLocked(commandBuffer);
            writeFrameTraceTimestampLocked(commandBuffer, "splashUpload");
            recordPaintSplashComputeLocked(commandBuffer, frameTimeNs);
            writeFrameTraceTimestampLocked(commandBuffer, "splashCompute");
            recordBlobPassLocked(commandBuffer, paintSplashWorkRect);
            writeFrameTraceTimestampLocked(commandBuffer, "blob");
        }
        if (hasPaintSplashGlassSurface) {
            recordPaintSplashGlassSurfacePassesLocked(
                commandBuffer,
                frameTimeNs,
                hasPaintSplashes
            );
        }
        transitionTeleportImagesForSamplingLocked(commandBuffer);
        transitionBackdropImageForSamplingLocked(commandBuffer);
        writeFrameTraceTimestampLocked(commandBuffer, "backdropTransition");
        if (hasTeleportSceneLocked()) {
            recordTeleportGlassSceneLocked(commandBuffer);
        } else if (!recordBackdropOverlayCompositeLocked(commandBuffer, hasPaintSplashes) &&
            activeBackdropEntry_ != nullptr) {
            updateBackdropSourceDescriptorsLocked(activeBackdropEntry_->imageView);
        }
        currentFrameTraceFlags_.hasBackdropComposite = hasBackdropImageLocked();
        writeFrameTraceTimestampLocked(commandBuffer, "backdropOverlay");
        recordBackdropStatsLocked(commandBuffer);
        writeFrameTraceTimestampLocked(commandBuffer, "stats");
        recordBackdropBlurPassesLocked(commandBuffer);
        writeFrameTraceTimestampLocked(commandBuffer, "blur");
        recordCompositePassLocked(
            commandBuffer,
            imageIndex,
            hasPaintSplashes,
            hasPaintSplashGlassSurface,
            frameTimeNs,
            paintSplashWorkRect
        );
        writeFrameTraceTimestampLocked(commandBuffer, "frameEnd");
        isOk(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer failed");
    }

    void recordPendingPaintSplashUploadsLocked(VkCommandBuffer commandBuffer) {
        for (PaintSplashItem& item : splashItems_) {
            if (!item.snapshotUploadPending ||
                item.snapshotStaging == nullptr ||
                item.snapshotStaging->buffer == VK_NULL_HANDLE ||
                item.snapshotImage == VK_NULL_HANDLE ||
                item.snapshotWidth == 0 ||
                item.snapshotHeight == 0) {
                continue;
            }

            VkImageMemoryBarrier toTransfer{};
            toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toTransfer.image = item.snapshotImage;
            toTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            toTransfer.subresourceRange.baseMipLevel = 0;
            toTransfer.subresourceRange.levelCount = 1;
            toTransfer.subresourceRange.baseArrayLayer = 0;
            toTransfer.subresourceRange.layerCount = 1;
            toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                0,
                nullptr,
                0,
                nullptr,
                1,
                &toTransfer
            );

            VkBufferImageCopy copyRegion{};
            copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copyRegion.imageSubresource.mipLevel = 0;
            copyRegion.imageSubresource.baseArrayLayer = 0;
            copyRegion.imageSubresource.layerCount = 1;
            copyRegion.imageExtent = {item.snapshotWidth, item.snapshotHeight, 1};
            vkCmdCopyBufferToImage(
                commandBuffer,
                item.snapshotStaging->buffer,
                item.snapshotImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1,
                &copyRegion
            );

            VkImageMemoryBarrier toShader{};
            toShader.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            toShader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toShader.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            toShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toShader.image = item.snapshotImage;
            toShader.subresourceRange = toTransfer.subresourceRange;
            vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0,
                nullptr,
                0,
                nullptr,
                1,
                &toShader
            );

            pendingSplashStagingRelease_.push_back(item.snapshotStaging);
            item.snapshotStaging = nullptr;
            item.snapshotUploadPending = false;
        }
    }

    void recordPaintSplashComputeLocked(VkCommandBuffer commandBuffer, int64_t frameTimeNs) {
        if (splashItems_.empty() ||
            splashInitPipeline_ == VK_NULL_HANDLE ||
            splashInitPipelineLayout_ == VK_NULL_HANDLE ||
            splashUpdatePipeline_ == VK_NULL_HANDLE ||
            splashUpdatePipelineLayout_ == VK_NULL_HANDLE) {
            return;
        }

        updatePaintSplashGlassTargetsLocked();
        if (paintSplashGlassTargetCount_ > 0) {
            paintSplashGlassDripEndNs_ = std::max(
                paintSplashGlassDripEndNs_,
                frameTimeNs + kPaintSplashGlassDripDurationNs
            );
        }

        bool didWriteDroplets = false;
        for (PaintSplashItem& item : splashItems_) {
            if (item.descriptorSet == VK_NULL_HANDLE ||
                item.dropletBuffer == VK_NULL_HANDLE ||
                item.snapshotUploadPending ||
                item.dropletCount == 0) {
                continue;
            }

            const uint32_t groups = (item.dropletCount + 63u) / 64u;
            if (!item.initialized) {
                SplashInitPushConstants pushConstants{};
                pushConstants.itemSize[0] = std::max(1.0f, item.right - item.left);
                pushConstants.itemSize[1] = std::max(1.0f, item.bottom - item.top);
                pushConstants.dropletCount = item.dropletCount;

                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, splashInitPipeline_);
                vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    splashInitPipelineLayout_,
                    0,
                    1,
                    &item.descriptorSet,
                    0,
                    nullptr
                );
                vkCmdPushConstants(
                    commandBuffer,
                    splashInitPipelineLayout_,
                    VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    sizeof(SplashInitPushConstants),
                    &pushConstants
                );
                vkCmdDispatch(commandBuffer, groups, 1, 1);
                item.initialized = true;
                item.lastUpdateNs = frameTimeNs;
                didWriteDroplets = true;
                continue;
            }

            const float dt = std::clamp(
                static_cast<float>(frameTimeNs - item.lastUpdateNs) / 1000000000.0f,
                0.001f,
                0.05f
            );
            item.lastUpdateNs = frameTimeNs;
            SplashUpdatePushConstants pushConstants{};
            pushConstants.timeStep = dt;
            pushConstants.dropletCount = item.dropletCount;
            pushConstants.glassHitTargetCount = paintSplashGlassTargetCount_;
            pushConstants.glassEventCapacity = kMaxPaintSplashGlassDroplets;
            pushConstants.itemOrigin[0] = item.left;
            pushConstants.itemOrigin[1] = item.top;

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, splashUpdatePipeline_);
            vkCmdBindDescriptorSets(
                commandBuffer,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                splashUpdatePipelineLayout_,
                0,
                1,
                &item.descriptorSet,
                0,
                nullptr
            );
            vkCmdPushConstants(
                commandBuffer,
                splashUpdatePipelineLayout_,
                VK_SHADER_STAGE_COMPUTE_BIT,
                0,
                sizeof(SplashUpdatePushConstants),
                &pushConstants
            );
            vkCmdDispatch(commandBuffer, groups, 1, 1);
            didWriteDroplets = true;
        }

        if (!didWriteDroplets) {
            return;
        }

        VkMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
            0,
            1,
            &barrier,
            0,
            nullptr,
            0,
            nullptr
        );
    }

    VkRect2D paintSplashWorkRectLocked(int64_t frameTimeNs) const {
        if (swapchainExtent_.width == 0 || swapchainExtent_.height == 0 || splashItems_.empty()) {
            return VkRect2D{};
        }

        float left = static_cast<float>(swapchainExtent_.width);
        float top = static_cast<float>(swapchainExtent_.height);
        float right = 0.0f;
        float bottom = 0.0f;
        bool hasRect = false;

        for (const PaintSplashItem& item : splashItems_) {
            const float width = std::max(1.0f, item.right - item.left);
            const float height = std::max(1.0f, item.bottom - item.top);
            if (item.dropletCount == 0 || width <= 0.0f || height <= 0.0f) {
                continue;
            }

            const float elapsedSeconds = std::clamp(
                static_cast<float>(frameTimeNs - item.startTimeNs) / 1000000000.0f,
                0.0f,
                static_cast<float>(kPaintSplashDurationNs) / 1000000000.0f
            );
            const float flightProgress = std::clamp(elapsedSeconds / 0.9f, 0.0f, 1.0f);
            const float diagonal = std::hypot(width, height);
            const float blobPadding = std::clamp(std::max(width, height) * 0.20f, 48.0f, 128.0f);
            const float spreadPadding =
                diagonal * (0.16f + std::sqrt(flightProgress) * 0.72f) + blobPadding + 10.0f;

            left = std::min(left, item.left - spreadPadding);
            top = std::min(top, item.top - spreadPadding);
            right = std::max(right, item.right + spreadPadding);
            bottom = std::max(bottom, item.bottom + spreadPadding);
            hasRect = true;
        }

        if (!hasRect) {
            return VkRect2D{};
        }

        const int32_t x0 = static_cast<int32_t>(std::max(0.0f, std::floor(left)));
        const int32_t y0 = static_cast<int32_t>(std::max(0.0f, std::floor(top)));
        const uint32_t x1 = std::min(
            swapchainExtent_.width,
            static_cast<uint32_t>(std::ceil(std::max(left, right)))
        );
        const uint32_t y1 = std::min(
            swapchainExtent_.height,
            static_cast<uint32_t>(std::ceil(std::max(top, bottom)))
        );
        if (x1 <= static_cast<uint32_t>(x0) || y1 <= static_cast<uint32_t>(y0)) {
            return VkRect2D{};
        }

        VkRect2D rect{};
        rect.offset = {x0, y0};
        rect.extent = {x1 - static_cast<uint32_t>(x0), y1 - static_cast<uint32_t>(y0)};
        return rect;
    }

    VkExtent2D paintSplashBlobExtentLocked() const {
        if (swapchainExtent_.width == 0 || swapchainExtent_.height == 0) {
            return VkExtent2D{};
        }
        VkExtent2D extent{};
        extent.width = std::max(
            1u,
            static_cast<uint32_t>(
                std::ceil(static_cast<float>(swapchainExtent_.width) *
                    kPaintSplashBlobSurfaceScale)
            )
        );
        extent.height = std::max(
            1u,
            static_cast<uint32_t>(
                std::ceil(static_cast<float>(swapchainExtent_.height) *
                    kPaintSplashBlobSurfaceScale)
            )
        );
        return extent;
    }

    VkRect2D paintSplashBlobWorkRectLocked(const VkRect2D& screenRect) const {
        if (blobExtent_.width == 0 ||
            blobExtent_.height == 0 ||
            swapchainExtent_.width == 0 ||
            swapchainExtent_.height == 0 ||
            screenRect.extent.width == 0 ||
            screenRect.extent.height == 0) {
            return VkRect2D{};
        }

        const float scaleX =
            static_cast<float>(blobExtent_.width) / static_cast<float>(swapchainExtent_.width);
        const float scaleY =
            static_cast<float>(blobExtent_.height) / static_cast<float>(swapchainExtent_.height);
        const float screenLeft = static_cast<float>(screenRect.offset.x);
        const float screenTop = static_cast<float>(screenRect.offset.y);
        const float screenRight = screenLeft + static_cast<float>(screenRect.extent.width);
        const float screenBottom = screenTop + static_cast<float>(screenRect.extent.height);

        const int32_t x0 = static_cast<int32_t>(
            std::max(0.0f, std::floor(screenLeft * scaleX) - 1.0f)
        );
        const int32_t y0 = static_cast<int32_t>(
            std::max(0.0f, std::floor(screenTop * scaleY) - 1.0f)
        );
        const uint32_t x1 = std::min(
            blobExtent_.width,
            static_cast<uint32_t>(std::ceil(screenRight * scaleX) + 1.0f)
        );
        const uint32_t y1 = std::min(
            blobExtent_.height,
            static_cast<uint32_t>(std::ceil(screenBottom * scaleY) + 1.0f)
        );
        if (x1 <= static_cast<uint32_t>(x0) || y1 <= static_cast<uint32_t>(y0)) {
            return VkRect2D{};
        }

        VkRect2D rect{};
        rect.offset = {x0, y0};
        rect.extent = {x1 - static_cast<uint32_t>(x0), y1 - static_cast<uint32_t>(y0)};
        return rect;
    }

    void recordBlobPassLocked(VkCommandBuffer commandBuffer, const VkRect2D& workRect) {
        if (blobRenderPass_ == VK_NULL_HANDLE ||
            blobFramebuffer_ == VK_NULL_HANDLE ||
            particlePipeline_ == VK_NULL_HANDLE ||
            particlePipelineLayout_ == VK_NULL_HANDLE ||
            splashDescriptorSetLayout_ == VK_NULL_HANDLE ||
            blobExtent_.width == 0 ||
            blobExtent_.height == 0 ||
            workRect.extent.width == 0 ||
            workRect.extent.height == 0) {
            return;
        }

        const VkRect2D blobWorkRect = paintSplashBlobWorkRectLocked(workRect);
        if (blobWorkRect.extent.width == 0 || blobWorkRect.extent.height == 0) {
            return;
        }

        VkClearValue clearValue{};
        clearValue.color.float32[0] = 0.0f;
        clearValue.color.float32[1] = 0.0f;
        clearValue.color.float32[2] = 0.0f;
        clearValue.color.float32[3] = 0.0f;

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = blobRenderPass_;
        renderPassInfo.framebuffer = blobFramebuffer_;
        renderPassInfo.renderArea = blobWorkRect;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearValue;

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(blobExtent_.width);
        viewport.height = static_cast<float>(blobExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        const float scaleX =
            viewport.width / static_cast<float>(std::max(1u, swapchainExtent_.width));
        const float scaleY =
            viewport.height / static_cast<float>(std::max(1u, swapchainExtent_.height));

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, particlePipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &blobWorkRect);
        for (const PaintSplashItem& item : splashItems_) {
            if (item.descriptorSet == VK_NULL_HANDLE ||
                item.snapshotUploadPending ||
                !item.initialized ||
                item.dropletCount == 0) {
                continue;
            }
            ParticlePushConstants pushConstants{};
            pushConstants.viewportSize[0] = viewport.width;
            pushConstants.viewportSize[1] = viewport.height;
            pushConstants.itemOrigin[0] = item.left;
            pushConstants.itemOrigin[1] = item.top;
            pushConstants.screenToTargetScale[0] = scaleX;
            pushConstants.screenToTargetScale[1] = scaleY;
            pushConstants.blobScale = kPaintSplashBlobScale;
            vkCmdBindDescriptorSets(
                commandBuffer,
                VK_PIPELINE_BIND_POINT_GRAPHICS,
                particlePipelineLayout_,
                0,
                1,
                &item.descriptorSet,
                0,
                nullptr
            );
            vkCmdPushConstants(
                commandBuffer,
                particlePipelineLayout_,
                VK_SHADER_STAGE_VERTEX_BIT,
                0,
                sizeof(ParticlePushConstants),
                &pushConstants
            );
            vkCmdDraw(commandBuffer, 6, item.dropletCount, 0, 0);
        }
        vkCmdEndRenderPass(commandBuffer);
    }

    void recordPaintSplashGlassSurfacePassesLocked(
        VkCommandBuffer commandBuffer,
        int64_t frameTimeNs,
        bool hasVisibleSplash
    ) {
        if (paintSplashGlassImpactRenderPass_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactFramebuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactPipeline_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactPipelineLayout_ == VK_NULL_HANDLE ||
            paintSplashGlassImpactDescriptorSet_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfacePipeline_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfacePipelineLayout_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfaceDescriptorSet_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfaceImage_ == VK_NULL_HANDLE ||
            paintSplashGlassSurfaceWorkImage_ == VK_NULL_HANDLE ||
            paintSplashGlassVelocityImage_ == VK_NULL_HANDLE ||
            paintSplashGlassVelocityWorkImage_ == VK_NULL_HANDLE ||
            paintSplashGlassEventBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassCursorBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassCellBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSphParticleBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSphParticleWorkBuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSphUpdatePipeline_ == VK_NULL_HANDLE ||
            paintSplashGlassSphUpdatePipelineLayout_ == VK_NULL_HANDLE ||
            paintSplashGlassSphUpdateDescriptorSet_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFieldPipeline_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFieldPipelineLayout_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFieldDescriptorSet_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFramebuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSphImage_ == VK_NULL_HANDLE) {
            return;
        }

        updatePaintSplashGlassTargetsLocked();
        currentFrameTraceFlags_.glassTargetCount = paintSplashGlassTargetCount_;
        const VkRect2D screenWorkRect = paintSplashGlassWorkRectLocked();
        const VkRect2D surfaceWorkRect = paintSplashGlassSurfaceWorkRectLocked(screenWorkRect);
        if (surfaceWorkRect.extent.width == 0 || surfaceWorkRect.extent.height == 0) {
            return;
        }
        if (hasVisibleSplash && paintSplashGlassTargetCount_ > 0) {
            paintSplashGlassDripEndNs_ = std::max(
                paintSplashGlassDripEndNs_,
                frameTimeNs + kPaintSplashGlassDripDurationNs
            );
        }

        if (paintSplashGlassNeedsReset_) {
            recordResetPaintSplashGlassSurfaceLocked(commandBuffer);
            writeFrameTraceTimestampLocked(commandBuffer, "wetReset");
            paintSplashGlassLastSurfaceUpdateNs_ = frameTimeNs;
            paintSplashGlassLastSphUpdateNs_ = frameTimeNs;
            paintSplashGlassSurfaceAge_ = 0.0f;
            paintSplashGlassNeedsReset_ = false;
        }

        recordPaintSplashGlassImpactPassLocked(commandBuffer, surfaceWorkRect);
        writeFrameTraceTimestampLocked(commandBuffer, "wetImpact");
        recordClearPaintSplashGlassEventsLocked(commandBuffer);
        writeFrameTraceTimestampLocked(commandBuffer, "wetEventClear");
        recordPaintSplashGlassSurfaceComputeLocked(commandBuffer, frameTimeNs, surfaceWorkRect);
        writeFrameTraceTimestampLocked(commandBuffer, "wetCompute");
        recordPaintSplashGlassSphUpdateLocked(commandBuffer, frameTimeNs);
        writeFrameTraceTimestampLocked(commandBuffer, "sphCompute");
        recordPaintSplashGlassSphFieldPassLocked(commandBuffer, frameTimeNs);
        writeFrameTraceTimestampLocked(commandBuffer, "sphField");
    }

    VkExtent2D paintSplashGlassSurfaceExtentLocked() const {
        if (swapchainExtent_.width == 0 || swapchainExtent_.height == 0) {
            return VkExtent2D{};
        }
        VkExtent2D extent{};
        extent.width = std::max(
            1u,
            static_cast<uint32_t>(
                std::ceil(static_cast<float>(swapchainExtent_.width) *
                    kPaintSplashGlassSurfaceScale)
            )
        );
        extent.height = std::max(
            1u,
            static_cast<uint32_t>(
                std::ceil(static_cast<float>(swapchainExtent_.height) *
                    kPaintSplashGlassSurfaceScale)
            )
        );
        return extent;
    }

    VkExtent2D paintSplashGlassSphExtentLocked() const {
        if (swapchainExtent_.width == 0 || swapchainExtent_.height == 0) {
            return VkExtent2D{};
        }
        VkExtent2D extent{};
        extent.width = std::max(
            1u,
            static_cast<uint32_t>(
                std::ceil(static_cast<float>(swapchainExtent_.width) *
                    kPaintSplashGlassSphSurfaceScale)
            )
        );
        extent.height = std::max(
            1u,
            static_cast<uint32_t>(
                std::ceil(static_cast<float>(swapchainExtent_.height) *
                    kPaintSplashGlassSphSurfaceScale)
            )
        );
        return extent;
    }

    VkRect2D paintSplashGlassWorkRectLocked() const {
        if (swapchainExtent_.width == 0 || swapchainExtent_.height == 0 || backdropRects_.empty()) {
            return VkRect2D{};
        }

        float left = static_cast<float>(swapchainExtent_.width);
        float top = static_cast<float>(swapchainExtent_.height);
        float right = 0.0f;
        float bottom = 0.0f;
        bool hasRect = false;
        for (const BackdropRect& rect : backdropRects_) {
            if (rect.right <= rect.left || rect.bottom <= rect.top || rect.opacity <= 0.0f) {
                continue;
            }
            const float margin = std::max(24.0f, rect.glassThickness * 0.45f);
            left = std::min(left, rect.left - margin);
            top = std::min(top, rect.top - margin);
            right = std::max(right, rect.right + margin);
            bottom = std::max(bottom, rect.bottom + margin);
            hasRect = true;
        }
        if (!hasRect) {
            return VkRect2D{};
        }

        const int32_t x0 = static_cast<int32_t>(std::max(0.0f, std::floor(left)));
        const int32_t y0 = static_cast<int32_t>(std::max(0.0f, std::floor(top)));
        const uint32_t x1 = std::min(
            swapchainExtent_.width,
            static_cast<uint32_t>(std::ceil(std::max(left, right)))
        );
        const uint32_t y1 = std::min(
            swapchainExtent_.height,
            static_cast<uint32_t>(std::ceil(std::max(top, bottom)))
        );
        if (x1 <= static_cast<uint32_t>(x0) || y1 <= static_cast<uint32_t>(y0)) {
            return VkRect2D{};
        }

        VkRect2D rect{};
        rect.offset = {x0, y0};
        rect.extent = {x1 - static_cast<uint32_t>(x0), y1 - static_cast<uint32_t>(y0)};
        return rect;
    }

    VkRect2D paintSplashGlassSurfaceWorkRectLocked(const VkRect2D& screenRect) const {
        if (screenRect.extent.width == 0 ||
            screenRect.extent.height == 0 ||
            swapchainExtent_.width == 0 ||
            swapchainExtent_.height == 0) {
            return VkRect2D{};
        }

        const VkExtent2D surfaceExtent = paintSplashGlassSurfaceExtentLocked();
        if (surfaceExtent.width == 0 || surfaceExtent.height == 0) {
            return VkRect2D{};
        }

        const float scaleX =
            static_cast<float>(surfaceExtent.width) /
            static_cast<float>(swapchainExtent_.width);
        const float scaleY =
            static_cast<float>(surfaceExtent.height) /
            static_cast<float>(swapchainExtent_.height);
        const float screenLeft = static_cast<float>(screenRect.offset.x);
        const float screenTop = static_cast<float>(screenRect.offset.y);
        const float screenRight =
            static_cast<float>(screenRect.offset.x) +
            static_cast<float>(screenRect.extent.width);
        const float screenBottom =
            static_cast<float>(screenRect.offset.y) +
            static_cast<float>(screenRect.extent.height);

        const int32_t x0 = static_cast<int32_t>(
            std::max(0.0f, std::floor(screenLeft * scaleX))
        );
        const int32_t y0 = static_cast<int32_t>(
            std::max(0.0f, std::floor(screenTop * scaleY))
        );
        const uint32_t x1 = std::min(
            surfaceExtent.width,
            static_cast<uint32_t>(std::ceil(screenRight * scaleX))
        );
        const uint32_t y1 = std::min(
            surfaceExtent.height,
            static_cast<uint32_t>(std::ceil(screenBottom * scaleY))
        );
        if (x1 <= static_cast<uint32_t>(x0) || y1 <= static_cast<uint32_t>(y0)) {
            return VkRect2D{};
        }

        VkRect2D rect{};
        rect.offset = {x0, y0};
        rect.extent = {x1 - static_cast<uint32_t>(x0), y1 - static_cast<uint32_t>(y0)};
        return rect;
    }

    VkRect2D paintSplashGlassSphWorkRectLocked(const VkRect2D& screenRect) const {
        if (screenRect.extent.width == 0 ||
            screenRect.extent.height == 0 ||
            swapchainExtent_.width == 0 ||
            swapchainExtent_.height == 0) {
            return VkRect2D{};
        }

        const VkExtent2D sphExtent = paintSplashGlassSphExtentLocked();
        if (sphExtent.width == 0 || sphExtent.height == 0) {
            return VkRect2D{};
        }

        const float scaleX =
            static_cast<float>(sphExtent.width) /
            static_cast<float>(swapchainExtent_.width);
        const float scaleY =
            static_cast<float>(sphExtent.height) /
            static_cast<float>(swapchainExtent_.height);
        const float screenLeft = static_cast<float>(screenRect.offset.x);
        const float screenTop = static_cast<float>(screenRect.offset.y);
        const float screenRight =
            static_cast<float>(screenRect.offset.x) +
            static_cast<float>(screenRect.extent.width);
        const float screenBottom =
            static_cast<float>(screenRect.offset.y) +
            static_cast<float>(screenRect.extent.height);

        const int32_t x0 = static_cast<int32_t>(
            std::max(0.0f, std::floor(screenLeft * scaleX))
        );
        const int32_t y0 = static_cast<int32_t>(
            std::max(0.0f, std::floor(screenTop * scaleY))
        );
        const uint32_t x1 = std::min(
            sphExtent.width,
            static_cast<uint32_t>(std::ceil(screenRight * scaleX))
        );
        const uint32_t y1 = std::min(
            sphExtent.height,
            static_cast<uint32_t>(std::ceil(screenBottom * scaleY))
        );
        if (x1 <= static_cast<uint32_t>(x0) || y1 <= static_cast<uint32_t>(y0)) {
            return VkRect2D{};
        }

        VkRect2D rect{};
        rect.offset = {x0, y0};
        rect.extent = {x1 - static_cast<uint32_t>(x0), y1 - static_cast<uint32_t>(y0)};
        return rect;
    }

    VkRect2D paintSplashGlassSphScreenWorkRectLocked() const {
        if (swapchainExtent_.width == 0 || swapchainExtent_.height == 0 || backdropRects_.empty()) {
            return VkRect2D{};
        }

        float left = static_cast<float>(swapchainExtent_.width);
        float top = static_cast<float>(swapchainExtent_.height);
        float right = 0.0f;
        float bottom = 0.0f;
        bool hasRect = false;
        for (const BackdropRect& rect : backdropRects_) {
            if (rect.right <= rect.left || rect.bottom <= rect.top || rect.opacity <= 0.0f) {
                continue;
            }
            const float height = rect.bottom - rect.top;
            const float sideMargin = std::max(72.0f, rect.glassThickness * 0.90f);
            const float topMargin = std::max(32.0f, rect.glassThickness * 0.35f);
            const float bottomMargin = std::max(220.0f, height * 2.20f);
            left = std::min(left, rect.left - sideMargin);
            top = std::min(top, rect.top - topMargin);
            right = std::max(right, rect.right + sideMargin);
            bottom = std::max(bottom, rect.bottom + bottomMargin);
            hasRect = true;
        }
        if (!hasRect) {
            return VkRect2D{};
        }

        const int32_t x0 = static_cast<int32_t>(std::max(0.0f, std::floor(left)));
        const int32_t y0 = static_cast<int32_t>(std::max(0.0f, std::floor(top)));
        const uint32_t x1 = std::min(
            swapchainExtent_.width,
            static_cast<uint32_t>(std::ceil(std::max(left, right)))
        );
        const uint32_t y1 = std::min(
            swapchainExtent_.height,
            static_cast<uint32_t>(std::ceil(std::max(top, bottom)))
        );
        if (x1 <= static_cast<uint32_t>(x0) || y1 <= static_cast<uint32_t>(y0)) {
            return VkRect2D{};
        }

        VkRect2D rect{};
        rect.offset = {x0, y0};
        rect.extent = {x1 - static_cast<uint32_t>(x0), y1 - static_cast<uint32_t>(y0)};
        return rect;
    }

    uint32_t paintSplashGlassSphParticleCapacityLocked() const {
        return std::min(
            kMaxPaintSplashGlassSphParticles,
            paintSplashGlassTargetCount_ * kPaintSplashGlassSphParticlesPerTarget
        );
    }

    float paintSplashGlassSphVisibleFadeLocked(int64_t nowNs) const {
        if (!splashItems_.empty()) {
            return 1.0f;
        }
        if (paintSplashGlassDripEndNs_ <= nowNs) {
            return 0.0f;
        }
        const float remainingSeconds =
            static_cast<float>(paintSplashGlassDripEndNs_ - nowNs) / 1000000000.0f;
        return std::clamp(remainingSeconds / 1.15f, 0.0f, 1.0f);
    }

    void recordResetPaintSplashGlassSurfaceLocked(VkCommandBuffer commandBuffer) {
        VkClearColorValue clearColor{};
        clearColor.float32[0] = 0.0f;
        clearColor.float32[1] = 0.0f;
        clearColor.float32[2] = 0.0f;
        clearColor.float32[3] = 0.0f;

        clearPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassSurfaceImage_,
            paintSplashGlassSurfaceLayout_,
            clearColor
        );
        clearPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassSurfaceWorkImage_,
            paintSplashGlassSurfaceWorkLayout_,
            clearColor
        );
        clearPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassVelocityImage_,
            paintSplashGlassVelocityLayout_,
            clearColor
        );
        clearPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassVelocityWorkImage_,
            paintSplashGlassVelocityWorkLayout_,
            clearColor
        );
        clearPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassImpactImage_,
            paintSplashGlassImpactLayout_,
            clearColor
        );
        clearPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassImpactVelocityImage_,
            paintSplashGlassImpactVelocityLayout_,
            clearColor
        );
        clearPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassSphImage_,
            paintSplashGlassSphLayout_,
            clearColor
        );

        vkCmdFillBuffer(
            commandBuffer,
            paintSplashGlassEventBuffer_,
            0,
            sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets,
            0
        );
        vkCmdFillBuffer(commandBuffer, paintSplashGlassCursorBuffer_, 0, sizeof(uint32_t), 0);
        vkCmdFillBuffer(
            commandBuffer,
            paintSplashGlassCellBuffer_,
            0,
            sizeof(uint32_t) * kPaintSplashGlassCellCount,
            0
        );
        vkCmdFillBuffer(
            commandBuffer,
            paintSplashGlassSphParticleBuffer_,
            0,
            sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles,
            0
        );
        vkCmdFillBuffer(
            commandBuffer,
            paintSplashGlassSphParticleWorkBuffer_,
            0,
            sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles,
            0
        );

        std::array<VkBufferMemoryBarrier, 5> barriers{};
        barriers[0].buffer = paintSplashGlassEventBuffer_;
        barriers[0].size = sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets;
        barriers[1].buffer = paintSplashGlassCursorBuffer_;
        barriers[1].size = sizeof(uint32_t);
        barriers[2].buffer = paintSplashGlassCellBuffer_;
        barriers[2].size = sizeof(uint32_t) * kPaintSplashGlassCellCount;
        barriers[3].buffer = paintSplashGlassSphParticleBuffer_;
        barriers[3].size = sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles;
        barriers[4].buffer = paintSplashGlassSphParticleWorkBuffer_;
        barriers[4].size = sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles;
        for (VkBufferMemoryBarrier& barrier : barriers) {
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.offset = 0;
        }

        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
            0,
            0,
            nullptr,
            static_cast<uint32_t>(barriers.size()),
            barriers.data(),
            0,
            nullptr
        );

        updatePaintSplashGlassSurfaceDescriptorLocked();
        updatePaintSplashGlassSphUpdateDescriptorLocked();
        updatePaintSplashGlassSphFieldDescriptorLocked();
        updatePaintSplashGlassSphCompositeDescriptorLocked();
        updateBackdropDescriptorLocked();
    }

    void recordPaintSplashGlassImpactPassLocked(
        VkCommandBuffer commandBuffer,
        const VkRect2D& workRect
    ) {
        const VkExtent2D surfaceExtent = paintSplashGlassSurfaceExtentLocked();
        if (surfaceExtent.width == 0 || surfaceExtent.height == 0) {
            return;
        }

        VkClearValue clearValues[2]{};
        for (VkClearValue& clearValue : clearValues) {
            clearValue.color.float32[0] = 0.0f;
            clearValue.color.float32[1] = 0.0f;
            clearValue.color.float32[2] = 0.0f;
            clearValue.color.float32[3] = 0.0f;
        }

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = paintSplashGlassImpactRenderPass_;
        renderPassInfo.framebuffer = paintSplashGlassImpactFramebuffer_;
        renderPassInfo.renderArea = workRect;
        renderPassInfo.clearValueCount = 2;
        renderPassInfo.pClearValues = clearValues;
        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(surfaceExtent.width);
        viewport.height = static_cast<float>(surfaceExtent.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        GlassImpactPushConstants pushConstants{};
        pushConstants.viewportSize[0] = static_cast<float>(swapchainExtent_.width);
        pushConstants.viewportSize[1] = static_cast<float>(swapchainExtent_.height);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, paintSplashGlassImpactPipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &workRect);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            paintSplashGlassImpactPipelineLayout_,
            0,
            1,
            &paintSplashGlassImpactDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            paintSplashGlassImpactPipelineLayout_,
            VK_SHADER_STAGE_VERTEX_BIT,
            0,
            sizeof(GlassImpactPushConstants),
            &pushConstants
        );
        vkCmdDraw(commandBuffer, 6, kMaxPaintSplashGlassDroplets, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        paintSplashGlassImpactLayout_ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        paintSplashGlassImpactVelocityLayout_ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    void recordClearPaintSplashGlassEventsLocked(VkCommandBuffer commandBuffer) {
        std::array<VkBufferMemoryBarrier, 3> beforeClear{};
        beforeClear[0].buffer = paintSplashGlassEventBuffer_;
        beforeClear[0].size = sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets;
        beforeClear[1].buffer = paintSplashGlassCursorBuffer_;
        beforeClear[1].size = sizeof(uint32_t);
        beforeClear[2].buffer = paintSplashGlassCellBuffer_;
        beforeClear[2].size = sizeof(uint32_t) * kPaintSplashGlassCellCount;
        for (VkBufferMemoryBarrier& barrier : beforeClear) {
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.offset = 0;
        }
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            static_cast<uint32_t>(beforeClear.size()),
            beforeClear.data(),
            0,
            nullptr
        );

        vkCmdFillBuffer(
            commandBuffer,
            paintSplashGlassEventBuffer_,
            0,
            sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets,
            0
        );
        vkCmdFillBuffer(commandBuffer, paintSplashGlassCursorBuffer_, 0, sizeof(uint32_t), 0);
        vkCmdFillBuffer(
            commandBuffer,
            paintSplashGlassCellBuffer_,
            0,
            sizeof(uint32_t) * kPaintSplashGlassCellCount,
            0
        );

        std::array<VkBufferMemoryBarrier, 3> afterClear{};
        afterClear[0].buffer = paintSplashGlassEventBuffer_;
        afterClear[0].size = sizeof(GpuGlassDroplet) * kMaxPaintSplashGlassDroplets;
        afterClear[1].buffer = paintSplashGlassCursorBuffer_;
        afterClear[1].size = sizeof(uint32_t);
        afterClear[2].buffer = paintSplashGlassCellBuffer_;
        afterClear[2].size = sizeof(uint32_t) * kPaintSplashGlassCellCount;
        for (VkBufferMemoryBarrier& barrier : afterClear) {
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.offset = 0;
        }
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
            0,
            0,
            nullptr,
            static_cast<uint32_t>(afterClear.size()),
            afterClear.data(),
            0,
            nullptr
        );
    }

    void recordPaintSplashGlassSurfaceComputeLocked(
        VkCommandBuffer commandBuffer,
        int64_t frameTimeNs,
        const VkRect2D& workRect
    ) {
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassSurfaceImage_,
            paintSplashGlassSurfaceLayout_,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        );
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassVelocityImage_,
            paintSplashGlassVelocityLayout_,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        );
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassImpactImage_,
            paintSplashGlassImpactLayout_,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        );
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassImpactVelocityImage_,
            paintSplashGlassImpactVelocityLayout_,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        );
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassSurfaceWorkImage_,
            paintSplashGlassSurfaceWorkLayout_,
            VK_IMAGE_LAYOUT_GENERAL
        );
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassVelocityWorkImage_,
            paintSplashGlassVelocityWorkLayout_,
            VK_IMAGE_LAYOUT_GENERAL
        );

        updatePaintSplashGlassSurfaceDescriptorLocked();

        const float dt = paintSplashGlassLastSurfaceUpdateNs_ > 0
            ? std::clamp(
                static_cast<float>(frameTimeNs - paintSplashGlassLastSurfaceUpdateNs_) /
                    1000000000.0f,
                0.001f,
                0.05f
            )
            : 0.016f;
        paintSplashGlassLastSurfaceUpdateNs_ = frameTimeNs;
        paintSplashGlassSurfaceAge_ += dt;

        GlassSurfacePushConstants pushConstants{};
        pushConstants.timeStep = dt;
        pushConstants.hitTargetCount = paintSplashGlassTargetCount_;
        pushConstants.viewportSize[0] = static_cast<float>(swapchainExtent_.width);
        pushConstants.viewportSize[1] = static_cast<float>(swapchainExtent_.height);
        pushConstants.dispatchOrigin[0] = workRect.offset.x;
        pushConstants.dispatchOrigin[1] = workRect.offset.y;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, paintSplashGlassSurfacePipeline_);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            paintSplashGlassSurfacePipelineLayout_,
            0,
            1,
            &paintSplashGlassSurfaceDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            paintSplashGlassSurfacePipelineLayout_,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(GlassSurfacePushConstants),
            &pushConstants
        );
        vkCmdDispatch(
            commandBuffer,
            (workRect.extent.width + 15u) / 16u,
            (workRect.extent.height + 15u) / 16u,
            1
        );

        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassSurfaceWorkImage_,
            paintSplashGlassSurfaceWorkLayout_,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        );
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            paintSplashGlassVelocityWorkImage_,
            paintSplashGlassVelocityWorkLayout_,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        );

        std::swap(paintSplashGlassSurfaceImage_, paintSplashGlassSurfaceWorkImage_);
        std::swap(paintSplashGlassSurfaceMemory_, paintSplashGlassSurfaceWorkMemory_);
        std::swap(paintSplashGlassSurfaceImageView_, paintSplashGlassSurfaceWorkImageView_);
        std::swap(paintSplashGlassSurfaceLayout_, paintSplashGlassSurfaceWorkLayout_);

        std::swap(paintSplashGlassVelocityImage_, paintSplashGlassVelocityWorkImage_);
        std::swap(paintSplashGlassVelocityMemory_, paintSplashGlassVelocityWorkMemory_);
        std::swap(paintSplashGlassVelocityImageView_, paintSplashGlassVelocityWorkImageView_);
        std::swap(paintSplashGlassVelocityLayout_, paintSplashGlassVelocityWorkLayout_);

        updatePaintSplashGlassSurfaceDescriptorLocked();
        updateBackdropDescriptorLocked();
    }

    void recordPaintSplashGlassSphUpdateLocked(
        VkCommandBuffer commandBuffer,
        int64_t frameTimeNs
    ) {
        const uint32_t particleCapacity = paintSplashGlassSphParticleCapacityLocked();
        if (particleCapacity == 0 ||
            paintSplashGlassSphUpdatePipeline_ == VK_NULL_HANDLE ||
            paintSplashGlassSphUpdatePipelineLayout_ == VK_NULL_HANDLE ||
            paintSplashGlassSphUpdateDescriptorSet_ == VK_NULL_HANDLE) {
            return;
        }

        updatePaintSplashGlassSphUpdateDescriptorLocked();

        const float dt = paintSplashGlassLastSphUpdateNs_ > 0
            ? std::clamp(
                static_cast<float>(frameTimeNs - paintSplashGlassLastSphUpdateNs_) /
                    1000000000.0f,
                0.001f,
                0.05f
            )
            : 0.016f;
        paintSplashGlassLastSphUpdateNs_ = frameTimeNs;

        GlassSphUpdatePushConstants pushConstants{};
        pushConstants.timeStep = dt;
        pushConstants.hitTargetCount = paintSplashGlassTargetCount_;
        pushConstants.particleCapacity = particleCapacity;
        pushConstants.viewportSize[0] = static_cast<float>(swapchainExtent_.width);
        pushConstants.viewportSize[1] = static_cast<float>(swapchainExtent_.height);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, paintSplashGlassSphUpdatePipeline_);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            paintSplashGlassSphUpdatePipelineLayout_,
            0,
            1,
            &paintSplashGlassSphUpdateDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            paintSplashGlassSphUpdatePipelineLayout_,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(GlassSphUpdatePushConstants),
            &pushConstants
        );
        vkCmdDispatch(commandBuffer, (particleCapacity + 63u) / 64u, 1, 1);

        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = paintSplashGlassSphParticleWorkBuffer_;
        barrier.offset = 0;
        barrier.size = sizeof(GpuGlassSphParticle) * kMaxPaintSplashGlassSphParticles;
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
            0,
            0,
            nullptr,
            1,
            &barrier,
            0,
            nullptr
        );

        std::swap(paintSplashGlassSphParticleBuffer_, paintSplashGlassSphParticleWorkBuffer_);
        std::swap(paintSplashGlassSphParticleMemory_, paintSplashGlassSphParticleWorkMemory_);
        updatePaintSplashGlassSphFieldDescriptorLocked();
    }

    void recordPaintSplashGlassSphFieldPassLocked(
        VkCommandBuffer commandBuffer,
        int64_t frameTimeNs
    ) {
        const uint32_t particleCapacity = paintSplashGlassSphParticleCapacityLocked();
        const float visibleFade = paintSplashGlassSphVisibleFadeLocked(frameTimeNs);
        if (particleCapacity == 0 ||
            visibleFade <= 0.010f ||
            blobRenderPass_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFramebuffer_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFieldPipeline_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFieldPipelineLayout_ == VK_NULL_HANDLE ||
            paintSplashGlassSphFieldDescriptorSet_ == VK_NULL_HANDLE) {
            return;
        }

        const VkRect2D screenWorkRect = paintSplashGlassSphScreenWorkRectLocked();
        const VkRect2D sphWorkRect = paintSplashGlassSphWorkRectLocked(screenWorkRect);
        const VkExtent2D sphExtent = paintSplashGlassSphExtentLocked();
        if (screenWorkRect.extent.width == 0 ||
            screenWorkRect.extent.height == 0 ||
            sphWorkRect.extent.width == 0 ||
            sphWorkRect.extent.height == 0 ||
            sphExtent.width == 0 ||
            sphExtent.height == 0) {
            return;
        }

        VkClearValue clearValue{};
        clearValue.color.float32[0] = 0.0f;
        clearValue.color.float32[1] = 0.0f;
        clearValue.color.float32[2] = 0.0f;
        clearValue.color.float32[3] = 0.0f;

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = blobRenderPass_;
        renderPassInfo.framebuffer = paintSplashGlassSphFramebuffer_;
        renderPassInfo.renderArea = sphWorkRect;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearValue;

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(sphExtent.width);
        viewport.height = static_cast<float>(sphExtent.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        GlassSphRenderPushConstants pushConstants{};
        pushConstants.viewportSize[0] = static_cast<float>(swapchainExtent_.width);
        pushConstants.viewportSize[1] = static_cast<float>(swapchainExtent_.height);
        pushConstants.visibleFade = visibleFade;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, paintSplashGlassSphFieldPipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &sphWorkRect);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            paintSplashGlassSphFieldPipelineLayout_,
            0,
            1,
            &paintSplashGlassSphFieldDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            paintSplashGlassSphFieldPipelineLayout_,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            sizeof(GlassSphRenderPushConstants),
            &pushConstants
        );
        vkCmdDraw(commandBuffer, 6, particleCapacity, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        paintSplashGlassSphLayout_ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        updatePaintSplashGlassSphCompositeDescriptorLocked();
    }

    bool recordBackdropOverlayCompositeLocked(
        VkCommandBuffer commandBuffer,
        bool hasPaintSplashes
    ) {
        if (!hasPaintSplashes ||
            activeBackdropEntry_ == nullptr ||
            activeBackdropEntry_->imageView == VK_NULL_HANDLE ||
            backdropOverlayPipeline_ == VK_NULL_HANDLE ||
            backdropOverlayPipelineLayout_ == VK_NULL_HANDLE ||
            backdropOverlayDescriptorSet_ == VK_NULL_HANDLE ||
            backdropCompositeImage_ == VK_NULL_HANDLE ||
            backdropCompositeImageView_ == VK_NULL_HANDLE ||
            backdropCompositeFramebuffer_ == VK_NULL_HANDLE ||
            blobImageView_ == VK_NULL_HANDLE ||
            backdropBlurRenderPass_ == VK_NULL_HANDLE ||
            backdropWidth_ <= 0 ||
            backdropHeight_ <= 0) {
            return false;
        }

        updateBackdropOverlayDescriptorLocked();
        transitionImageForColorAttachmentLocked(
            commandBuffer,
            backdropCompositeImage_,
            backdropCompositeLayout_
        );

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = backdropBlurRenderPass_;
        renderPassInfo.framebuffer = backdropCompositeFramebuffer_;
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = {
            static_cast<uint32_t>(backdropWidth_),
            static_cast<uint32_t>(backdropHeight_)
        };

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(backdropWidth_);
        viewport.height = static_cast<float>(backdropHeight_);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = {
            static_cast<uint32_t>(backdropWidth_),
            static_cast<uint32_t>(backdropHeight_)
        };

        BackdropOverlayPushConstants pushConstants{};
        pushConstants.viewportSize[0] = static_cast<float>(swapchainExtent_.width);
        pushConstants.viewportSize[1] = static_cast<float>(swapchainExtent_.height);
        pushConstants.textureSize[0] = static_cast<float>(backdropWidth_);
        pushConstants.textureSize[1] = static_cast<float>(backdropHeight_);
        pushConstants.textureOrigin[0] = backdropTextureOrigin_[0];
        pushConstants.textureOrigin[1] = backdropTextureOrigin_[1];
        pushConstants.overlayAlpha = 1.0f;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, backdropOverlayPipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            backdropOverlayPipelineLayout_,
            0,
            1,
            &backdropOverlayDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            backdropOverlayPipelineLayout_,
            VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            sizeof(BackdropOverlayPushConstants),
            &pushConstants
        );
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        backdropCompositeLayout_ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        updateBackdropSourceDescriptorsLocked(backdropCompositeImageView_);
        return true;
    }

    TeleportPushConstants teleportPushConstantsLocked(
        float outputWidth,
        float outputHeight,
        float outputLeft,
        float outputTop
    ) const {
        TeleportPushConstants pushConstants{};
        pushConstants.outputSize[0] = outputWidth;
        pushConstants.outputSize[1] = outputHeight;
        pushConstants.outputOrigin[0] = outputLeft;
        pushConstants.outputOrigin[1] = outputTop;
        std::copy(
            teleportViewportRect_.begin(),
            teleportViewportRect_.end(),
            pushConstants.viewportRect
        );
        pushConstants.sceneTextureSize[0] =
            static_cast<float>(std::max(1, teleportOldEntry_.width));
        pushConstants.sceneTextureSize[1] =
            static_cast<float>(std::max(1, teleportOldEntry_.height));
        pushConstants.progress = teleportProgress_;
        pushConstants.directionSign = teleportDirectionSign_;
        return pushConstants;
    }

    bool recordTeleportGlassSceneLocked(VkCommandBuffer commandBuffer) {
        if (!hasTeleportSceneLocked() ||
            backdropCompositeImage_ == VK_NULL_HANDLE ||
            backdropCompositeFramebuffer_ == VK_NULL_HANDLE ||
            backdropBlurRenderPass_ == VK_NULL_HANDLE ||
            teleportGlassPipeline_ == VK_NULL_HANDLE ||
            teleportPipelineLayout_ == VK_NULL_HANDLE ||
            backdropWidth_ <= 0 ||
            backdropHeight_ <= 0) {
            return false;
        }

        transitionImageForColorAttachmentLocked(
            commandBuffer,
            backdropCompositeImage_,
            backdropCompositeLayout_
        );

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = backdropBlurRenderPass_;
        renderPassInfo.framebuffer = backdropCompositeFramebuffer_;
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = {
            static_cast<uint32_t>(backdropWidth_),
            static_cast<uint32_t>(backdropHeight_)
        };
        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(backdropWidth_);
        viewport.height = static_cast<float>(backdropHeight_);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;
        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = {
            static_cast<uint32_t>(backdropWidth_),
            static_cast<uint32_t>(backdropHeight_)
        };
        const TeleportPushConstants pushConstants = teleportPushConstantsLocked(
            viewport.width,
            viewport.height,
            teleportCaptureOrigin_[0],
            teleportCaptureOrigin_[1]
        );

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, teleportGlassPipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            teleportPipelineLayout_,
            0,
            1,
            &teleportDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            teleportPipelineLayout_,
            VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            sizeof(TeleportPushConstants),
            &pushConstants
        );
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
        backdropCompositeLayout_ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        return true;
    }

    void recordBackdropBlurPassesLocked(VkCommandBuffer commandBuffer) {
        if (!hasBackdropImageLocked() ||
            !hasBackdropBlurResourcesLocked() ||
            backdropBlurPipeline_ == VK_NULL_HANDLE ||
            backdropBlurPipelineLayout_ == VK_NULL_HANDLE ||
            backdropBlurSourceDescriptorSet_ == VK_NULL_HANDLE ||
            backdropBlurTempDescriptorSet_ == VK_NULL_HANDLE) {
            return;
        }

        transitionImageForColorAttachmentLocked(
            commandBuffer,
            backdropBlurTempImage_,
            backdropBlurTempLayout_
        );
        recordSingleBackdropBlurPassLocked(
            commandBuffer,
            backdropBlurTempFramebuffer_,
            backdropBlurSourceDescriptorSet_,
            1.0f / static_cast<float>(std::max(1u, backdropBlurWidth_)),
            0.0f
        );
        backdropBlurTempLayout_ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        transitionImageForColorAttachmentLocked(
            commandBuffer,
            backdropBlurImage_,
            backdropBlurLayout_
        );
        recordSingleBackdropBlurPassLocked(
            commandBuffer,
            backdropBlurFramebuffer_,
            backdropBlurTempDescriptorSet_,
            0.0f,
            1.0f / static_cast<float>(std::max(1u, backdropBlurHeight_))
        );
        backdropBlurLayout_ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    bool hasBackdropBlurResourcesLocked() const {
        return backdropBlurWidth_ > 0 &&
            backdropBlurHeight_ > 0 &&
            backdropBlurRenderPass_ != VK_NULL_HANDLE &&
            backdropBlurTempImage_ != VK_NULL_HANDLE &&
            backdropBlurImage_ != VK_NULL_HANDLE &&
            backdropBlurTempImageView_ != VK_NULL_HANDLE &&
            backdropBlurImageView_ != VK_NULL_HANDLE &&
            backdropBlurTempFramebuffer_ != VK_NULL_HANDLE &&
            backdropBlurFramebuffer_ != VK_NULL_HANDLE;
    }

    void transitionImageForColorAttachmentLocked(
        VkCommandBuffer commandBuffer,
        VkImage image,
        VkImageLayout oldLayout
    ) {
        if (image == VK_NULL_HANDLE || oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            return;
        }

        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = oldLayout;
        barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;

        VkPipelineStageFlags srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        if (oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            srcStage =
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        }
        barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        vkCmdPipelineBarrier(
            commandBuffer,
            srcStage,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &barrier
        );
    }

    void transitionPaintSplashGlassImageLocked(
        VkCommandBuffer commandBuffer,
        VkImage image,
        VkImageLayout& layout,
        VkImageLayout newLayout
    ) {
        if (image == VK_NULL_HANDLE || layout == newLayout) {
            return;
        }

        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = layout;
        barrier.newLayout = newLayout;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;

        VkPipelineStageFlags srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        if (layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        } else if (layout == VK_IMAGE_LAYOUT_GENERAL) {
            barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            srcStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        } else if (layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        } else if (layout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        }

        VkPipelineStageFlags dstStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        if (newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        } else if (newLayout == VK_IMAGE_LAYOUT_GENERAL) {
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            dstStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        } else if (newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        } else if (newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        }

        vkCmdPipelineBarrier(
            commandBuffer,
            srcStage,
            dstStage,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &barrier
        );
        layout = newLayout;
    }

    void clearPaintSplashGlassImageLocked(
        VkCommandBuffer commandBuffer,
        VkImage image,
        VkImageLayout& layout,
        const VkClearColorValue& clearColor
    ) {
        if (image == VK_NULL_HANDLE) {
            return;
        }
        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            image,
            layout,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
        );

        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.baseMipLevel = 0;
        range.levelCount = 1;
        range.baseArrayLayer = 0;
        range.layerCount = 1;
        vkCmdClearColorImage(
            commandBuffer,
            image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            &clearColor,
            1,
            &range
        );

        transitionPaintSplashGlassImageLocked(
            commandBuffer,
            image,
            layout,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        );
    }

    void recordSingleBackdropBlurPassLocked(
        VkCommandBuffer commandBuffer,
        VkFramebuffer framebuffer,
        VkDescriptorSet descriptorSet,
        float texelStepX,
        float texelStepY
    ) {
        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = backdropBlurRenderPass_;
        renderPassInfo.framebuffer = framebuffer;
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = {backdropBlurWidth_, backdropBlurHeight_};

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(backdropBlurWidth_);
        viewport.height = static_cast<float>(backdropBlurHeight_);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = {backdropBlurWidth_, backdropBlurHeight_};

        BlurPushConstants pushConstants{};
        pushConstants.texelStep[0] = texelStepX;
        pushConstants.texelStep[1] = texelStepY;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, backdropBlurPipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            backdropBlurPipelineLayout_,
            0,
            1,
            &descriptorSet,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            backdropBlurPipelineLayout_,
            VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            sizeof(BlurPushConstants),
            &pushConstants
        );
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
    }

    void recordTeleportScenePresentLocked(VkCommandBuffer commandBuffer) {
        if (!hasTeleportSceneLocked() ||
            teleportPresentPipeline_ == VK_NULL_HANDLE ||
            teleportPipelineLayout_ == VK_NULL_HANDLE ||
            swapchainExtent_.width == 0 ||
            swapchainExtent_.height == 0) {
            return;
        }

        const int32_t left = static_cast<int32_t>(std::max(
            0.0f,
            std::floor(teleportViewportRect_[0])
        ));
        const int32_t top = static_cast<int32_t>(std::max(
            0.0f,
            std::floor(teleportViewportRect_[1])
        ));
        const uint32_t right = static_cast<uint32_t>(std::clamp(
            std::ceil(std::max(teleportViewportRect_[0], teleportViewportRect_[2])),
            0.0f,
            static_cast<float>(swapchainExtent_.width)
        ));
        const uint32_t bottom = static_cast<uint32_t>(std::clamp(
            std::ceil(std::max(teleportViewportRect_[1], teleportViewportRect_[3])),
            0.0f,
            static_cast<float>(swapchainExtent_.height)
        ));
        if (right <= static_cast<uint32_t>(left) ||
            bottom <= static_cast<uint32_t>(top)) {
            return;
        }

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;
        VkRect2D scissor{};
        scissor.offset = {left, top};
        scissor.extent = {
            right - static_cast<uint32_t>(left),
            bottom - static_cast<uint32_t>(top)
        };
        const TeleportPushConstants pushConstants = teleportPushConstantsLocked(
            viewport.width,
            viewport.height,
            0.0f,
            0.0f
        );

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, teleportPresentPipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            teleportPipelineLayout_,
            0,
            1,
            &teleportDescriptorSet_,
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            teleportPipelineLayout_,
            VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            sizeof(TeleportPushConstants),
            &pushConstants
        );
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
    }

    void recordCompositePassLocked(
        VkCommandBuffer commandBuffer,
        uint32_t imageIndex,
        bool shouldComposite,
        bool shouldCompositeSph,
        int64_t frameTimeNs,
        const VkRect2D& paintSplashWorkRect
    ) {
        if (imageIndex >= framebuffers_.size()) {
            return;
        }

        VkClearValue clearValue{};
        clearValue.color.float32[0] = 0.0f;
        clearValue.color.float32[1] = 0.0f;
        clearValue.color.float32[2] = 0.0f;
        clearValue.color.float32[3] = 0.0f;

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = renderPass_;
        renderPassInfo.framebuffer = framebuffers_[imageIndex];
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = swapchainExtent_;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearValue;

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        recordTeleportScenePresentLocked(commandBuffer);
        if (shouldComposite &&
            compositePipeline_ != VK_NULL_HANDLE &&
            compositePipelineLayout_ != VK_NULL_HANDLE &&
            compositeDescriptorSet_ != VK_NULL_HANDLE &&
            paintSplashWorkRect.extent.width > 0 &&
            paintSplashWorkRect.extent.height > 0) {
            VkViewport viewport{};
            viewport.x = 0.0f;
            viewport.y = 0.0f;
            viewport.width = static_cast<float>(swapchainExtent_.width);
            viewport.height = static_cast<float>(swapchainExtent_.height);
            viewport.minDepth = 0.0f;
            viewport.maxDepth = 1.0f;

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline_);
            vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
            vkCmdSetScissor(commandBuffer, 0, 1, &paintSplashWorkRect);
            vkCmdBindDescriptorSets(
                commandBuffer,
                VK_PIPELINE_BIND_POINT_GRAPHICS,
                compositePipelineLayout_,
                0,
                1,
                &compositeDescriptorSet_,
                0,
                nullptr
            );
            CompositePushConstants pushConstants{};
            pushConstants.viewportSize[0] = viewport.width;
            pushConstants.viewportSize[1] = viewport.height;
            pushConstants.blobTextureSize[0] = static_cast<float>(std::max(1u, blobExtent_.width));
            pushConstants.blobTextureSize[1] = static_cast<float>(std::max(1u, blobExtent_.height));
            vkCmdPushConstants(
                commandBuffer,
                compositePipelineLayout_,
                VK_SHADER_STAGE_FRAGMENT_BIT,
                0,
                sizeof(CompositePushConstants),
                &pushConstants
            );
            vkCmdDraw(commandBuffer, 6, 1, 0, 0);
        }
        if (hasBackdropImageLocked()) {
            recordBackdropCompositeLocked(commandBuffer);
        }
        if (shouldCompositeSph) {
            recordPaintSplashGlassSphCompositeLocked(commandBuffer, frameTimeNs);
        }
        vkCmdEndRenderPass(commandBuffer);
        writeFrameTraceTimestampLocked(commandBuffer, "composite");
    }

    void recordPaintSplashGlassSphCompositeLocked(
        VkCommandBuffer commandBuffer,
        int64_t frameTimeNs
    ) {
        if (paintSplashGlassSphCompositePipeline_ == VK_NULL_HANDLE ||
            paintSplashGlassSphCompositePipelineLayout_ == VK_NULL_HANDLE ||
            paintSplashGlassSphCompositeDescriptorSet_ == VK_NULL_HANDLE ||
            paintSplashGlassSphImageView_ == VK_NULL_HANDLE ||
            paintSplashGlassSphLayout_ != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ||
            paintSplashGlassSphVisibleFadeLocked(frameTimeNs) <= 0.010f) {
            return;
        }

        const VkRect2D scissor = paintSplashGlassSphScreenWorkRectLocked();
        if (scissor.extent.width == 0 || scissor.extent.height == 0) {
            return;
        }

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, paintSplashGlassSphCompositePipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            paintSplashGlassSphCompositePipelineLayout_,
            0,
            1,
            &paintSplashGlassSphCompositeDescriptorSet_,
            0,
            nullptr
        );
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
    }

    void recordBackdropCompositeLocked(VkCommandBuffer commandBuffer) {
        if (backdropPipeline_ == VK_NULL_HANDLE ||
            backdropPipelineLayout_ == VK_NULL_HANDLE ||
            backdropDescriptorSet_ == VK_NULL_HANDLE) {
            return;
        }

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, backdropPipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            backdropPipelineLayout_,
            0,
            1,
            &backdropDescriptorSet_,
            0,
            nullptr
        );

        for (const BackdropRect& rect : backdropRects_) {
            if (rect.right <= rect.left || rect.bottom <= rect.top || rect.opacity <= 0.0f) {
                continue;
            }

            const int32_t scissorLeft = static_cast<int32_t>(
                std::max(0.0f, std::floor(rect.left))
            );
            const int32_t scissorTop = static_cast<int32_t>(
                std::max(0.0f, std::floor(rect.top))
            );
            const uint32_t scissorRight = std::min(
                swapchainExtent_.width,
                static_cast<uint32_t>(std::ceil(std::max(rect.left, rect.right)))
            );
            const uint32_t scissorBottom = std::min(
                swapchainExtent_.height,
                static_cast<uint32_t>(std::ceil(std::max(rect.top, rect.bottom)))
            );
            if (scissorRight <= static_cast<uint32_t>(scissorLeft) ||
                scissorBottom <= static_cast<uint32_t>(scissorTop)) {
                continue;
            }

            VkRect2D scissor{};
            scissor.offset = {scissorLeft, scissorTop};
            scissor.extent = {
                scissorRight - static_cast<uint32_t>(scissorLeft),
                scissorBottom - static_cast<uint32_t>(scissorTop)
            };

            BackdropPushConstants pushConstants{};
            pushConstants.viewportSize[0] = viewport.width;
            pushConstants.viewportSize[1] = viewport.height;
            pushConstants.textureSize[0] = static_cast<float>(backdropWidth_);
            pushConstants.textureSize[1] = static_cast<float>(backdropHeight_);
            pushConstants.rect[0] = rect.left;
            pushConstants.rect[1] = rect.top;
            pushConstants.rect[2] = rect.right;
            pushConstants.rect[3] = rect.bottom;
            pushConstants.opacity = rect.opacity;
            pushConstants.cornerRadius = rect.cornerRadius;
            pushConstants.textureOrigin[0] = backdropTextureOrigin_[0];
            pushConstants.textureOrigin[1] = backdropTextureOrigin_[1];
            pushConstants.bezelWidth = rect.bezelWidth;
            pushConstants.glassThickness = rect.glassThickness;
            pushConstants.adaptiveAppearance = rect.adaptiveAppearance;
            pushConstants.adaptiveContrast = rect.adaptiveContrast;
            pushConstants.splashSurfaceIntensity =
                paintSplashGlassSurfaceIntensityLocked(nowNanos());
            pushConstants.splashSurfaceAge = paintSplashGlassSurfaceAge_;

            vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
            vkCmdPushConstants(
                commandBuffer,
                backdropPipelineLayout_,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0,
                sizeof(BackdropPushConstants),
                &pushConstants
            );
            vkCmdDraw(commandBuffer, 6, 1, 0, 0);
        }
    }

    bool hasActivePaintSplashesLocked() {
        return hasActivePaintSplashesLocked(nowNanos());
    }

    bool hasActivePaintSplashesLocked(int64_t nowNs) {
        if (splashItems_.empty()) {
            return false;
        }

        for (auto it = splashItems_.begin(); it != splashItems_.end();) {
            if (nowNs - it->startTimeNs >= kPaintSplashDurationNs) {
                destroyPaintSplashItemLocked(*it);
                it = splashItems_.erase(it);
            } else {
                ++it;
            }
        }
        return !splashItems_.empty();
    }

    bool hasLivePaintSplashesLocked(int64_t nowNs) const {
        for (const PaintSplashItem& item : splashItems_) {
            if (nowNs - item.startTimeNs < kPaintSplashDurationNs) {
                return true;
            }
        }
        return false;
    }

    bool hasActivePaintSplashGlassSurfaceLocked(int64_t nowNs) const {
        return paintSplashGlassDripEndNs_ > nowNs;
    }

    bool hasRenderableWorkLocked(int64_t nowNs) const {
        return teleportActive_ ||
            backdropNeedsRender_ ||
            hasLivePaintSplashesLocked(nowNs) ||
            hasActivePaintSplashGlassSurfaceLocked(nowNs);
    }

    float paintSplashGlassSurfaceIntensityLocked(int64_t nowNs) const {
        if (paintSplashGlassDripEndNs_ <= nowNs) {
            return 0.0f;
        }
        if (!splashItems_.empty()) {
            return 1.0f;
        }
        const float remaining = static_cast<float>(paintSplashGlassDripEndNs_ - nowNs) /
            static_cast<float>(kPaintSplashGlassDripDurationNs);
        return std::clamp(remaining, 0.0f, 1.0f);
    }

    int64_t nowNanos() const {
        timespec timeSpec{};
        clock_gettime(CLOCK_MONOTONIC, &timeSpec);
        return static_cast<int64_t>(timeSpec.tv_sec) * 1000000000LL +
            static_cast<int64_t>(timeSpec.tv_nsec);
    }

    double nanosToMillis(int64_t nanos) const {
        return static_cast<double>(nanos) / 1000000.0;
    }

    void recreateSwapchainLocked() {
        if (device_ == VK_NULL_HANDLE || surface_ == VK_NULL_HANDLE) {
            return;
        }
        vkDeviceWaitIdle(device_);
        cleanupSwapchainLocked();
        createSwapchainLocked();
    }

    void destroySurfaceLocked() {
        if (device_ != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device_);
        }
        cleanupSwapchainLocked();
        if (surface_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    void cleanupSwapchainLocked() {
        if (device_ == VK_NULL_HANDLE) {
            return;
        }
        if (!commandBuffers_.empty() && commandPool_ != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(
                device_,
                commandPool_,
                static_cast<uint32_t>(commandBuffers_.size()),
                commandBuffers_.data()
            );
            commandBuffers_.clear();
        }
        for (VkFramebuffer framebuffer : framebuffers_) {
            vkDestroyFramebuffer(device_, framebuffer, nullptr);
        }
        framebuffers_.clear();

        destroyTeleportSceneLocked(false);

        destroyPaintSplashItemsLocked();
        releaseCompletedSplashUploadStagingLocked();
        destroyPaintSplashGlassTargetBufferLocked();
        destroyPaintSplashGlassEventBuffersLocked();
        destroyPaintSplashGlassSphBuffersLocked();

        if (splashInitPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, splashInitPipeline_, nullptr);
            splashInitPipeline_ = VK_NULL_HANDLE;
        }
        if (splashInitPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, splashInitPipelineLayout_, nullptr);
            splashInitPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (splashUpdatePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, splashUpdatePipeline_, nullptr);
            splashUpdatePipeline_ = VK_NULL_HANDLE;
        }
        if (splashUpdatePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, splashUpdatePipelineLayout_, nullptr);
            splashUpdatePipelineLayout_ = VK_NULL_HANDLE;
        }
        if (particlePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, particlePipeline_, nullptr);
            particlePipeline_ = VK_NULL_HANDLE;
        }
        if (particlePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, particlePipelineLayout_, nullptr);
            particlePipelineLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassImpactPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, paintSplashGlassImpactPipeline_, nullptr);
            paintSplashGlassImpactPipeline_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassImpactPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, paintSplashGlassImpactPipelineLayout_, nullptr);
            paintSplashGlassImpactPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSurfacePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, paintSplashGlassSurfacePipeline_, nullptr);
            paintSplashGlassSurfacePipeline_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSurfacePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSurfacePipelineLayout_, nullptr);
            paintSplashGlassSurfacePipelineLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphUpdatePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, paintSplashGlassSphUpdatePipeline_, nullptr);
            paintSplashGlassSphUpdatePipeline_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphUpdatePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSphUpdatePipelineLayout_, nullptr);
            paintSplashGlassSphUpdatePipelineLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphFieldPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, paintSplashGlassSphFieldPipeline_, nullptr);
            paintSplashGlassSphFieldPipeline_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphFieldPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSphFieldPipelineLayout_, nullptr);
            paintSplashGlassSphFieldPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphCompositePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, paintSplashGlassSphCompositePipeline_, nullptr);
            paintSplashGlassSphCompositePipeline_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphCompositePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, paintSplashGlassSphCompositePipelineLayout_, nullptr);
            paintSplashGlassSphCompositePipelineLayout_ = VK_NULL_HANDLE;
        }
        if (compositePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, compositePipeline_, nullptr);
            compositePipeline_ = VK_NULL_HANDLE;
        }
        if (compositePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, compositePipelineLayout_, nullptr);
            compositePipelineLayout_ = VK_NULL_HANDLE;
        }
        if (backdropPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, backdropPipeline_, nullptr);
            backdropPipeline_ = VK_NULL_HANDLE;
        }
        if (backdropPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, backdropPipelineLayout_, nullptr);
            backdropPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (teleportGlassPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, teleportGlassPipeline_, nullptr);
            teleportGlassPipeline_ = VK_NULL_HANDLE;
        }
        if (teleportPresentPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, teleportPresentPipeline_, nullptr);
            teleportPresentPipeline_ = VK_NULL_HANDLE;
        }
        if (teleportPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, teleportPipelineLayout_, nullptr);
            teleportPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (backdropOverlayPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, backdropOverlayPipeline_, nullptr);
            backdropOverlayPipeline_ = VK_NULL_HANDLE;
        }
        if (backdropOverlayPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, backdropOverlayPipelineLayout_, nullptr);
            backdropOverlayPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (backdropBlurPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, backdropBlurPipeline_, nullptr);
            backdropBlurPipeline_ = VK_NULL_HANDLE;
        }
        if (backdropBlurPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, backdropBlurPipelineLayout_, nullptr);
            backdropBlurPipelineLayout_ = VK_NULL_HANDLE;
        }
        destroyBackdropStatsResourcesLocked();

        destroyBackdropBlurSizeResourcesLocked();
        if (backdropBlurRenderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, backdropBlurRenderPass_, nullptr);
            backdropBlurRenderPass_ = VK_NULL_HANDLE;
        }

        destroyPaintSplashGlassImagesLocked();
        if (paintSplashGlassImpactRenderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, paintSplashGlassImpactRenderPass_, nullptr);
            paintSplashGlassImpactRenderPass_ = VK_NULL_HANDLE;
        }

        if (blobFramebuffer_ != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, blobFramebuffer_, nullptr);
            blobFramebuffer_ = VK_NULL_HANDLE;
        }
        if (blobRenderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, blobRenderPass_, nullptr);
            blobRenderPass_ = VK_NULL_HANDLE;
        }
        if (blobImageView_ != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, blobImageView_, nullptr);
            blobImageView_ = VK_NULL_HANDLE;
        }
        if (blobImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, blobImage_, nullptr);
            blobImage_ = VK_NULL_HANDLE;
        }
        if (blobImageMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, blobImageMemory_, nullptr);
            blobImageMemory_ = VK_NULL_HANDLE;
        }
        blobExtent_ = {};

        if (splashDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, splashDescriptorPool_, nullptr);
            splashDescriptorPool_ = VK_NULL_HANDLE;
        }
        if (splashDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, splashDescriptorSetLayout_, nullptr);
            splashDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassImpactDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, paintSplashGlassImpactDescriptorPool_, nullptr);
            paintSplashGlassImpactDescriptorPool_ = VK_NULL_HANDLE;
            paintSplashGlassImpactDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassImpactDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(
                device_,
                paintSplashGlassImpactDescriptorSetLayout_,
                nullptr
            );
            paintSplashGlassImpactDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSurfaceDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, paintSplashGlassSurfaceDescriptorPool_, nullptr);
            paintSplashGlassSurfaceDescriptorPool_ = VK_NULL_HANDLE;
            paintSplashGlassSurfaceDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSurfaceDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(
                device_,
                paintSplashGlassSurfaceDescriptorSetLayout_,
                nullptr
            );
            paintSplashGlassSurfaceDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphUpdateDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, paintSplashGlassSphUpdateDescriptorPool_, nullptr);
            paintSplashGlassSphUpdateDescriptorPool_ = VK_NULL_HANDLE;
            paintSplashGlassSphUpdateDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphUpdateDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(
                device_,
                paintSplashGlassSphUpdateDescriptorSetLayout_,
                nullptr
            );
            paintSplashGlassSphUpdateDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphFieldDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, paintSplashGlassSphFieldDescriptorPool_, nullptr);
            paintSplashGlassSphFieldDescriptorPool_ = VK_NULL_HANDLE;
            paintSplashGlassSphFieldDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphFieldDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(
                device_,
                paintSplashGlassSphFieldDescriptorSetLayout_,
                nullptr
            );
            paintSplashGlassSphFieldDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphCompositeDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, paintSplashGlassSphCompositeDescriptorPool_, nullptr);
            paintSplashGlassSphCompositeDescriptorPool_ = VK_NULL_HANDLE;
            paintSplashGlassSphCompositeDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (paintSplashGlassSphCompositeDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(
                device_,
                paintSplashGlassSphCompositeDescriptorSetLayout_,
                nullptr
            );
            paintSplashGlassSphCompositeDescriptorSetLayout_ = VK_NULL_HANDLE;
        }

        if (renderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, renderPass_, nullptr);
            renderPass_ = VK_NULL_HANDLE;
        }

        for (VkImageView imageView : swapchainImageViews_) {
            vkDestroyImageView(device_, imageView, nullptr);
        }
        swapchainImageViews_.clear();
        swapchainImages_.clear();

        if (swapchain_ != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            swapchain_ = VK_NULL_HANDLE;
        }
    }

    void destroyDeviceLocked() {
        if (device_ == VK_NULL_HANDLE) {
            destroyBackdropImportCacheLocked();
            physicalDevice_ = VK_NULL_HANDLE;
            queueFamilyIndex_ = 0;
            graphicsQueue_ = VK_NULL_HANDLE;
            getAndroidHardwareBufferProperties_ = nullptr;
            return;
        }
        vkDeviceWaitIdle(device_);
        cleanupSwapchainLocked();
        destroyBackdropImportCacheLocked();
        destroySplashStagingPoolLocked();

        if (inFlightFence_ != VK_NULL_HANDLE) {
            vkDestroyFence(device_, inFlightFence_, nullptr);
            inFlightFence_ = VK_NULL_HANDLE;
        }
        if (renderFinishedSemaphore_ != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, renderFinishedSemaphore_, nullptr);
            renderFinishedSemaphore_ = VK_NULL_HANDLE;
        }
        if (imageAvailableSemaphore_ != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, imageAvailableSemaphore_, nullptr);
            imageAvailableSemaphore_ = VK_NULL_HANDLE;
        }
        if (commandPool_ != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device_, commandPool_, nullptr);
            commandPool_ = VK_NULL_HANDLE;
        }
        destroyFrameTraceResourcesLocked();
        if (blobSampler_ != VK_NULL_HANDLE) {
            vkDestroySampler(device_, blobSampler_, nullptr);
            blobSampler_ = VK_NULL_HANDLE;
        }
        if (backdropSampler_ != VK_NULL_HANDLE) {
            vkDestroySampler(device_, backdropSampler_, nullptr);
            backdropSampler_ = VK_NULL_HANDLE;
        }
        if (compositeDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, compositeDescriptorPool_, nullptr);
            compositeDescriptorPool_ = VK_NULL_HANDLE;
            compositeDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (backdropDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, backdropDescriptorPool_, nullptr);
            backdropDescriptorPool_ = VK_NULL_HANDLE;
            backdropDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (backdropBlurDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, backdropBlurDescriptorPool_, nullptr);
            backdropBlurDescriptorPool_ = VK_NULL_HANDLE;
            backdropBlurSourceDescriptorSet_ = VK_NULL_HANDLE;
            backdropBlurTempDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (backdropOverlayDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, backdropOverlayDescriptorPool_, nullptr);
            backdropOverlayDescriptorPool_ = VK_NULL_HANDLE;
            backdropOverlayDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (teleportDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, teleportDescriptorPool_, nullptr);
            teleportDescriptorPool_ = VK_NULL_HANDLE;
            teleportDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (compositeDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, compositeDescriptorSetLayout_, nullptr);
            compositeDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (backdropDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, backdropDescriptorSetLayout_, nullptr);
            backdropDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (backdropBlurDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, backdropBlurDescriptorSetLayout_, nullptr);
            backdropBlurDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (backdropOverlayDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, backdropOverlayDescriptorSetLayout_, nullptr);
            backdropOverlayDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (teleportDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, teleportDescriptorSetLayout_, nullptr);
            teleportDescriptorSetLayout_ = VK_NULL_HANDLE;
        }

        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        physicalDevice_ = VK_NULL_HANDLE;
        queueFamilyIndex_ = 0;
        graphicsQueue_ = VK_NULL_HANDLE;
        getAndroidHardwareBufferProperties_ = nullptr;
        hardwareBufferImportSupported_ = false;
    }

    mutable std::mutex mutex_;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAndroidHardwareBufferProperties_ = nullptr;
    uint32_t queueFamilyIndex_ = 0;

    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent_{};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;
    std::vector<VkFramebuffer> framebuffers_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers_;
    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;
    VkQueryPool frameTraceQueryPool_ = VK_NULL_HANDLE;
    float frameTraceTimestampPeriodNs_ = 0.0f;
    uint32_t frameTraceTimestampValidBits_ = 0;
    uint32_t frameTraceQueryCount_ = 0;
    std::array<const char*, kMaxFrameTraceQueries> frameTraceNames_{};
    bool currentFrameTraceHasEffects_ = false;
    FrameTraceFlags currentFrameTraceFlags_{};
    bool pendingFrameTraceActive_ = false;
    bool pendingFrameTraceHasEffects_ = false;
    FrameTraceFlags pendingFrameTraceFlags_{};
    uint32_t pendingFrameTraceQueryCount_ = 0;
    std::array<const char*, kMaxFrameTraceQueries> pendingFrameTraceNames_{};
    FrameTraceCpuTiming pendingFrameTraceCpuTiming_{};
    uint32_t frameTraceLogsRemaining_ = 120;
    uint32_t frameSkipLogsRemaining_ = 120;
    VkDescriptorSetLayout splashDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool splashDescriptorPool_ = VK_NULL_HANDLE;
    VkPipelineLayout splashInitPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline splashInitPipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout splashUpdatePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline splashUpdatePipeline_ = VK_NULL_HANDLE;
    VkBuffer paintSplashGlassTargetBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassTargetMemory_ = VK_NULL_HANDLE;
    GpuGlassHitTarget* paintSplashGlassTargetMapped_ = nullptr;
    uint32_t paintSplashGlassTargetCount_ = 0;
    VkBuffer paintSplashGlassEventBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassEventMemory_ = VK_NULL_HANDLE;
    VkBuffer paintSplashGlassCursorBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassCursorMemory_ = VK_NULL_HANDLE;
    VkBuffer paintSplashGlassCellBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassCellMemory_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout paintSplashGlassImpactDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool paintSplashGlassImpactDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet paintSplashGlassImpactDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout paintSplashGlassSurfaceDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool paintSplashGlassSurfaceDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet paintSplashGlassSurfaceDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout paintSplashGlassImpactPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline paintSplashGlassImpactPipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout paintSplashGlassSurfacePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline paintSplashGlassSurfacePipeline_ = VK_NULL_HANDLE;
    VkFormat paintSplashGlassFormat_ = VK_FORMAT_R16G16B16A16_SFLOAT;
    VkRenderPass paintSplashGlassImpactRenderPass_ = VK_NULL_HANDLE;
    VkImage paintSplashGlassSurfaceImage_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassSurfaceMemory_ = VK_NULL_HANDLE;
    VkImageView paintSplashGlassSurfaceImageView_ = VK_NULL_HANDLE;
    VkImageLayout paintSplashGlassSurfaceLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage paintSplashGlassSurfaceWorkImage_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassSurfaceWorkMemory_ = VK_NULL_HANDLE;
    VkImageView paintSplashGlassSurfaceWorkImageView_ = VK_NULL_HANDLE;
    VkImageLayout paintSplashGlassSurfaceWorkLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage paintSplashGlassVelocityImage_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassVelocityMemory_ = VK_NULL_HANDLE;
    VkImageView paintSplashGlassVelocityImageView_ = VK_NULL_HANDLE;
    VkImageLayout paintSplashGlassVelocityLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage paintSplashGlassVelocityWorkImage_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassVelocityWorkMemory_ = VK_NULL_HANDLE;
    VkImageView paintSplashGlassVelocityWorkImageView_ = VK_NULL_HANDLE;
    VkImageLayout paintSplashGlassVelocityWorkLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage paintSplashGlassImpactImage_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassImpactMemory_ = VK_NULL_HANDLE;
    VkImageView paintSplashGlassImpactImageView_ = VK_NULL_HANDLE;
    VkImageLayout paintSplashGlassImpactLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage paintSplashGlassImpactVelocityImage_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassImpactVelocityMemory_ = VK_NULL_HANDLE;
    VkImageView paintSplashGlassImpactVelocityImageView_ = VK_NULL_HANDLE;
    VkImageLayout paintSplashGlassImpactVelocityLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkFramebuffer paintSplashGlassImpactFramebuffer_ = VK_NULL_HANDLE;
    VkBuffer paintSplashGlassSphParticleBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassSphParticleMemory_ = VK_NULL_HANDLE;
    VkBuffer paintSplashGlassSphParticleWorkBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassSphParticleWorkMemory_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout paintSplashGlassSphUpdateDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool paintSplashGlassSphUpdateDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet paintSplashGlassSphUpdateDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout paintSplashGlassSphFieldDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool paintSplashGlassSphFieldDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet paintSplashGlassSphFieldDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout paintSplashGlassSphCompositeDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool paintSplashGlassSphCompositeDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet paintSplashGlassSphCompositeDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout paintSplashGlassSphUpdatePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline paintSplashGlassSphUpdatePipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout paintSplashGlassSphFieldPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline paintSplashGlassSphFieldPipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout paintSplashGlassSphCompositePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline paintSplashGlassSphCompositePipeline_ = VK_NULL_HANDLE;
    VkImage paintSplashGlassSphImage_ = VK_NULL_HANDLE;
    VkDeviceMemory paintSplashGlassSphMemory_ = VK_NULL_HANDLE;
    VkImageView paintSplashGlassSphImageView_ = VK_NULL_HANDLE;
    VkImageLayout paintSplashGlassSphLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkFramebuffer paintSplashGlassSphFramebuffer_ = VK_NULL_HANDLE;
    bool paintSplashGlassNeedsReset_ = true;
    int64_t paintSplashGlassDripEndNs_ = 0;
    int64_t paintSplashGlassLastSurfaceUpdateNs_ = 0;
    int64_t paintSplashGlassLastSphUpdateNs_ = 0;
    float paintSplashGlassSurfaceAge_ = 0.0f;
    VkPipelineLayout particlePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline particlePipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout compositePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline compositePipeline_ = VK_NULL_HANDLE;
    VkFormat blobFormat_ = VK_FORMAT_R16G16B16A16_SFLOAT;
    VkRenderPass blobRenderPass_ = VK_NULL_HANDLE;
    VkImage blobImage_ = VK_NULL_HANDLE;
    VkDeviceMemory blobImageMemory_ = VK_NULL_HANDLE;
    VkImageView blobImageView_ = VK_NULL_HANDLE;
    VkFramebuffer blobFramebuffer_ = VK_NULL_HANDLE;
    VkExtent2D blobExtent_{};
    VkSampler blobSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout compositeDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool compositeDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet compositeDescriptorSet_ = VK_NULL_HANDLE;
    std::vector<std::unique_ptr<BackdropImportEntry>> backdropImportCache_;
    BackdropImportEntry* activeBackdropEntry_ = nullptr;
    BackdropImportEntry teleportOldEntry_{};
    BackdropImportEntry teleportNewEntry_{};
    VkDescriptorSetLayout teleportDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool teleportDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet teleportDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout teleportPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline teleportGlassPipeline_ = VK_NULL_HANDLE;
    VkPipeline teleportPresentPipeline_ = VK_NULL_HANDLE;
    std::array<float, 4> teleportViewportRect_{};
    std::array<float, 2> teleportCaptureOrigin_{};
    float teleportProgress_ = 0.0f;
    float teleportDirectionSign_ = 1.0f;
    bool teleportNeedsTransition_ = false;
    bool teleportActive_ = false;
    VkSampler backdropSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout backdropDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool backdropDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout backdropStatsDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool backdropStatsDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropStatsDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout backdropStatsPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline backdropStatsPipeline_ = VK_NULL_HANDLE;
    VkBuffer backdropStatsBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory backdropStatsBufferMemory_ = VK_NULL_HANDLE;
    float* backdropStatsMapped_ = nullptr;
    bool backdropStatsPending_ = false;
    bool hasUnreadBackdropStats_ = false;
    BackdropStatsResult latestBackdropStats_{};
    VkDescriptorSetLayout backdropOverlayDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool backdropOverlayDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropOverlayDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout backdropOverlayPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline backdropOverlayPipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout backdropBlurDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool backdropBlurDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropBlurSourceDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropBlurTempDescriptorSet_ = VK_NULL_HANDLE;
    VkFormat backdropBlurFormat_ = VK_FORMAT_R8G8B8A8_UNORM;
    VkRenderPass backdropBlurRenderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout backdropBlurPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline backdropBlurPipeline_ = VK_NULL_HANDLE;
    VkImage backdropCompositeImage_ = VK_NULL_HANDLE;
    VkDeviceMemory backdropCompositeImageMemory_ = VK_NULL_HANDLE;
    VkImageView backdropCompositeImageView_ = VK_NULL_HANDLE;
    VkFramebuffer backdropCompositeFramebuffer_ = VK_NULL_HANDLE;
    VkImageLayout backdropCompositeLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage backdropBlurTempImage_ = VK_NULL_HANDLE;
    VkDeviceMemory backdropBlurTempImageMemory_ = VK_NULL_HANDLE;
    VkImageView backdropBlurTempImageView_ = VK_NULL_HANDLE;
    VkFramebuffer backdropBlurTempFramebuffer_ = VK_NULL_HANDLE;
    VkImageLayout backdropBlurTempLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage backdropBlurImage_ = VK_NULL_HANDLE;
    VkDeviceMemory backdropBlurImageMemory_ = VK_NULL_HANDLE;
    VkImageView backdropBlurImageView_ = VK_NULL_HANDLE;
    VkFramebuffer backdropBlurFramebuffer_ = VK_NULL_HANDLE;
    VkImageLayout backdropBlurLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    uint32_t backdropBlurWidth_ = 0;
    uint32_t backdropBlurHeight_ = 0;
    VkPipelineLayout backdropPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline backdropPipeline_ = VK_NULL_HANDLE;
    int backdropWidth_ = 0;
    int backdropHeight_ = 0;
    bool backdropNeedsTransition_ = false;
    bool backdropNeedsRender_ = false;
    std::vector<BackdropRect> backdropRects_;
    std::array<float, 2> backdropTextureOrigin_{0.0f, 0.0f};

    int width_ = 1;
    int height_ = 1;
    std::array<float, 4> clearColor_{0.05f, 0.72f, 0.86f, 1.0f};
    std::array<float, 4> inputBarBounds_{};
    bool hasInputBarBounds_ = false;
    bool hardwareBufferImportSupported_ = false;

    std::vector<PaintSplashItem> splashItems_;
    std::vector<std::unique_ptr<SplashStagingBuffer>> splashStagingPool_;
    std::vector<SplashStagingBuffer*> pendingSplashStagingRelease_;
    bool didLogParticleDraw_ = false;
};

VulkanChatRenderer* rendererFromHandle(jlong handle) {
    return reinterpret_cast<VulkanChatRenderer*>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeCreate(JNIEnv*, jobject) {
    auto* renderer = new VulkanChatRenderer();
    if (!renderer->isReady()) {
        delete renderer;
        return 0;
    }
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete rendererFromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr) {
        return;
    }
    renderer->setSurface(env, surface, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeSetClearColor(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat red,
    jfloat green,
    jfloat blue,
    jfloat alpha
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr) {
        return;
    }
    renderer->setClearColor(red, green, blue, alpha);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeSetInputBarBounds(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat left,
    jfloat top,
    jfloat right,
    jfloat bottom
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr) {
        return;
    }
    renderer->setInputBarBounds(left, top, right, bottom);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeAddPaintSplashBitmap(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap,
    jfloat left,
    jfloat top,
    jfloat right,
    jfloat bottom
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr || bitmap == nullptr) {
        return;
    }

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        logWarn("AndroidBitmap_getInfo failed");
        return;
    }
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        logWarn("Unsupported paint splash bitmap format");
        return;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        logWarn("AndroidBitmap_lockPixels failed");
        return;
    }
    renderer->addPaintSplashBitmap(left, top, right, bottom, bitmapInfo, pixels);
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeProbeHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBuffer
) {
    if (hardwareBuffer == nullptr) {
        return JNI_FALSE;
    }

    AHardwareBuffer* nativeBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (nativeBuffer == nullptr) {
        logWarn("AHardwareBuffer_fromHardwareBuffer failed");
        return JNI_FALSE;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(nativeBuffer, &desc);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "AHB native probe width=%u height=%u layers=%u format=%u usage=0x%llx stride=%u",
        desc.width,
        desc.height,
        desc.layers,
        desc.format,
        static_cast<unsigned long long>(desc.usage),
        desc.stride
    );
    return JNI_TRUE;
}

static bool parseBackdropRects(
    JNIEnv* env,
    jfloatArray rectValues,
    std::vector<BackdropRect>& rects
) {
    if (rectValues == nullptr) {
        return false;
    }

    const jsize valueCount = env->GetArrayLength(rectValues);
    if (valueCount < static_cast<jsize>(kBackdropRectFloatCount)) {
        return false;
    }

    jboolean didCopy = JNI_FALSE;
    jfloat* values = env->GetFloatArrayElements(rectValues, &didCopy);
    if (values == nullptr) {
        return false;
    }

    const uint32_t rectCount = std::min(
        kMaxBackdropRects,
        static_cast<uint32_t>(valueCount / static_cast<jsize>(kBackdropRectFloatCount))
    );
    rects.clear();
    rects.reserve(rectCount);
    for (uint32_t rectIndex = 0; rectIndex < rectCount; ++rectIndex) {
        const uint32_t base = rectIndex * kBackdropRectFloatCount;
        BackdropRect rect{};
        rect.left = values[base];
        rect.top = values[base + 1];
        rect.right = values[base + 2];
        rect.bottom = values[base + 3];
        rect.cornerRadius = std::max(0.0f, values[base + 4]);
        rect.opacity = std::clamp(values[base + 5], 0.0f, 1.0f);
        rect.bezelWidth = std::max(0.0f, values[base + 6]);
        rect.glassThickness = std::max(0.0f, values[base + 7]);
        rect.adaptiveAppearance = std::clamp(values[base + 8], 0.0f, 1.0f);
        rect.adaptiveContrast = std::clamp(values[base + 9], 0.0f, 1.0f);
        rect.shapeKind = values[base + 10] > 0.5f ? 1.0f : 0.0f;
        if (
            rect.right > rect.left &&
            rect.bottom > rect.top &&
            rect.opacity > 0.0f &&
            rect.bezelWidth > 0.0f &&
            rect.glassThickness > 0.0f
        ) {
            rects.push_back(rect);
        }
    }
    env->ReleaseFloatArrayElements(rectValues, values, JNI_ABORT);
    return !rects.empty();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeSetBackdropHardwareBuffer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject hardwareBuffer,
    jfloatArray rectValues,
    jfloat textureLeft,
    jfloat textureTop
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr || hardwareBuffer == nullptr || rectValues == nullptr) {
        return JNI_FALSE;
    }
    std::vector<BackdropRect> rects;
    if (!parseBackdropRects(env, rectValues, rects)) {
        return JNI_FALSE;
    }
    return renderer->setBackdropHardwareBuffer(
        env,
        hardwareBuffer,
        std::move(rects),
        textureLeft,
        textureTop
    )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeUpdateBackdropRects(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray rectValues,
    jfloat textureLeft,
    jfloat textureTop
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr || rectValues == nullptr) {
        return JNI_FALSE;
    }

    std::vector<BackdropRect> rects;
    if (!parseBackdropRects(env, rectValues, rects)) {
        return JNI_FALSE;
    }

    return renderer->updateBackdropRects(
        std::move(rects),
        textureLeft,
        textureTop
    )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeSetTeleportHardwareBuffers(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject oldHardwareBuffer,
    jobject newHardwareBuffer,
    jfloat viewportLeft,
    jfloat viewportTop,
    jfloat viewportRight,
    jfloat viewportBottom,
    jfloat captureLeft,
    jfloat captureTop,
    jint captureWidth,
    jint captureHeight,
    jfloat directionSign
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr || oldHardwareBuffer == nullptr || newHardwareBuffer == nullptr) {
        return JNI_FALSE;
    }
    return renderer->setTeleportHardwareBuffers(
        env,
        oldHardwareBuffer,
        newHardwareBuffer,
        viewportLeft,
        viewportTop,
        viewportRight,
        viewportBottom,
        captureLeft,
        captureTop,
        captureWidth,
        captureHeight,
        directionSign
    )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeUpdateTeleportProgress(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    return renderer != nullptr && renderer->updateTeleportProgress(progress)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeClearTeleport(
    JNIEnv*,
    jobject,
    jlong handle
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer != nullptr) {
        renderer->clearTeleport();
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativePollBackdropStats(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr) {
        return nullptr;
    }

    BackdropStatsResult stats{};
    if (!renderer->pollBackdropStats(stats)) {
        return nullptr;
    }

    std::array<jfloat, 4> values = {
        stats.meanLuma,
        stats.variance,
        stats.brightFraction,
        stats.darkFraction
    };
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeClearBackdropHardwareBuffer(
    JNIEnv*,
    jobject,
    jlong handle
) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr) {
        return;
    }
    renderer->clearBackdropHardwareBuffer();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zyna_app_ui_glass_NativeVulkanChat_nativeRenderFrame(JNIEnv*, jobject, jlong handle) {
    VulkanChatRenderer* renderer = rendererFromHandle(handle);
    if (renderer == nullptr) {
        return JNI_FALSE;
    }
    return renderer->renderFrame() ? JNI_TRUE : JNI_FALSE;
}
