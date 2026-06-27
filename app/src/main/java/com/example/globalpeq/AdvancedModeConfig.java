package com.example.globalpeq;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AdvancedModeConfig {
    private static final int DSP_SAMPLE_RATE = 48000;
    private static final int PRE_RENDER_DIVISOR = 4;
    private static final int PRE_RENDER_BLOCK_STEP = 64;

    static final AdvancedModeConfig DEFAULT = new AdvancedModeConfig(
            "",
            "",
            90,
            512,
            750,
            24,
            Collections.emptyList()
    );

    final String monitoredAppPackage;
    final String monitoredAppLabel;
    final int preRenderMs;
    final int latencyMs;
    final int bufferSizeFrames;
    final int monitorIntervalMs;
    final int lookaheadMs;
    final List<MonitoredAppItem> monitoredApps;

    AdvancedModeConfig(String monitoredAppPackage,
                       String monitoredAppLabel,
                       int latencyMs,
                       int bufferSizeFrames,
                       int monitorIntervalMs,
                       int lookaheadMs,
                       List<MonitoredAppItem> monitoredApps) {
        this.monitoredAppPackage = monitoredAppPackage == null ? "" : monitoredAppPackage.trim();
        this.monitoredAppLabel = monitoredAppLabel == null ? "" : monitoredAppLabel.trim();
        this.preRenderMs = normalizePreRenderMs(latencyMs, bufferSizeFrames);
        this.latencyMs = this.preRenderMs;
        this.bufferSizeFrames = deriveBufferSizeFrames(this.preRenderMs);
        this.monitorIntervalMs = clamp(monitorIntervalMs, 100, 5000);
        this.lookaheadMs = clamp(lookaheadMs, 0, 120);
        this.monitoredApps = Collections.unmodifiableList(normalizeApps(monitoredApps));
    }

    AdvancedModeConfig withMonitoredApp(String packageName, String label) {
        return new AdvancedModeConfig(packageName, label, preRenderMs, bufferSizeFrames, monitorIntervalMs, lookaheadMs, monitoredApps);
    }

    AdvancedModeConfig withAddedMonitoredApp(String packageName, String label) {
        return new AdvancedModeConfig(
                packageName,
                label,
                preRenderMs,
                bufferSizeFrames,
                monitorIntervalMs,
                lookaheadMs,
                appendMonitoredApp(monitoredApps, packageName, label)
        );
    }

    AdvancedModeConfig withPreRenderMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, value, deriveBufferSizeFrames(value), monitorIntervalMs, lookaheadMs, monitoredApps);
    }

    AdvancedModeConfig withLatencyMs(int value) {
        return withPreRenderMs(value);
    }

    AdvancedModeConfig withBufferSizeFrames(int value) {
        return withPreRenderMs(derivePreRenderMsFromBuffer(value));
    }

    AdvancedModeConfig withMonitorIntervalMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, preRenderMs, bufferSizeFrames, value, lookaheadMs, monitoredApps);
    }

    AdvancedModeConfig withLookaheadMs(int value) {
        return new AdvancedModeConfig(monitoredAppPackage, monitoredAppLabel, preRenderMs, bufferSizeFrames, monitorIntervalMs, value, monitoredApps);
    }

    String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("monitoredAppPackage", monitoredAppPackage);
            object.put("monitoredAppLabel", monitoredAppLabel);
            object.put("preRenderMs", preRenderMs);
            object.put("latencyMs", latencyMs);
            object.put("bufferSizeFrames", bufferSizeFrames);
            object.put("monitorIntervalMs", monitorIntervalMs);
            object.put("lookaheadMs", lookaheadMs);
            JSONArray apps = new JSONArray();
            for (MonitoredAppItem item : monitoredApps) {
                JSONObject app = new JSONObject();
                app.put("packageName", item.packageName);
                app.put("label", item.label);
                apps.put(app);
            }
            object.put("monitoredApps", apps);
            object.put("manualMonitoredApps", apps);
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
            int preRenderMs = object.optInt("preRenderMs", -1);
            int legacyLatencyMs = object.optInt("latencyMs", DEFAULT.preRenderMs);
            int legacyBufferSizeFrames = object.optInt("bufferSizeFrames", DEFAULT.bufferSizeFrames);
            return new AdvancedModeConfig(
                    object.optString("monitoredAppPackage", ""),
                    object.optString("monitoredAppLabel", ""),
                    preRenderMs > 0 ? preRenderMs : legacyLatencyMs,
                    preRenderMs > 0 ? deriveBufferSizeFrames(preRenderMs) : legacyBufferSizeFrames,
                    object.optInt("monitorIntervalMs", DEFAULT.monitorIntervalMs),
                    object.optInt("lookaheadMs", DEFAULT.lookaheadMs),
                    parseApps(object.optJSONArray("manualMonitoredApps"))
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

    private static int normalizePreRenderMs(int latencyMs, int bufferSizeFrames) {
        int legacyLatency = clamp(latencyMs, 20, 400);
        int derivedFromBuffer = derivePreRenderMsFromBuffer(bufferSizeFrames);
        return clamp(Math.max(legacyLatency, derivedFromBuffer), 20, 400);
    }

    private static int derivePreRenderMsFromBuffer(int bufferSizeFrames) {
        int safeFrames = clamp(bufferSizeFrames, 128, 4096);
        int prerenderFrames = safeFrames * PRE_RENDER_DIVISOR;
        return clamp(Math.round(prerenderFrames * 1000f / DSP_SAMPLE_RATE), 20, 400);
    }

    private static int deriveBufferSizeFrames(int preRenderMs) {
        int safePreRenderMs = clamp(preRenderMs, 20, 400);
        int targetFrames = Math.round((DSP_SAMPLE_RATE * safePreRenderMs / 1000f) / (float) PRE_RENDER_DIVISOR);
        int steppedFrames = Math.round(targetFrames / (float) PRE_RENDER_BLOCK_STEP) * PRE_RENDER_BLOCK_STEP;
        return clamp(Math.max(PRE_RENDER_BLOCK_STEP * 2, steppedFrames), 128, 4096);
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
