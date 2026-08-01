package com.example.globalpeq;

final class AudioProcessingPolicy {
    private static final int ADVANCED_MODE_IMPLICIT_HEADROOM_MB = -900;

    private AudioProcessingPolicy() {
    }

    static boolean advancedModeEnabled(ProcessingMode mode) {
        return mode == ProcessingMode.SHIZUKU_MUTE;
    }

    static boolean reverbAllowed(ProcessingMode mode) {
        return mode == ProcessingMode.SHIZUKU_MUTE;
    }

    static boolean virtualBassModeAllowed(ProcessingMode mode, int virtualBassModeIndex) {
        if (virtualBassModeIndex < 0 || virtualBassModeIndex > 2) {
            return false;
        }
        if (mode == ProcessingMode.SHIZUKU_MUTE) {
            return true;
        }
        return virtualBassModeIndex <= 1;
    }

    static boolean systemVirtualBassAllowed(int virtualBassModeIndex) {
        return virtualBassModeIndex == 1;
    }

    static boolean dspVirtualBassAllowed(ProcessingMode mode, int virtualBassModeIndex) {
        return mode == ProcessingMode.SHIZUKU_MUTE && virtualBassModeIndex == 2;
    }

    static Preset effectiveDspPreset(Preset preset, ProcessingMode mode, int virtualBassModeIndex) {
        if (preset == null) {
            return null;
        }

        Preset effective = preset;
        if (!reverbAllowed(mode) || "Default".equals(effective.reverbType)) {
            effective = effective.withReverbType("Default");
        }
        if (mode == ProcessingMode.SHIZUKU_MUTE && effective.enabled) {
            effective = effective.withPregainMb(effective.pregainMb + ADVANCED_MODE_IMPLICIT_HEADROOM_MB);
        }
        return effective;
    }

    static boolean requiresPcmReplay(ProcessingMode mode, Preset preset, int virtualBassModeIndex) {
        return mode == ProcessingMode.SHIZUKU_MUTE && preset != null && preset.enabled;
    }

    static Preset effectiveSystemPreset(Preset preset, ProcessingMode mode, int virtualBassModeIndex) {
        if (preset == null) {
            return null;
        }
        if (mode == null || !mode.usesSystemEqBackend()) {
            return Preset.flat(preset.enabled).withName(preset.name);
        }
        Preset effective = preset.withReverbType("Default");
        if (!systemVirtualBassAllowed(virtualBassModeIndex)) {
            effective = effective.withSystemVirtualBassAmountPercent(0);
        }
        return effective;
    }
}
