package com.example.globalpeq;

final class AudioProcessingPolicy {
    private AudioProcessingPolicy() {
    }

    static boolean advancedModeEnabled(ProcessingMode mode) {
        return mode == ProcessingMode.ADVANCED_DSP || mode == ProcessingMode.SHIZUKU_MUTE;
    }

    static boolean systemBassBoostAllowed(ProcessingMode mode, int bassModeIndex) {
        return advancedModeEnabled(mode) && bassModeIndex == 1;
    }

    static boolean dspBassAllowed(ProcessingMode mode, int bassModeIndex) {
        return advancedModeEnabled(mode) && bassModeIndex == 2;
    }

    static boolean dspVirtualBassAllowed(ProcessingMode mode, int bassModeIndex) {
        return advancedModeEnabled(mode) && bassModeIndex == 2;
    }

    static Preset effectiveDspPreset(Preset preset, ProcessingMode mode, int bassModeIndex) {
        if (preset == null) {
            return null;
        }

        Preset effective = preset;
        if (!dspVirtualBassAllowed(mode, bassModeIndex)) {
            effective = effective.withVirtualBassEnabled(false);
        }
        if (!advancedModeEnabled(mode) || "Default".equals(effective.reverbType)) {
            effective = effective.withReverbType("Default");
        }
        return effective;
    }

    static Preset effectiveSystemPreset(Preset preset, ProcessingMode mode, int bassModeIndex) {
        if (preset == null) {
            return null;
        }
        if (advancedModeEnabled(mode)) {
            return Preset.flat(preset.enabled).withName(preset.name);
        }
        Preset effective = preset.withReverbType("Default");
        if (!systemBassBoostAllowed(mode, bassModeIndex)) {
            effective = effective.withSystemBassBoostPercent(0);
        }
        return effective;
    }
}
