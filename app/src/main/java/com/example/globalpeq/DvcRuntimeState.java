package com.example.globalpeq;

import org.json.JSONException;
import org.json.JSONObject;

final class DvcRuntimeState {
    enum Kind {
        OFF,
        ACTIVE,
        BLUETOOTH_UNAVAILABLE,
        USB_HARDWARE,
        USB_DIGITAL_ONLY,
        ROUTE_UNAVAILABLE,
        PROBE_FAILED
    }

    static final DvcRuntimeState DEFAULT = new DvcRuntimeState(
            Kind.OFF, false, true, "", "", 0, 0, 0, 0, "");

    final Kind kind;
    final boolean active;
    final boolean switchAvailable;
    final String routeKey;
    final String routeLabel;
    final int initialVolumeIndex;
    final int currentVolumeIndex;
    final int minVolumeIndex;
    final int maxVolumeIndex;
    final String detail;

    DvcRuntimeState(Kind kind,
                    boolean active,
                    boolean switchAvailable,
                    String routeKey,
                    String routeLabel,
                    int initialVolumeIndex,
                    int currentVolumeIndex,
                    int minVolumeIndex,
                    int maxVolumeIndex,
                    String detail) {
        this.kind = kind == null ? Kind.OFF : kind;
        this.active = active;
        this.switchAvailable = switchAvailable;
        this.routeKey = normalize(routeKey);
        this.routeLabel = normalize(routeLabel);
        this.initialVolumeIndex = initialVolumeIndex;
        this.currentVolumeIndex = currentVolumeIndex;
        this.minVolumeIndex = minVolumeIndex;
        this.maxVolumeIndex = maxVolumeIndex;
        this.detail = normalize(detail);
    }

    String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("kind", kind.name());
            object.put("active", active);
            object.put("switchAvailable", switchAvailable);
            object.put("routeKey", routeKey);
            object.put("routeLabel", routeLabel);
            object.put("initialVolumeIndex", initialVolumeIndex);
            object.put("currentVolumeIndex", currentVolumeIndex);
            object.put("minVolumeIndex", minVolumeIndex);
            object.put("maxVolumeIndex", maxVolumeIndex);
            object.put("detail", detail);
            return object.toString();
        } catch (JSONException ignored) {
            return "{}";
        }
    }

    static DvcRuntimeState fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return DEFAULT;
        }
        try {
            JSONObject object = new JSONObject(json);
            Kind kind;
            try {
                kind = Kind.valueOf(object.optString("kind", Kind.OFF.name()));
            } catch (IllegalArgumentException ignored) {
                kind = Kind.OFF;
            }
            return new DvcRuntimeState(
                    kind,
                    object.optBoolean("active", false),
                    object.optBoolean("switchAvailable", true),
                    object.optString("routeKey", ""),
                    object.optString("routeLabel", ""),
                    object.optInt("initialVolumeIndex", 0),
                    object.optInt("currentVolumeIndex", 0),
                    object.optInt("minVolumeIndex", 0),
                    object.optInt("maxVolumeIndex", 0),
                    object.optString("detail", ""));
        } catch (JSONException ignored) {
            return DEFAULT;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
