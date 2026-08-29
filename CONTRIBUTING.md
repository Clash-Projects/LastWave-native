# Contributing to LastWave

Thank you for your interest in contributing to LastWave. This document outlines the development workflow, code standards, and submission guidelines.

## Code of Conduct

All contributors are expected to adhere to the [Code of Conduct](CODE_OF_CONDUCT.md). Please report unacceptable behavior to maintainers.

## Getting Started

### Prerequisites
* Android Studio Ladybug or newer
* JDK 17 (Temurin recommended)
* Android SDK (API 35) & NDK 26+
* Git

### Cloning the Repository
```bash
git clone https://github.com/Clash-Projects/LastWave-native.git
cd LastWave-native
```

### Community & Questions
For real-time development discussion and support:
* Telegram Channel: https://t.me/clashprojects
* Discussion Group: https://t.me/clashdiscussion

### Local Configuration
Copy `.env.example` to `.env` to supply build-time environment properties:
```bash
cp .env.example .env
```

## Architecture Overview

LastWave is built using modern Android architecture patterns:
* **UI Layer:** Jetpack Compose, Material 3, Navigation Compose.
* **Audio Engine:** ExoPlayer / Media3 with Jellyfin FFmpeg software decoders and custom C++ DSP processing (`AudioEngine.cpp`).
* **Dependency Injection:** Dagger Hilt.
* **Storage & Caching:** Room Database, DataStore Preferences.
* **Networking:** Retrofit, OkHttp 4, Kotlinx Serialization.

## Development Guidelines

### Branching Strategy
* `main` contains the latest stable development code.
* Create feature or bugfix branches from `main` using descriptive names:
  * `feat/your-feature-name`
  * `fix/issue-description`

### Code Style & Quality
* Follow official Kotlin coding conventions and Android Architecture recommendations.
* Keep composables focused, stateless where possible, and extract complex state into ViewModels.
* Ensure all native C++ code respects 16KB memory page alignment constraints for Android 15+.
* Avoid embedding hardcoded API secrets or URLs directly in source files.

### Testing
Run local unit tests before opening a pull request:
```bash
./gradlew testDebugUnitTest
```

## Submitting a Pull Request

1. Push your branch to your fork.
2. Open a Pull Request against the `main` branch.
3. Provide a concise summary of changes, problem analysis, and testing steps in the PR description.
4. Ensure continuous integration checks pass.
5. Address reviewer feedback promptly.
