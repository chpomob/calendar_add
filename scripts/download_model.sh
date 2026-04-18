#!/bin/bash

# Gemma Model Download Script
# Downloads Gemma-2B-IT GGUF model for Android

set -e

MODEL_NAME="${1:-gemma-2b-it-q4f32}"
OUTPUT_DIR="${2:-app/src/main/assets/models}"

echo "=== Gemma Model Downloader ==="
echo "Model: $MODEL_NAME"
echo "Output: $OUTPUT_DIR"

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Get the correct model URL
case "$MODEL_NAME" in
    "gemma-2b-it-q4f32")
        MODEL_URL="https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q4f32.gguf"
        ;;
    "gemma-2b-it-q8_0")
        MODEL_URL="https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q8_0.gguf"
        ;;
    *)
        echo "Unknown model: $MODEL_NAME"
        exit 1
        ;;
esac

echo ""
echo "Model URL: $MODEL_URL"
echo ""

# Check if wget is available
if command -v wget &> /dev/null; then
    echo "Using wget..."
    echo "Downloading model (~${MODEL_SIZE:-1500} MB)..."
    wget --show-progress -O "$OUTPUT_DIR/$MODEL_NAME.gguf" "$MODEL_URL"
    echo ""
    echo "Download complete!"
    ls -lh "$OUTPUT_DIR/$MODEL_NAME.gguf"
elif command -v curl &> /dev/null; then
    echo "Using curl..."
    curl -L -o "$OUTPUT_DIR/$MODEL_NAME.gguf" "$MODEL_URL"
    echo ""
    echo "Download complete!"
    ls -lh "$OUTPUT_DIR/$MODEL_NAME.gguf"
else
    echo "Error: wget or curl is required"
    echo "Install one of: sudo apt-get install wget curl"
    exit 1
fi

echo ""
echo "=== Verification ==="
# Verify model integrity
if file "$OUTPUT_DIR/$MODEL_NAME.gguf" | grep -q "GGML"; then
    echo "✓ Model file is valid GGUF format"
else
    echo "⚠ Warning: Model file may be corrupted"
fi

echo ""
echo "Model downloaded successfully!"
echo "Path: $OUTPUT_DIR/$MODEL_NAME.gguf"
echo "Size: $(ls -lh "$OUTPUT_DIR/$MODEL_NAME.gguf" | awk '{print $5}')"
