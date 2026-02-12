#!/usr/bin/env sh

set -e

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "Gradle executable not found in PATH. Please install Gradle or add Gradle Wrapper files." >&2
exit 1
