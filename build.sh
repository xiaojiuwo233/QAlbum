#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

ANDROID_STUDIO_JBR="${ANDROID_STUDIO_JBR:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

usage() {
  cat <<'EOF'
Usage:
  ./build.sh [command] [gradle args...]

Commands:
  debug      Build debug APK. This is the default.
  release    Build release APK with the current Gradle signing config.
  install    Build and install debug APK to a connected device.
  rebuild    Clean, then build debug APK.
  clean      Run Gradle clean.
  lint       Run Android lint for the app module.
  test       Run debug unit tests.
  help       Show this help.

Examples:
  ./build.sh
  ./build.sh rebuild
  ./build.sh install
  ./build.sh debug --info
EOF
}

java_major() {
  local java_bin="$1"
  local version

  version="$("$java_bin" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
  if [[ "$version" == 1.* ]]; then
    awk -F '.' '{ print $2 }' <<<"$version"
  else
    awk -F '.' '{ print $1 }' <<<"$version"
  fi
}

select_java_home() {
  local java_bin=""
  local major=""

  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    java_bin="$JAVA_HOME/bin/java"
  else
    java_bin="$(command -v java || true)"
  fi

  if [[ -n "$java_bin" ]]; then
    major="$(java_major "$java_bin" || true)"
  fi

  if [[ -z "${JAVA_HOME:-}" && -x "$ANDROID_STUDIO_JBR/bin/java" ]]; then
    export JAVA_HOME="$ANDROID_STUDIO_JBR"
    return
  fi

  if [[ -n "$major" && "$major" -ge 25 && -x "$ANDROID_STUDIO_JBR/bin/java" ]]; then
    export JAVA_HOME="$ANDROID_STUDIO_JBR"
  fi
}

COMMAND="${1:-debug}"
if [[ $# -gt 0 ]]; then
  shift
fi

case "$COMMAND" in
  debug)
    TASKS=(assembleDebug)
    ;;
  release)
    TASKS=(assembleRelease)
    ;;
  install)
    TASKS=(installDebug)
    ;;
  rebuild)
    TASKS=(clean assembleDebug)
    ;;
  clean)
    TASKS=(clean)
    ;;
  lint)
    TASKS=(:app:lint)
    ;;
  test)
    TASKS=(testDebugUnitTest)
    ;;
  help|-h|--help)
    usage
    exit 0
    ;;
  *)
    TASKS=("$COMMAND")
    ;;
esac

select_java_home

if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "[build] JAVA_HOME=$JAVA_HOME"
fi

echo "[build] Running: ./gradlew ${TASKS[*]} $*"
./gradlew "${TASKS[@]}" "$@"

DEBUG_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"

if [[ " ${TASKS[*]} " == *" assembleDebug "* && -f "$DEBUG_APK" ]]; then
  echo "[build] Debug APK: $DEBUG_APK"
fi

if [[ " ${TASKS[*]} " == *" assembleRelease "* && -f "$RELEASE_APK" ]]; then
  echo "[build] Release APK: $RELEASE_APK"
fi
