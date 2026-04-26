Image fixture suite

This directory contains deterministic flyer-style images for regression tests.

Format
- `manifest.json` describes every case.
- Each case lives in its own directory.
- `flyer.png` is a generated PNG flyer.
- `expected-event.json` stores the normalized event payload used by the checker.

The image layout is synthetic but the event text is sourced from public event
pages and flyers. The assets are kept small and reproducible so they can be
checked locally against Gemma 4. Use `scripts/generate_image_fixtures.sh` to
refresh the rendered flyers after updating the manifest.

The suite now includes a multi-row workshop series case to exercise schedules
with multiple explicit dates and titles.
