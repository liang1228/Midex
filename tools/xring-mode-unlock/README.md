# XRING external mode-table trial

Experimental tool for **Xiaomi Pad 7S Pro / violin / OS4.0.6.0.XOTCNXM**.
It is separate from the ExtBridge APK and is not enabled by the app.

Direct-DP follow-up: natural four-lane HBR2 allowed real 1440p144. The exact
165 Hz timing also needs the current kernel's separate 594 MHz product-cap
exception. `--prefer-165` now strictly selects real 2560x1440 modes up to
165 Hz and skips activation without 2K; it cannot fall back to a smaller
resolution. See `kernel618/README.md` and the latest evidence/status at
`outputs/dp-direct-2k-20261117/README.md` in the project root. Physical
164.95774 Hz was observed. The interrupted trial was traced to a recorded
RescueParty warm reboot; recovery is verified at internal 144 Hz / external
2K60, with stock HAL, no temporary module and the desktop restored. Sustained
165 Hz remains unverified. The new restart-budget guard blocks activation
on the current RescueParty boot without blocking stock restoration.

## Why the OPlusAutoHR approach needs an extra step here

Reference: OPlusAutoHR commit `40e758c16ce9de5823d098c5050409b2199028b4`,
`agent/oah_v2_agent.js` (`scanCfgs`, `applyHwc`) and `bin/oahctl`.
Its agent queries actual HWC configurations before switching and synchronizing
SurfaceFlinger. It cannot use a config that this HAL already erased.
No OPlusAutoHR code or runtime is included in this tool.

The captured XRING library's `XdmDisplayPluggable::setPreferredConfig()`:

1. Selects a default among real configurations using its existing policy.
2. Copies the winner, erases the entire map and inserts the winner as key 0.
3. Activates key 0. Android consequently sees a single 60 Hz mode.

The trial changes four ARM64 instructions at file offset `0x7de7c`:

```asm
mov x0, x19       // this
mov w1, w24       // the original selector's winning key
bl 0xd0a50       // setActiveConfigInternal@plt
b  0x7e1c0       // original stack guard and full epilogue
```

The map is retained. The original selector still chooses the initial mode.
The default trial does not force 1080p, manufacture EDID modes, or change DP bandwidth.
Only kernel-derived modes can become available. Kernel probing retained
1080p120, but did not retain the monitor's 1440p100/120/144 timings.

## Preparation and validation

Run `patch_xdm.py ORIGINAL OUTPUT` using the captured library. It rejects all
other SHA-256 hashes and never overwrites the input or an existing output.
Use `verify_patch.py ORIGINAL KERNEL_MODES` with Unicorn installed separately
to exercise the actual vendor ARM64 selection instructions and patched return.
The current report covers 38 cases plus 3 wrong-binary guards. Logging and the
final activation function are stubbed: these checks are **not hardware proof**.

Generated file: `outputs/libxdmcore-unfiltered-trial.so` at the project root.
Manifest: `outputs/libxdmcore-unfiltered-trial.json`.

## 2026-11-17 device result

The user authorized temporary testing and Composer/SurfaceFlinger restarts.
The default patch exposed 27 SF/HWC configurations, including 1080p120, but
the Android user-preference request stayed at 1440p60. SF transaction 1035
with the actual external mode ID and physical display ID 1 returned EINVAL.

An explicit `--prefer-120` variant additionally changes four selector
comparisons at `0x7db1c`, `0x7db40`, `0x7dc0c`, `0x7dc14` from 50/60 to 120.
It selects an existing 120 Hz configuration at initialization using the
original selection and activation code; it does not hardcode a config ID.
Both variants pass 38 actual-instruction cases and 3 wrong-binary guards.

The variant was tested from 08:41:54 to 08:42:39 device time:

- Android: 1920 x 1080, 120.00001 Hz; SF/HWC active config 24.
- DRM CRTC 205: valid=1, 1920 x 1080, 297000 kHz / 2200 / 1125 = 120 Hz.
- ExtBridge VD: user-configured 2560 x 1440 / 240 dpi at 120.00001 Hz,
  scaled to the physical 1080p output. This is not physical 1440p120.
- The 45-second device watchdog restored stock. Both screens and the
  original 4.3.1 desktop daemon were restored at 1440p60 external output.

Latest report: `outputs/xring-1080p120-trial-20261117.md` at project root.
That HAL trial was not persistently installed or integrated into the main APK.
Hot switching, 1440p high refresh and sustained application 120 FPS remain
unverified. The separate internal LSPosed trial below subsequently reached 144 Hz.

## Internal LSPosed unlock and current 1440p investigation

The user now explicitly requires physical 2560 x 1440 external output. Do not
use `prefer120` as a substitute: its proven external output was 1080p120.

