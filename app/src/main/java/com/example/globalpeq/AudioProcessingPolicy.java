package com.example.globalpeq;

final class AudioProcessingPolicy {
    private AudioProcessingPolicy() {
    }

    static boolean advancedModeEnabled(ProcessingMode mode) {
        return mode == ProcessingMode.ADVANCED_DSP;
    }

    static boolean systemBassBoostAllowed(ProcessingMode mode, int bassModeIndex) {
        return advancedModeEnabled(mode) && bassModeIndex == 1;
    }

    static boolean dspBassAllowed(ProcessingMode mode, int bassModeIndex) {
        return advancedModeEnabled(mode) && bassModeIndex == 2;
    }

    static Preset effectiveSystemPreset(Preset preset, ProcessingMode mode, int bassModeIndex) {
        if (preset == null) {
            return null;
        }
        Preset effective = preset.withReverbType("Default");
        if (!systemBassBoostAllowed(mode, bassModeIndex)) {
            effective = effective.withSystemBassBoostPercent(0);
        }
        return effective;
    }
}
