#!/usr/bin/env bash
#
# package.sh <aosp_out_dir>
#
# Packages a completed PrismOS build into the format expected by VmController:
#
#   prism_os/
#     prism_os.img      — composite ext4 image (system + userdata)
#     vm_config.json    — AVF VirtualMachineConfig descriptor
#     kernel            — Microdroid kernel image (for AVF path)
#
# The output directory is intended to be copied/downloaded into
# Android internal storage: context.filesDir/prism_os/
#

set -euo pipefail

AOSP_OUT="${1:?Usage: package.sh <aosp_out_dir>}"
PRODUCT_OUT="$AOSP_OUT/out/target/product/prism_vm"
PACKAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/dist"

mkdir -p "$PACKAGE_DIR/prism_os"

echo "=== Packaging PrismOS from $PRODUCT_OUT ==="

# ── System image ──────────────────────────────────────────────────────────────
echo "Copying system.img..."
cp "$PRODUCT_OUT/system.img" "$PACKAGE_DIR/prism_os/prism_os.img"

# ── Kernel (Microdroid prebuilt) ──────────────────────────────────────────────
echo "Copying kernel..."
KERNEL="$AOSP_OUT/out/target/product/prism_vm/kernel"
if [ -f "$KERNEL" ]; then
    cp "$KERNEL" "$PACKAGE_DIR/prism_os/kernel"
fi

# ── AVF vm_config.json ────────────────────────────────────────────────────────
echo "Writing vm_config.json..."
cat > "$PACKAGE_DIR/prism_os/vm_config.json" <<'EOF'
{
  "os": {
    "name": "PrismOS"
  },
  "kernel": "kernel",
  "initrd": null,
  "params": "rw console=hvc0 loglevel=7",
  "disks": [
    {
      "image": "prism_os.img",
      "writable": true,
      "partitions": [
        { "label": "system", "path": "prism_os.img" }
      ]
    }
  ],
  "protected": false,
  "cpu_topology": "match_host",
  "platform_version": "~1.0"
}
EOF

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "Package ready at: $PACKAGE_DIR/prism_os/"
du -sh "$PACKAGE_DIR/prism_os/"
echo ""
echo "To deploy to a device:"
echo "  adb push $PACKAGE_DIR/prism_os /data/data/com.prism.launcher/files/prism_os"
