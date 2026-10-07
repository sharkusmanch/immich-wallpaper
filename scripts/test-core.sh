#!/bin/sh
# Runs the pure-Kotlin :core tests with only a JDK (no Android SDK, works on Alpine/musl).
# Configure-on-demand keeps Gradle from configuring :app, which needs the SDK.
set -eu
cd "$(dirname "$0")/.."
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/share/mise/installs/java/zulu-musl-21* 2>/dev/null | head -1)}"
: "${GRADLE_USER_HOME:=/tmp/gradle-home}"
export JAVA_HOME GRADLE_USER_HOME
exec ./gradlew --no-daemon --console=plain -Dorg.gradle.configureondemand=true :core:test "$@"
