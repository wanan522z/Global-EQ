package com.example.globalpeq;

enum FilterType {
    PEAK("PEAK"),
    LOW_SHELF("LSHELF"),
    HIGH_SHELF("HSHELF"),
    LOW_PASS("LPASS"),
    HIGH_PASS("HPASS");

    final String label;

    FilterType(String label) {
        this.label = label;
    }

    static FilterType fromLabel(String label) {
        for (FilterType type : values()) {
            if (type.label.equalsIgnoreCase(label) || type.name().equalsIgnoreCase(label)) {
                return type;
            }
        }
        if ("LowShelf".equalsIgnoreCase(label)) {
            return LOW_SHELF;
        }
        if ("HighShelf".equalsIgnoreCase(label)) {
            return HIGH_SHELF;
        }
        if ("LowPass".equalsIgnoreCase(label)) {
            return LOW_PASS;
        }
        if ("HighPass".equalsIgnoreCase(label)) {
            return HIGH_PASS;
        }
        return PEAK;
    }

    static String[] labels() {
        FilterType[] values = values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i].label;
        }
        return labels;
    }
}
