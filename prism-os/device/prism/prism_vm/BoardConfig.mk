#
# PrismOS BoardConfig — arm64 virtual machine target
#

TARGET_ARCH         := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI      := arm64-v8a
TARGET_CPU_VARIANT  := generic

TARGET_2ND_ARCH         := arm
TARGET_2ND_ARCH_VARIANT := armv8-a
TARGET_2ND_CPU_ABI      := armeabi-v7a
TARGET_2ND_CPU_ABI2     := armeabi
TARGET_2ND_CPU_VARIANT  := generic

# ── Kernel ────────────────────────────────────────────────────────────────────
# Use the prebuilt Microdroid kernel (maintained by Google AVF team)
BOARD_KERNEL_IMAGE_NAME := Image.gz
TARGET_KERNEL_USE        := 6.6
TARGET_USES_MICRODROID_KERNEL := true

# ── Virtual disk / partitions ────────────────────────────────────────────────
# Minimal system image — target under 2 GB so it fits in a phone's internal storage
BOARD_SYSTEMIMAGE_PARTITION_SIZE       := 2147483648  # 2 GB
BOARD_USERDATAIMAGE_PARTITION_SIZE     := 536870912   # 512 MB
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE    := ext4
BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE  := ext4

TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := false

# ── Build type ────────────────────────────────────────────────────────────────
TARGET_NO_BOOTLOADER := true
TARGET_NO_RECOVERY   := true
TARGET_NO_KERNEL     := false

# ── Compiler ──────────────────────────────────────────────────────────────────
TARGET_CLANG_TRIPLE := aarch64-linux-gnu-

# ── SELinux ───────────────────────────────────────────────────────────────────
BOARD_SEPOLICY_DIRS := device/prism/prism_vm/sepolicy

# ── Verified Boot ─────────────────────────────────────────────────────────────
BOARD_AVB_ENABLE := false   # Disabled for now; enable for production signing

# ── virtio peripherals ────────────────────────────────────────────────────────
# QEMU / AVF expose these:
BOARD_USES_GENERIC_AUDIO              := true
TARGET_USES_HWC2                      := true
USE_OPENGL_RENDERER                   := true
NUM_FRAMEBUFFER_SURFACE_BUFFERS       := 3
