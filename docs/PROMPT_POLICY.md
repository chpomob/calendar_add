# Prompt Policy

Keep the prompt small and stable.

Rules:
- Add a new corpus fixture before changing the prompt for a new edge case.
- Prefer parser and post-processing fixes over prompt expansion when the issue is mechanical.
- Only add prompt text when the same rule is useful across multiple fixtures or user reports.
- Keep prompt wording generic. Do not encode source-specific, fixture-specific, or one-off examples in the prompt.
- Keep heavy-mode prompts focused on decomposition and normalization, not on case-specific heuristics.

Workflow for a new failure:
1. Add a regression case to `app/src/test/resources/audio-fixtures/` or `app/src/test/resources/image-fixtures/`.
2. Re-run the relevant fixture suite and note whether the failure is prompt, parser, or model limitation.
3. Change the prompt only if the rule helps across several cases.
4. Keep the change small and rerun `./gradlew test`.

Guardrails:
- Light prompts should stay under the prompt budget enforced by tests.
- Heavy prompts should stay under their own budget and remain stage-based.
- If a new behavior requires many new instructions, it probably belongs in the corpus or in post-processing instead.
