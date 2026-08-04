# Prism — Completed Work Log

## OS Virtualization Page (Launcher Integration)

### New Files Created
| File | Purpose |
|------|---------|
| `app/src/main/java/com/prism/launcher/virtualization/PrismOsConfig.kt` | Constants: payload dirs, QEMU binary name, vsock CID/port, default RAM |
| `app/src/main/java/com/prism/launcher/virtualization/VmController.kt` | VM lifecycle manager — AVF (API 34+) and QEMU backends, vsock/ADB app routing |
| `app/src/main/java/com/prism/launcher/virtualization/VncSurfaceRenderer.kt` | RFB (VNC) client that streams QEMU framebuffer updates to an Android Surface |
| `app/src/main/java/com/prism/launcher/virtualization/VirtualizationPageView.kt` | Launcher page View — inflates layout, owns VmController, exposes `launchApp(cn)` |
| `app/src/main/res/layout/page_virtualization_root.xml` | Layout: SurfaceView for VM display + translucent control bar + boot overlay |

### Modified Files
| File | What Changed |
|------|-------------|
| `app/src/main/java/com/prism/launcher/SlotAssignment.kt` | Added `VirtualizationOs` data object; serialize = `"virtualization_os"` |
| `app/src/main/java/com/prism/launcher/MainDesktopPagerAdapter.kt` | Added `VirtualizationOs` branch in `buildPage()` |
| `app/src/main/java/com/prism/launcher/PagePickerAdapters.kt` | Added `VirtualizationOs` to `PagePickChoice`; added position 7 in options adapter |
| `app/src/main/java/com/prism/launcher/LauncherActivity.kt` | `launchComponent()` now routes to VM if PrismOS enabled; `findVirtualizationOsPosition()` helper; `logLaunchStat()` extracted |
| `app/src/main/java/com/prism/launcher/PrismSettings.kt` | Added `VIRT_MODE_PRISM_OS`, `VIRT_MODE_CUSTOM_ISO`; getters/setters for enabled, mode, ISO path |
| `app/src/main/java/com/prism/launcher/SettingsActivity.kt` | Added "OS Virtualization" section (toggle, mode picker, ISO file nav); `isoPicker` launcher |
| `app/src/main/res/values/strings.xml` | Added `slot_virtualization_os`, `virt_status_*` strings |
| `app/src/main/AndroidManifest.xml` | Added `MANAGE_VIRTUAL_MACHINE` permission |

### How It Works
1. User adds an "OS Virtualization" page via the page picker
2. Settings → OS Virtualization: toggle enable, pick PrismOS or custom ISO, select ISO file
3. When enabled (PrismOS mode) and a virtualization page exists, tapping any app icon navigates to the virtualization page and sends the ComponentName to the VM
4. VmController uses AVF on API 34+ with hardware support; falls back to bundled QEMU binary
5. VM display rendered into a SurfaceView via VNC (QEMU) or VirtualDisplay (AVF)
6. App intents forwarded to PrismOS guest via vsock (AVF) or ADB-over-TCP (QEMU)

---

## PrismOS Build System

### Files Created under `prism-os/`
| File | Purpose |
|------|---------|
| `manifest.xml` | Repo manifest — pins AOSP android-15.0.0_r1 + PrismOS overlay repos |
| `device/prism/prism_vm/AndroidProducts.mk` | Registers `prism_vm-userdebug/user/eng` lunch targets |
| `device/prism/prism_vm/prism_vm.mk` | Product definition — inherits Microdroid, strips bloat, adds PrismGuestReceiver |
| `device/prism/prism_vm/BoardConfig.mk` | arm64 VM board: 2 GB system, 512 MB userdata, virtio GPU, no AVB |
| `device/prism/prism_vm/init.prism_vm.rc` | Init script — enables ADB on port 5554, starts guest bridge |
| `device/prism/prism_vm/sepolicy/prism_guest_receiver.te` | SELinux policy for the guest bridge service |
| `packages/apps/PrismGuestReceiver/Android.bp` | Soong build rule (privileged system app, platform cert) |
| `packages/apps/PrismGuestReceiver/AndroidManifest.xml` | Persistent system app with boot receiver |
| `packages/apps/PrismGuestReceiver/src/.../BootReceiver.kt` | Starts GuestBridgeService on boot |
| `packages/apps/PrismGuestReceiver/src/.../GuestBridgeService.kt` | TCP server on :5554; handles LAUNCH/PING commands from host |
| `scripts/setup.sh` | One-shot Linux setup: installs deps, inits repo, syncs AOSP, symlinks PrismOS trees |
| `scripts/package.sh` | Packages build output into `prism_os/` dir for deployment via `adb push` |

### Build Instructions (Linux)
```bash
bash prism-os/scripts/setup.sh          # ~70 GB sync, takes 1–3 hours
cd $HOME/prism_os_src
source build/envsetup.sh
lunch prism_vm-userdebug
m -j$(nproc)                            # build, ~2–4 hours
bash prism-os/scripts/package.sh $HOME/prism_os_src
adb push prism-os/dist/prism_os /data/data/com.prism.launcher/files/prism_os
```

### How PrismOS Receives App Launches
1. Host `VmController.sendViaAdb()` writes `LAUNCH <component>` over TCP to port 5554
2. `GuestBridgeService` inside PrismOS receives the command and calls `startActivity()`
3. The app opens inside the PrismOS VM, rendered back to the host via VNC/VirtualDisplay
