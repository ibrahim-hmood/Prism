#
# PrismOS — Lightweight AOSP product definition
# Target: virtual machine (AVF Microdroid-compatible or QEMU arm64)
#

# Inherit the Microdroid base (stripped AOSP userspace for pVMs)
$(call inherit-product, packages/modules/Virtualization/microdroid/microdroid.mk)

PRODUCT_NAME   := prism_vm
PRODUCT_DEVICE := prism_vm
PRODUCT_BRAND  := PrismOS
PRODUCT_MANUFACTURER := Prism
PRODUCT_MODEL  := PrismOS Virtual Machine

# SDK level
PRODUCT_SHIPPING_API_LEVEL := 35

# ── Minimal package set ──────────────────────────────────────────────────────
# Keep the image small: no Play Store, no Google apps, no bloat.
PRODUCT_PACKAGES += \
    framework \
    services \
    com.android.runtime \
    SettingsProvider \
    PackageInstaller \
    Shell \
    am \
    pm \
    cmd \
    logcat \
    toybox

# PrismOS companion app — receives app-launch intents forwarded by the host
PRODUCT_PACKAGES += \
    PrismGuestReceiver

# ── Remove packages that ship with Microdroid but are unneeded ────────────────
PRODUCT_PACKAGES_ETC_REMOVE := \
    init.microdroid.rc

# ── System properties ────────────────────────────────────────────────────────
PRODUCT_PROPERTY_OVERRIDES += \
    ro.prism.build=1 \
    ro.prism.version=$(PLATFORM_VERSION) \
    ro.product.first_api_level=35 \
    debug.sf.nobootanimation=1 \
    ro.setupwizard.mode=DISABLED \
    ro.com.android.dataroaming=false

# ── Overlays ─────────────────────────────────────────────────────────────────
DEVICE_PACKAGE_OVERLAYS += device/prism/prism_vm/overlay

# ── Locale ───────────────────────────────────────────────────────────────────
PRODUCT_LOCALES := en_US

# ── Locale ───────────────────────────────────────────────────────────────────
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
    ro.adb.secure=0 \
    service.adb.tcp.port=5554

# ── SELinux — permissive in eng/userdebug, enforcing in user ─────────────────
ifeq ($(TARGET_BUILD_VARIANT),user)
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += ro.build.selinux=1
else
PRODUCT_PROPERTY_OVERRIDES += ro.build.selinux=0
endif
