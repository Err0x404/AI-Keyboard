# AI Keyboard

This repository contains the Android input method source code and its build configuration. The application processes input on-device; AI inference does not depend on cloud quotas.

## Building

Use the Android SDK, a JDK supported by the configured Android Gradle Plugin, and the included Gradle wrapper:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The app can build without a librime checkout, in which case the native bridge reports Rime as unavailable. To build with librime, pass its source directory as a Gradle property:

```powershell
.\gradlew.bat -PrimeSourceDir="<librime-source-directory>" :app:assembleDebug
```

Replace the placeholder with the path to your local librime checkout.

The CMake build downloads and verifies its Boost archive as part of the native build. No local SDK or Java installation path is stored in this repository.

## Optional on-device model

Some releases bundle the Gemma 3 1B IT Q4_K_M model at install time; other releases may require the model as a separate local file. To build a release that includes the model, obtain the compatible GGUF file from an authorized Unsloth release, review the applicable Gemma Terms of Use, and place it at:

```text
models/gemma-3-1b-it.Q4_K_M.gguf
```

The model is not included in this source repository. Model use and distribution are governed by its own terms, not by this project's source-code license. See [NOTICE](NOTICE) for third-party source and license information.
