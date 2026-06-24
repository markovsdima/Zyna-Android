# Chat Vulkan glass performance summary

This note summarizes the main performance findings from local Vulkan chat glass
profiling. It records directional impact and stable conclusions, not every raw
measurement window.

## Test Device

- Device: POCO M8 5G
- OS: HyperOS 3
- SoC: Snapdragon 6 Gen 3 (`ro.soc.model`: Qualcomm SM6475)
- GPU: Adreno (TM) 710
- Vulkan driver: Qualcomm Adreno Vulkan Driver, build `4e394f1848`
- GLES driver: OpenGL ES 3.2 `V@0800.70` (driver date: 2026-02-04)
- RAM: 7.2 GiB reported by `/proc/meminfo` (`MemTotal: 7569356 kB`; 8 GB class)
- Resolution used in tests: 1080 x 2392
- Active display mode for 120 Hz runs: 120.00001 Hz

These numbers are device-specific profiling checkpoints, not product-wide
performance guarantees.

## Summary Table

| Change | Observed impact | Conclusion |
| --- | --- | --- |
| Avoid per-tick `nativeSetSurface()` | Removed 60-100 ms stalls from repeated swapchain teardown/recreate | Critical fix. Surface binding must be cached and only changed on first bind or size change. |
| Remove 8 ms capture throttle | Stable active windows reached about 119-120 Hz | Choreographer already limits pending work; the fixed throttle plus work cost missed 120 Hz frames. |
| Cache AHB imports by `AHardwareBuffer*` | `importAvgMs` dropped from about 0.5-0.6 ms to about 0.05 ms | Critical fix. Do not recreate imported `VkImage`/memory/view every backdrop frame. |
| Consume frames from `ImageReader.OnImageAvailableListener` | Fixed stale backdrop after interrupted fling; stable active windows stayed near 120 Hz | Correctness fix. Do not assume immediate `acquireLatestImage()` after `syncAndDraw()` is fresh. |
| Add iOS-style Vulkan glass material and two-pass blur | Native render cost rose to roughly 0.4-0.56 ms in stable windows | Acceptable cost for real input glass; AHB import remained cheap. |
| Move from preview rect to real input bar rects | Later stable windows were roughly 0.9-1.1 ms total per backdrop tick | Real input integration stayed within 120 Hz active-scroll budget. |
| Software adaptive luma stats | Total tick cost rose to roughly 2.2-2.5 ms; overlay work dominated | Too expensive as a long-term path because it reintroduced CPU-side view rendering. |
| GPU adaptive luma stats | Stable total returned to roughly 1.0-1.5 ms; overlay about 0.53-0.8 ms | Correct path. Keep luma stats on GPU and poll asynchronously. |
| PaintSplash-to-backdrop overlay | Overlay record section stayed around 0.02-0.03 ms | Not a meaningful bottleneck. |
| PaintSplash + wet film + SPH drips | GPU frames often measured around 9-10 ms, with spikes above 12 ms | Current heavy path exceeds the 8.33 ms 120 Hz budget on this device. The main future target is final composite cost. |

## Current Bottlenecks

- Live glass backdrop capture/import is no longer the main bottleneck in stable
  active-scroll windows.
- AHB import cache keeps import cost near 0.05 ms.
- Stable non-splash input glass can sustain about 120 Hz on the test device.
- During PaintSplash plus wet drips, the GPU budget is dominated by final
  composite work, with secondary cost from wet/SPH passes.

## Rejected Perf Experiment

Updating wet film and SPH drips at an artificial 60 Hz did not provide a useful
win in the tested form. Time-based gating still advanced almost every real
render after `fenceBusy` skips, and forced alternating produced lighter cached
frames but left heavy frames above budget. The experiment was reverted.

Future optimization should start with the composite path before changing capture,
AHB import, or wet/SPH update cadence again.
