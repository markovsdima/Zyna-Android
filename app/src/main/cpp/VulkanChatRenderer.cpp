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
#include <ctime>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <vector>

namespace {

constexpr const char* kTag = "ZynaVulkanChat";
constexpr float kPi = 3.14159265358979323846f;
constexpr int64_t kPaintSplashDurationNs = 1200000000LL;
constexpr float kPaintSplashReferenceArea = 8000.0f;
constexpr float kPaintSplashBaseParticleCount = 300.0f;
constexpr float kPaintSplashBlobScale = 2.5f;
constexpr uint32_t kMaxSplashItems = 8;
constexpr uint32_t kMinParticlesPerSplash = 200;
constexpr uint32_t kMaxParticlesPerSplash = 1200;
constexpr uint32_t kVerticesPerParticle = 6;
constexpr uint32_t kMaxParticleVertices =
    kMaxSplashItems * kMaxParticlesPerSplash * kVerticesPerParticle;
constexpr uint32_t kMaxBackdropRects = 16;
constexpr uint32_t kBackdropRectFloatCount = 8;
constexpr size_t kMaxBackdropImportCacheEntries = 6;

alignas(uint32_t) constexpr uint32_t kPaintSplashVertSpv[] =
#include "paint_splash_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kPaintSplashFragSpv[] =
#include "paint_splash_frag_spv.inc"
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

alignas(uint32_t) constexpr uint32_t kChatBackdropBlurVertSpv[] =
#include "chat_backdrop_blur_vert_spv.inc"
;

alignas(uint32_t) constexpr uint32_t kChatBackdropBlurFragSpv[] =
#include "chat_backdrop_blur_frag_spv.inc"
;

struct ParticleVertex {
    float position[2];
    float local[2];
    float color[4];
};

struct ParticlePushConstants {
    float viewportSize[2];
};

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
};

struct BlurPushConstants {
    float texelStep[2];
};

struct BackdropRect {
    float left;
    float top;
    float right;
    float bottom;
    float cornerRadius;
    float opacity;
    float bezelWidth;
    float glassThickness;
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

struct BitmapColor {
    float red;
    float green;
    float blue;
    float alpha;
};

struct PaintParticle {
    float originX;
    float originY;
    float velocityX;
    float velocityY;
    float halfWidth;
    float halfHeight;
    float red;
    float green;
    float blue;
    float alpha;
    float lifetime;
    float fadeDuration;
    float dragFactor;
};
struct PaintSplashItem {
    float left;
    float top;
    float right;
    float bottom;
    int64_t startTimeNs;
    std::vector<PaintParticle> particles;
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

uint32_t hashUint(uint32_t value) {
    value ^= value >> 16;
    value *= 0x7feb352du;
    value ^= value >> 15;
    value *= 0x846ca68bu;
    value ^= value >> 16;
    return value;
}

float randomFloat(uint32_t seed) {
    return static_cast<float>(hashUint(seed) & 0x00ffffffu) /
        static_cast<float>(0x01000000u);
}

float mixFloat(float from, float to, float progress) {
    return from + (to - from) * progress;
}

float smoothStep(float edge0, float edge1, float value) {
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

class VulkanChatRenderer {
public:
    VulkanChatRenderer() {
        particleVertices_.reserve(kMaxParticleVertices);
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
        return importBackdropHardwareBufferLocked(env, hardwareBuffer);
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
        if (splashItems_.size() >= kMaxSplashItems) {
            splashItems_.erase(splashItems_.begin());
        }
        PaintSplashItem item{};
        item.left = left;
        item.top = top;
        item.right = right;
        item.bottom = bottom;
        item.startTimeNs = nowNanos();
        buildPaintParticlesLocked(item, bitmapInfo, bitmapPixels);
        if (item.particles.empty()) {
            addFallbackParticleLocked(item);
        }
        splashItems_.push_back(std::move(item));
        didLogParticleDraw_ = false;
        logDebugf("native splash queued particles=%u",
                  static_cast<uint32_t>(splashItems_.back().particles.size()));
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

        VkResult fenceResult = vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, 100000000);
        if (fenceResult != VK_SUCCESS) {
            logWarn("vkWaitForFences failed", fenceResult);
            return false;
        }
        vkResetFences(device_, 1, &inFlightFence_);

        uint32_t imageIndex = 0;
        VkResult acquireResult = vkAcquireNextImageKHR(
            device_,
            swapchain_,
            std::numeric_limits<uint64_t>::max(),
            imageAvailableSemaphore_,
            VK_NULL_HANDLE,
            &imageIndex
        );
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
        recordClearPassLocked(commandBuffer, imageIndex);

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

        if (!isOk(vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_),
                  "vkQueueSubmit failed")) {
            vkResetFences(device_, 1, &inFlightFence_);
            return false;
        }

        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &renderFinishedSemaphore_;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain_;
        presentInfo.pImageIndices = &imageIndex;

        VkResult presentResult = vkQueuePresentKHR(graphicsQueue_, &presentInfo);
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
            recreateSwapchainLocked();
        } else if (presentResult != VK_SUCCESS) {
            logWarn("vkQueuePresentKHR failed", presentResult);
            return false;
        }

