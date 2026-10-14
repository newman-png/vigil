#!/usr/bin/env sh
# Downloads Nothing's Glyph SDK (https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit) into app/libs.
# It is not committed here because it has no open-source licence. Without it the app still builds, minus the Glyph lights.
set -eu
cd "$(dirname "$0")/.."
COMMIT=8ee807a9312a640b0d43051450924e3446bc1d78
OUT=app/libs/glyph-matrix-sdk-2.0.aar
mkdir -p app/libs
if [ -f "$OUT" ]; then echo "Glyph SDK already present: $OUT"; exit 0; fi
curl -fsSL -o "$OUT" "https://raw.githubusercontent.com/Nothing-Developer-Programme/Glyph-Developer-Kit/$COMMIT/sdk/glyph-matrix-sdk-2.0.aar"
echo "Downloaded $OUT"
