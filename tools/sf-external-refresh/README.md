# Xiaomi 15 Ultra native external-refresh preview

This KernelSU module previews the stock SurfaceFlinger mode-change flow on xuanyuan OS4.0.3.0.XOACNXM with the measured external monitor. The policy guard runs at the caller-thread check and the repeated check in the scheduled main-thread task. Both admit the measured external physical display ID with default mode IDs 0 through 5. The mode submission guard requires the measured physical display, 2560×1440 dimensions, and matching SurfaceFlinger and HWC mode IDs. It checks the rate against the measured mapping, where IDs 0 through 5 mean 60, 180, 165, 144, 120 and 100 Hz over a direct USB-C to DP connection. It also accepts 100 Hz for mode 1 on the dock. The stock mode controller still performs the actual switch.

The module uses KernelSU's OverlayFS metamodule to mount the patched library for one boot. The install stage arms it. The pre-mount stage validates the stock hash and only permits the overlay before SurfaceFlinger starts. It consumes the arm marker. The post-mount stage restores `skip_mount` for the next boot. Late load skips the overlay. [KernelSU metamodule stages](https://kernelsu.org/guide/metamodule.html)

Build with AArch64 GNU binutils, Python, `pyelftools` and `unicorn`. Obtain `stock.so` from `/system_ext/lib64/libsurfaceflinger.so` on the target build first.

```sh
aarch64-linux-gnu-as -o guard.o xuanyuan-guard.S
aarch64-linux-gnu-ld --entry=external_mode_guard --section-start=.text=0xce6010 \
  --defsym=stock_branch=0x4fee1c --defsym=stock_accept=0x4fee70 \
  --defsym=policy_accept=0x529f20 --defsym=policy_refuse=0x529f0c \
  --defsym=scheduled_policy_accept=0x56d5e0 --defsym=scheduled_policy_refuse=0x56d5cc \
  -o guard.elf guard.o
python pack_overlay.py stock.so guard.elf ExtBridge-Xuanyuan-2K-Refresh-Preview-0.1.4.zip
```

The packer checks the stock hash and validates all three branch sites, the extended executable segment, and available ELF padding. It emulates both policy checks and the mode submission in the original and packaged ARM64 instructions. The submission test ends at the mode-controller call.

For device verification, confirm the loaded library and use ExtBridge's automatic refresh selection with the monitor connected. Capture the SurfaceFlinger policy, active mode and render rate, DRM timing, and the external desktop before testing interactions. Check the DP link for resets and confirm the physical monitor remains visible. Verify the internal display on the same capture and confirm the stock library after the following boot.