        return hasActivePaintSplashesLocked();
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

        if (!createParticleBufferLocked()) {
            destroyDeviceLocked();
            return false;
        }

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
            if ((queueFamilies[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) {
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
            createBackdropDescriptorResourcesLocked() &&
            createBackdropBlurResourcesLocked() &&
            createParticlePipelineLocked() &&
            createBackdropBlurPipelineLocked() &&
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
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

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
            !createBlobDescriptorResourcesLocked()) {
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
            vkCreateRenderPass(device_, &renderPassInfo, nullptr, &blobRenderPass_),
            "vkCreateRenderPass blob failed"
        );
    }

    bool createBlobImageLocked() {
        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = swapchainExtent_.width;
        imageInfo.extent.height = swapchainExtent_.height;
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
        framebufferInfo.width = swapchainExtent_.width;
        framebufferInfo.height = swapchainExtent_.height;
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
            poolSize.descriptorCount = 2;

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
        if (backdropDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            activeBackdropEntry_ == nullptr ||
            activeBackdropEntry_->imageView == VK_NULL_HANDLE ||
            backdropBlurImageView_ == VK_NULL_HANDLE) {
            return;
        }

        std::array<VkDescriptorImageInfo, 2> imageInfos{};
        imageInfos[0].sampler = backdropSampler_;
        imageInfos[0].imageView = activeBackdropEntry_->imageView;
        imageInfos[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageInfos[1].sampler = backdropSampler_;
        imageInfos[1].imageView = backdropBlurImageView_;
        imageInfos[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkWriteDescriptorSet, 2> writes{};
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

    bool ensureBackdropBlurSizeResourcesLocked(uint32_t width, uint32_t height) {
        if (width == 0 || height == 0) {
            return false;
        }
        if (backdropBlurWidth_ == width &&
            backdropBlurHeight_ == height &&
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
                backdropBlurImageView_,
                backdropBlurFramebuffer_
            )) {
            destroyBackdropBlurSizeResourcesLocked();
            return false;
        }

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
        if (backdropBlurSourceDescriptorSet_ == VK_NULL_HANDLE ||
            backdropSampler_ == VK_NULL_HANDLE ||
            activeBackdropEntry_ == nullptr ||
            activeBackdropEntry_->imageView == VK_NULL_HANDLE) {
            return;
        }
        updateSingleImageDescriptorLocked(
            backdropBlurSourceDescriptorSet_,
            activeBackdropEntry_->imageView
        );
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

    bool createParticleBufferLocked() {
        const VkDeviceSize bufferSize = sizeof(ParticleVertex) * kMaxParticleVertices;

        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = bufferSize;
        bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!isOk(vkCreateBuffer(device_, &bufferInfo, nullptr, &particleVertexBuffer_),
                  "vkCreateBuffer particle failed")) {
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetBufferMemoryRequirements(device_, particleVertexBuffer_, &memoryRequirements);

        uint32_t memoryTypeIndex = 0;
        if (!findMemoryTypeLocked(
                memoryRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                memoryTypeIndex
            )) {
            logWarn("No host visible memory for particle buffer");
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        if (!isOk(vkAllocateMemory(device_, &allocateInfo, nullptr, &particleVertexMemory_),
                  "vkAllocateMemory particle failed")) {
            return false;
        }

        return isOk(
            vkBindBufferMemory(device_, particleVertexBuffer_, particleVertexMemory_, 0),
            "vkBindBufferMemory particle failed"
        );
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
            updateBackdropBlurSourceDescriptorLocked();
            updateBackdropDescriptorLocked();
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
        updateBackdropBlurSourceDescriptorLocked();
        updateBackdropDescriptorLocked();
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
            }
            destroyBackdropImportEntryLocked(*entry);
            backdropImportCache_.erase(backdropImportCache_.begin());
        }
    }

    void waitForFrameFenceLocked() {
        if (device_ != VK_NULL_HANDLE && inFlightFence_ != VK_NULL_HANDLE) {
            vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, 100000000);
        }
    }

    void clearBackdropLocked() {
        backdropRects_.clear();
        backdropTextureOrigin_ = {0.0f, 0.0f};
        activeBackdropEntry_ = nullptr;
        backdropWidth_ = 0;
        backdropHeight_ = 0;
        backdropNeedsTransition_ = false;
        destroyBackdropImportCacheLocked();
    }

    void destroyBackdropBlurSizeResourcesLocked() {
        if (device_ == VK_NULL_HANDLE) {
            backdropBlurWidth_ = 0;
            backdropBlurHeight_ = 0;
            backdropBlurTempLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
            backdropBlurLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
            return;
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
        if (backdropBlurTempImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, backdropBlurTempImage_, nullptr);
            backdropBlurTempImage_ = VK_NULL_HANDLE;
        }
        if (backdropBlurImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, backdropBlurImage_, nullptr);
            backdropBlurImage_ = VK_NULL_HANDLE;
        }
        if (backdropBlurTempImageMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, backdropBlurTempImageMemory_, nullptr);
            backdropBlurTempImageMemory_ = VK_NULL_HANDLE;
        }
        if (backdropBlurImageMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, backdropBlurImageMemory_, nullptr);
            backdropBlurImageMemory_ = VK_NULL_HANDLE;
        }
        backdropBlurWidth_ = 0;
        backdropBlurHeight_ = 0;
        backdropBlurTempLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
        backdropBlurLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
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
    }

