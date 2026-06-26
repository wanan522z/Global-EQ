package com.example.globalpeq;

enum ProcessingMode {
    SYSTEM_EQ("SYSTEM_EQ", "Default"),
    ADVANCED_DSP("ADVANCED_DSP", "Monitor DSP");

    final String key;
    final String label;

    ProcessingMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    static ProcessingMode fromKey(String key) {
        if (key != null) {
            for (ProcessingMode mode : values()) {
                if (mode.key.equalsIgnoreCase(key) || mode.label.equalsIgnoreCase(key)) {
                    return mode;
                }
            }
        }
        return SYSTEM_EQ;
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
