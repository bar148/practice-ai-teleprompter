#!/usr/bin/env bash
# בונה את רכיב זיהוי הדיבור למק (Apple Silicon + Intel בקובץ אחד). רץ על מק בלבד.
# שימוש:  bash desktop/mac/build-helper.sh <תיקיית-יעד>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here}"
mkdir -p "$out"
tmp="$(mktemp -d)"
for arch in arm64 x86_64; do
  swiftc -O -target "${arch}-apple-macos12" "$here/SpeechHelper.swift" -o "$tmp/tp-speech-$arch" \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$here/Info.plist"
done
lipo -create "$tmp/tp-speech-arm64" "$tmp/tp-speech-x86_64" -output "$out/tp-speech"
chmod +x "$out/tp-speech"
lipo -info "$out/tp-speech"
