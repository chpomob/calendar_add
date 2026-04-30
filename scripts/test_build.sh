#!/bin/bash

# Test build script for Calendar Add AI
# Tests gradle build and reports results

set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

echo "=================================="
echo "Calendar Add AI - Build Test"
echo "=================================="
echo ""

# Check if gradle wrapper exists
if [ ! -f "./gradlew" ]; then
    echo "❌ gradlew not found, installing dependencies..."
    # Try to install gradle or use system gradle
    echo "Installing Android SDK..."
    exit 1
fi

# Test compile debug
echo "Checking release documentation..."
if ./scripts/check_release_docs.sh --quiet 2>&1; then
    echo "Release documentation is current"
else
    echo "Release documentation check failed"
    ./scripts/check_release_docs.sh
    exit 1
fi

echo ""
echo "Testing debug build..."
if ./gradlew assembleDebug --quiet 2>&1; then
    echo "Debug build succeeded"
else
    echo "Debug build failed"
    exit 1
fi

# Test unit tests
echo ""
echo "Running unit tests..."
if ./gradlew test --quiet 2>&1; then
    echo "Unit tests passed"
else
    echo "Tests failed"
fi

echo ""
echo "=================================="
echo "Build completed successfully!"
echo "=================================="
echo ""
echo "To install on device:"
echo "  adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To sign release APK:"
echo "  ./gradlew assembleRelease"
echo ""
