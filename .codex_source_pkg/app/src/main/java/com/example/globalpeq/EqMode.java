package com.example.globalpeq;

enum EqMode {
    PEQ("PEQ", "Global PEQ"),
    GEQ("GEQ", "Global GEQ");

    final String key;
    final String label;

    EqMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    static EqMode fromKey(String key) {
        if (key != null) {
            for (EqMode mode : values()) {
                if (mode.key.equalsIgnoreCase(key) || mode.label.equalsIgnoreCase(key)) {
                    return mode;
                }
            }
        }
        return PEQ;
    }

    static String[] labels() {
        EqMode[] modes = values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modes[i].label;
        }
        return labels;
    }
}