`lsposed/build.ps1` builds the independent `com.extbridge.refreshunlock` APK
using the existing local ExtBridge signing key and compile-only Xposed API.
It only supports the exact firmware fingerprint above, process/package
`android` and UID 1000. Enable only its System Framework scope in LSPosed.
It does not start another ExtBridge daemon.

The hook disables only the verified OEM `shouldExtraDisplayLimitRefresh`
decision while Global `extbridge_unlock_builtin_refresh=1`, then refreshes
the existing MIUI vote on its own handler. The real external connection flag
and independent thermal votes remain intact. Missing switch defaults to off.

`lsposed/control.sh` is staged as
`/data/local/tmp/extbridge-refresh-unlock/control.sh`. `start max` saves the
original three user refresh settings, requests 144 and arms a detached
45-second rollback; `keep` requires actual internal physical 144 Hz.
`restore` restores the saved switch and all three settings; `off` restores
then disables. `check` and `status` inspect state. No boot script is installed.

On device, 120 Hz followed by automatic rollback was verified, then physical
143.99993 Hz was verified and kept at native internal resolution. All three
original 120 settings remain backed up. External output stayed 2560 x 1440
at 59.95055 Hz. Cross-reboot, thermal and sustained FPS tests remain pending.

The earlier `internal-policy-trial.sh` changes the OEM connection flag solely
for diagnosis and was restored; it is not the final approach.
`builtin-high-refresh.sh` tested a vendor capability property with service
restart and **did not unlock** this firmware; its watchdog restored stock.

Current-driver inspection used the device's own vendor_boot_a module, SHA-256
`7a0014feaf4868edcfef31dec9c6f68bce21ccb616179ed5a2812c204b2b1913`.
`trace-dp-policy.sh` temporarily observes fields with a private kprobe event
and tracing instance, then removes only its own objects. It does not alter
return values or kernel data. The observed stream is capped at 360000 kHz:
2 lanes, initial/actual HBR2, sink capability HBR3 and no sink DSC. The live
device tree has `max-link-rate=2`; driver preparation also takes the minimum
of defaults, sink capability and optional debug limits.

`drm_read_props.c` reads connector properties. Its SET_CLIENT_CAP only exposes
atomic properties on that private read-only fd; it does not acquire DRM master,
write a display property or commit modes. Even with atomic visibility, the
device exposes no link-rate, bpc or output-format control. DP debugfs control
nodes were not found; no supported live HBR3 control has been established.

Latest report at project root:
`outputs/root-lsposed-refresh-unlock-20261117.md`.
The simultaneous two-screen maximum-refresh goal is **not complete**.

## Optional console extension (0.2.0)

ExtBridge 4.4.0 bundles the independent module as `assets/refresh-unlock.apk`.
Without Root, the original console and ADB activation remain available.
With Root, the Display page offers an explicit install/update action; the
ExtBridge application itself needs a Root grant. LSPosed and the independent
module's System Framework scope are required, followed by a device reboot.
Do not enable the main application's legacy LSPosed backend alongside the
existing Root desktop backend.

The module retains the exact-firmware and system-process guards. Versioned
reports contain BOOT_COUNT and the enabled state, and a fresh nonce must be
acknowledged before the console changes the existing policy setting. The UI
toggle preserves user refresh preferences; the earlier `control.sh restore`
remains the path for restoring all three settings saved during the 144 Hz trial.

On 2026-11-17, module 0.2.0 was verified after a full device reboot (BOOT_COUNT
56). With the external monitor connected, real console UI toggles produced
internal DRM CRTC rates of 59.99996 Hz (off) and 143.99993 Hz (on), with distinct
matching nonce acknowledgements. User refresh preferences and configuration
hashes stayed unchanged; the external desktop recovered normally. The final
toggle is enabled. This verifies policy switching, not sustained application FPS.
External output remains 2560 x 1440 at 59.95055 Hz; this extension does not
unlock the driver/link limit. Delivery report:
`outputs/tiered-root-extension-20261117.md` at the project root.

## Display and window extension 0.4.0

ExtBridge 4.6.0 bundles `ExtBridge-DisplayWindowExtension-0.4.0.apk`. Enable the
independent module in LSPosed with both `android` (System Framework) and
`com.android.systemui` (System UI), then reboot. The refresh hook keeps its
existing protocol 3; the independent window acknowledgement uses protocol 1.
APK version 4 is required for the new window switches. An installed package
alone does not count as a loaded hook.

On the exact supported firmware, SystemUI WM Shell decorates only freeform
tasks on virtual displays named `ExtBridge-*`. `extbridge_square_windows`
removes task-leash corner radii (including HyperOS MiCornerRadii), and
`extbridge_native_caption` installs a SurfaceControlViewHost with a real 32dp
captionBar inset. The caption provides drag, minimize, maximize/restore and
close. Inner-display tasks, application content widgets and console cards are
outside this scope. Both settings default to zero.

