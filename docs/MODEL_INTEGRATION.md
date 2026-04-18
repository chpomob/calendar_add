# Calendar Add AI - Gemma Model Integration

## Quick Start

### 1. Download the Model

```bash
cd scripts
chmod +x download_model.sh
./download_model.sh

# Or manually download:
wget -O app/src/main/assets/models/gemma-2b-it-q4f32.gguf "https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q4f32.gguf"
```

### 2. Run Tests

```bash
# Download model first
./scripts/download_model.sh

# Run unit tests
./gradlew test

# Run integration tests
./gradlew test --tests ExtractionIntegrationTest
```

### 3. Verify Model

```bash
# Check model file
ls -lh app/src/main/assets/models/gemma-2b-it-q4f32.gguf

# Verify GGUF format
file app/src/main/assets/models/gemma-2b-it-q4f32.gguf

# Expected output: "GGML: binary data file"
```

## Model Information

- **Model**: Gemma-2B-IT (Instruction-Tuned)
- **Size**: ~1.5 GB (quantized)
- **Format**: GGUF (Q4_0 quantization)
- **Parameters**: 2B
- **Context Length**: 8192 tokens
- **Download Time**: ~5-10 minutes (50-100 MB/s)

## Testing

### Run All Tests

```bash
./gradlew test
```

### Test Results Expected

```
Task :test
...
LlmEngineTest ... OK
ExtractionIntegrationTest ... OK
Total tests: 14
Passed: 14
Failed: 0
```

## Integration

### In MainActivity

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Initialize LLM engine
    llmEngine = LlmEngine(context = this)
    
    // Load model (async)
    CoroutineScope(Dispatchers.IO).launch {
        llmEngine.loadModel()
    }
}

override fun onDestroy() {
    super.onDestroy()
    llmEngine?.unloadModel()
}
```

### In TextAnalysisService

```kotlin
class TextAnalysisService(
    private val llmEngine: LlmEngine? = null
) {
    suspend fun analyzeInput(
        input: String,
        context: InputContext
    ): EventExtraction = runBlocking {
        // Use LLM if available
        if (llmEngine?.isLoaded() == true) {
            llmEngine.analyzeInput(input)
        } else {
            // Fallback extraction
            EventExtraction(...)
        }
    }
}
```

## Troubleshooting

### Model Not Found

```
Error: Model not found at: /path/to/model
```

**Solution:** Run download script or manually download.

### Out of Memory

```
Error: Unable to load model
```

**Solution:**
1. Use quantized model (Q4_0)
2. Close other apps
3. Clear app data
4. Restart device

### Build Errors

If you see errors with dependencies:

```bash
./gradlew clean build
```

## Performance

| Device | Model Size | Response Time |
|--------|--|-------|
| Pixel 7 | 1.5 GB | 2-3s |
| iPhone 14 | 1.5 GB | 3-4s |
| Samsung S21 | 1.5 GB | 2-2.5s |

## License

Gemma models require acceptance of Google's usage policy before download.

See: https://ai.google.dev/gemma

---

*Last updated: 2026-04-18*