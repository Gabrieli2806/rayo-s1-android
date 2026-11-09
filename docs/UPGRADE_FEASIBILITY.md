# Newer Android feasibility and hardware limits

Assessment date: November 9, 2026. This is a research plan, not evidence that a newer Android release boots on this unit. Android 8.1 is the only verified newer platform; v14 is the current checkpoint. The subsequent [Android 12 TV assessment](ANDROID12_TV_ASSESSMENT.md) contains actual source/runtime findings, without claiming a working 12 port.

## Current upstream versus this board

The official AOSP site currently identifies `android17-release` as its latest release branch. That does not establish a supported ARMv7 board configuration or compatibility with this projector. [AOSP release information](https://source.android.com/docs/whatsnew/site-updates).

There is no verified maximum Android version for this unit. A credible answer requires building the chosen release for its actual instruction set, auditing kernel/userspace dependencies, adapting hardware interfaces, and testing physical output. A boot animation or screenshot alone is insufficient.

| Layer | Fixed hardware or current dependency | Development implications |
|---|---|---|
| CPU | Cortex-A7, 32-bit ARMv7-A | Cannot execute AArch64/ARM64 instructions natively; select compatible compiler targets and binaries |
| Memory/storage | Approximately 1 GB RAM, 8 GB eMMC | Budget resident services, graphics buffers, app working sets, image size, and free storage |
| Kernel | Factory 3.10.104 and private board integration | A newer kernel is a separate board/driver port; existing modules cannot simply be reused |
| Graphics | Mali-400 and factory Mali/gralloc/HWC stack | Existing adapter solves particular 8.1 ABIs, not all later Android graphics interfaces |
| Display | Rockchip LCD output plus projector-specific integration | GPU rendering support alone does not establish projected output |
| Peripherals | AP6330 and OEM audio/power/input/projection services | Each must be preserved or replaced and independently validated |

Arm documents Cortex-A7 as an ARMv7-A processor supporting 32-bit operating systems. Software cannot add a missing 64-bit execution architecture. Emulation is a different mechanism and not a practical performance strategy for this projector. [Arm Cortex-A7](https://www.arm.com/products/silicon-ip-cpu/cortex-a/cortex-a7).

## Kernel paths

1. **Preserve the stock kernel and backport selected functionality.** This is the approach demonstrated by isolated HwBinder on 8.1. Later releases may require additional interfaces, security features, or substantially changed userspace contracts. Each dependency needs assessment; the current success does not prove arbitrary upgrades.
2. **Port the board to a newer kernel.** This requires clocks, pin control, storage, display timing/output, GPU buffer sharing, Wi-Fi/BT, audio, battery, thermal/power controls, and projector behavior. Public RK3128 code is a starting point, not a proven Canon board port. Kernel APIs change; factory `.ko` files and userspace ioctl expectations are not automatically compatible.

AOSP maintains specific tested platform/kernel combinations. Its newer kernel compatibility rules do not make factory Linux 3.10 a drop-in supported kernel for current Android. Conversely, official launch certification requirements are not a mathematical proof that every experimental port is impossible. [Android common kernels](https://source.android.com/docs/core/architecture/kernel/android-common).

## Graphics options

The lowest-risk continuation is measuring and improving the existing working factory stack and adapter. Binary-only driver internals cannot be rebuilt without suitable source; adapter, compositor, configuration, and rendering workload can still be improved.

Mesa Lima supports Mali-400. Its upstream integration began with Mesa 19.1 and Linux 5.2, and its main API target is OpenGL ES 2.0. GPU rendering and display output are separate; a compatible display driver and shared-buffer path are also required. This would be a new graphics integration effort, not copying a newer `.so` into the trial. Do not promise better speed before measurement or claim Vulkan/modern GLES capabilities beyond the hardware. [Mesa Lima documentation](https://docs.mesa3d.org/drivers/lima.html).

## Practical progression

1. Freeze the 8.1 checkpoint and complete audio/video/Bluetooth, power, suspend, thermal, and long-run validation.
2. Measure frame timing and input-to-visible-response latency, CPU load, memory pressure, app start time, and hardware video decoding against stock under identical conditions.
3. Optimize measured bottlenecks: unnecessary services, launcher overdraw/animations, redundant buffer copies, frame synchronization, or excessive rendering resolution. Do not assume lowering resolution helps without checking projector scaling and image quality. Consider zram only after confirming kernel support and real memory pressure; it is not extra physical RAM.
4. Investigate Android 9 first as a smaller next porting step. Android 10–12 can be later research targets, not promised supported releases. These are engineering priorities rather than a fixed CPU version ceiling.
5. Treat current-generation Android as a separate feasibility project: audit ARM32 build/runtime availability, kernel gaps, graphics/composer/buffer allocation, media, radio HALs, memory, and image size before any device writes. Do not flash a generic GSI without demonstrated vendor compatibility.

More recent Android, a different ROM brand, or a newer kernel does not inherently mean lower latency or RAM use. Prefer measured usability and hardware functionality over the version number. Keeping the 8.1 base also retains old security components; responsiveness is not equivalent to current security support.
