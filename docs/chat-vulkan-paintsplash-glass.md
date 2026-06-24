# Chat Vulkan PaintSplash glass

This note documents the current chat-only Vulkan glass and PaintSplash delete
effect pipeline. It is meant to capture stable implementation constraints, not
profiling history.

## Scope

- Keep the Canvas/AGSL glass system as the app-wide glass path.
- Use the Vulkan chat overlay for chat input glass, PaintSplash delete bursts,
  wet paint on glass, and falling paint drips.
- Keep message deletion and visual deletion decoupled: capture the visible
  bubble before redaction, start the effect, then continue the normal redaction
  flow.

## View and Surface Model

- `GlassChatLayout` owns the Android view hierarchy and backdrop capture
  scheduling.
- `VulkanChatOverlayView` owns the chat-local Vulkan `TextureView`, native
  renderer handle, render thread, and frame pacing.
- `nativeSetSurface` must only run on first bind or size change. Calling it on
  every backdrop update recreates the swapchain and stalls rendering.
- Native render work must stay off the UI thread. UI code queues native work;
  the overlay render thread drains it and renders.

## Backdrop Capture

- The chat backdrop is captured with `HardwareRenderer` + `ImageReader` +
  `HardwareBuffer`.
- Captured buffers are imported into Vulkan through AHardwareBuffer, without CPU
  bitmap readback.
- ImageReader frames are consumed from `OnImageAvailableListener`; do not assume
  an image is available immediately after `syncAndDraw()`.
- Imported AHB resources are cached by `AHardwareBuffer*`. Reuse `VkImage`,
  `VkDeviceMemory`, and `VkImageView` for recurring ImageReader buffers.
- Keep Java `Image` objects alive until the Vulkan frame that samples them has
  completed, then close them.

## Glass Rect ABI

Kotlin sends glass target data to native as 11 floats per rect:

1. `left`
2. `top`
3. `right`
4. `bottom`
5. `cornerRadius`
6. `opacity`
7. `bezelWidth`
8. `glassThickness`
9. `adaptiveAppearance`
10. `adaptiveContrast`
11. `shapeKind`

`shapeKind` is `0` for rounded rects and `1` for circles. Native clamps it to
those two values and forwards it to glass hit targets as `params.z`.

## PaintSplash Burst

- Bubble snapshots are captured before the source view is hidden/redacted.
- Particle initialization samples the snapshot color and creates an iOS-style
  metaball field: droplet count scales with area, blob radius is tied to the
  droplet grid, and drag depends on size.
- Visible burst rendering uses an offscreen blob texture followed by a composite
  pass. Do not directly draw individual droplets to the swapchain for the final
  effect.

## Wet Surface and Drips

- Splash droplets that hit glass write glass impact events into a GPU buffer.
- The wet surface pipeline consumes those events through an impact pass, then
  updates the wet film in a compute pass.
- The SPH drip layer reads the wet surface and velocity textures, spawns
  shape-aware drip particles from glass nozzles, renders a full-resolution SPH
  field, and composites falling drips over the chat overlay.
- Rounded rects use multiple bottom-edge nozzles with seeded variation.
- Circular buttons use circle-aware bottom geometry and nozzle placement.
- Wet film and SPH motion are updated from elapsed native frame time, not from a
  fixed per-frame increment.

## Performance Invariants

- Do not re-create the swapchain on hot-path backdrop updates.
- Do not add CPU `RecyclerView.draw()` readbacks to the live glass path.
- Do not recreate imported AHB Vulkan resources every frame.
- Do not block the UI thread on native rendering.
- Keep wet/SPH work scoped to glass-related work rects where possible.
- Prefer profiling over guessing. Use `ZynaVulkanChat` `frameTrace` for native
  GPU stage timing and `ZynaHwBufferCapture` for capture/import timing.

## Known Bottleneck

The current visual target is accepted for this stage, but 120 Hz headroom during
PaintSplash plus wet drips is limited mostly by the final composite path. Future
performance work should start there before changing capture or AHB import again.
