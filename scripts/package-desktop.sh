#!/bin/sh
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ "${NOISELESS_SKIP_BUILD:-0}" != 1 ]; then "$BASE/mvnw" -q clean verify; fi
[ -f "$BASE/target/noiseless.jar" ] || { echo 'Build target/noiseless.jar first' >&2; exit 1; }
STAGING=$(mktemp -d)
trap 'rm -rf "$STAGING"' EXIT
mkdir "$STAGING/input"
cp "$BASE/target/noiseless.jar" "$STAGING/input/noiseless.jar"
set --
NAME=Noiseless
if [ "$(uname -s)" = Darwin ]; then
  set -- --java-options "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED"
  NAME=Noiseless.app
fi
jpackage "$@" --type app-image --name Noiseless --app-version 1.1.0 --vendor Noiseless \
  --input "$STAGING/input" --main-jar noiseless.jar --main-class io.noiseless.Main \
  --java-options "--enable-native-access=ALL-UNNAMED" --dest "$STAGING/output"
mkdir -p "$BASE/target/native"
if [ -e "$BASE/target/native/$NAME" ]; then mv "$BASE/target/native/$NAME" "$STAGING/previous"; fi
mv "$STAGING/output/$NAME" "$BASE/target/native/$NAME"
printf '%s\n' "$BASE/target/native/$NAME"
