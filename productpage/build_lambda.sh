#!/usr/bin/env bash
set -euo pipefail

PRODUCTPAGE_DIR="$(cd "$(dirname "$0")" && pwd)"
PACKAGE_DIR="$PRODUCTPAGE_DIR/build/lambda-package"
ZIP_PATH="$PRODUCTPAGE_DIR/build/productpage-lambda.zip"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "Python with a recent pip is required; set PYTHON_BIN to its executable." >&2
    exit 1
fi
if ! command -v zip >/dev/null 2>&1; then
    echo "The zip command is required to create the deployment archive." >&2
    exit 1
fi

rm -rf "$PACKAGE_DIR" "$ZIP_PATH"
mkdir -p "$PACKAGE_DIR"

"$PYTHON_BIN" -m pip install \
    --no-cache-dir \
    --no-compile \
    --platform manylinux2014_x86_64 \
    --python-version 3.13 \
    --implementation cp \
    --abi cp313 \
    --only-binary=:all: \
    --target "$PACKAGE_DIR" \
    -r "$PRODUCTPAGE_DIR/lambda-requirements.txt"

"$PYTHON_BIN" -m pip install \
    --no-cache-dir \
    --no-deps \
    --no-compile \
    --target "$PACKAGE_DIR" \
    json2html==1.3.0

cp "$PRODUCTPAGE_DIR/lambda_handler.py" "$PRODUCTPAGE_DIR/productpage.py" "$PACKAGE_DIR/"
cp -R "$PRODUCTPAGE_DIR/templates" "$PRODUCTPAGE_DIR/static" "$PACKAGE_DIR/"

(
    cd "$PACKAGE_DIR"
    zip -qr "$ZIP_PATH" .
)

echo "Created $ZIP_PATH"
