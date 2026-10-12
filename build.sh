#!/bin/sh
# Builds the AntiRevoke APK and the APatch/Magisk module zip.
# JDK and the Android SDK can be specified via JAVA_HOME and ANDROID_SDK / ANDROID_HOME.
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"
if [ ! -d "$JAVA_HOME" ] && [ -d "$HOME/.local/jdk/temurin-17" ]; then
  JAVA_HOME="$HOME/.local/jdk/temurin-17"
fi

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/.local/android-sdk}}"
if [ ! -d "$ANDROID_SDK" ] && [ -d "$HOME/Android/Sdk" ]; then
  ANDROID_SDK="$HOME/Android/Sdk"
fi

BUILD_TOOLS="$ANDROID_SDK/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"
OUT="$ROOT/dist"
WORK="$OUT/work"

if [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "missing JDK at $JAVA_HOME" >&2
  exit 1
fi
if [ ! -x "$BUILD_TOOLS/aapt2" ] || [ ! -f "$ANDROID_JAR" ]; then
  echo "missing Android SDK platform 34 or build-tools 34.0.0 under $ANDROID_SDK" >&2
  exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/gen" "$WORK/dex" "$OUT/module"

echo "xposed stubs"
mkdir -p "$WORK/stubs"
javac --release 11 -encoding UTF-8 -d "$WORK/stubs" $(find "$ROOT/stubs/src" -name '*.java')
jar cf "$WORK/xposed-stubs.jar" -C "$WORK/stubs" .

echo "resources"
aapt2 compile --dir "$ROOT/app/res" -o "$WORK/res.zip"
aapt2 link -o "$WORK/linked.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  --java "$WORK/gen" \
  -R "$WORK/res.zip" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 3 \
  --version-name 0.3 \
  --auto-add-overlay

echo "javac"
find "$ROOT/app/src" "$WORK/gen" -name '*.java' > "$WORK/sources.list"
javac --release 11 -encoding UTF-8 \
  -classpath "$ANDROID_JAR:$WORK/xposed-stubs.jar" \
  -d "$WORK/classes" \
  @"${WORK}/sources.list"

echo "dex"
d8 --min-api 29 --lib "$ANDROID_JAR" --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class')

cp "$WORK/linked.apk" "$WORK/unsigned.apk"
cp "$WORK/dex/classes.dex" "$WORK/classes.dex"
cp "$ROOT/app/assets/xposed_init" "$WORK/xposed_init"
python3 - "$WORK" <<'PY'
import sys, zipfile
work = sys.argv[1]
with zipfile.ZipFile(f"{work}/unsigned.apk", "a") as apk:
    apk.write(f"{work}/classes.dex", "classes.dex")
    apk.write(f"{work}/xposed_init", "assets/xposed_init")
PY

zipalign -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
KEYSTORE_DIR="$OUT/keystore"
KEYSTORE="$KEYSTORE_DIR/wx-debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$KEYSTORE_DIR"
  keytool -genkeypair -keystore "$KEYSTORE" \
    -storepass android -alias wx -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=WX,O=local,C=CN"
fi
apksigner sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$OUT/AntiRevoke.apk" \
  "$WORK/aligned.apk"
apksigner verify "$OUT/AntiRevoke.apk"

echo "module zip"
cp "$ROOT/module/module.prop" "$ROOT/module/customize.sh" "$ROOT/module/boot-completed.sh" \
  "$ROOT/module/action.sh" "$ROOT/module/uninstall.sh" "$ROOT/module/skip_mount" \
  "$OUT/module/"
cp "$OUT/AntiRevoke.apk" "$OUT/module/AntiRevoke.apk"
chmod 755 "$OUT/module/customize.sh" "$OUT/module/boot-completed.sh" \
  "$OUT/module/action.sh" "$OUT/module/uninstall.sh"
rm -f "$OUT/wx-antirevoke-*.zip"
python3 - "$OUT" <<'PY'
import os, sys, zipfile
out = sys.argv[1]
src = os.path.join(out, "module")
with zipfile.ZipFile(os.path.join(out, "wx-antirevoke-0.3.zip"), "w") as z:
    for name in sorted(os.listdir(src)):
        path = os.path.join(src, name)
        info = zipfile.ZipInfo(name)
        mode = 0o755 if name.endswith(".sh") else 0o644
        info.external_attr = mode << 16
        with open(path, "rb") as handle:
            z.writestr(info, handle.read())
PY
echo "built $OUT/AntiRevoke.apk"
echo "built $OUT/wx-antirevoke-0.3.zip"
