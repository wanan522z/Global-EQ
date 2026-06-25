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
    final int pregainMb;
    final int virtualBassCutoffHz;
    final int virtualBassAmountPercent;
    final int systemBassBoostPercent;
    final ParametricBand[] bands;
    final int[] geqGainsMb;

    Preset(String name, EqMode mode, boolean enabled, int pregainMb, int virtualBassCutoffHz, int virtualBassAmountPercent, int systemBassBoostPercent, ParametricBand[] bands, int[] geqGainsMb) {
        this.name = name == null || name.trim().isEmpty() ? "Default" : name.trim();
        this.mode = mode == null ? EqMode.PEQ : mode;
        this.enabled = enabled;
        this.pregainMb = clamp(pregainMb, -2400, 1200);
        this.virtualBassCutoffHz = clamp(virtualBassCutoffHz, 60, 250);
        this.virtualBassAmountPercent = clamp(virtualBassAmountPercent, 0, 100);
        this.systemBassBoostPercent = clamp(systemBassBoostPercent, 0, 100);
        this.bands = bands;
        this.geqGainsMb = normalizedGeqGains(geqGainsMb);
    }

    static Preset flat(boolean enabled) {
        ParametricBand[] bands = new ParametricBand[DEFAULT_FILTER_COUNT];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = defaultBand(i);
        }
        return new Preset("Default", EqMode.PEQ, enabled, 0, 120, 0, 0, bands, new int[GEQ_BAND_COUNT]);
    }

    Preset withName(String nextName) {
        return copy(nextName, mode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, bands.clone(), geqGainsMb.clone());
    }

    Preset withMode(EqMode nextMode) {
        return copy(name, nextMode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, bands.clone(), geqGainsMb.clone());
    }

    Preset withEnabled(boolean nextEnabled) {
        return copy(name, mode, nextEnabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, bands.clone(), geqGainsMb.clone());
    }

    Preset withPregainMb(int nextPregainMb) {
        return copy(name, mode, enabled, nextPregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, bands.clone(), geqGainsMb.clone());
    }

    Preset withVirtualBassCutoffHz(int nextCutoffHz) {
        return copy(name, mode, enabled, pregainMb, nextCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, bands.clone(), geqGainsMb.clone());
    }

    Preset withVirtualBassAmountPercent(int nextAmountPercent) {
        return copy(name, mode, enabled, pregainMb, virtualBassCutoffHz, nextAmountPercent, systemBassBoostPercent, bands.clone(), geqGainsMb.clone());
    }

    Preset withSystemBassBoostPercent(int nextPercent) {
        return copy(name, mode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, nextPercent, bands.clone(), geqGainsMb.clone());
    }

    Preset withBand(int band, ParametricBand nextBand) {
        ParametricBand[] next = bands.clone();
        if (band >= 0 && band < next.length) {
            next[band] = nextBand;
        }
        return copy(name, mode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, next, geqGainsMb.clone());
    }

    Preset withGeqGainMb(int band, int gainMb) {
        int[] next = geqGainsMb.clone();
        if (band >= 0 && band < next.length) {
            next[band] = clamp(gainMb, -1800, 1800);
        }
        return copy(name, mode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, bands.clone(), next);
    }

    Preset withAddedBand() {
        ParametricBand[] next = new ParametricBand[bands.length + 1];
        System.arraycopy(bands, 0, next, 0, bands.length);
        next[bands.length] = new ParametricBand(FilterType.PEAK, true, 1000, 0, 100);
        return copy(name, mode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, next, geqGainsMb.clone());
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
        return copy(name, mode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, next, geqGainsMb.clone());
    }

    String toJson() {
        JSONObject object = new JSONObject();
        JSONArray peqBands = new JSONArray();
        JSONArray geqBands = new JSONArray();
        try {
            object.put("name", name);
            object.put("mode", mode.key);
            object.put("enabled", enabled);
            object.put("pregainMb", pregainMb);
            object.put("virtualBassCutoffHz", virtualBassCutoffHz);
            object.put("virtualBassAmountPercent", virtualBassAmountPercent);
            object.put("systemBassBoostPercent", systemBassBoostPercent);
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

            JSONArray oldGains = object.optJSONArray("gainsMb");
            if (peqBands == null && oldGains != null) {
                for (int i = 0; i < parsed.length && i < oldGains.length(); i++) {
                    parsed[i] = parsed[i].withGainMb(oldGains.optInt(i, 0));
                }
            }
            int[] geqGains = new int[GEQ_BAND_COUNT];
            JSONArray savedGeq = object.optJSONArray("geqGainsMb");
            if (savedGeq == null) {
                savedGeq = oldGains;
            }
            if (savedGeq != null) {
                for (int i = 0; i < geqGains.length && i < savedGeq.length(); i++) {
                    geqGains[i] = clamp(savedGeq.optInt(i, 0), -1800, 1800);
                }
            }
            return new Preset(
                    object.optString("name", "Default"),
                    EqMode.fromKey(object.optString("mode", EqMode.PEQ.key)),
                    object.optBoolean("enabled", false),
                    object.optInt("pregainMb", 0),
                    object.optInt("virtualBassCutoffHz", 120),
                    object.optInt("virtualBassAmountPercent", 0),
                    object.optInt("systemBassBoostPercent", 0),
                    parsed,
                    geqGains
            );
        } catch (JSONException ex) {
            return flat(false);
        }
    }

    private static Preset copy(String name, EqMode mode, boolean enabled, int pregainMb, int virtualBassCutoffHz, int virtualBassAmountPercent, int systemBassBoostPercent, ParametricBand[] bands, int[] geqGainsMb) {
        return new Preset(name, mode, enabled, pregainMb, virtualBassCutoffHz, virtualBassAmountPercent, systemBassBoostPercent, bands, geqGainsMb);
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
}
