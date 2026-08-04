#!/usr/bin/env bash
#
# PrismOS build environment setup
# Run once on a Linux machine (Ubuntu 22.04 / Debian 12 recommended).
# Requires ~70 GB free disk space for the full AOSP sync.
#

set -euo pipefail

PRISM_OS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AOSP_DIR="${AOSP_DIR:-$HOME/prism_os_src}"
ANDROID_BRANCH="android-15.0.0_r1"

echo "=== PrismOS Setup ==="
echo "AOSP source  : $AOSP_DIR"
echo "Branch       : $ANDROID_BRANCH"
echo "Estimated disk: ~70 GB"
echo ""

# ── 1. Install build dependencies ─────────────────────────────────────────────
install_deps() {
    echo "[1/4] Installing build dependencies..."
    sudo apt-get update -qq
    sudo apt-get install -y \
        bc bison build-essential ccache curl flex g++-multilib gcc-multilib \
        git gnupg gperf imagemagick lib32ncurses5-dev lib32readline-dev \
        lib32z1-dev libelf-dev liblz4-tool libncurses5-dev libsdl1.2-dev \
        libssl-dev libxml2 libxml2-utils lzop m4 make ninja-build \
        python3 python3-pip repo rsync schedtool squashfs-tools unzip \
        xsltproc zip zlib1g-dev openjdk-17-jdk
    echo "Done."
}

# ── 2. Set up repo tool ───────────────────────────────────────────────────────
setup_repo() {
    echo "[2/4] Setting up repo tool..."
    if ! command -v repo &>/dev/null; then
        mkdir -p ~/.local/bin
        curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo \
            -o ~/.local/bin/repo
        chmod +x ~/.local/bin/repo
        export PATH="$HOME/.local/bin:$PATH"
    fi
    echo "Done."
}

# ── 3. Sync AOSP source ───────────────────────────────────────────────────────
sync_aosp() {
    echo "[3/4] Syncing AOSP ($ANDROID_BRANCH)..."
    mkdir -p "$AOSP_DIR"
    cd "$AOSP_DIR"

    repo init \
        --depth=1 \
        -u https://android.googlesource.com/platform/manifest \
        -b "$ANDROID_BRANCH"

    # Overlay PrismOS-specific project list
    mkdir -p .repo/local_manifests
    cp "$PRISM_OS_DIR/manifest.xml" .repo/local_manifests/prism_os.xml

    # Symlink device + vendor trees
    mkdir -p device/prism vendor/prism packages/apps
    ln -sfn "$PRISM_OS_DIR/device/prism/prism_vm" device/prism/prism_vm
    ln -sfn "$PRISM_OS_DIR/vendor/prism/prism_vm" vendor/prism/prism_vm
    ln -sfn "$PRISM_OS_DIR/packages/apps/PrismGuestReceiver" packages/apps/PrismGuestReceiver

    repo sync -c -j"$(nproc)" --no-tags --force-sync
    echo "Sync complete."
}

# ── 4. Print build instructions ───────────────────────────────────────────────
print_build_instructions() {
    echo ""
    echo "[4/4] Setup complete! To build PrismOS:"
    echo ""
    echo "  cd $AOSP_DIR"
    echo "  source build/envsetup.sh"
    echo "  lunch prism_vm-userdebug"
    echo "  m -j\$(nproc)"
    echo ""
    echo "Output image: $AOSP_DIR/out/target/product/prism_vm/system.img"
    echo "              $AOSP_DIR/out/target/product/prism_vm/userdata.img"
    echo ""
    echo "To package for the Prism launcher, run:"
    echo "  $PRISM_OS_DIR/scripts/package.sh $AOSP_DIR"
}

install_deps
setup_repo
sync_aosp
print_build_instructions
