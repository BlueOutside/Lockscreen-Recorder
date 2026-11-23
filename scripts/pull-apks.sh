#!/usr/bin/env bash
# Pulls Samsung SmartCapture and SystemUI APKs from a connected rooted device
# into ./apks (gitignored — Samsung binaries must stay local).
#
# Usage: ./scripts/pull-apks.sh [adb-serial]
set -euo pipefail

cd "$(dirname "$0")/.."
SERIAL="${1:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB+=( -s "$SERIAL" )

mkdir -p apks

echo "[*] copying APKs to /data/local/tmp on device..."
"${ADB[@]}" shell "su -c '
  cp /system/app/SmartCapture/SmartCapture.apk /data/local/tmp/SmartCapture.apk 2>/dev/null || true
  cp /system_ext/priv-app/SystemUI/SystemUI.apk /data/local/tmp/SystemUI.apk 2>/dev/null || true
  chmod 644 /data/local/tmp/SmartCapture.apk /data/local/tmp/SystemUI.apk 2>/dev/null || true
'"

echo "[*] pulling..."
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep /data/... paths intact
"${ADB[@]}" pull /data/local/tmp/SmartCapture.apk apks/SmartCapture.apk || echo "[!] SmartCapture pull failed"
"${ADB[@]}" pull /data/local/tmp/SystemUI.apk apks/SystemUI.apk || echo "[!] SystemUI pull failed"

ls -la apks/
