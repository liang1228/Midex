# Current Android 17 / Linux 6.18 adaptation

Exact target: Xiaomi violin / OS4.0.6.0.XOTCNXM,
`6.18.21-android17-5-g284297031c53-abogki553682993-4k`.
This directory is a temporary diagnostic tool, separate from the optional
LSPosed extension and the ExtBridge APK. Nothing is installed at boot.

## Verified boundary

- The observer and trial LKMs loaded and unloaded through normal insmod/rmmod
  on the current device. No force flags, CRC edits or vermagic edits were used.
- Original vendor PD code sent pin-C Configure, received a real ACK and
  switched USB to DP_MODE. The sink still reported DPCD lane byte `0xc2`
  (two lanes), even with pin C active. Do not manufacture a four-lane sink.
- Pin C plus HBR3 achieved actual 8.1 Gbps training and a 540 MHz pixel-clock
  budget briefly. The sink then lost lock, raised HPD IRQ, and the original
  driver downgraded to HBR2. This was not stable 2K high-refresh output.
- Disabling SSC was independently tested: SSC became zero, but HBR3 clock
  recovery failed and the driver fell back to HBR2. It did not fix the link.
- Automatic 45-second D/HBR2 restoration and module/probe cleanup were
  verified. Final physical modes remained internal 144 Hz and external
  2560x1440 at 59.95055 Hz. Internal idle downclocking remains enabled.

Evidence: [device report](../../../../outputs/kernel618-adaptation-20261117/README.md).

## Direct DP follow-up

After changing to USB-C to DP, the real link negotiated four-lane HBR2
without this directory's `lane_trial`. Physical 1440p144 was activated by
the HAL-only prefer144 trial. The 165 Hz EDID timing needs a separate
594 MHz product-cap exception; `mode_cap/` allows only the exact real
2560x1440 / 699500 kHz mode and retains the subsequent bandwidth check.
It expires after 90 seconds and does not itself restore active modes.

`verify_mode_cap.py PROJECT_ROOT` checks BTF/CRCs/KCFI and executes 256
compiled-callback plus stock-checker cases. `run-direct165.sh` uses the
parent `trial.sh`'s independent 45-second HAL restoration, with exact
current firmware, kernel, vendor_boot, EDID and artifact guards.
Its service/package diagnostics tolerate Android being temporarily unready
after the restart. It does not change screen-timeout settings.

Physical 164.95774 Hz was observed, but sustained operation remains unverified.
The interrupted trial ended in a logged RescueParty warm reboot. Recovery
is now verified: stock HAL, no mode-cap module, internal 144 Hz, external
2K60 and the desktop restored. `run-direct165.sh` now checks the parent
script's restart budget before insmod; the current RescueParty boot is
blocked. It never resets rescue counters or blocks stock restoration.
Nineteen isolated shell checks and real-device refusal checks passed. Read the
[direct-DP evidence](../../../../outputs/dp-direct-2k-20261117/README.md)
before any further device action. Do not reuse HDMI partner/config guards
on this direct-DP receiver. No persistent installation is provided here.

## Files

- `observer/`, `observe-trial.sh`: read-only PD cache observer; device-side
  unload watchdog. Does not load modified OEM modules.
- `lane_trial/`: one-shot C, HBR3, combined, or SSC diagnostic; delayed work
  restores the original D selection after 45 seconds independently of ADB.
- `lane-trial.sh`: exact firmware/kernel/slot/vendor_boot/PD/module hash guards,
  private trace instance, phased cleanup, and read-only physical CRTC capture.
- `verify_lane_trial.py`: running-kernel BTF and symbol-version comparison,
  KCFI hashes, 32768 compiled pin-selection cases, 50 rate-min cases, and
  16 SSC-mask cases. These are offline checks, separate from device evidence.
- `patch_modules.py`, `verify.py`: exact-current-binary offline candidates
  and instruction verification. The two OEM replacement modules were not
  installed. Never load them alongside the stock modules or force-unload DRM.

## Build and ABI validation

