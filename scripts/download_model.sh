#!/bin/bash

set -euo pipefail

cat <<'EOF'
This script is deprecated.

Calendar Add no longer uses manual GGUF downloads into app assets.
The app now downloads LiteRT-LM .litertlm models at runtime with Android DownloadManager.

Use the app itself:
1. Launch the app.
2. Open Settings to choose a model.
3. Return to Home and start the model download.

See docs/MODEL_INTEGRATION.md for the current model workflow.
EOF

exit 1
