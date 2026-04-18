#!/bin/sh

# Gradle Wrapper - Minimal version for rebuilding when gradlew is lost

# Set project root
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

# Check if GRADLE_HOME or gradle command exists
if command -v gradle >/dev/null 2>&1; then
    gradle "$@"
else
    echo "Error: Gradle wrapper is missing and no system 'gradle' command was found."
    echo "Please install Gradle or restore the official gradlew from a template."
    exit 1
fi