The console writes a fresh request nonce and verifies a matching same-boot
`extbridge_window_status` before accepting a setting change. It restores the
previous setting on failure. `extbridge_caption_tasks` contains only tasks
with a successfully installed native caption; the existing overlay caption
is suppressed for those tasks and remains the fallback elsewhere. Caption
teardown removes its own inset and surface; disabling square corners restores
the captured OEM radii. Root task-control RPCs remain limited to trusted UIDs
and the current desktop's tasks.

Target checks: 30 host contract cases, actual console switches in both
directions, OEM radius restoration, caption inset removal, and three real
caption input cycles plus drag/close at each of 60Hz and 144Hz. This verifies
the current boot's SystemUI hooks; this version has not undergone a full
reboot acceptance test. Window features do not unlock physical display modes.

The separate live SF trial waits up to 30 seconds for the asynchronous 2K60
mode restoration before checking physical scanout and restoring the original
SF branch. Do not substitute a single early CRTC sample for recovery proof.

## External refresh during DP hotplug

`external-refresh.sh` selects the exact 2560 x 1440 timing before the first
DP video stream starts. It restores both HWC code ranges and unloads the
temporary mode cap module after the CRTC confirms the selected clock. On the
tested violin firmware and direct DP connection, the monitor reported
2560 x 1440 at 165 Hz and SurfaceFlinger confirmed the 165 Hz VSync period.
The earlier post-connect mode switch left the monitor receiving 60 Hz.

Install the verified native bundle with `Install-ExternalRefresh.ps1` and
start the one-connection procedure from the ExtBridge display page. The tool
checks the build, kernel, stock library, running process and monitor EDID.
The console reports when to unplug and reconnect. See
`outputs/external-refresh-20261118/README.md` at the project root for the
device evidence and supported setup.

## Device trial commands (temporary HAL experiment)

The reviewed scripts and trial library can be staged in the fixed directory
`/data/local/tmp/extbridge-xring-mode-trial`. `trial.sh check` and `status` only
inspect state. **`start` is disruptive.** The default profile retains the
original default; the explicit `prefer120` profile changes the initial mode.

```sh
sh /data/local/tmp/extbridge-xring-mode-trial/trial.sh restart-check
sh /data/local/tmp/extbridge-xring-mode-trial/trial.sh check
sh /data/local/tmp/extbridge-xring-mode-trial/trial.sh start
sh /data/local/tmp/extbridge-xring-mode-trial/trial.sh restore

# Explicit 120 Hz initialization variant, after separate generation/verification:
sh /data/local/tmp/extbridge-xring-mode-trial/trial.sh check prefer120
sh /data/local/tmp/extbridge-xring-mode-trial/trial.sh start prefer120
sh /data/local/tmp/extbridge-xring-mode-trial/trial.sh restore prefer120
```

`start` temporarily bind-mounts one library and restarts `vendor.hwcomposer-3`.
This device's `/vendor/etc/init/hwc3-xring.rc` includes
`onrestart restart surfaceflinger`: both screens can go black and the Android
interface/apps can reload. This is why the trial requires a separate user
decision after reviewing the patch. The user explicitly accepted that impact
for the completed temporary trial; no further confirmation was required.

A detached device-side watchdog is armed first and attempts to unmount the
trial and restart the stock services after 45 seconds, even if ADB disconnects.
The initial normal unmount returned EBUSY. The corrected script verifies
the trial hash again, lazily detaches that exact mount, and restarts services
to release the mapped library. Automatic stock recovery was then verified
on device for both profiles; arbitrary failure scenarios remain untested.
A device reboot also discards the
temporary mount. There is no boot module, vendor partition write or persistent
SELinux setting. The script verifies exact firmware/library hashes, service
namespace and absence of an existing mount before starting.

For `prefer120`, generate `libxdmcore-prefer120-trial.so` with `patch_xdm.py
ORIGINAL OUTPUT --prefer-120`; verify with `verify_patch.py ORIGINAL
KERNEL_MODES --prefer-120`. Stage that library alongside the default one.
The script also requires `/data/local/tmp/extbridge-drm-read-modes` and
checks its read-only cached inventory contains real 1080p120 before starting.
The variant SHA-256 is
`1892d5722388d27d1aa569d669fe0bf928a708f6f1462534e1737adac4106f51`.

After a trial, verify stock hash, mount absence, both service PIDs, physical
and virtual modes, visible desktop and existing application tasks. A successful
enumeration alone does not prove 120 Hz physical scanout: check SF/HWC active
configuration and DRM CRTC mode/period after explicitly selecting 1080p120.
`drm_read_current.c` uses only GETRESOURCES and GETCRTC and can be compiled
as a static AArch64 diagnostic; it never acquires DRM master or sets a mode.
After the Android service reload, wake and normally dismiss a nonsecure
keyguard before checking active CRTCs. An old ExtBridge process may survive
with dead system-service Binder references; verify its PID/cmdline/classpath
before replacing only that process. Restore any temporary power settings.
