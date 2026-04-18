# Gemma Model Integration Guide

## Overview

This guide explains how to integrate Gemma models with your Calendar Add AI Android app.

## Gemma Model Versions (April 2026)

| Model | Size | Parameters | Use Case |
|-------|------|------------|----------|
| Gemma 4 - E2B | ~1.5 GB | 2B | Mobile devices |
| Gemma 4 - E4B | ~3 GB | 4B | Power devices |
| Gemma 4 - 26B | ~15 GB | 26B | PC/Server |

## Download Options

### Option 1: HuggingFace Direct Download (Recommended)

```bash
# Download Gemma-2B-IT GGUF quantized version
curl -o gemma-2b-it-q4f32.gguf https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q4f32.gguf

# Check file size (~1.5 GB)
ls -lh gemma-2b-it-q4f32.gguf
```

### Option 2: Using `wget`

```bash
wget -O gemma-2b-it-q4f32.gguf "https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q4f32.gguf"
```

## Model Formats

| Format | Size | Speed | Compatibility |
|--------|------|-------|---------------|
| GGUF Q4_0 | ~1.5 GB | Fast | llama.cpp |
| GGUF Q4_K_M | ~1.4 GB | Fast | llama.cpp |
| GGUF Q5_K_M | ~1.7 GB | Fast | llama.cpp |
| GGUF Q8_0 | ~2.2 GB | Fastest | llama.cpp |

## Integration Steps

### Step 1: Download Model

```bash
# Navigate to app directory
cd app/src/main/assets/models/

# Download Gemma model
wget -O gemma-2b-it-q4f32.gguf "https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q4f32.gguf"

# Verify download
ls -lh gemma-2b-it-q4f32.gguf
```

### Step 2: Add JNI Dependency (for llama.cpp)

```kotlin
dependencies {
    // llama.cpp JNI bindings (example)
    implementation("com.llama.cpp:llama-cpp-android:0.1.0")
}
```

### Step 3: Update LlmEngine

See `app/src/main/java/com/calendaradd/model/LlmEngine.kt` for implementation.

### Step 4: Handle Model Loading

```kotlin
// In MainActivity
llmEngine = LlmEngine(context = this, modelPath = "models/gemma-2b-it-q4f32.gguf")

// Load model
llmEngine.loadModel("models/gemma-2b-it-q4f32.gguf")

// Check if loaded
llmEngine.isLoaded()
```

## Testing

### Unit Tests

```kotlin
class LlmEngineTest {
    @Test
    fun testModelLoading() = runBlocking {
        assertTrue(llmEngine.loadModel("path/to/model"))
    }
}
```

### Integration Tests

1. Load model
2. Send test prompt
3. Verify response
4. Check memory usage

## Memory Management

### Background Model Loading

```kotlin
// Load model in background
LifecycleScope.current.launch {
    llmEngine.loadModel(modelPath)
}
```

### Memory Release

```kotlin
// Unload when app goes to background
override fun onResume() {
    super.onResume()
    if (llmEngine.isLoaded()) {
        llmEngine.loadModel(modelPath) // Reloading if needed
    }
}

override fun onPause() {
    super.onPause()
    // Model stays loaded for faster resume
}
```

## Troubleshooting

### Model Not Found

```
Error: Model not found at: /data/app/.../base.apk/assets/models/
```

**Solution:** Ensure model is in correct path or use external storage.

### Out of Memory

```
Error: Unable to load model
```

**Solution:**
1. Use quantized model (Q4_0)
2. Reduce concurrent tasks
3. Clear cache before loading

## License & Usage

- Gemma license: https://ai.google.dev/gemma
- Prohibited use policy: https://ai.google.dev/gemma/prohibited_use_policy
- Accept license before downloading

## Performance Benchmarks (approximate)

| Device | Model | Response Time |
|--------|-------|---------------|
| Pixel 7 | Gemma 2B | 2-3s |
| iPhone 14 | Gemma 2B | 3-4s |
| Samsung S21 | Gemma 2B | 2-2.5s |

## References

- Gemma docs: https://ai.google.dev/gemma
- GGUF models: https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF
- llama.cpp: https://github.com/ggml-org/llama.cpp
- MLC-LLM: https://github.com/mlc-ai/mlc-llm

---

*Last updated: 2026-04-18*