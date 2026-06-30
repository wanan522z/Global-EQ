package com.example.globalpeq;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Preset {
    static final int DEFAULT_FILTER_COUNT = 5;
    static final int GEQ_BAND_COUNT = 10;
    private static final int[] DEFAULT_FREQUENCIES = {80, 250, 1000, 4000, 10000};
    static final int[] GEQ_FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    final String name;
    final EqMode mode;
    final boolean enabled;
    final boolean extraBassEnabled;
    final int pregainMb;
    final int virtualBassModeIndex;
    final int virtualBassCutoffHz;
    final int extraBassCutoffHz;
    final int extraBassAmountPercent;
    final int virtualBassAmountPercent;
    final int systemVirtualBassCutoffHz;
    final int dspVirtualBassCutoffHz;
    final int systemVirtualBassAmountPercent;
    final int dspVirtualBassAmountPercent;
    final String reverbType;
    final int reverbDecayPercent;
    final int reverbPredelayMs;
    final int reverbSizePercent;
    final int reverbWetPercent;
    final int reverbDryMb;
    final String deviceCurveName;
    final String targetCurveName;
    final FrequencyCurve deviceCurveData;
    final FrequencyCurve targetCurveData;
    final float deviceCurveGainOffsetDb;
    final float targetCurveGainOffsetDb;
    final String deviceCurveSmoothing;
    final String targetCurveSmoothing;
    final ParametricBand[] bands;
    final int[] geqGainsMb;

    Preset(String name,
           EqMode mode,
           boolean enabled,
           boolean extraBassEnabled,
           int pregainMb,
           int virtualBassModeIndex,
           int systemVirtualBassCutoffHz,
           int dspVirtualBassCutoffHz,
           int extraBassCutoffHz,
           int extraBassAmountPercent,
           int systemVirtualBassAmountPercent,
           int dspVirtualBassAmountPercent,
           String reverbType,
           int reverbDecayPercent,
           int reverbPredelayMs,
           int reverbSizePercent,
           int reverbWetPercent,
           int reverbDryMb,
           String deviceCurveName,
           String targetCurveName,
           float deviceCurveGainOffsetDb,
           float targetCurveGainOffsetDb,
           String deviceCurveSmoothing,
           String targetCurveSmoothing,
           ParametricBand[] bands,
           int[] geqGainsMb) {
        this(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz,
                dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent,
                dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent,
                reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb,
                targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands, geqGainsMb,
                FrequencyCurve.DEFAULT, FrequencyCurve.DEFAULT);
    }

    Preset(String name,
           EqMode mode,
           boolean enabled,
           boolean extraBassEnabled,
           int pregainMb,
           int virtualBassModeIndex,
           int systemVirtualBassCutoffHz,
           int dspVirtualBassCutoffHz,
           int extraBassCutoffHz,
           int extraBassAmountPercent,
           int systemVirtualBassAmountPercent,
           int dspVirtualBassAmountPercent,
           String reverbType,
           int reverbDecayPercent,
           int reverbPredelayMs,
           int reverbSizePercent,
           int reverbWetPercent,
           int reverbDryMb,
           String deviceCurveName,
           String targetCurveName,
           float deviceCurveGainOffsetDb,
           float targetCurveGainOffsetDb,
           String deviceCurveSmoothing,
           String targetCurveSmoothing,
           ParametricBand[] bands,
           int[] geqGainsMb,
           FrequencyCurve deviceCurveData,
           FrequencyCurve targetCurveData) {
        this.name = name == null || name.trim().isEmpty() ? "Default" : name.trim();
        this.mode = mode == null ? EqMode.PEQ : mode;
        this.enabled = enabled;
        this.extraBassEnabled = extraBassEnabled;
        this.pregainMb = clamp(pregainMb, -2400, 1200);
        this.virtualBassModeIndex = clamp(virtualBassModeIndex, 0, 2);
        this.systemVirtualBassCutoffHz = clamp(systemVirtualBassCutoffHz, 20, 250);
        this.dspVirtualBassCutoffHz = clamp(dspVirtualBassCutoffHz, 20, 250);
        this.extraBassCutoffHz = clamp(extraBassCutoffHz, 60, 250);
        this.extraBassAmountPercent = clamp(extraBassAmountPercent, 0, 100);
        this.systemVirtualBassAmountPercent = clamp(systemVirtualBassAmountPercent, 0, 100);
        this.dspVirtualBassAmountPercent = clamp(dspVirtualBassAmountPercent, 0, 100);
        this.virtualBassCutoffHz = resolveActiveVirtualBassCutoffHz(
                this.virtualBassModeIndex,
                this.systemVirtualBassCutoffHz,
                this.dspVirtualBassCutoffHz);
        this.virtualBassAmountPercent = resolveActiveVirtualBassAmountPercent(
                this.virtualBassModeIndex,
                this.systemVirtualBassAmountPercent,
                this.dspVirtualBassAmountPercent);
        this.reverbType = normalizeReverbType(reverbType);
        this.reverbDecayPercent = clamp(reverbDecayPercent, 0, 1200);
        this.reverbPredelayMs = clamp(reverbPredelayMs, 0, 250);
        this.reverbSizePercent = clamp(reverbSizePercent, 0, 100);
        this.reverbWetPercent = clamp(reverbWetPercent, 0, 100);
        this.reverbDryMb = clamp(reverbDryMb, -12000, 300);
        this.deviceCurveName = normalizeCurveName(deviceCurveName);
        this.targetCurveName = normalizeCurveName(targetCurveName);
        this.deviceCurveData = normalizeEmbeddedCurve(this.deviceCurveName, deviceCurveData);
        this.targetCurveData = normalizeEmbeddedCurve(this.targetCurveName, targetCurveData);
        this.deviceCurveGainOffsetDb = clampFloat(deviceCurveGainOffsetDb, -24f, 24f);
        this.targetCurveGainOffsetDb = clampFloat(targetCurveGainOffsetDb, -24f, 24f);
        this.deviceCurveSmoothing = normalizeCurveSmoothing(deviceCurveSmoothing);
        this.targetCurveSmoothing = normalizeCurveSmoothing(targetCurveSmoothing);
        this.bands = bands;
        this.geqGainsMb = normalizedGeqGains(geqGainsMb);
    }

    static Preset flat(boolean enabled) {
        ParametricBand[] bands = new ParametricBand[DEFAULT_FILTER_COUNT];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = defaultBand(i);
        }
        return new Preset("Default", EqMode.PEQ, enabled, false, 0, 0, 95, 95, 120, 0, 0, 0, "Default", 0, 0, 0, 0, 0, "Default", "Default", 0f, 0f, "Default", "Default", bands, new int[GEQ_BAND_COUNT]);
    }

    Preset withName(String nextName) {
        return copy(nextName, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withMode(EqMode nextMode) {
        return copy(name, nextMode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withEnabled(boolean nextEnabled) {
        return copy(name, mode, nextEnabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withExtraBassEnabled(boolean nextEnabled) {
        return copy(name, mode, enabled, nextEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withPregainMb(int nextPregainMb) {
        return copy(name, mode, enabled, extraBassEnabled, nextPregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withVirtualBassModeIndex(int nextModeIndex) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, nextModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withVirtualBassCutoffHz(int nextCutoffHz) {
        if (virtualBassModeIndex == 2) {
            return withDspVirtualBassCutoffHz(nextCutoffHz);
        }
        return withSystemVirtualBassCutoffHz(nextCutoffHz);
    }

    Preset withSystemVirtualBassCutoffHz(int nextCutoffHz) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, nextCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withDspVirtualBassCutoffHz(int nextCutoffHz) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, nextCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withExtraBassCutoffHz(int nextCutoffHz) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, nextCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withExtraBassAmountPercent(int nextAmountPercent) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, nextAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withVirtualBassAmountPercent(int nextPercent) {
        if (virtualBassModeIndex == 2) {
            return withDspVirtualBassAmountPercent(nextPercent);
        }
        return withSystemVirtualBassAmountPercent(nextPercent);
    }

    Preset withSystemVirtualBassAmountPercent(int nextPercent) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, nextPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withDspVirtualBassAmountPercent(int nextPercent) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, nextPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withReverbType(String nextType) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, nextType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withReverbSendSettings(int nextDryMb, int nextDecayPercent, int nextPredelayMs, int nextSizePercent, int nextWetPercent) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, nextDecayPercent, nextPredelayMs, nextSizePercent, nextWetPercent, nextDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withCurveSettings(String nextDeviceCurveName, String nextTargetCurveName, float nextDeviceCurveGainOffsetDb, float nextTargetCurveGainOffsetDb, String nextDeviceCurveSmoothing, String nextTargetCurveSmoothing) {
        return withCurveSettings(nextDeviceCurveName, nextTargetCurveName, nextDeviceCurveGainOffsetDb, nextTargetCurveGainOffsetDb, nextDeviceCurveSmoothing, nextTargetCurveSmoothing, deviceCurveData, targetCurveData);
    }

    Preset withCurveSettings(String nextDeviceCurveName, String nextTargetCurveName, float nextDeviceCurveGainOffsetDb, float nextTargetCurveGainOffsetDb, String nextDeviceCurveSmoothing, String nextTargetCurveSmoothing, FrequencyCurve nextDeviceCurveData, FrequencyCurve nextTargetCurveData) {
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, nextDeviceCurveName, nextTargetCurveName, nextDeviceCurveGainOffsetDb, nextTargetCurveGainOffsetDb, nextDeviceCurveSmoothing, nextTargetCurveSmoothing, bands.clone(), geqGainsMb.clone(), nextDeviceCurveData, nextTargetCurveData);
    }

    Preset withBand(int band, ParametricBand nextBand) {
        ParametricBand[] next = bands.clone();
        if (band >= 0 && band < next.length) {
            next[band] = nextBand;
        }
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, next, geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withGeqGainMb(int band, int gainMb) {
        int[] next = geqGainsMb.clone();
        if (band >= 0 && band < next.length) {
            next[band] = clamp(gainMb, -1800, 1800);
        }
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands.clone(), next, deviceCurveData, targetCurveData);
    }

    Preset withAddedBand() {
        ParametricBand[] next = new ParametricBand[bands.length + 1];
        System.arraycopy(bands, 0, next, 0, bands.length);
        next[bands.length] = new ParametricBand(FilterType.PEAK, true, 1000, 0, 100);
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, next, geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    Preset withoutBand(int band) {
        if (bands.length <= 1 || band < 0 || band >= bands.length) {
            return this;
        }

        ParametricBand[] next = new ParametricBand[bands.length - 1];
        for (int i = 0, j = 0; i < bands.length; i++) {
            if (i != band) {
                next[j++] = bands[i];
            }
        }
        return copy(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, next, geqGainsMb.clone(), deviceCurveData, targetCurveData);
    }

    String toJson() {
        JSONObject object = new JSONObject();
        JSONArray peqBands = new JSONArray();
        JSONArray geqBands = new JSONArray();
        try {
            object.put("name", name);
            object.put("mode", mode.key);
            object.put("enabled", enabled);
            object.put("extraBassEnabled", extraBassEnabled);
            object.put("pregainMb", pregainMb);
            object.put("virtualBassModeIndex", virtualBassModeIndex);
            object.put("systemVirtualBassCutoffHz", systemVirtualBassCutoffHz);
            object.put("dspVirtualBassCutoffHz", dspVirtualBassCutoffHz);
            object.put("extraBassCutoffHz", extraBassCutoffHz);
            object.put("extraBassAmountPercent", extraBassAmountPercent);
            object.put("systemVirtualBassAmountPercent", systemVirtualBassAmountPercent);
            object.put("dspVirtualBassAmountPercent", dspVirtualBassAmountPercent);
            object.put("reverbType", reverbType);
            object.put("reverbDecayPercent", reverbDecayPercent);
            object.put("reverbPredelayMs", reverbPredelayMs);
            object.put("reverbSizePercent", reverbSizePercent);
            object.put("reverbWetPercent", reverbWetPercent);
            object.put("reverbDryMb", reverbDryMb);
            object.put("deviceCurveName", deviceCurveName);
            object.put("targetCurveName", targetCurveName);
            if (deviceCurveData != null && !deviceCurveData.isDefault()) {
                object.put("deviceCurveData", new JSONObject(deviceCurveData.toJson()));
            }
            if (targetCurveData != null && !targetCurveData.isDefault()) {
                object.put("targetCurveData", new JSONObject(targetCurveData.toJson()));
            }
            object.put("deviceCurveGainOffsetDb", deviceCurveGainOffsetDb);
            object.put("targetCurveGainOffsetDb", targetCurveGainOffsetDb);
            object.put("deviceCurveSmoothing", deviceCurveSmoothing);
            object.put("targetCurveSmoothing", targetCurveSmoothing);
            for (ParametricBand band : bands) {
                peqBands.put(band.toJson());
            }
            object.put("peqBands", peqBands);
            for (int gainMb : geqGainsMb) {
                geqBands.put(gainMb);
            }
            object.put("geqGainsMb", geqBands);
        } catch (JSONException ignored) {
            return "{}";
        }
        return object.toString();
    }

    static Preset fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return flat(false);
        }

        try {
            JSONObject object = new JSONObject(json);
            JSONArray peqBands = object.optJSONArray("peqBands");
            int parsedCount = peqBands == null ? DEFAULT_FILTER_COUNT : Math.max(DEFAULT_FILTER_COUNT, peqBands.length());
            ParametricBand[] parsed = new ParametricBand[parsedCount];
            for (int i = 0; i < parsed.length; i++) {
                JSONObject bandObject = peqBands == null ? null : peqBands.optJSONObject(i);
                parsed[i] = ParametricBand.fromJson(bandObject, defaultBand(i));
            }

            int[] geqGains = new int[GEQ_BAND_COUNT];
            JSONArray savedGeq = object.optJSONArray("geqGainsMb");
            if (savedGeq != null) {
                for (int i = 0; i < geqGains.length && i < savedGeq.length(); i++) {
                    geqGains[i] = clamp(savedGeq.optInt(i, 0), -1800, 1800);
                }
            }
            JSONObject deviceCurveObject = object.optJSONObject("deviceCurveData");
            JSONObject targetCurveObject = object.optJSONObject("targetCurveData");
            int modeIndex = object.optInt("virtualBassModeIndex", 0);
            return new Preset(
                    object.optString("name", "Default"),
                    EqMode.fromKey(object.optString("mode", EqMode.PEQ.key)),
                    object.optBoolean("enabled", false),
                    object.optBoolean("extraBassEnabled", false),
                    object.optInt("pregainMb", 0),
                    modeIndex,
                    object.optInt("systemVirtualBassCutoffHz", 95),
                    object.optInt("dspVirtualBassCutoffHz", 95),
                    object.optInt("extraBassCutoffHz", 120),
                    object.optInt("extraBassAmountPercent", 0),
                    object.optInt("systemVirtualBassAmountPercent", 0),
                    object.optInt("dspVirtualBassAmountPercent", 0),
                    object.optString("reverbType", "Default"),
                    object.optInt("reverbDecayPercent", 0),
                    object.optInt("reverbPredelayMs", 0),
                    object.optInt("reverbSizePercent", 0),
                    object.optInt("reverbWetPercent", 0),
                    object.optInt("reverbDryMb", 0),
                    object.optString("deviceCurveName", "Default"),
                    object.optString("targetCurveName", "Default"),
                    (float) object.optDouble("deviceCurveGainOffsetDb", 0.0),
                    (float) object.optDouble("targetCurveGainOffsetDb", 0.0),
                    object.optString("deviceCurveSmoothing", "Default"),
                    object.optString("targetCurveSmoothing", "Default"),
                    parsed,
                    geqGains,
                    FrequencyCurve.fromJson(deviceCurveObject == null ? null : deviceCurveObject.toString()),
                    FrequencyCurve.fromJson(targetCurveObject == null ? null : targetCurveObject.toString())
            );
        } catch (JSONException ex) {
            return flat(false);
        }
    }

    private static Preset copy(String name,
                               EqMode mode,
                               boolean enabled,
                               boolean extraBassEnabled,
                               int pregainMb,
                               int virtualBassModeIndex,
                               int systemVirtualBassCutoffHz,
                               int dspVirtualBassCutoffHz,
                               int extraBassCutoffHz,
                               int extraBassAmountPercent,
                               int systemVirtualBassAmountPercent,
                               int dspVirtualBassAmountPercent,
                               String reverbType,
                               int reverbDecayPercent,
                               int reverbPredelayMs,
                               int reverbSizePercent,
                               int reverbWetPercent,
                               int reverbDryMb,
                               String deviceCurveName,
                               String targetCurveName,
                               float deviceCurveGainOffsetDb,
                               float targetCurveGainOffsetDb,
                               String deviceCurveSmoothing,
                               String targetCurveSmoothing,
                               ParametricBand[] bands,
                               int[] geqGainsMb,
                               FrequencyCurve deviceCurveData,
                               FrequencyCurve targetCurveData) {
        return new Preset(name, mode, enabled, extraBassEnabled, pregainMb, virtualBassModeIndex, systemVirtualBassCutoffHz, dspVirtualBassCutoffHz, extraBassCutoffHz, extraBassAmountPercent, systemVirtualBassAmountPercent, dspVirtualBassAmountPercent, reverbType, reverbDecayPercent, reverbPredelayMs, reverbSizePercent, reverbWetPercent, reverbDryMb, deviceCurveName, targetCurveName, deviceCurveGainOffsetDb, targetCurveGainOffsetDb, deviceCurveSmoothing, targetCurveSmoothing, bands, geqGainsMb, deviceCurveData, targetCurveData);
    }

    private static int resolveActiveVirtualBassCutoffHz(int modeIndex, int systemCutoffHz, int dspCutoffHz) {
        return modeIndex == 2 ? dspCutoffHz : systemCutoffHz;
    }

    private static int resolveActiveVirtualBassAmountPercent(int modeIndex, int systemAmountPercent, int dspAmountPercent) {
        return modeIndex == 2 ? dspAmountPercent : systemAmountPercent;
    }

    private static int[] normalizedGeqGains(int[] values) {
        int[] normalized = new int[GEQ_BAND_COUNT];
        if (values == null) {
            return normalized;
        }
        for (int i = 0; i < normalized.length && i < values.length; i++) {
            normalized[i] = clamp(values[i], -1800, 1800);
        }
        return normalized;
    }

    private static ParametricBand defaultBand(int index) {
        int safeIndex = Math.max(0, Math.min(DEFAULT_FREQUENCIES.length - 1, index));
        return new ParametricBand(FilterType.PEAK, true, DEFAULT_FREQUENCIES[safeIndex], 0, 100);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeCurveName(String name) {
        return name == null || name.trim().isEmpty() ? "Default" : name.trim();
    }

    private static String normalizeCurveSmoothing(String smoothing) {
        return smoothing == null || smoothing.trim().isEmpty() ? "Default" : smoothing.trim();
    }

    private static FrequencyCurve normalizeEmbeddedCurve(String curveName, FrequencyCurve curve) {
        String normalizedName = normalizeCurveName(curveName);
        if ("Default".equals(normalizedName) || curve == null || curve.isDefault()) {
            return FrequencyCurve.DEFAULT;
        }
        return curve.withName(normalizedName);
    }

    private static String normalizeReverbType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "Default";
        }
        String normalized = type.trim();
        if ("Hall".equalsIgnoreCase(normalized)) {
            return "Hall";
        }
        if ("Plate".equalsIgnoreCase(normalized)) {
            return "Plate";
        }
        if ("Chamber".equalsIgnoreCase(normalized)) {
            return "Chamber";
        }
        if ("Room".equalsIgnoreCase(normalized)) {
            return "Room";
        }
        if ("Studio".equalsIgnoreCase(normalized)) {
            return "Studio";
        }
        return "Default";
    }
}
