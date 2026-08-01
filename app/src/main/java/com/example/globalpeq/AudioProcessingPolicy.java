package com.example.globalpeq;

final class AudioProcessingPolicy {
    private static final int ADVANCED_MODE_IMPLICIT_HEADROOM_MB = -900;

    private AudioProcessingPolicy() {
    }

    static boolean advancedModeEnabled(ProcessingMode mode) {
        return mode != null && mode.usesNativeCapture();
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

        Preset effective = mode == ProcessingMode.GLOBAL_DSP
                ? Preset.flat(preset.enabled)
                .withName(preset.name)
                .withPregainMb(ADVANCED_MODE_IMPLICIT_HEADROOM_MB)
                .withVirtualBassModeIndex(preset.virtualBassModeIndex)
                .withDspVirtualBassCutoffHz(preset.dspVirtualBassCutoffHz)
                .withDspVirtualBassAmountPercent(preset.dspVirtualBassAmountPercent)
                .withReverbType(preset.reverbType)
                .withReverbSendSettings(
                        preset.reverbDryMb,
                        preset.reverbDecayPercent,
                        preset.reverbPredelayMs,
                        preset.reverbSizePercent,
                        preset.reverbWetPercent)
                : preset;
        if (!reverbAllowed(mode) || "Default".equals(effective.reverbType)) {
            effective = effective.withReverbType("Default");
        }
        if (mode == ProcessingMode.SHIZUKU_MUTE && effective.enabled) {
            effective = effective.withPregainMb(effective.pregainMb + ADVANCED_MODE_IMPLICIT_HEADROOM_MB);
        }
        return effective;
    }

    static boolean requiresPcmReplay(ProcessingMode mode, Preset preset, int virtualBassModeIndex) {
        if (mode == ProcessingMode.SHIZUKU_MUTE) {
            return preset != null && preset.enabled;
        }
        if (mode != ProcessingMode.GLOBAL_DSP || preset == null || !preset.enabled) {
            return false;
        }
        boolean dspBassEnabled = dspVirtualBassAllowed(mode, virtualBassModeIndex)
                && preset.dspVirtualBassAmountPercent > 0;
        boolean reverbEnabled = !"Default".equals(preset.reverbType)
                && preset.reverbWetPercent > 0;
        return dspBassEnabled || reverbEnabled;
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
