#!/usr/bin/env bash
# Сборка APK вручную, без Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Подходит для машин с малым объёмом ОЗУ и для CI.
# Требует: JDK 17+, Android SDK (build-tools, platforms).
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)

: "${JAVA_HOME:=/usr/lib/jvm/java-21-openjdk-amd64}"
: "${ANDROID_HOME:=$HOME/android-sdk}"
# берём самые свежие установленные build-tools и platform
BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$ANDROID_HOME"/platforms/android-* 2>/dev/null | grep -v ext | sort -V | tail -1)/android.jar
[ -x "$BT/aapt2" ] || { echo "Нужны Android SDK build-tools (sdkmanager 'build-tools;35.0.0')"; exit 1; }
[ -f "$PLATFORM" ] || { echo "Нужна платформа: sdkmanager 'platforms;android-34'"; exit 1; }
echo "build-tools: $BT"
echo "platform:    $PLATFORM"
AAPT2="$BT/aapt2"
D8="$BT/d8"
export PATH="$JAVA_HOME/bin:$PATH"

PKG=by.viberlead.app
# версия читается из app/build.gradle — в скрипте она не дублируется
VC=$(grep -oP 'versionCode\s+\K[0-9]+' app/build.gradle)
VN=$(grep -oP 'versionName\s+"\K[^"]+' app/build.gradle)
[ -n "$VC" ] && [ -n "$VN" ] || { echo "Не удалось прочитать versionCode/versionName из app/build.gradle"; exit 1; }
echo "version: $VN (code $VC)"
SRC=app/src/main/java
RES=app/src/main/res
ASSETS=app/src/main/assets
MANIFEST=app/src/main/AndroidManifest.xml
OUT="$ROOT/build/manual"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "==> 1/6 aapt2 compile"
"$AAPT2" compile --dir "$RES" -o "$OUT/res.zip"

echo "==> 2/6 aapt2 link"
# Gradle-проект задаёт пакет через namespace в build.gradle, а ручной aapt2
# требует атрибут package в манифесте — добавляем его во временную копию.
# Плейсхолдеры ${appName} и ${applicationId} aapt2 не подставит — заменяем сами.
MANIFEST_GEN="$OUT/AndroidManifest.xml"
sed -e 's|<manifest |<manifest package="'"$PKG"'" |' \
    -e 's|\${appName}|Заявки в Viber|g' \
    -e 's|\${applicationId}|'"$PKG"'|g' "$MANIFEST" > "$MANIFEST_GEN"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$MANIFEST_GEN" \
  -A "$ASSETS" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code "$VC" \
  --version-name "$VN" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "==> 3/6 javac"
find "$SRC" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" \
  -source 11 -target 11 \
  -classpath "$PLATFORM" \
  -encoding UTF-8 \
  -nowarn \
  -d "$OUT/obj" \
  @"$OUT/sources.txt"

echo "==> 4/6 d8 (dex)"
find "$OUT/obj" -name '*.class' > "$OUT/classes.txt"
"$D8" --release --min-api 21 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> 5/6 упаковка + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 подпись"
bash "$ROOT/scripts/make-keystore.sh" >/dev/null
"$BT/apksigner" sign \
  --ks "$ROOT/keystore/viberlead.jks" \
  --ks-key-alias viberlead \
  --ks-pass pass:viberlead \
  --key-pass pass:viberlead \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$OUT/viberlead-signed.apk" \
  "$OUT/aligned.apk"

mkdir -p "$ROOT/dist"
cp "$OUT/viberlead-signed.apk" "$ROOT/dist/viberlead-release.apk"
echo
echo "ГОТОВО: $ROOT/dist/viberlead-release.apk"
"$BT/apksigner" verify --print-certs "$ROOT/dist/viberlead-release.apk" | head -6
ls -la "$ROOT/dist"
