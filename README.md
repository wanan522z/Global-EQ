# Global PEQ

Minimal non-root Android prototype for global PEQ-style equalization.

## What is implemented

- Foreground service that keeps a global Android `Equalizer` attached to audio session `0`.
- Eight PEQ-style filters with frequency, gain, Q, and per-band enable state.
- No compressor, no limiter, no loudness effect.
- Per-device presets keyed from `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`.
- Automatic preset switching for speaker, wired, Bluetooth, and USB outputs.
- Boot receiver restores the last enabled device preset.

## Important compatibility note

This follows the same Android-level global route exposed to third-party apps: session `0`.
Some ROMs still allow it, while others block or ignore it. Android's public global effect
path exposes a fixed-band system `Equalizer`, so the PEQ controls are mapped onto those
system bands using frequency and Q. No compression stage is created.

## Open in Android Studio

Open the `GlobalPEQ` directory as a Gradle Android project. The local environment used to
create this scaffold did not include Gradle or `ANDROID_HOME`, so APK compilation was not
run here.
