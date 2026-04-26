Audio fixture suite

This directory contains small, deterministic audio samples for regression tests.

Format
- `manifest.json` describes every case.
- Each case lives in its own directory.
- `transcript.txt` stores the transcript text used to synthesize the sample.
- `expected-event.json` stores the expected normalized event payload.
- `audio.wav` is a generated 16 kHz mono PCM fixture.

The text sources are paraphrased from public transcript pages and are meant for
testing only. The audio is generated locally with `scripts/generate_audio_fixtures.sh`
so the suite stays reproducible and easy to refresh. Use
`scripts/run_audio_fixture_on_device.sh <fixture-id>` to push a sample into the
device share path and exercise the real audio analysis flow.

Hard-case coverage currently includes a negative incidental-time sample. The
model can still over-trigger on bare time mentions and invent a generic
`Meeting`, which is a known prompt/model limitation.
