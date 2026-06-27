package com.example.globalpeq;

import org.json.JSONException;
import org.json.JSONObject;

final class ParametricBand {
    final FilterType type;
    final boolean enabled;
    final int frequencyHz;
    final int gainMb;
    final int qHundred;

    ParametricBand(FilterType type, boolean enabled, int frequencyHz, int gainMb, int qHundred) {
        this.type = type == null ? FilterType.PEAK : type;
        this.enabled = enabled;
        this.frequencyHz = clamp(frequencyHz, 20, 20000);
        this.gainMb = clamp(gainMb, -1800, 1800);
        this.qHundred = clamp(qHundred, 20, 1000);
    }

    ParametricBand withType(FilterType nextType) {
        return new ParametricBand(nextType, enabled, frequencyHz, gainMb, qHundred);
    }

    ParametricBand withEnabled(boolean nextEnabled) {
        return new ParametricBand(type, nextEnabled, frequencyHz, gainMb, qHundred);
    }

    ParametricBand withFrequencyHz(int nextFrequencyHz) {
        return new ParametricBand(type, enabled, nextFrequencyHz, gainMb, qHundred);
    }

    ParametricBand withGainMb(int nextGainMb) {
        return new ParametricBand(type, enabled, frequencyHz, nextGainMb, qHundred);
    }

    ParametricBand withQHundred(int nextQHundred) {
        return new ParametricBand(type, enabled, frequencyHz, gainMb, nextQHundred);
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("type", type.label);
        object.put("enabled", enabled);
        object.put("frequencyHz", frequencyHz);
        object.put("gainMb", gainMb);
        object.put("qHundred", qHundred);
        return object;
    }

    static ParametricBand fromJson(JSONObject object, ParametricBand fallback) {
        if (object == null) {
            return fallback;
        }
        return new ParametricBand(
                FilterType.fromLabel(object.optString("type", fallback.type.label)),
                object.optBoolean("enabled", fallback.enabled),
                object.optInt("frequencyHz", fallback.frequencyHz),
                object.optInt("gainMb", fallback.gainMb),
                object.optInt("qHundred", fallback.qHundred)
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
