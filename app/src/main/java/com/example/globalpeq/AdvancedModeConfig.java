package com.example.globalpeq;

import org.json.JSONException;
import org.json.JSONObject;

final class AdvancedModeConfig {
    static final AdvancedModeConfig DEFAULT = new AdvancedModeConfig(
            "",
            "",
            90,
            512,
            750,
            24,
            88
    );

    final String monitoredAppPackage;
    final String monitoredAppLabel;
    final int latencyMs;
    final int bufferSizeFrames;
    final int monitorIntervalMs;
    final int lookaheadMs;
    final int wetMixPercent;

    AdvancedModeConfig(String monitoredAppPackage,
                       String monitoredAppLabel,
                       int latencyMs,
                       int bufferSizeFrames,
                       int monitorIntervalMs,
                       int lookaheadMs,
                       int wetMixPercent) {
        this.monitoredAppPackage = monitoredAppPackage == null ? "" : monitoredAppPackage.trim();
        this.monitoredAppLabel = monitoredAppLabel == null ? "" : monitoredAppLabel.trim();
        this.latencyMs = clamp(latencyMs, 20, 400);
        this.bufferSizeFrames = clamp(bufferSizeFrames, 128, 4096);
        this.monitorIntervalMs = clamp(monitorIntervalMs, 100, 5000);
        this.lookaheadMs = clamp(lookaheadMs, 0, 120);
        this.wetMixPercent = clamp(wetMixPercent, 0, 100);
    }

    AdvancedModeConfig withMonitoredApp(String packageName, String label) {
        return new AdvancedModeConfig(packageName, label, latencyMs, bufferSizeFrames, monitorIntervalMs, lookaheadMs, wetMixPercent);
    }

    AdvancedModeConfig withLatencyMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, value, bufferSizeFrames, monitorIntervalMs, lookaheadMs, wetMixPercent);
    }

    AdvancedModeConfig withBufferSizeFrames(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, value, monitorIntervalMs, lookaheadMs, wetMixPercent);
    }

    AdvancedModeConfig withMonitorIntervalMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, bufferSizeFrames, value, lookaheadMs, wetMixPercent);
    }

    AdvancedModeConfig withLookaheadMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, bufferSizeFrames, monitorIntervalMs, value, wetMixPercent);
    }

    AdvancedModeConfig withWetMixPercent(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, bufferSizeFrames, monitorIntervalMs, lookaheadMs, value);
    }

    String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("monitoredAppPackage", monitoredAppPackage);
            object.put("monitoredAppLabel", monitoredAppLabel);
            object.put("latencyMs", latencyMs);
            object.put("bufferSizeFrames", bufferSizeFrames);
            object.put("monitorIntervalMs", monitorIntervalMs);
            object.put("lookaheadMs", lookaheadMs);
            object.put("wetMixPercent", wetMixPercent);
        } catch (JSONException ignored) {
            return "{}";
        }
        return object.toString();
    }

    static AdvancedModeConfig fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return DEFAULT;
        }
        try {
            JSONObject object = new JSONObject(json);
            return new AdvancedModeConfig(
                    object.optString("monitoredAppPackage", ""),
                    object.optString("monitoredAppLabel", ""),
                    object.optInt("latencyMs", DEFAULT.latencyMs),
                    object.optInt("bufferSizeFrames", DEFAULT.bufferSizeFrames),
                    object.optInt("monitorIntervalMs", DEFAULT.monitorIntervalMs),
                    object.optInt("lookaheadMs", DEFAULT.lookaheadMs),
                    object.optInt("wetMixPercent", DEFAULT.wetMixPercent)
            );
        } catch (JSONException ignored) {
            return DEFAULT;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
