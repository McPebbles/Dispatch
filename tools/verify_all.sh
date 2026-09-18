#!/usr/bin/env bash
# Everything that can be checked without a Kotlin compiler or an Android SDK.
#
# What this does NOT do is build the app or run the JUnit suite. For that:
#   ./gradlew assembleDebug
#   ./gradlew testDebugUnitTest
#
# And for the bundled feed set against the live web (needs real network):
#   python3 tools/check_feeds.py
set -u
cd "$(dirname "$0")/.."
fail=0
run() { echo; echo "=== $* ==="; "$@" || fail=1; }

run python3 tools/verify_kotlin.py
run python3 tools/verify_resources.py
run python3 tools/verify_frame.py
run python3 tools/rules_suite.py
run python3 tools/sql_suite.py
run python3 tools/render_icon.py build/icon

echo
if [ "$fail" -ne 0 ]; then echo "FAILURES ABOVE"; exit 1; fi
echo "all checks clean"