The prepared build workspace is `/tmp/extbridge-kernel618-15549627` in WSL.
It contains `common/` (official DDK headers/Kbuild scripts), `prepared/`
(official modules_prepare output, .config and Module.symvers), and the pinned
musl kernel build tools. Inputs are retained in the project at
`references/gki-android17-6.18-15549627/`: build 15549627, tag
`android17-6.18-2026-06_r1`, commit `30c0eaeef78d5c19f687ad8418c27b76353ab261`.
This build script requires that prepared workspace; it does not bootstrap it
or download a toolchain. It checks its config, symvers and Makefile hashes.

`references/gki-android17-6.18-15549627/prepared-ddk-workspace.tar.gz` retains
the prepared workspace across WSL /tmp cleanup. SHA256:
`234c9550d56262a76234a08ef9556973a5c10c818344bfce5ba9fce24eb81fc3`.
After checking that hash, extract it into the same
`/tmp/extbridge-kernel618-15549627` directory. The archive contains the official
prepared files and local build-tool setup, not a new OEM kernel source tree.

```sh
sh source/tools/xring-mode-unlock/kernel618/build.sh \
  /tmp/extbridge-kernel618-15549627 /absolute/new-output-directory
```

The current compiler is Ubuntu Clang 21.1.8; official build metadata says
Android Clang 22.0.1. The mismatch is reported, not hidden. KCFI, PAC/BTI,
MODVERSIONS, the official module layout and stack guards remain enabled.
All 29 imported CRCs match official GKI/current vendor exports, and the
relevant eight BTF structures match the running kernel. The official GKI
commit differs from the running OEM commit; runtime load/unload was tested
in addition to these checks. This is not a universal 6.18 module.

```powershell
& 'D:\software\Anaconda3\envs\pytorch_env\python.exe' `
  source/tools/xring-mode-unlock/kernel618/verify_lane_trial.py `
  E:\workspace\projects\midex
```

The default verifier artifact is `extbridge_618_lane_trial-v6.ko` in the
project outputs directory. Rebuilds must be verified before use; the device
runner accepts only the tested SHA256. `build-script-check/` reproduced both
tested module hashes exactly on the existing prepared workspace.

## Device operation

Stage the exact verified v6 binary as
`/data/local/tmp/extbridge-kernel618-lane/extbridge_618_lane_trial.ko`
and the runner in the same directory. The existing read-only DRM helpers
`extbridge-drm-read-current` and `extbridge-drm-read-modes` are prerequisites.
Root shell commands:

```sh
sh /data/local/tmp/extbridge-kernel618-lane/lane-trial.sh probe combined
sh /data/local/tmp/extbridge-kernel618-lane/lane-trial.sh run combined
```

Profiles: `lanes` (C/HBR2), `hbr3` (D/HBR3), `combined` (C/HBR3),
`combined-nossc` (C/HBR3/SSC disabled). `probe` does not issue PD commands.
`recover` queries the real partner status then requests original D/HBR2.
The trial can blank/reconnect the external display during reconfiguration.

Restoration must refresh DPStatus before sending Configure. Replaying the
cached HPD IRQ bit yields an IRQ instead of Plug-In and can leave the CRTC
invalid despite a successful Configure ACK. The ACK probe must target the
cross-translation-unit `tcpci_dp_notify_config_done(tcpc,local,remote,ack)`;
the same-file wrapper was inlined on this firmware.

If restoration lacks a real ACK, the module retains its own reference and
waits for restoration or a physical unplug. Do not force-unload it. Phase 3
means PD ACK observed; physical CRTC validity must still be checked separately.

## Primary references

- [Linux Kprobes API and register/PC semantics](https://docs.kernel.org/trace/kprobes.html)
- [Official GKI build 15549627](https://ci.android.com/builds/submitted/15549627/kernel_aarch64/latest)
- [Pinned Android 17 / 6.18 source](https://android.googlesource.com/kernel/common/+/refs/tags/android17-6.18-2026-06_r1/)
- [MiCode vendor source reference](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/tree/99a9aff00a2856f065954672660bd0fe0ad0a064)

The public MiCode baseline is 6.6.30. Its structures/signatures are reference
material only; all active offsets and hook instructions were checked against
the current 6.18 binaries before loading.
