#!/system/bin/sh
# This script is sourced by Magisk's installer, after file extraction.
ui_print "- Checking exact Mangmi Air X / GammaOS Next 1.2 build"
[ "$(getprop ro.lineage.version)" = "GammaOS_Next_v1.2" ] || abort "Unsupported ROM. Must be GammaOS_Next_v1.2."
[ "$(getprop ro.product.cpu.abi)" = "arm64-v8a" ] || abort "ARM64 hardware required."
grep -q 'Name="AIR X Controller"' /proc/bus/input/devices || abort "AIR X Controller not found."
[ -f /data/GammaPad/RANGE_CALIBRATION ] || abort "Calibration file absent. Run calibrate-airx.ps1 first."
[ -f /data/GammaPad/CALIBRATION_DATA ] || abort "Stock CALIBRATION_DATA absent. Restore it first."
EXPECTED=55d1bb0e0ccd1609cc3906d3a0cbf615d6c279f003817ffe8a3fd947c1a80b23
ACTUAL=$(sha256sum /vendor/bin/gammapad | cut -d ' ' -f1)
[ "$EXPECTED" = "$ACTUAL" ] || abort "Different GammaPad binary ($ACTUAL). No changes applied."
[ -f "$MODPATH/system/vendor/bin/gammapad" ] || abort "Missing patched gammapad."
[ -f "$MODPATH/system/vendor/lib64/gpad.so" ] || abort "Missing ARM64 correction shim."
chmod 755 "$MODPATH/system/vendor/bin/gammapad"
chmod 644 "$MODPATH/system/vendor/lib64/gpad.so"
ui_print "- Installed an isolated overlay; stock vendor partition stays unchanged."
ui_print "- Reboot to activate. Undo via Magisk Modules or ADB disable."
