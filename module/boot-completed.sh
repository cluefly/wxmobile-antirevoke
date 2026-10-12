#!/system/bin/sh
MODDIR=${0%/*}
APK="$MODDIR/AntiRevoke.apk"
LOGDIR=/data/adb/wx_antirevoke
LOG="$LOGDIR/install.log"
mkdir -p "$LOGDIR"
if [ ! -f "$APK" ]; then
  echo "missing apk" >> "$LOG"
  exit 0
fi
pm install -r -g "$APK" >> "$LOG" 2>&1
