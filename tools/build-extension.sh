#!/usr/bin/env bash
# Builds dist/SesiMini-extension-<ver>.zip from extension/ + the shared assets in app/src/main/assets.
set -euo pipefail
cd "$(dirname "$0")/.."
A=app/src/main/assets; E=extension; OUT=build/extension
VER=$(python3 -c "import json;print(json.load(open('$E/manifest.json'))['version'])")
rm -rf "$OUT" && mkdir -p "$OUT/icons"
cp "$E"/manifest.json "$E"/content.js "$E"/content.css "$E"/background.js "$E"/dashboard.html "$E"/dashboard.css "$E"/dashboard.js "$OUT"/
cp "$E"/icons/*.png "$OUT/icons/"
cp "$A"/auto-prompt.js "$A"/inject.js "$A"/single-clip-enforcer.js "$A"/Introvert-Dreams-SKILL-v5.md "$OUT"/
# ext-bridge = template + attach-md function expression (same file the APK evaluates)
python3 - "$E/ext-bridge.template.js" "$A/attach-md.js" "$OUT/ext-bridge.js" <<'PY'
import sys
tpl, attach, out = sys.argv[1:]
open(out, 'w').write(open(tpl).read().replace('__ATTACH_MD__', open(attach).read().rstrip().rstrip(';')) + ';\n')
PY
for f in "$OUT"/*.js; do node --check "$f"; done
mkdir -p dist && rm -f "dist/SesiMini-extension-$VER.zip"
( cd "$OUT" && zip -qr "../../dist/SesiMini-extension-$VER.zip" . )
echo "dist/SesiMini-extension-$VER.zip"
