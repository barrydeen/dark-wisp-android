#!/usr/bin/env bash
# Pre-release APK audit gate. Usage: tools/audit-apk.sh <apk> [build-tools-dir]
#
# Verifies the release candidate is a sane, properly packaged, release-signed
# APK before it is published anywhere. Added after the 1.0.2 GrapheneOS perf
# regression investigation so a malformed artifact can never ship unnoticed.
set -u

APK="${1:?usage: tools/audit-apk.sh <apk> [build-tools-dir]}"
BT="${2:-$HOME/Android/Sdk/build-tools/35.0.0}"
FAIL=0

check() { # check <label> <0|1 ok> <detail>
    if [ "$2" -eq 0 ]; then
        printf 'PASS  %-28s %s\n' "$1" "$3"
    else
        printf 'FAIL  %-28s %s\n' "$1" "$3"
        FAIL=1
    fi
}

[ -f "$APK" ] || { echo "no such file: $APK"; exit 1; }
[ -x "$BT/aapt2" ] || { echo "build-tools not found at $BT"; exit 1; }

echo "Auditing: $APK ($(du -h "$APK" | cut -f1))"

# 1. Must not be debuggable
DBG=$("$BT/aapt2" dump badging "$APK" 2>/dev/null | grep -ci debuggable)
check "not debuggable" "$DBG" "application-debuggable flags: $DBG"

# 2. Native lib packaging must be internally consistent
EXTRACT=$("$BT/aapt2" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null \
    | grep -i extractNativeLibs | grep -oiE 'true|false' | head -1)
EXTRACT=${EXTRACT:-true} # absent attribute defaults to extract (legacy)
SO_TOTAL=$(unzip -lv "$APK" 'lib/*.so' 2>/dev/null | grep -cE '\.so$')
SO_STORED=$(unzip -lv "$APK" 'lib/*.so' 2>/dev/null | grep -E '\.so$' | grep -c Stored)
if [ "$EXTRACT" = "false" ]; then
    [ "$SO_STORED" -eq "$SO_TOTAL" ]; check "libs stored (no-extract)" $? "$SO_STORED/$SO_TOTAL stored"
    "$BT/zipalign" -c -P 16 -v 4 "$APK" >/dev/null 2>&1; check "zipalign 16KB" $? "zipalign -c -P 16 -v 4"
else
    [ "$SO_STORED" -eq 0 ]; check "libs compressed (extract)" $? "$((SO_TOTAL - SO_STORED))/$SO_TOTAL compressed"
    "$BT/zipalign" -c 4 "$APK" >/dev/null 2>&1; check "zipalign" $? "zipalign -c 4"
fi

# 3. R8 applied (heuristic: minified builds keep few un-renamed app class refs)
APP_REFS=$(unzip -p "$APK" classes.dex 2>/dev/null | strings | grep -cE '^Lcom/(darkwisp|wisp)/app/')
[ "$APP_REFS" -lt 50 ]; check "R8 minified" $? "un-renamed app class refs in classes.dex: $APP_REFS"

# 4. Baseline profile present (runtime confirmation: adb logcat -s ProfileVerifier)
PROF_SIZE=$(unzip -l "$APK" assets/dexopt/baseline.prof 2>/dev/null | awk '/baseline\.prof$/{print $1}')
[ -n "$PROF_SIZE" ] && [ "$PROF_SIZE" -gt 1000 ]; check "baseline profile" $? "assets/dexopt/baseline.prof ${PROF_SIZE:-0} bytes"

# 5. Release-signed with a non-debug cert
SIGN_OUT=$("$BT/apksigner" verify --print-certs "$APK" 2>&1)
SIGN_OK=$?
check "signature valid" "$SIGN_OK" "apksigner verify"
DN=$(echo "$SIGN_OUT" | grep -m1 'certificate DN' | sed 's/.*DN: //')
[ -n "$DN" ] && ! echo "$DN" | grep -qi 'Android Debug'; check "release cert" $? "${DN:-no cert found}"

exit $FAIL
