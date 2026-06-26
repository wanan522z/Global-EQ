package com.example.globalpeq;

final class AudioProcessingPolicy {
    private AudioProcessingPolicy() {
    }

    static boolean advancedModeEnabled(ProcessingMode mode) {
        return mode == ProcessingMode.SHIZUKU_MUTE;
    }

    static boolean reverbAllowed(ProcessingMode mode) {
        return advancedModeEnabled(mode);
    }

    static boolean bassModeAllowed(ProcessingMode mode, int bassModeIndex) {
        if (bassModeIndex < 0 || bassModeIndex > 2) {
            return false;
        }
        if (advancedModeEnabled(mode)) {
            return true;
        }
        return bassModeIndex <= 1;
    }

    static int sanitizeBassModeIndex(ProcessingMode mode, int bassModeIndex) {
        return bassModeAllowed(mode, bassModeIndex) ? bassModeIndex : 0;
    }

    static boolean systemBassBoostAllowed(int bassModeIndex) {
        return bassModeIndex == 1;
    }

    static boolean dspBassAllowed(ProcessingMode mode, int bassModeIndex) {
        return advancedModeEnabled(mode) && bassModeIndex == 2;
    }

    static Preset effectiveDspPreset(Preset preset, ProcessingMode mode, int bassModeIndex) {
        if (preset == null) {
            return null;
        }

        Preset effective = preset;
        if (!reverbAllowed(mode) || "Default".equals(effective.reverbType)) {
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
        if (!systemBassBoostAllowed(bassModeIndex)) {
            effective = effective.withSystemBassBoostPercent(0);
        }
        return effective;
    }
}
