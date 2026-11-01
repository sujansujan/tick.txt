#!/bin/sh
# Gradle startup script for tick.txt
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "Gradle wrapper uses gradle/actions/setup-gradle@v4 in GitHub Actions or local gradle."
    exit 1
fi
