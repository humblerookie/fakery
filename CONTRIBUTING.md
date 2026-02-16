# Contributing to Fakery

## Prerequisites

- JDK 21+
- Android SDK (optional — only needed to build the `androidTarget`)

## Build & test

```bash
# Run all JVM tests (fastest feedback loop)
./gradlew jvmTest

# Run sample module tests
./gradlew :sample:jvmTest

# Run everything
./gradlew test
```

## Project structure

| Module | Purpose |
|---|---|
| `:` (root) | The Fakery library |
| `:sample` | Example app showing library integration |

## Branch workflow

- `main` — stable, always green
- Feature/fix work goes on a branch: `feature/your-feature` or `fix/your-fix`
- Open a PR against `main`; CI must pass before merge

## Code style

- No wildcard imports (`import foo.*`)
- KDoc on all `public` API surface
- Explicit return types on `public` functions

## Adding a new platform

1. Declare the target in `build.gradle.kts`
2. Add `actual fun createFakeryServer(...)` in the appropriate source set
3. Add `actual fun loadStubsFromDirectory(...)` and `actual fun loadStubsFromFile(...)` for file I/O
4. Add at least one smoke test in the new target's test source set
