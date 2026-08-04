package com.prism.launcher.virtualization

internal object PrismOsConfig {
    const val PAYLOAD_DIR        = "prism_os"
    const val MICRODROID_CONFIG  = "vm_config.json"
    //LD_LIBRARY_PATH, the environment variable, is why youre having issues with running qemu
    //Need to go through binaries and apply set-soname to their new name so they work

    //Build QEMU from scratch, thats the best option.

    // Android 10+ (API 29+) mounts app-private storage (filesDir, etc.) `noexec` — a binary
    // copied there can never be exec()'d, no matter its chmod bits. The only location the OS
    // guarantees is executable is the app's nativeLibraryDir, which is populated exclusively from
    // jniLibs/<abi>/*.so at install time. So the QEMU binary must live at
    // app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so (the "lib*.so" name is mandatory —
    // it's how the packaging tooling recognizes it as a native library to install), and is run
    // from context.applicationInfo.nativeLibraryDir, never from filesDir. See VmController.resolveQemuBinary.
    const val QEMU_BINARY        = "libqemu-system-aarch64.so"

    // Optional aarch64 UEFI firmware (edk2-aarch64-code.fd, renamed to this) — QEMU's "virt"
    // machine has no boot ROM of its own; without firmware handing off to the disk's bootloader,
    // the VM just sits with a black framebuffer forever, which looks identical to "stuck booting".
    // Look for it at filesDir/prism_os/QEMU_EFI.fd — data files (non-executable) are fine in
    // filesDir since QEMU only opens/reads them, it doesn't exec() them.
    const val UEFI_FIRMWARE      = "QEMU_EFI.fd"

    const val VSOCK_CID          = 10
    const val VSOCK_PORT         = 5554
    const val DEFAULT_RAM_MB     = 512
}