    bool hasBackdropImageLocked() const {
        return !backdropRects_.empty() &&
            activeBackdropEntry_ != nullptr &&
            activeBackdropEntry_->image != VK_NULL_HANDLE &&
            activeBackdropEntry_->imageView != VK_NULL_HANDLE &&
            backdropDescriptorSet_ != VK_NULL_HANDLE &&
            backdropBlurImageView_ != VK_NULL_HANDLE &&
            backdropWidth_ > 0 &&
            backdropHeight_ > 0;
    }

    void transitionBackdropImageForSamplingLocked(VkCommandBuffer commandBuffer) {
        if (!hasBackdropImageLocked() || !backdropNeedsTransition_) {
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
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
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

    bool createParticlePipelineLocked() {
        VkPushConstantRange pushConstantRange{};
        pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
        pushConstantRange.offset = 0;
        pushConstantRange.size = sizeof(ParticlePushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
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

        VkVertexInputBindingDescription bindingDescription{};
        bindingDescription.binding = 0;
        bindingDescription.stride = sizeof(ParticleVertex);
        bindingDescription.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

        std::array<VkVertexInputAttributeDescription, 3> attributeDescriptions{};
        attributeDescriptions[0].binding = 0;
        attributeDescriptions[0].location = 0;
        attributeDescriptions[0].format = VK_FORMAT_R32G32_SFLOAT;
        attributeDescriptions[0].offset = offsetof(ParticleVertex, position);
        attributeDescriptions[1].binding = 0;
        attributeDescriptions[1].location = 1;
        attributeDescriptions[1].format = VK_FORMAT_R32G32_SFLOAT;
        attributeDescriptions[1].offset = offsetof(ParticleVertex, local);
        attributeDescriptions[2].binding = 0;
        attributeDescriptions[2].location = 2;
        attributeDescriptions[2].format = VK_FORMAT_R32G32B32A32_SFLOAT;
        attributeDescriptions[2].offset = offsetof(ParticleVertex, color);

        VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
        vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        vertexInputInfo.vertexBindingDescriptionCount = 1;
        vertexInputInfo.pVertexBindingDescriptions = &bindingDescription;
        vertexInputInfo.vertexAttributeDescriptionCount =
            static_cast<uint32_t>(attributeDescriptions.size());
        vertexInputInfo.pVertexAttributeDescriptions = attributeDescriptions.data();

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
        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &compositeDescriptorSetLayout_;
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

    void buildPaintParticlesLocked(
        PaintSplashItem& item,
        const AndroidBitmapInfo& bitmapInfo,
        const void* bitmapPixels
    ) {
        if (bitmapPixels == nullptr ||
            bitmapInfo.width == 0 ||
            bitmapInfo.height == 0 ||
            bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            return;
        }

        const float width = std::max(1.0f, item.right - item.left);
        const float height = std::max(1.0f, item.bottom - item.top);
        const float area = width * height;
        const float scaledCount = kPaintSplashBaseParticleCount *
            std::pow(area / kPaintSplashReferenceArea, 0.6f);
        const uint32_t targetCount = clampUint(
            static_cast<uint32_t>(scaledCount),
            kMinParticlesPerSplash,
            kMaxParticlesPerSplash
        );
        item.particles.reserve(targetCount);

        const float aspect = width / std::max(1.0f, height);
        const uint32_t columns = std::max<uint32_t>(
            1,
            static_cast<uint32_t>(std::ceil(std::sqrt(static_cast<float>(targetCount) * aspect)))
        );
        const uint32_t rows = std::max<uint32_t>(
            1,
            static_cast<uint32_t>(std::ceil(static_cast<float>(targetCount) / columns))
        );
        const float cellWidth = width / static_cast<float>(columns);
        const float cellHeight = height / static_cast<float>(rows);
        const float baseGridSize = std::max(cellWidth, cellHeight);
        const float areaScale = std::clamp(
            std::pow(std::sqrt(area) / 90.0f, 0.35f),
            1.0f,
            1.6f
        );
        const float diagonal = std::sqrt(width * width + height * height);

        for (uint32_t index = 0; index < targetCount; ++index) {
            const uint32_t column = index % columns;
            const uint32_t row = index / columns;
            const uint32_t seed = hashUint(index * 747796405u + bitmapInfo.width * 97u +
                bitmapInfo.height * 193u);

            const float jitterX = (randomFloat(seed + 1u) - 0.5f) * cellWidth * 0.3f;
            const float jitterY = (randomFloat(seed + 2u) - 0.5f) * cellHeight * 0.3f;
            const float positionX = std::clamp(
                (static_cast<float>(column) + 0.5f) * cellWidth + jitterX,
                0.0f,
                width
            );
            const float positionY = std::clamp(
                (static_cast<float>(row) + 0.5f) * cellHeight + jitterY,
                0.0f,
                height
            );
            const float u = positionX / width;
            const float v = positionY / height;

            const BitmapColor color = sampleBitmapColor(bitmapInfo, bitmapPixels, u, v);
            if (color.alpha < 0.018f) {
                continue;
            }
            const float coverageWeight = smoothStep(0.018f, 0.18f, color.alpha);

            const float toEdgeX = positionX - width * 0.5f;
            const float toEdgeY = positionY - height * 0.5f;
            const float distanceFromCenter = std::sqrt(toEdgeX * toEdgeX + toEdgeY * toEdgeY);
            float directionX = 0.0f;
            float directionY = 0.0f;
            if (distanceFromCenter > 0.001f) {
                directionX = toEdgeX / distanceFromCenter;
                directionY = toEdgeY / distanceFromCenter;
            } else {
                const float angle = randomFloat(seed + 41u) * kPi * 2.0f;
                directionX = std::cos(angle);
                directionY = std::sin(angle);
            }

            const float spreadAngle = (randomFloat(seed + 53u) - 0.5f) * 0.6f;
            const float cosSpread = std::cos(spreadAngle);
            const float sinSpread = std::sin(spreadAngle);
            const float rotatedX = directionX * cosSpread - directionY * sinSpread;
            const float rotatedY = directionX * sinSpread + directionY * cosSpread;

            const float sizeBucket = randomFloat(seed + 79u);
            const float sizeScale = sizeBucket < 0.70f
                ? mixFloat(0.40f, 0.80f, randomFloat(seed + 83u))
                : (sizeBucket < 0.92f
                    ? mixFloat(0.80f, 1.50f, randomFloat(seed + 89u))
                    : mixFloat(1.50f, 3.00f, randomFloat(seed + 97u)));
            const float baseSize = std::max(baseGridSize * sizeScale * areaScale, 2.0f);
            const float rawSizeFactor = baseSize / std::max(baseGridSize * areaScale, 0.001f);
            const float speedSizeFactor = std::max(rawSizeFactor, 0.5f);
            const float halfExtent = baseSize * kPaintSplashBlobScale * 0.5f;
            const float normalizedDistance = distanceFromCenter / std::max(diagonal * 0.5f, 0.001f);
            float speed = diagonal * (1.2f + randomFloat(seed + 67u) * 1.5f);
            speed *= 0.7f + normalizedDistance * 0.6f;
            speed /= speedSizeFactor;

            PaintParticle particle{};
            particle.originX = u;
            particle.originY = v;
            particle.velocityX = rotatedX * speed;
            particle.velocityY = rotatedY * speed;
            particle.halfWidth = halfExtent;
            particle.halfHeight = halfExtent;
            particle.red = color.red;
            particle.green = color.green;
            particle.blue = color.blue;
            particle.alpha = mixFloat(0.40f, 1.0f, randomFloat(seed + 113u)) * coverageWeight;
            particle.lifetime = mixFloat(0.50f, 0.90f, randomFloat(seed + 127u));
            particle.fadeDuration = mixFloat(0.12f, 0.20f, randomFloat(seed + 131u));
            particle.dragFactor = 5.0f / std::max(rawSizeFactor, 1.0f);
            item.particles.push_back(particle);
        }
    }

    BitmapColor sampleBitmapColor(
        const AndroidBitmapInfo& bitmapInfo,
        const void* bitmapPixels,
        float u,
        float v
    ) const {
        const float pixelX = std::clamp(
            u * static_cast<float>(bitmapInfo.width) - 0.5f,
            0.0f,
            static_cast<float>(bitmapInfo.width - 1)
        );
        const float pixelY = std::clamp(
            v * static_cast<float>(bitmapInfo.height) - 0.5f,
            0.0f,
            static_cast<float>(bitmapInfo.height - 1)
        );
        const uint32_t x0 = static_cast<uint32_t>(std::floor(pixelX));
        const uint32_t y0 = static_cast<uint32_t>(std::floor(pixelY));
        const uint32_t x1 = std::min(bitmapInfo.width - 1, x0 + 1);
        const uint32_t y1 = std::min(bitmapInfo.height - 1, y0 + 1);
        const float tx = pixelX - static_cast<float>(x0);
        const float ty = pixelY - static_cast<float>(y0);

        const auto readPixel = [&](uint32_t x, uint32_t y) -> BitmapColor {
            const auto* row = static_cast<const uint8_t*>(bitmapPixels) + y * bitmapInfo.stride;
            const auto* pixel = row + x * 4;
            return BitmapColor{
                static_cast<float>(pixel[0]) / 255.0f,
                static_cast<float>(pixel[1]) / 255.0f,
                static_cast<float>(pixel[2]) / 255.0f,
                static_cast<float>(pixel[3]) / 255.0f
            };
        };

        const BitmapColor c00 = readPixel(x0, y0);
        const BitmapColor c10 = readPixel(x1, y0);
        const BitmapColor c01 = readPixel(x0, y1);
        const BitmapColor c11 = readPixel(x1, y1);

        const float redTop = mixFloat(c00.red, c10.red, tx);
        const float greenTop = mixFloat(c00.green, c10.green, tx);
        const float blueTop = mixFloat(c00.blue, c10.blue, tx);
        const float alphaTop = mixFloat(c00.alpha, c10.alpha, tx);
        const float redBottom = mixFloat(c01.red, c11.red, tx);
        const float greenBottom = mixFloat(c01.green, c11.green, tx);
        const float blueBottom = mixFloat(c01.blue, c11.blue, tx);
        const float alphaBottom = mixFloat(c01.alpha, c11.alpha, tx);

        const float red = mixFloat(redTop, redBottom, ty);
        const float green = mixFloat(greenTop, greenBottom, ty);
        const float blue = mixFloat(blueTop, blueBottom, ty);
        const float alpha = mixFloat(alphaTop, alphaBottom, ty);
        if (alpha <= 0.001f) {
            return BitmapColor{0.0f, 0.0f, 0.0f, 0.0f};
        }

        const float inverseAlpha = 1.0f / alpha;
        return BitmapColor{
            std::min(1.0f, red * inverseAlpha),
            std::min(1.0f, green * inverseAlpha),
            std::min(1.0f, blue * inverseAlpha),
            alpha
        };
    }

    void addFallbackParticleLocked(PaintSplashItem& item) {
        PaintParticle particle{};
        particle.originX = 0.5f;
        particle.originY = 0.5f;
        particle.velocityX = 0.0f;
        particle.velocityY = -120.0f;
        particle.halfWidth = 16.0f;
        particle.halfHeight = 16.0f;
        particle.red = clearColor_[0];
        particle.green = clearColor_[1];
        particle.blue = clearColor_[2];
        particle.alpha = clearColor_[3];
        particle.lifetime = 0.6f;
        particle.fadeDuration = 0.15f;
        particle.dragFactor = 5.0f;
        item.particles.push_back(particle);
    }

    void recordClearPassLocked(VkCommandBuffer commandBuffer, uint32_t imageIndex) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (!isOk(vkBeginCommandBuffer(commandBuffer, &beginInfo), "vkBeginCommandBuffer failed")) {
            return;
        }

        const uint32_t vertexCount = uploadPaintParticleVerticesLocked();
        if (vertexCount > 0) {
            recordBlobPassLocked(commandBuffer, vertexCount);
        }
        transitionBackdropImageForSamplingLocked(commandBuffer);
        recordBackdropBlurPassesLocked(commandBuffer);
        recordCompositePassLocked(commandBuffer, imageIndex, vertexCount > 0);
        isOk(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer failed");
    }

    void recordBlobPassLocked(VkCommandBuffer commandBuffer, uint32_t vertexCount) {
        if (blobRenderPass_ == VK_NULL_HANDLE ||
            blobFramebuffer_ == VK_NULL_HANDLE ||
            particlePipeline_ == VK_NULL_HANDLE ||
            particlePipelineLayout_ == VK_NULL_HANDLE ||
            particleVertexBuffer_ == VK_NULL_HANDLE) {
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
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = swapchainExtent_;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearValue;

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = swapchainExtent_;

        ParticlePushConstants pushConstants{};
        pushConstants.viewportSize[0] = viewport.width;
        pushConstants.viewportSize[1] = viewport.height;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, particlePipeline_);
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
        vkCmdPushConstants(
            commandBuffer,
            particlePipelineLayout_,
            VK_SHADER_STAGE_VERTEX_BIT,
            0,
            sizeof(ParticlePushConstants),
            &pushConstants
        );

        VkDeviceSize vertexOffset = 0;
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &particleVertexBuffer_, &vertexOffset);
        vkCmdDraw(commandBuffer, vertexCount, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
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
            srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
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

    void recordCompositePassLocked(
        VkCommandBuffer commandBuffer,
        uint32_t imageIndex,
        bool shouldComposite
    ) {
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
        if (hasBackdropImageLocked()) {
            recordBackdropCompositeLocked(commandBuffer);
        }
        if (shouldComposite &&
            compositePipeline_ != VK_NULL_HANDLE &&
            compositePipelineLayout_ != VK_NULL_HANDLE &&
            compositeDescriptorSet_ != VK_NULL_HANDLE) {
            VkViewport viewport{};
            viewport.x = 0.0f;
            viewport.y = 0.0f;
            viewport.width = static_cast<float>(swapchainExtent_.width);
            viewport.height = static_cast<float>(swapchainExtent_.height);
            viewport.minDepth = 0.0f;
            viewport.maxDepth = 1.0f;

            VkRect2D scissor{};
            scissor.offset = {0, 0};
            scissor.extent = swapchainExtent_;

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline_);
            vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
            vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
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
            vkCmdDraw(commandBuffer, 6, 1, 0, 0);
        }
        vkCmdEndRenderPass(commandBuffer);
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
            pushConstants.adaptiveAppearance = 1.0f;
            pushConstants.adaptiveContrast = 0.0f;

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
        if (splashItems_.empty()) {
            return false;
        }

        const int64_t nowNs = nowNanos();
        splashItems_.erase(
            std::remove_if(
                splashItems_.begin(),
                splashItems_.end(),
                [nowNs](const PaintSplashItem& item) {
                    return nowNs - item.startTimeNs >= kPaintSplashDurationNs;
                }
            ),
            splashItems_.end()
        );
        return !splashItems_.empty();
    }

    uint32_t uploadPaintParticleVerticesLocked() {
        const uint32_t vertexCount = buildPaintParticleVerticesLocked();
        if (vertexCount == 0 ||
            particleVertexBuffer_ == VK_NULL_HANDLE ||
            particleVertexMemory_ == VK_NULL_HANDLE) {
            return 0;
        }
        if (!didLogParticleDraw_) {
            logDebugf("draw particles vertexCount=%u", vertexCount);
            didLogParticleDraw_ = true;
        }

        void* mappedMemory = nullptr;
        if (!isOk(
                vkMapMemory(
                    device_,
                    particleVertexMemory_,
                    0,
                    sizeof(ParticleVertex) * vertexCount,
                    0,
                    &mappedMemory
                ),
                "vkMapMemory particle failed"
            )) {
            return 0;
        }
        std::memcpy(
            mappedMemory,
            particleVertices_.data(),
            sizeof(ParticleVertex) * vertexCount
        );
        vkUnmapMemory(device_, particleVertexMemory_);
        return vertexCount;
    }

    uint32_t buildPaintParticleVerticesLocked() {
        particleVertices_.clear();
        if (splashItems_.empty()) {
            return 0;
        }

        const int64_t nowNs = nowNanos();
        std::vector<PaintSplashItem> activeItems;
        activeItems.reserve(splashItems_.size());
        for (PaintSplashItem& item : splashItems_) {
            if (nowNs - item.startTimeNs < kPaintSplashDurationNs) {
                activeItems.push_back(std::move(item));
            }
        }
        splashItems_.swap(activeItems);

        for (const PaintSplashItem& item : splashItems_) {
            const float elapsedSeconds = static_cast<float>(nowNs - item.startTimeNs) /
                1000000000.0f;
            for (const PaintParticle& particle : item.particles) {
                const float fadeStart = particle.lifetime;
                const float fadeEnd = particle.lifetime + particle.fadeDuration;
                if (elapsedSeconds >= fadeEnd) {
                    continue;
                }
                const float fade = elapsedSeconds <= fadeStart
                    ? 1.0f
                    : 1.0f - std::clamp(
                        (elapsedSeconds - fadeStart) /
                            std::max(0.001f, particle.fadeDuration),
                        0.0f,
                        1.0f
                    );
                const float dragFactor = std::max(0.001f, particle.dragFactor);
                const float dragSeconds = (1.0f - std::exp(-elapsedSeconds * dragFactor)) /
                    dragFactor;
                const float gravitySeconds = (elapsedSeconds - dragSeconds) / dragFactor;
                const float x = item.left + (item.right - item.left) * particle.originX +
                    particle.velocityX * dragSeconds;
                const float y = item.top + (item.bottom - item.top) * particle.originY +
                    particle.velocityY * dragSeconds +
                    800.0f * gravitySeconds;
                appendParticleQuadLocked(
                    x,
                    y,
                    particle.halfWidth,
                    particle.halfHeight,
                    particle.red,
                    particle.green,
                    particle.blue,
                    particle.alpha * fade
                );
            }
        }
        return static_cast<uint32_t>(particleVertices_.size());
    }

    void appendParticleQuadLocked(
        float centerX,
        float centerY,
        float halfWidth,
        float halfHeight,
        float red,
        float green,
        float blue,
        float alpha
    ) {
        if (alpha <= 0.001f || particleVertices_.size() + kVerticesPerParticle > kMaxParticleVertices) {
            return;
        }

        const float left = centerX - halfWidth;
        const float right = centerX + halfWidth;
        const float top = centerY - halfHeight;
        const float bottom = centerY + halfHeight;

        const std::array<ParticleVertex, kVerticesPerParticle> vertices = {
            ParticleVertex{{left, top}, {-1.0f, -1.0f}, {red, green, blue, alpha}},
            ParticleVertex{{right, top}, {1.0f, -1.0f}, {red, green, blue, alpha}},
            ParticleVertex{{right, bottom}, {1.0f, 1.0f}, {red, green, blue, alpha}},
            ParticleVertex{{left, top}, {-1.0f, -1.0f}, {red, green, blue, alpha}},
            ParticleVertex{{right, bottom}, {1.0f, 1.0f}, {red, green, blue, alpha}},
            ParticleVertex{{left, bottom}, {-1.0f, 1.0f}, {red, green, blue, alpha}}
        };

        particleVertices_.insert(
            particleVertices_.end(),
            vertices.begin(),
            vertices.end()
        );
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

        if (particlePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, particlePipeline_, nullptr);
            particlePipeline_ = VK_NULL_HANDLE;
        }
        if (particlePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, particlePipelineLayout_, nullptr);
            particlePipelineLayout_ = VK_NULL_HANDLE;
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
        if (backdropBlurPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, backdropBlurPipeline_, nullptr);
            backdropBlurPipeline_ = VK_NULL_HANDLE;
        }
        if (backdropBlurPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, backdropBlurPipelineLayout_, nullptr);
            backdropBlurPipelineLayout_ = VK_NULL_HANDLE;
        }

        destroyBackdropBlurSizeResourcesLocked();
        if (backdropBlurRenderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, backdropBlurRenderPass_, nullptr);
            backdropBlurRenderPass_ = VK_NULL_HANDLE;
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
        if (particleVertexBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, particleVertexBuffer_, nullptr);
            particleVertexBuffer_ = VK_NULL_HANDLE;
        }
        if (particleVertexMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, particleVertexMemory_, nullptr);
            particleVertexMemory_ = VK_NULL_HANDLE;
        }
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
    VkPipelineLayout particlePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline particlePipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout compositePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline compositePipeline_ = VK_NULL_HANDLE;
    VkBuffer particleVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory particleVertexMemory_ = VK_NULL_HANDLE;
    VkFormat blobFormat_ = VK_FORMAT_R16G16B16A16_SFLOAT;
    VkRenderPass blobRenderPass_ = VK_NULL_HANDLE;
    VkImage blobImage_ = VK_NULL_HANDLE;
    VkDeviceMemory blobImageMemory_ = VK_NULL_HANDLE;
    VkImageView blobImageView_ = VK_NULL_HANDLE;
    VkFramebuffer blobFramebuffer_ = VK_NULL_HANDLE;
    VkSampler blobSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout compositeDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool compositeDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet compositeDescriptorSet_ = VK_NULL_HANDLE;
    std::vector<std::unique_ptr<BackdropImportEntry>> backdropImportCache_;
    BackdropImportEntry* activeBackdropEntry_ = nullptr;
    VkSampler backdropSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout backdropDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool backdropDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout backdropBlurDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool backdropBlurDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropBlurSourceDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSet backdropBlurTempDescriptorSet_ = VK_NULL_HANDLE;
    VkFormat backdropBlurFormat_ = VK_FORMAT_R8G8B8A8_UNORM;
    VkRenderPass backdropBlurRenderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout backdropBlurPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline backdropBlurPipeline_ = VK_NULL_HANDLE;
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
    std::vector<BackdropRect> backdropRects_;
    std::array<float, 2> backdropTextureOrigin_{0.0f, 0.0f};

    int width_ = 1;
    int height_ = 1;
    std::array<float, 4> clearColor_{0.05f, 0.72f, 0.86f, 1.0f};
    std::array<float, 4> inputBarBounds_{};
    bool hasInputBarBounds_ = false;
    bool hardwareBufferImportSupported_ = false;

    std::vector<PaintSplashItem> splashItems_;
    std::vector<ParticleVertex> particleVertices_;
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
    const jsize valueCount = env->GetArrayLength(rectValues);
    if (valueCount < static_cast<jsize>(kBackdropRectFloatCount)) {
        return JNI_FALSE;
    }

    jboolean didCopy = JNI_FALSE;
    jfloat* values = env->GetFloatArrayElements(rectValues, &didCopy);
    if (values == nullptr) {
        return JNI_FALSE;
    }

    const uint32_t rectCount = std::min(
        kMaxBackdropRects,
        static_cast<uint32_t>(valueCount / static_cast<jsize>(kBackdropRectFloatCount))
    );
    std::vector<BackdropRect> rects;
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

    if (rects.empty()) {
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
