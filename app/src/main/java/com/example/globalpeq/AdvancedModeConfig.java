package com.example.globalpeq;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AdvancedModeConfig {
    static final AdvancedModeConfig DEFAULT = new AdvancedModeConfig(
            "",
            "",
            90,
            512,
            750,
            24,
            88,
            Collections.emptyList()
    );

    final String monitoredAppPackage;
    final String monitoredAppLabel;
    final int latencyMs;
    final int bufferSizeFrames;
    final int monitorIntervalMs;
    final int lookaheadMs;
    final int wetMixPercent;
    final List<MonitoredAppItem> monitoredApps;

    AdvancedModeConfig(String monitoredAppPackage,
                       String monitoredAppLabel,
                       int latencyMs,
                       int bufferSizeFrames,
                       int monitorIntervalMs,
                       int lookaheadMs,
                       int wetMixPercent,
                       List<MonitoredAppItem> monitoredApps) {
        this.monitoredAppPackage = monitoredAppPackage == null ? "" : monitoredAppPackage.trim();
        this.monitoredAppLabel = monitoredAppLabel == null ? "" : monitoredAppLabel.trim();
        this.latencyMs = clamp(latencyMs, 20, 400);
        this.bufferSizeFrames = clamp(bufferSizeFrames, 128, 4096);
        this.monitorIntervalMs = clamp(monitorIntervalMs, 100, 5000);
        this.lookaheadMs = clamp(lookaheadMs, 0, 120);
        this.wetMixPercent = clamp(wetMixPercent, 0, 100);
        this.monitoredApps = Collections.unmodifiableList(normalizeApps(monitoredApps));
    }

    AdvancedModeConfig withMonitoredApp(String packageName, String label) {
        return new AdvancedModeConfig(packageName, label, latencyMs, bufferSizeFrames, monitorIntervalMs, lookaheadMs, wetMixPercent, monitoredApps);
    }

    AdvancedModeConfig withAddedMonitoredApp(String packageName, String label) {
        return new AdvancedModeConfig(
                packageName,
                label,
                latencyMs,
                bufferSizeFrames,
                monitorIntervalMs,
                lookaheadMs,
                wetMixPercent,
                appendMonitoredApp(monitoredApps, packageName, label)
        );
    }

    AdvancedModeConfig withLatencyMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, value, bufferSizeFrames, monitorIntervalMs, lookaheadMs, wetMixPercent, monitoredApps);
    }

    AdvancedModeConfig withBufferSizeFrames(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, value, monitorIntervalMs, lookaheadMs, wetMixPercent, monitoredApps);
    }

    AdvancedModeConfig withMonitorIntervalMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, bufferSizeFrames, value, lookaheadMs, wetMixPercent, monitoredApps);
    }

    AdvancedModeConfig withLookaheadMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, bufferSizeFrames, monitorIntervalMs, value, wetMixPercent, monitoredApps);
    }

    AdvancedModeConfig withWetMixPercent(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, latencyMs, bufferSizeFrames, monitorIntervalMs, lookaheadMs, value, monitoredApps);
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
            JSONArray apps = new JSONArray();
            for (MonitoredAppItem item : monitoredApps) {
                JSONObject app = new JSONObject();
                app.put("packageName", item.packageName);
                app.put("label", item.label);
                apps.put(app);
            }
            object.put("monitoredApps", apps);
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
                    object.optInt("wetMixPercent", DEFAULT.wetMixPercent),
                    parseApps(object.optJSONArray("monitoredApps"))
            );
        } catch (JSONException ignored) {
            return DEFAULT;
        }
    }

    private static List<MonitoredAppItem> parseApps(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<MonitoredAppItem> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject app = array.optJSONObject(i);
            if (app == null) {
                continue;
            }
            MonitoredAppItem item = new MonitoredAppItem(
                    app.optString("packageName", ""),
                    app.optString("label", "")
            );
            if (!item.packageName.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private static List<MonitoredAppItem> normalizeApps(List<MonitoredAppItem> source) {
        List<MonitoredAppItem> normalized = new ArrayList<>();
        if (source != null) {
            for (MonitoredAppItem item : source) {
                addNormalizedApp(normalized, item);
            }
        }
        return normalized;
    }

    private static List<MonitoredAppItem> appendMonitoredApp(List<MonitoredAppItem> source,
                                                             String packageName,
                                                             String label) {
        List<MonitoredAppItem> normalized = new ArrayList<>();
        if (source != null) {
            for (MonitoredAppItem item : source) {
                addNormalizedApp(normalized, item);
            }
        }
        addNormalizedApp(normalized, new MonitoredAppItem(packageName, label));
        return normalized;
    }

    private static void addNormalizedApp(List<MonitoredAppItem> items, MonitoredAppItem candidate) {
        if (candidate == null || candidate.packageName.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            MonitoredAppItem existing = items.get(i);
            if (!existing.packageName.equals(candidate.packageName)) {
                continue;
            }
            if (!candidate.label.isEmpty() && !candidate.label.equals(existing.label)) {
                items.set(i, candidate);
            }
            return;
        }
        items.add(candidate);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class MonitoredAppItem {
        final String packageName;
        final String label;

        MonitoredAppItem(String packageName, String label) {
            this.packageName = packageName == null ? "" : packageName.trim();
            this.label = label == null ? "" : label.trim();
        }
    }
}
