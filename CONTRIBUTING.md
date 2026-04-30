# Contributing to Calendar Add AI

Thank you for your interest in contributing! This document provides guidelines for contributors.

## Code of Conduct

- Be respectful and inclusive
- Welcome new contributors
- Focus on improvement, not blame

## Getting Started

1. **Fork** the repository
2. **Clone** to your machine:
   ```bash
   git clone https://github.com/username/calendar-add.git
   cd calendar-add
   ```
3. **Set up Android SDK** in `local.properties`
4. **Install dependencies**:
   ```bash
   ./gradlew tasks
   ```

## Development Workflow

### Branching

- Use issue-based branches: `issue-123-add-file-import`
- Create feature branches: `feature/audio-transcription`
- Always create PRs to `develop` branch

### Code Style

- Use `ktlint` for Kotlin formatting (config in `.editorconfig`)
- Follow KMP conventions where applicable
- Document complex logic with JDoc comments

### Submitting Changes

1. Make your changes
2. Run tests: `./gradlew test`
3. Update documentation if needed
4. Create a PR with:
   - Clear title
   - Description of changes
   - Screenshots for UI changes
   - References to related issues

## Reporting Issues

- Use GitHub Issues for bugs
- Include:
  - Device model and Android version
  - Steps to reproduce
  - Expected vs actual behavior
  - Screenshots if applicable

## Feature Requests

- Submit feature ideas via Issues
- Explain use case and benefits
- Provide mockups if applicable

## Pull Request Guidelines

- One feature per PR
- Update documentation
- Add tests for new features
- Keep PRs focused and small

## Architecture Overview

```
┌─────────────────────────────────────────┐
│           UI Layer (Compose)            │
│    ┌──────────────┬────────────────┐    │
│    │ Home Screen  │ Event List     │    │
│    │ Detail Screen│ Settings       │    │
│    └──────────────┴────────────────┘    │
└─────────────────────────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│       Business Logic (Use Cases)        │
│    ┌──────────────┬────────────────┐    │
│    │ CalendarUseCase │ Validation  │    │
│    │ EventService  │ ImportService │    │
│    └──────────────┴────────────────┘    │
└─────────────────────────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│         Data & AI Layer                 │
│    ┌──────────────┬────────────────┐    │
│    │ LLMEngine    │   Event DB    │    │
│    │ (TF Lite)    │  (Room)        │    │
│    └──────────────┴────────────────┘    │
└─────────────────────────────────────────┘
```

## Tests

Run all tests:
```bash
./gradlew test
./gradlew connectedAndroidTest
```

Coverage threshold: 80%

## Questions?

- Join our [Discord](#)
- Email [chpomob@gmail.com](mailto:chpomob@gmail.com)
- Check [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

## License

Calendar Add is shared under the WTFPL.

By contributing changes that are accepted into this repository, you agree that those contributions are also shared under the WTFPL.

These contribution guidelines only explain how to get changes merged here. They do not limit your right to fork, modify, publish, redistribute, sell, or use the project however you want under the WTFPL.
