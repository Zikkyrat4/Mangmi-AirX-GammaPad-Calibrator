#!/system/bin/sh
# Fail-closed watchdog: on consecutive startup failures, disable the overlay
# for the NEXT boot. NEVER reboot automatically.
MODDIR=${0%/*}
sleep 40
BAD=0
for i in 1 2 3; do
  if pidof gammapad >/dev/null 2>&1; then
    BAD=0
    break
  fi
  BAD=$((BAD + 1))
  sleep 10
done
if [ "$BAD" -ge 3 ]; then
  touch "$MODDIR/disable"
  echo "GammaPad absent after boot; disabled module for next restart." > "$MODDIR/needs_reboot.txt"
fi
