#!/usr/bin/env bash
# Build Glass Home and install it on a Chromecast with Google TV (or any Android TV).
#
#   scripts/install.sh <tv-ip>[:port]     build, install, open (port: 5555, or the
#                                         one Settings shows under Wireless debugging)
#   scripts/install.sh <tv-ip> --home     ...and make it what the Home button opens
#   scripts/install.sh <tv-ip> --restore  give the Home button back to Google TV
#   scripts/install.sh <tv-ip> --youtube-button
#                                         the remote's YouTube button opens your
#                                         YouTube app (e.g. TizenTube) instead
#
# On the TV first: Settings > System > About, press OK on "Android TV OS build"
# seven times; then Settings > System > Developer options > USB debugging on.
# The first `adb connect` shows an "Allow debugging?" prompt on the TV.
set -euo pipefail

cd "$(dirname "$0")/.."
TV="${1:?usage: scripts/install.sh <tv-ip> [--home|--restore]}"
MODE="${2:-}"
PKG=com.glasshome.tv
HOME_ROLE=android.app.role.HOME

# Google TV's home screen and its setup app (which takes over Home if it's the
# only other home app left), and the older Android TV home screen.
GOOGLE_TV_HOME=com.google.android.apps.tv.launcherx
STOCK_HOMES=("$GOOGLE_TV_HOME" com.google.android.tungsten.setupwraith com.google.android.tvlauncher)

[[ "$TV" == *:* ]] || TV="$TV:5555"
out=$(adb connect "$TV")
if ! grep -qE "connected to|already connected" <<<"$out"; then
  echo "Can't reach the TV at $TV: $out" >&2
  echo "Check the IP address, that USB debugging is on, and accept the prompt on the TV." >&2
  exit 1
fi
ADB=(adb -s "$TV")
"${ADB[@]}" wait-for-device

installed() { "${ADB[@]}" shell pm list packages "$1" | grep -qx "package:$1"; }

if [[ "$MODE" == "--restore" ]]; then
  for p in "${STOCK_HOMES[@]}"; do
    installed "$p" && "${ADB[@]}" shell pm enable --user 0 "$p" >/dev/null
  done
  # Re-enabling isn't enough: Glass Home still holds the Home role until it's handed back.
  for p in "$GOOGLE_TV_HOME" com.google.android.tvlauncher; do
    if installed "$p"; then
      "${ADB[@]}" shell cmd role add-role-holder "$HOME_ROLE" "$p" 0
      break
    fi
  done
  "${ADB[@]}" shell input keyevent KEYCODE_HOME
  echo "The stock home screen is back on the Home button."
  exit 0
fi

if [[ "$MODE" == "--youtube-button" ]]; then
  # Turns on Glass Home's accessibility service, which sees only button presses.
  SERVICE="$PKG/$PKG.RemoteButtons"
  current=$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
  if [[ "$current" == "null" || -z "$current" ]]; then list="$SERVICE"
  elif [[ ":$current:" == *":$SERVICE:"* ]]; then list="$current"
  else list="$current:$SERVICE"; fi
  "${ADB[@]}" shell settings put secure enabled_accessibility_services "$list"
  "${ADB[@]}" shell settings put secure accessibility_enabled 1
  echo "The remote's YouTube button now opens your YouTube app. Turn it off in"
  echo "Settings > System > Accessibility > Glass Home remote buttons."
  exit 0
fi

./gradlew -q assembleRelease
"${ADB[@]}" install -r app/build/outputs/apk/release/app-release.apk

if [[ "$MODE" == "--home" ]]; then
  # Take the Home role first, so there's never a moment with no home app.
  "${ADB[@]}" shell cmd role add-role-holder "$HOME_ROLE" "$PKG" 0
  for p in "${STOCK_HOMES[@]}"; do
    installed "$p" && "${ADB[@]}" shell pm disable-user --user 0 "$p" >/dev/null
  done
  holder=$("${ADB[@]}" shell cmd role get-role-holders "$HOME_ROLE" | tr -d '\r')
  if [[ "$holder" != "$PKG" ]]; then
    echo "The Home role is held by '$holder', not Glass Home." >&2
    exit 1
  fi
  "${ADB[@]}" shell input keyevent KEYCODE_HOME
  echo "Glass Home now opens on the Home button. Undo with: scripts/install.sh $TV --restore"
else
  "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
  echo "Installed. To make it the Home button: scripts/install.sh $TV --home"
fi
