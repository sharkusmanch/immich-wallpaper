#!/usr/bin/env bash
# One-command phone update: builds the debug APK and installs it in place on the
# connected phone. Same signature -> settings, cycles and the photo cache all
# survive, and the system rebinds the wallpaper automatically after the update.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
./gradlew assembleDebug -q
APK=app/build/outputs/apk/debug/app-debug.apk

# Physical devices only — updating the emulator by accident helps no one.
SERIAL=$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
if [ -z "$SERIAL" ]; then
    echo "No phone connected (enable USB debugging and plug it in; emulators are ignored)." >&2
    adb devices
    exit 1
fi

echo "Installing on $SERIAL…"
adb -s "$SERIAL" install -r "$APK"
echo "Done. If the wallpaper was cleared by the update, open the app → Re-apply wallpaper."
