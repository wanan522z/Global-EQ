package com.example.globalpeq;

enum ProcessingMode {
    SYSTEM_EQ("SYSTEM_EQ", "Default"),
    GLOBAL_DSP("GLOBAL_DSP", "Global DSP"),
    SHIZUKU_MUTE("SHIZUKU_MUTE", "Shizuku Mode");

    final String key;
    final String label;

    ProcessingMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    static ProcessingMode fromKey(String key) {
        if (key != null) {
            if ("ADVANCED_DSP".equalsIgnoreCase(key) || "Monitor DSP".equalsIgnoreCase(key)) {
                return SHIZUKU_MUTE;
            }
            for (ProcessingMode mode : values()) {
                if (mode.key.equalsIgnoreCase(key) || mode.label.equalsIgnoreCase(key)) {
                    return mode;
                }
            }
        }
        return SYSTEM_EQ;
    }

    boolean usesNativeCapture() {
        return this != SYSTEM_EQ;
    }

    boolean capturesSystemAudio() {
        return this == GLOBAL_DSP || this == SHIZUKU_MUTE;
    }

    boolean requiresShizukuMute() {
        return this == SHIZUKU_MUTE;
    }

    static String[] labels() {
        ProcessingMode[] modes = values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modes[i].label;
        }
        return labels;
    }
}
