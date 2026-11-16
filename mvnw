#!/bin/sh
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERSION=3.9.11
CACHE="$BASE/.cache/apache-maven-$VERSION"
if [ ! -x "$CACHE/bin/mvn" ]; then
  mkdir -p "$BASE/.cache"
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz"
  curl -fsSL "$URL" -o "$BASE/.cache/maven.tar.gz"
  curl -fsSL "$URL.sha512" -o "$BASE/.cache/maven.sha512"
  if command -v sha512sum >/dev/null 2>&1; then
    ACTUAL=$(sha512sum "$BASE/.cache/maven.tar.gz" | cut -d ' ' -f 1)
  else
    ACTUAL=$(shasum -a 512 "$BASE/.cache/maven.tar.gz" | cut -d ' ' -f 1)
  fi
  [ "$ACTUAL" = "$(cat "$BASE/.cache/maven.sha512")" ] || { echo 'Maven checksum failed' >&2; exit 1; }
  tar -xzf "$BASE/.cache/maven.tar.gz" -C "$BASE/.cache"
  rm "$BASE/.cache/maven.tar.gz" "$BASE/.cache/maven.sha512"
fi
exec "$CACHE/bin/mvn" -f "$BASE/pom.xml" "$@"
