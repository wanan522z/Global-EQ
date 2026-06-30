package com.example.globalpeq;

final class AudioOutputDevice {
    final String key;
    final String label;
    final String routeSignature;

    AudioOutputDevice(String key, String label) {
        this(key, label, key);
    }

    AudioOutputDevice(String key, String label, String routeSignature) {
        this.key = key;
        this.label = displayLabel(label);
        this.routeSignature = routeSignature == null || routeSignature.trim().isEmpty()
                ? (key == null ? "" : key)
                : routeSignature.trim();
    }

    private static String displayLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "Output device";
        }
        String cleaned = label.trim().replaceFirst(
                "^(Bluetooth SCO|Bluetooth|USB DAC|USB headset|Wired headphones|Wired headset|Speaker|Output \\d+)\\s+-\\s+",
                ""
        );
        cleaned = cleaned.replaceFirst("(?i)^dontapply[a-z]*volume\\s*", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (isSystemPolicyName(cleaned)) {
            return "Output device";
        }
        return cleaned;
    }

    boolean isDisplayable() {
        return !isSystemPolicyName(label) && !isGenericOutputLabel(label);
    }

    private static boolean isSystemPolicyName(String value) {
        if (value == null) {
            return true;
        }
        String compact = value.toLowerCase().replaceAll("[^a-z0-9]+", "");
        return compact.isEmpty()
                || compact.contains("audiopolicy")
                || compact.contains("volume");
    }

    private static boolean isGenericOutputLabel(String value) {
        if (value == null) {
            return true;
        }
        String compact = value.toLowerCase().replaceAll("[^a-z0-9]+", "");
        return compact.equals("outputdevice")
                || compact.equals("defaultoutput")
                || compact.equals("nooutputdevice");
    }
}
