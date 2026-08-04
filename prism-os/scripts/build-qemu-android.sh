#!/usr/bin/env bash
#
# Cross-compiles a minimal aarch64-softmmu QEMU for Android (arm64-v8a), built directly
# against the Android NDK toolchain (bionic), not adapted from a Termux package. Run this
# inside WSL2 Ubuntu (or native Linux) -- it does not run on Windows directly; see
# build-qemu-android.ps1 for the Windows-side wrapper.
#
# Link model: third-party dependencies (zlib, libiconv, pixman, glib) are static archives, so
# nothing extra needs bundling into the APK. libc is linked DYNAMICALLY against the device's
# own bionic -- see the long note in build_qemu() for why a static libc cannot work on Android.
#
# Why this exists: an earlier attempt to bundle Termux's own prebuilt qemu-system-aarch64
# package pulled in ~30 direct dependencies (X11, GTK, SDL2, GStreamer, JACK, PulseAudio,
# Spice, USB redirection, curl, libssh, gnutls+gmp+nettle...) meant for a desktop/Termux
# userspace, none of which apply on Android, and repeatedly hit SONAME mismatches, missing
# LD_LIBRARY_PATH, and finally a segfault before main() that survived every environmental
# fix tried (LD_PRELOAD, HOME/TMPDIR, exec-permission relocation, MTE, process lineage).
# Building from source against the NDK sidesteps all of that: the binary is compiled for
# exactly the environment it will run in, and disabling every feature Prism doesn't use
# (host audio, host USB, TLS/VNC-auth, X11 display, etc.) means there's almost nothing
# left to link against besides glib, pixman, zlib, and libiconv.
#
# Prerequisites (Ubuntu/WSL):
#   sudo apt install build-essential python3 python3-pip pipx git pkg-config \
#       autoconf automake libtool bison flex texinfo ninja-build curl xz-utils cmake \
#       qemu-user-static binfmt-support
#   sudo update-binfmts --enable qemu-aarch64
#   pipx ensurepath   # then restart your shell once, so ~/.local/bin is on PATH
#
# The qemu-user-static/binfmt step matters specifically for QEMU's own build (not the
# other dependencies): QEMU's ./configure generates its own internal meson cross-file and,
# on at least this NDK toolchain layout, doesn't reliably set needs_exe_wrapper -- meson's
# compiler sanity check then tries to directly execute the compiled aarch64 test binary on
# this x86_64 build machine and fails ("wrong architecture"). Registering qemu-aarch64 with
# binfmt_misc makes the kernel transparently route foreign-arch execs through user-mode
# QEMU, so the sanity check (and anything else that tries to run a target binary during the
# build) just works without needing to fight QEMU's own cross-file generation logic.
#
# You also need an Android NDK. If you have Android Studio installed on the Windows side,
# reuse it instead of downloading a second copy -- see ANDROID_NDK_HOME below.
#
# Usage:
#   export ANDROID_NDK_HOME=/mnt/c/Users/<you>/AppData/Local/Android/Sdk/ndk/<version>
#   ./build-qemu-android.sh
#
# Output: out/libqemu-system-aarch64.so, ready to drop into
#   app/src/main/jniLibs/arm64-v8a/

set -euo pipefail

# ── Versions ─────────────────────────────────────────────────────────────────
# Bump these independently if a newer release is available; each is fetched fresh,
# nothing here is pinned to what happened to be current when this script was written.
ZLIB_VERSION="1.3.1"
LIBICONV_VERSION="1.17"
PIXMAN_VERSION="0.43.4"
GLIB_VERSION="2.80.0"
QEMU_VERSION="9.1.0"

