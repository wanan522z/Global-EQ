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

    static boolean virtualBassModeAllowed(ProcessingMode mode, int virtualBassModeIndex) {
        if (virtualBassModeIndex < 0 || virtualBassModeIndex > 2) {
            return false;
        }
        if (advancedModeEnabled(mode)) {
            return true;
        }
        return virtualBassModeIndex <= 1;
    }

    static int sanitizeVirtualBassModeIndex(ProcessingMode mode, int virtualBassModeIndex) {
        return virtualBassModeAllowed(mode, virtualBassModeIndex) ? virtualBassModeIndex : 0;
    }

    static boolean systemVirtualBassAllowed(int virtualBassModeIndex) {
        return virtualBassModeIndex == 1;
    }

    static boolean dspVirtualBassAllowed(ProcessingMode mode, int virtualBassModeIndex) {
        return advancedModeEnabled(mode) && virtualBassModeIndex == 2;
    }

    static Preset effectiveDspPreset(Preset preset, ProcessingMode mode, int virtualBassModeIndex) {
        if (preset == null) {
            return null;
        }

        Preset effective = preset;
        if (!reverbAllowed(mode) || "Default".equals(effective.reverbType)) {
            effective = effective.withReverbType("Default");
        }
        return effective;
    }

    static Preset effectiveSystemPreset(Preset preset, ProcessingMode mode, int virtualBassModeIndex) {
        if (preset == null) {
            return null;
        }
        if (advancedModeEnabled(mode)) {
            return Preset.flat(preset.enabled).withName(preset.name);
        }
        Preset effective = preset.withReverbType("Default");
        if (!systemVirtualBassAllowed(virtualBassModeIndex)) {
            effective = effective.withVirtualBassAmountPercent(0);
        }
        return effective;
    }
}