# Deliberately low, and deliberately NOT raised to match any one test device. This binary should
# run on any arm64 Android phone, so it targets the oldest API worth supporting rather than the
# newest one convenient to develop against.
#
# The cost of that choice is real and shows up below: bionic hides a handful of functions behind
# __INTRODUCED_IN() gates above this level (close_range, getrandom, copy_file_range, memfd_create),
# so QEMU's probes have to be corrected and its own portable fallbacks used instead. Those
# fallbacks are plain syscall wrappers, so runtime behaviour is identical -- the only thing given
# up is the convenience of letting libc provide them.
API_LEVEL=26
TARGET_TRIPLE="aarch64-linux-android"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="${WORK_DIR:-$SCRIPT_DIR/qemu-android-build}"
SYSROOT="$WORK_DIR/sysroot"
SRC_DIR="$WORK_DIR/src"
OUT_DIR="$SCRIPT_DIR/out"
MARKERS="$WORK_DIR/.markers"

mkdir -p "$WORK_DIR" "$SYSROOT" "$SRC_DIR" "$OUT_DIR" "$MARKERS"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARNING: %s\033[0m\n' "$*" >&2; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# Bumped whenever the build recipe changes in a way that invalidates artifacts already on disk.
# Markers now record the recipe version they were produced under and a step is only skipped if
# they match, so editing this script re-runs the affected steps instead of silently reusing a
# stale binary. The previous "touch a file" markers meant that clearing build/ still reported
# "QEMU already built" and then failed to find a binary -- an easy hour to lose.
#
#   1 -- original, static-PIE build
#   2 -- dynamically-linked PIE (dropped --static; see build_qemu)
BUILD_RECIPE_VERSION="2"

done_marker() {
    [ -f "$MARKERS/$1" ] && [ "$(cat "$MARKERS/$1" 2>/dev/null)" = "$BUILD_RECIPE_VERSION" ]
}
mark_done() { printf '%s' "$BUILD_RECIPE_VERSION" > "$MARKERS/$1"; }

# ── Sanity checks ────────────────────────────────────────────────────────────
log "Checking prerequisites"

for tool in git python3 pkg-config autoconf automake libtoolize bison flex ninja curl cmake; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool not found. Run: sudo apt install build-essential python3 pipx git pkg-config autoconf automake libtool bison flex texinfo ninja-build curl xz-utils cmake"
done

# QEMU's own configure needs to actually execute compiled aarch64 test binaries on this
# x86_64 build machine during its meson sanity check -- without binfmt_misc registered for
# qemu-aarch64, that fails with "wrong architecture" partway through QEMU's configure step,
# after everything else has already built. Check for it up front instead.
if ! grep -q "^enabled" /proc/sys/fs/binfmt_misc/qemu-aarch64 2>/dev/null; then
    die "binfmt_misc isn't registered for aarch64, which QEMU's own configure step needs. Run:
  sudo apt install -y qemu-user-static binfmt-support
  sudo update-binfmts --enable qemu-aarch64"
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    die "ANDROID_NDK_HOME is not set. Point it at your NDK install, e.g.:
  export ANDROID_NDK_HOME=/mnt/c/Users/<you>/AppData/Local/Android/Sdk/ndk/<version>
(reuse the copy Android Studio already downloaded rather than fetching a second one)"
fi
[ -d "$ANDROID_NDK_HOME" ] || die "ANDROID_NDK_HOME ($ANDROID_NDK_HOME) does not exist -- check the path"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || die "Expected NDK toolchain not found at $TOOLCHAIN -- is ANDROID_NDK_HOME correct?"

# The NDK's clang wrapper scripts (e.g. aarch64-linux-android26-clang) shell out to the
# real versioned compiler (clang-18, etc.) by bare name rather than resolving it relative
# to their own location -- that lookup only succeeds if the toolchain's bin/ is on PATH.
# We call $CC/$CXX directly by absolute path ourselves, but CMake/autotools/meson invoke
# these wrapper scripts as subprocesses during their own compiler tests, so PATH needs it too.
export PATH="$TOOLCHAIN/bin:$PATH"

# Android Studio's SDK Manager downloads and extracts the NDK on the Windows side, where
# creating real NTFS symlinks needs elevated privileges it doesn't have -- so the NDK
# archive's symlinks (bin/clang -> clang-18, bin/clang++ -> clang++-18, etc.) get silently
# flattened into plain text files containing just the target's bare filename instead. A
# shell "executing" one of those runs that bare word as a command with zero arguments,
# which is why bin/clang looked like it was finding clang-18 but dropping every argument
# (including the input file) -- it's not a script, there's no argument-forwarding logic
# in it at all. Detect and repair any of these before they cause a confusing failure
# somewhere downstream (CMake, meson, or autotools all shell out to bare tool names).
repair_ndk_pseudo_symlinks() {
    local dir="$TOOLCHAIN/bin" fixed=0
    for f in "$dir"/*; do
        [ -f "$f" ] || continue
        local head4
        head4="$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')"
        [ "$head4" = "7f454c46" ] && continue          # real ELF binary, fine
        [ "$(head -c 2 "$f" 2>/dev/null)" = "#!" ] && continue   # real shebang script, fine

        local size content target_path
        size="$(stat -c%s "$f" 2>/dev/null || echo 0)"
        if [ "$size" -gt 0 ] && [ "$size" -lt 100 ]; then
            content="$(tr -d '\n\r' < "$f")"
            target_path="$dir/$content"
            if [ -n "$content" ] && [ -f "$target_path" ] && [ "$target_path" != "$f" ]; then
                rm -f "$f"
                ln -s "$content" "$f"
                fixed=$((fixed + 1))
            fi
        fi
    done
    if [ "$fixed" -gt 0 ]; then
        log "Repaired $fixed NDK symlink(s) that Windows extraction had flattened into plain text files"
    fi
}
repair_ndk_pseudo_symlinks

export CC="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API_LEVEL}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API_LEVEL}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export NM="$TOOLCHAIN/bin/llvm-nm"
export PKG_CONFIG_PATH="$SYSROOT/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$SYSROOT/lib/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR=""

# Force pkg-config to emit Libs.private even though the final link is no longer fully static.
#
# Every dependency in $SYSROOT is a static archive (.a), and a static archive carries none of its
# own transitive dependencies -- linking libglib-2.0.a also requires -lintl, -liconv, -lz and
# friends, which pkg-config only reports with --static. QEMU's configure used to pass that flag
# itself as a side effect of --static; now that libc is linked dynamically it no longer does, so
# it has to be set here or glib's link fails with a wall of undefined symbols that look like a
# missing library rather than a missing flag.
export PKG_CONFIG="pkg-config --static"
export CFLAGS="-I$SYSROOT/include -fPIC"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-L$SYSROOT/lib"

for exe in "$CC" "$CXX" "$AR" "$RANLIB" "$STRIP"; do
    [ -x "$exe" ] || die "Expected NDK tool missing/not executable: $exe"
done
log "NDK toolchain OK: $CC"

# ── Meson: this is the actual thing that killed the last attempt ───────────────
# WSL Ubuntu's apt-provided meson is version-locked to whatever shipped with that
# Ubuntu release and never gets feature updates (apt only backports security fixes).
# pixman/glib/qemu all need a meson newer than that. Installing via pipx pulls from
# PyPI directly, completely independent of Ubuntu's package freeze.
log "Setting up a current meson via pipx (not apt)"

if ! command -v pipx >/dev/null 2>&1; then
    die "pipx not found. Run: sudo apt install pipx && pipx ensurepath (then restart your shell)"
fi

pipx install --force meson >/dev/null 2>&1 || pipx upgrade meson || true
MESON="$(command -v meson || echo "$HOME/.local/bin/meson")"
[ -x "$MESON" ] || die "meson still not found after pipx install -- check 'pipx ensurepath' was run and your shell restarted"

pipx install --force ninja >/dev/null 2>&1 || pipx upgrade ninja || true

MESON_VERSION="$("$MESON" --version)"
log "Using meson $MESON_VERSION at $MESON"

write_cross_file() {
    local cross_file="$WORK_DIR/android-aarch64-cross.ini"
    cat > "$cross_file" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
ranlib = '$RANLIB'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true
EOF
    echo "$cross_file"
}
CROSS_FILE="$(write_cross_file)"

fetch_and_extract() {
    local url="$1" out_name="$2"
    local dest="$SRC_DIR/$out_name"
    if [ -d "$dest" ]; then
        log "Source already present: $out_name"
        return
    fi
    log "Fetching $out_name"
    local archive="$SRC_DIR/$(basename "$url")"
    curl -L --fail -o "$archive" "$url"
    mkdir -p "$dest"
    tar -xf "$archive" -C "$dest" --strip-components=1
}

# ── zlib ─────────────────────────────────────────────────────────────────────
build_zlib() {
    done_marker zlib && { log "zlib already built"; return; }
    # zlib.net only serves the current release at its top-level URL and moves older
    # ones to a /fossils/ path -- GitHub's release tags stay put permanently instead.
    fetch_and_extract "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" "zlib-${ZLIB_VERSION}"
    log "Building zlib $ZLIB_VERSION"
    command -v cmake >/dev/null 2>&1 || die "cmake not found. Run: sudo apt install cmake"
    (
        cd "$SRC_DIR/zlib-${ZLIB_VERSION}"
        # zlib's own ./configure is a hand-rolled script (not autoconf) that treats ANY
        # stderr output from its test compile -- even a harmless clang note -- as if
        # -Werror were set, and refuses to proceed ("too harsh"). NDK clang is more
        # talkative than the compilers it was written against, so it trips this every
        # time. Building via CMake with the NDK's own toolchain file sidesteps the
        # whole fragile check and is the NDK-recommended way to cross-compile anyway.
        # The NDK's own android.toolchain.cmake computes its compiler path from
        # ANDROID_ABI/ANDROID_PLATFORM via `set(... CACHE ... FORCE)`, which overrides even
        # command-line -DCMAKE_C_COMPILER -- so forcing it explicitly had no effect, and
        # whatever it lands on here drops the --target flag entirely. Using CMake's own
        # native, built-in Android support instead (no NDK-supplied toolchain file at all)
        # resolves the compiler through CMake's own internal logic, sidestepping that file.
        cmake -B build -G "Unix Makefiles" \
            -DCMAKE_SYSTEM_NAME=Android \
            -DCMAKE_SYSTEM_VERSION="${API_LEVEL}" \
            -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
            -DCMAKE_ANDROID_NDK="$ANDROID_NDK_HOME" \
            -DCMAKE_INSTALL_PREFIX="$SYSROOT" \
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
            -DBUILD_SHARED_LIBS=OFF
        cmake --build build -j"$(nproc)"
        cmake --install build
        # zlib's CMakeLists builds a shared libz.so alongside the static one regardless
        # of BUILD_SHARED_LIBS on some versions -- remove it so nothing downstream can
        # accidentally link against it instead of the static archive.
        rm -f "$SYSROOT"/lib/libz.so*
    )
    mark_done zlib
}

# ── GNU libiconv ─────────────────────────────────────────────────────────────
# bionic's libc has no real iconv() -- glib needs one for text encoding conversion,
# so this has to be built and linked in explicitly (glib's meson build is told about
# it via -Diconv=external below).
build_libiconv() {
    done_marker libiconv && { log "libiconv already built"; return; }
    fetch_and_extract "https://ftp.gnu.org/pub/gnu/libiconv/libiconv-${LIBICONV_VERSION}.tar.gz" "libiconv-${LIBICONV_VERSION}"
    log "Building GNU libiconv $LIBICONV_VERSION"
    (
        cd "$SRC_DIR/libiconv-${LIBICONV_VERSION}"
        ./configure --host="${TARGET_TRIPLE}" --prefix="$SYSROOT" \
            --enable-static --disable-shared
        make -j"$(nproc)"
        make install
    )
    mark_done libiconv
}

# ── pixman ───────────────────────────────────────────────────────────────────
build_pixman() {
    done_marker pixman && { log "pixman already built"; return; }
    fetch_and_extract "https://cairographics.org/releases/pixman-${PIXMAN_VERSION}.tar.gz" "pixman-${PIXMAN_VERSION}"
    log "Building pixman $PIXMAN_VERSION"
    (
        cd "$SRC_DIR/pixman-${PIXMAN_VERSION}"
        "$MESON" setup build --cross-file="$CROSS_FILE" --prefix="$SYSROOT" \
            --default-library=static --buildtype=release \
            -Dtests=disabled -Ddemos=disabled
        ninja -C build install
    )
    mark_done pixman
}

# ── glib ─────────────────────────────────────────────────────────────────────
build_glib() {
    done_marker glib && { log "glib already built"; return; }
    local minor major
    major="$(echo "$GLIB_VERSION" | cut -d. -f1,2)"
    fetch_and_extract "https://download.gnome.org/sources/glib/${major}/glib-${GLIB_VERSION}.tar.xz" "glib-${GLIB_VERSION}"
    log "Building glib $GLIB_VERSION"
    (
        cd "$SRC_DIR/glib-${GLIB_VERSION}"
        # No explicit -Diconv= override: that option's name/presence has changed across
        # glib versions and guessing wrong just trades one meson error for another. We
        # already built libiconv into $SYSROOT with CFLAGS/LDFLAGS/PKG_CONFIG_PATH pointing
        # there, so glib's own automatic iconv detection should find it unaided -- bionic's
        # libc iconv check will fail first (it has no real iconv), then it falls back to
        # searching for exactly the external GNU libiconv we already installed.
        "$MESON" setup build --cross-file="$CROSS_FILE" --prefix="$SYSROOT" \
            --default-library=static --buildtype=release \
            -Dnls=disabled -Dtests=false -Dlibmount=disabled \
            -Dselinux=disabled -Ddtrace=false -Dsystemtap=false \
            -Dman=false -Dman-pages=disabled -Dgtk_doc=false
        ninja -C build install
    )
    mark_done glib
}

# ── QEMU ─────────────────────────────────────────────────────────────────────
build_qemu() {
    done_marker qemu && { log "QEMU already built"; return; }
    fetch_and_extract "https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz" "qemu-${QEMU_VERSION}"

    # bionic never implemented POSIX shm_open()/shm_unlink() at all (unlike close_range/
    # getrandom/copy_file_range, this isn't a hidden-behind-an-API-level false positive --
    # bionic's own posix_limits.h says so outright, and the symbols aren't in libc.so either).
    # backends/meson.build only gates hostmem-shm.c on `host_os != 'windows'`, with no feature
    # flag to disable it, and we don't need this backend (external-process shared-memory-backed
    # guest RAM -- same unneeded category as the vhost-user backends already disabled above).
    # Patched at the source level since there's no configure flag for it; idempotent so re-running
    # this script against an already-patched checkout is a no-op.
    local backends_meson="$SRC_DIR/qemu-${QEMU_VERSION}/backends/meson.build"
    if grep -q "hostmem-shm.c" "$backends_meson"; then
        sed -i "/hostmem-shm\.c/d" "$backends_meson"
        log "Patched out hostmem-shm.c (bionic has no shm_open/shm_unlink)"
    fi

    # util/memfd.c must be left ALONE now, and this block exists to undo an earlier patch that
    # is actively harmful under dynamic linking.
    #
    # The history: memfd_create() is __INTRODUCED_IN(30) in the NDK headers, so at API 26 the
    # declaration is hidden and meson's CONFIG_MEMFD probe fails -- which makes QEMU compile its
    # own syscall()-based fallback. Under the old --static build that fallback collided with the
    # real symbol sitting in the NDK's unified libc.a ("duplicate symbol"), so the script patched
    # memfd.c to skip the fallback and just declare the libc one.
    #
    # Linking dynamically inverts that completely. The NDK's *stub* libc.so for API 26 does not
    # export memfd_create at all, so declaring it produces an undefined symbol at link time. The
    # fallback is now both correct and required -- and it is the more portable answer anyway,
    # since __NR_memfd_create has existed since Linux 3.17 and therefore works on every Android
    # device this binary could plausibly run on, not just ones on API 30+.
    #
    # Written as a revert rather than "don't apply", because working trees from earlier runs of
    # this script are already patched and are not re-extracted.
    local memfd_c="$SRC_DIR/qemu-${QEMU_VERSION}/util/memfd.c"
    if grep -q "__BIONIC__" "$memfd_c"; then
        python3 - "$memfd_c" <<'PYEOF'
import sys
path = sys.argv[1]
patched = """#if defined CONFIG_LINUX && !defined CONFIG_MEMFD && !defined __BIONIC__
#include <sys/syscall.h>
#include <asm/unistd.h>

int memfd_create(const char *name, unsigned int flags)
{
#ifdef __NR_memfd_create
    return syscall(__NR_memfd_create, name, flags);
#else
    errno = ENOSYS;
    return -1;
#endif
}
#elif defined CONFIG_LINUX && !defined CONFIG_MEMFD && defined __BIONIC__
/* bionic's libc.a has always shipped a real memfd_create() symbol, but the NDK's
 * <sys/mman.h> only declares it when targeting API 30+ (__INTRODUCED_IN), and we
 * build against a lower API level for device compatibility. The symbol is present
 * and linkable, just undeclared -- declare it ourselves instead of using QEMU's
 * syscall() fallback above, which would define a second, colliding memfd_create
 * symbol at link time against the one already in libc.a. */
extern int memfd_create(const char *name, unsigned int flags);
#endif"""
original = """#if defined CONFIG_LINUX && !defined CONFIG_MEMFD
#include <sys/syscall.h>
#include <asm/unistd.h>

int memfd_create(const char *name, unsigned int flags)
{
#ifdef __NR_memfd_create
    return syscall(__NR_memfd_create, name, flags);
#else
    errno = ENOSYS;
    return -1;
#endif
}
#endif"""
text = open(path).read()
if patched not in text:
    sys.exit("memfd.c revert: patched block not found, leaving file alone")
open(path, "w").write(text.replace(patched, original, 1))
PYEOF
        log "Reverted util/memfd.c to upstream (dynamic libc has no memfd_create at API $API_LEVEL)"
    fi

    # ── Why there is no --static here ────────────────────────────────────────
    # There used to be. It produced a static-PIE (ELF type DYN, no PT_INTERP), and on Android
    # that is the least-supported startup path there is: with no interpreter, the binary has to
    # relocate itself from _start before libc initializes, using the load bias worked out from
    # the auxiliary vector. That handoff did not survive, and the binary died before main() with
    #
    #   page_size()                 bionic/libc/platform/bionic/page.h:30
    #   __allocate_temp_bionic_tls  bionic/libc/bionic/pthread_create.cpp:75
    #   __libc_init_main_thread_late __libc_init_main_thread.cpp:120
    #   __real_libc_init            bionic/libc/bionic/libc_init_static.cpp:408
    #
    # -- entirely inside bionic's own static startup, before a single line of QEMU ran, which is
    # why there was never any output to go on. A normal dynamically-linked PIE hands all of that
    # to /system/bin/linker64, which is the path every other native binary on the device uses.
    #
    # Dropping --static does NOT mean shipping glib/pixman/zlib as shared objects. Those stay
    # static archives in $SYSROOT exactly as before; --static additionally forced a static libc,
    # and that was the only part causing this.
    log "Configuring QEMU $QEMU_VERSION (aarch64-softmmu only, minimal feature set)"
    (
        cd "$SRC_DIR/qemu-${QEMU_VERSION}"
        ./configure \
            --cross-prefix= \
            --host-cc=cc \
            --cc="$CC" --cxx="$CXX" \
            --cpu=aarch64 \
            --target-list=aarch64-softmmu \
            --prefix="$OUT_DIR" \
            --extra-cflags="-I$SYSROOT/include" \
            --extra-ldflags="-L$SYSROOT/lib" \
            --enable-debug-info \
            --disable-tools --disable-guest-agent --disable-docs \
            --disable-sdl --disable-gtk --disable-vte --disable-curses --disable-cocoa \
            --disable-opengl --disable-virglrenderer \
            --disable-spice --disable-usb-redir --disable-libusb \
            --disable-alsa --disable-pa --disable-jack --disable-oss --disable-sndio \
            --disable-curl --disable-libssh --disable-libnfs \
            --disable-bzip2 --disable-lzo --disable-snappy \
            --disable-gnutls --disable-nettle --disable-gcrypt \
            --disable-capstone \
            --disable-slirp \
            --disable-brlapi --disable-numa --disable-rdma \
            --disable-xen --disable-vde --disable-netmap --disable-smartcard \
            --disable-selinux \
            --disable-virtfs --disable-virtfs-proxy-helper \
            --disable-vhost-crypto --disable-vhost-kernel --disable-vhost-net \
            --disable-vhost-user --disable-vhost-user-blk-server --disable-vhost-vdpa \
            --enable-vnc --disable-vnc-sasl --disable-vnc-jpeg \
            --enable-fdt=internal \
            || { tail -100 config.log 2>/dev/null; die "QEMU configure failed -- see config.log above"; }

        # Meson's cc.has_function() probe (meson.build) doesn't compile with the same
        # -Wimplicit-function-declaration=error flags the real build uses, and with
        # needs_exe_wrapper=true it never actually runs/links the probe binary -- so it
        # reports functions as available even when bionic's libc.a for our API level
        # doesn't declare or export them. close_range() was only added to bionic in a much
        # later API level than 26, so strip the resulting false-positive define; the code
        # already has a portable fallback path guarded by #ifdef CONFIG_CLOSE_RANGE.
        # If a similar "undeclared function" error shows up for a different symbol later,
        # the fix is the same: add another line here removing that CONFIG_* define.
        sed -i '/#define CONFIG_CLOSE_RANGE/d' build/config-host.h
        sed -i '/#define CONFIG_GETRANDOM/d' build/config-host.h
        # HAVE_COPY_FILE_RANGE: same false-positive detection, gated to API 34 in bionic's
        # unistd.h. Here QEMU's own fallback (block/file-posix.c) is even better than a plain
        # workaround -- it's a real syscall(__NR_copy_file_range, ...) wrapper, so behavior at
        # runtime is identical to the libc-provided version, not just a compile-time stub.
        sed -i '/#define HAVE_COPY_FILE_RANGE/d' build/config-host.h

        # Build only the actual emulator binary, not the default "all" target. QEMU's ninja
        # build also compiles its qtest/unit-test harnesses and host tools by default, and
        # those pull in source files that were never meant to be cross-compiled for Android
        # (e.g. tests/qtest/libqos/virtio-9p-client.c drags in fsdev/9p-marshal.h, which
        # collides with bionic's st_*time_nsec macros -- a bug in test-only code we don't
        # need, not in the emulator itself). Scoping to this one target's dependency graph
        # skips that whole class of irrelevant-subsystem build breaks entirely.
        make -j"$(nproc)" qemu-system-aarch64
    )
    mark_done qemu
}

build_zlib
build_libiconv
build_pixman
build_glib
build_qemu

# ── Package the result ──────────────────────────────────────────────────────
log "Packaging output"
BUILT_BIN="$SRC_DIR/qemu-${QEMU_VERSION}/build/qemu-system-aarch64"
[ -f "$BUILT_BIN" ] || BUILT_BIN="$SRC_DIR/qemu-${QEMU_VERSION}/qemu-system-aarch64"
[ -f "$BUILT_BIN" ] || die "Couldn't find the built qemu-system-aarch64 binary -- check the build output above"

FINAL="$OUT_DIR/libqemu-system-aarch64.so"
cp "$BUILT_BIN" "$FINAL"
# Stripping is OFF while we're actively debugging the runtime segfault -- --strip-unneeded also
# removes the dynamic symbol table, which is why gdb showed a bare "?? ()" with no module name at
# all instead of even a raw "<libqemu-system-aarch64.so+0x...>". Combined with --enable-debug-info
# above, the binary now carries full DWARF debug info too, so gdb should resolve real function
# names/source lines at the crash site. Re-enable stripping (uncomment the line below) once the
# crash is fixed and this is heading toward a release build -- the unstripped binary with debug
# info is considerably larger.
# "$STRIP" --strip-unneeded "$FINAL" || warn "strip failed (non-fatal, binary will just be larger)"

# ── Verify the link model ───────────────────────────────────────────────────
# This check exists because a static-PIE looks completely healthy right up until it is executed,
# and then dies inside bionic's own startup with no output at all. Everything below is cheap and
# catches that class of failure at build time instead of on the device.
log "Verifying link model"

command -v readelf >/dev/null 2>&1 || die "readelf not found (apt install binutils) -- needed to verify the binary is not a static-PIE"

ELF_TYPE="$(readelf -h "$FINAL" 2>/dev/null | awk -F: '/^ *Type:/ {gsub(/^ +| +$/,"",$2); print $2}' || true)"
HAS_INTERP="$(readelf -l "$FINAL" 2>/dev/null | grep -c 'INTERP' || true)"
NEEDED_LIBS="$(readelf -d "$FINAL" 2>/dev/null | grep 'NEEDED' || true)"
# Guard against an empty capture turning the numeric test below into a fatal syntax error.
HAS_INTERP="${HAS_INTERP:-0}"
ELF_TYPE="${ELF_TYPE:-unknown}"

echo "  ELF type:     $ELF_TYPE"
echo "  PT_INTERP:    $([ "$HAS_INTERP" -gt 0 ] && echo present || echo MISSING)"
echo "  NEEDED:"
if [ -n "$NEEDED_LIBS" ]; then
    echo "$NEEDED_LIBS" | sed 's/^/    /'
else
    echo "    (none)"
fi

if [ "$HAS_INTERP" -eq 0 ]; then
    die "This binary has no PT_INTERP segment, which means it is a static-PIE.

Android has no working startup path for that: with no interpreter there is no dynamic linker,
so the binary must relocate itself before libc initializes, and bionic dies during
__libc_init_main_thread_late() before main() is ever reached -- with no output to diagnose it by.

Something re-introduced static libc linking. Check that neither --static nor -static is being
passed to QEMU's configure or in --extra-ldflags."
fi

if [ -z "$NEEDED_LIBS" ]; then
    warn "PT_INTERP is present but there are no NEEDED entries, which is contradictory.
Inspect the binary before trusting it."
fi

echo
log "Done: $FINAL"
echo "Next steps:"
echo "  1. Copy $FINAL to app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so"
echo "  2. Rebuild and reinstall the app"
echo "  3. The NEEDED list above should contain only bionic's own libraries (libc.so, libm.so,"
echo "     libdl.so and friends), which every Android device already provides -- nothing to"
echo "     bundle. If anything else appears there, that dependency failed to link statically"
echo "     and needs bundling into jniLibs plus a fix_sonames.py pass."
