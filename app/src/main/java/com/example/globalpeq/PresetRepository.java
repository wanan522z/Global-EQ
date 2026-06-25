package com.example.globalpeq;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PresetRepository {
    private static final String PREFS = "global_peq";
    private static final String GLOBAL_PRESET = "global_preset";
    private static final String DRAFT_PRESET = "draft_preset";
    private static final String LAST_DEVICE_KEY = "last_device_key";
    private static final String LAST_DEVICE_LABEL = "last_device_label";
    private static final String AUTO_SWITCH_OUTPUT = "auto_switch_output";
    private static final String NAMED_PRESETS = "named_presets";
    private static final String KNOWN_DEVICES = "known_devices";
    private static final String DEVICE_CURVES = "device_curves";
    private static final String TARGET_CURVES = "target_curves";
    private static final String SELECTED_DEVICE_CURVE = "selected_device_curve";
    private static final String SELECTED_TARGET_CURVE = "selected_target_curve";
    private static final String DEVICE_CURVE_GAIN_OFFSET = "device_curve_gain_offset";
    private static final String TARGET_CURVE_GAIN_OFFSET = "target_curve_gain_offset";
    private static final String DEVICE_CURVE_SMOOTHING = "device_curve_smoothing";
    private static final String TARGET_CURVE_SMOOTHING = "target_curve_smoothing";
    private static final String DEVICE_SEPARATOR = "\t";

    private final Context appContext;
    private final SharedPreferences prefs;

    PresetRepository(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Preset loadGlobalPreset() {
        return Preset.fromJson(prefs.getString(GLOBAL_PRESET, null));
    }

    void saveGlobalPreset(Preset preset) {
        prefs.edit().putString(GLOBAL_PRESET, preset.toJson()).apply();
    }

    Preset loadDraftPreset() {
        String json = prefs.getString(DRAFT_PRESET, null);
        return json == null ? null : Preset.fromJson(json);
    }

    void saveDraftPreset(Preset preset) {
        prefs.edit().putString(DRAFT_PRESET, preset.toJson()).commit();
    }

    Preset loadPreset(AudioOutputDevice device) {
        String json = prefs.getString(deviceKey(device), null);
        if (json == null) {
            json = prefs.getString(GLOBAL_PRESET, null);
        }
        return Preset.fromJson(json);
    }

    void saveKnownDevice(AudioOutputDevice device) {
        if (device == null || device.key == null || device.key.trim().isEmpty() || !device.isDisplayable()) {
            return;
        }

        Set<String> devices = new HashSet<>(prefs.getStringSet(KNOWN_DEVICES, Collections.emptySet()));
        String prefix = device.key + DEVICE_SEPARATOR;
        devices.removeIf(value -> value.startsWith(prefix));
        devices.add(device.key + DEVICE_SEPARATOR + device.label);
        prefs.edit()
                .putStringSet(KNOWN_DEVICES, devices)
                .commit();
    }

    void saveSelectedDevice(AudioOutputDevice device) {
        if (device == null || device.key == null || device.key.trim().isEmpty()) {
            return;
        }
        saveKnownDevice(device);
        prefs.edit()
                .putString(LAST_DEVICE_KEY, device.key)
                .putString(LAST_DEVICE_LABEL, device.label)
                .apply();
    }

    boolean loadAutoSwitchOutput() {
        return prefs.getBoolean(AUTO_SWITCH_OUTPUT, true);
    }

    void saveAutoSwitchOutput(boolean enabled) {
        prefs.edit()
                .putBoolean(AUTO_SWITCH_OUTPUT, enabled)
                .commit();
    }

    AudioOutputDevice loadSelectedDevice() {
        String key = prefs.getString(LAST_DEVICE_KEY, null);
        String label = prefs.getString(LAST_DEVICE_LABEL, null);
        if (key == null || key.trim().isEmpty() || label == null || label.trim().isEmpty()) {
            return null;
        }
        return new AudioOutputDevice(key, label);
    }

    List<AudioOutputDevice> loadKnownDevices() {
        Set<String> saved = prefs.getStringSet(KNOWN_DEVICES, Collections.emptySet());
        List<AudioOutputDevice> devices = new ArrayList<>();
        Set<String> seenLabels = new HashSet<>();
        Set<String> cleaned = new HashSet<>();
        for (String value : saved) {
            int separator = value.indexOf(DEVICE_SEPARATOR);
            if (separator <= 0 || separator >= value.length() - 1) {
                continue;
            }
            AudioOutputDevice device = new AudioOutputDevice(
                    value.substring(0, separator),
                    value.substring(separator + DEVICE_SEPARATOR.length())
            );
            if (!device.isDisplayable()) {
                continue;
            }
            String labelKey = device.label.toLowerCase();
            if (!seenLabels.add(labelKey)) {
                continue;
            }
            devices.add(device);
            cleaned.add(device.key + DEVICE_SEPARATOR + device.label);
        }
        devices.sort((left, right) -> left.label.compareToIgnoreCase(right.label));
        if (!cleaned.equals(saved)) {
            prefs.edit().putStringSet(KNOWN_DEVICES, cleaned).commit();
        }
        return devices;
    }

    void savePreset(AudioOutputDevice device, Preset preset) {
        prefs.edit()
                .putString(deviceKey(device), preset.toJson())
                .putString(LAST_DEVICE_KEY, device.key)
                .putString(LAST_DEVICE_LABEL, device.label)
                .apply();
    }

    void saveNamedPreset(Preset preset) {
        Set<String> names = new HashSet<>(prefs.getStringSet(NAMED_PRESETS, Collections.emptySet()));
        names.add(preset.name);
        prefs.edit()
                .putString(namedPresetKey(preset.name), preset.toJson())
                .putStringSet(NAMED_PRESETS, names)
                .commit();
    }

    void renameNamedPreset(String oldName, Preset renamedPreset) {
        Set<String> names = new HashSet<>(prefs.getStringSet(NAMED_PRESETS, Collections.emptySet()));
        names.remove(oldName);
        names.add(renamedPreset.name);
        prefs.edit()
                .remove(namedPresetKey(oldName))
                .putString(namedPresetKey(renamedPreset.name), renamedPreset.toJson())
                .putStringSet(NAMED_PRESETS, names)
                .commit();
    }

    void deleteNamedPreset(String name) {
        Set<String> names = new HashSet<>(prefs.getStringSet(NAMED_PRESETS, Collections.emptySet()));
        names.remove(name);
        prefs.edit()
                .remove(namedPresetKey(name))
                .putStringSet(NAMED_PRESETS, names)
                .commit();
    }

    Preset loadNamedPreset(String name) {
        return Preset.fromJson(prefs.getString(namedPresetKey(name), null));
    }

    List<String> loadNamedPresetNames() {
        Set<String> names = prefs.getStringSet(NAMED_PRESETS, Collections.emptySet());
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return sorted;
    }

    void saveDeviceCurve(FrequencyCurve curve) {
        saveCurve(DEVICE_CURVES, curve);
    }

    void saveTargetCurve(FrequencyCurve curve) {
        saveCurve(TARGET_CURVES, curve);
    }

    void deleteDeviceCurve(String name) {
        deleteCurve(DEVICE_CURVES, name);
    }

    void deleteTargetCurve(String name) {
        deleteCurve(TARGET_CURVES, name);
    }

    FrequencyCurve loadDeviceCurve(String name) {
        return loadCurve(DEVICE_CURVES, name);
    }

    FrequencyCurve loadTargetCurve(String name) {
        if ("JM-1".equals(name)) {
            return loadRawCurve("JM-1", R.raw.jm1);
        }
        if ("WoodenEarsTarget".equals(name)) {
            return FrequencyCurve.builtInWoodenEars().normalizedAtHz(1000);
        }
        return loadCurve(TARGET_CURVES, name);
    }

    List<String> loadDeviceCurveNames() {
        return loadCurveNames(DEVICE_CURVES);
    }

    List<String> loadTargetCurveNames() {
        return loadCurveNames(TARGET_CURVES);
    }

    void saveSelectedDeviceCurveName(String name) {
        prefs.edit().putString(SELECTED_DEVICE_CURVE, normalizeCurveName(name)).commit();
    }

    void saveSelectedTargetCurveName(String name) {
        prefs.edit().putString(SELECTED_TARGET_CURVE, normalizeCurveName(name)).commit();
    }

    void saveDeviceCurveGainOffsetDb(float offsetDb) {
        prefs.edit().putFloat(DEVICE_CURVE_GAIN_OFFSET, offsetDb).apply();
    }

    void saveTargetCurveGainOffsetDb(float offsetDb) {
        prefs.edit().putFloat(TARGET_CURVE_GAIN_OFFSET, offsetDb).apply();
    }

    void saveDeviceCurveSmoothing(String smoothing) {
        prefs.edit().putString(DEVICE_CURVE_SMOOTHING, normalizeCurveSmoothing(smoothing)).apply();
    }

    void saveTargetCurveSmoothing(String smoothing) {
        prefs.edit().putString(TARGET_CURVE_SMOOTHING, normalizeCurveSmoothing(smoothing)).apply();
    }

    String loadSelectedDeviceCurveName() {
        return prefs.getString(SELECTED_DEVICE_CURVE, "Default");
    }

    String loadSelectedTargetCurveName() {
        return prefs.getString(SELECTED_TARGET_CURVE, "Default");
    }

    float loadDeviceCurveGainOffsetDb() {
        return prefs.getFloat(DEVICE_CURVE_GAIN_OFFSET, 0f);
    }

    float loadTargetCurveGainOffsetDb() {
        return prefs.getFloat(TARGET_CURVE_GAIN_OFFSET, 0f);
    }

    String loadDeviceCurveSmoothing() {
        return prefs.getString(DEVICE_CURVE_SMOOTHING, "Default");
    }

    String loadTargetCurveSmoothing() {
        return prefs.getString(TARGET_CURVE_SMOOTHING, "Default");
    }

    Preset loadLastPreset() {
        String key = prefs.getString(LAST_DEVICE_KEY, null);
        if (key == null) {
            return loadGlobalPreset();
        }
        return Preset.fromJson(prefs.getString("preset_" + key, null));
    }

    private String deviceKey(AudioOutputDevice device) {
        return "preset_" + device.key;
    }

    private String namedPresetKey(String name) {
        return "named_preset_" + name.trim().toLowerCase().replaceAll("[^a-z0-9_\\-]+", "_");
    }

    private void saveCurve(String setKey, FrequencyCurve curve) {
        if (curve == null || curve.isDefault()) {
            return;
        }
        Set<String> names = new HashSet<>(prefs.getStringSet(setKey, Collections.emptySet()));
        names.add(curve.name);
        prefs.edit()
                .putString(curveKey(setKey, curve.name), curve.toJson())
                .putStringSet(setKey, names)
                .commit();
    }

    private void deleteCurve(String setKey, String name) {
        if (name == null || name.trim().isEmpty() || "Default".equals(name)) {
            return;
        }
        Set<String> names = new HashSet<>(prefs.getStringSet(setKey, Collections.emptySet()));
        names.remove(name);
        prefs.edit()
                .remove(curveKey(setKey, name))
                .putStringSet(setKey, names)
                .commit();
    }

    private FrequencyCurve loadCurve(String setKey, String name) {
        if (name == null || name.trim().isEmpty() || "Default".equals(name)) {
            return FrequencyCurve.DEFAULT;
        }
        return FrequencyCurve.fromJson(prefs.getString(curveKey(setKey, name), null)).normalizedAtHz(1000);
    }

    private FrequencyCurve loadRawCurve(String name, int rawResourceId) {
        StringBuilder text = new StringBuilder();
        try (InputStream stream = appContext.getResources().openRawResource(rawResourceId);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        } catch (IOException | RuntimeException ex) {
            return FrequencyCurve.DEFAULT;
        }
        return FrequencyCurve.fromText(name, text.toString()).normalizedAtHz(1000);
    }

    private List<String> loadCurveNames(String setKey) {
        Set<String> names = prefs.getStringSet(setKey, Collections.emptySet());
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return sorted;
    }

    private String normalizeCurveName(String name) {
        return name == null || name.trim().isEmpty() ? "Default" : name.trim();
    }

    private String normalizeCurveSmoothing(String smoothing) {
        return smoothing == null || smoothing.trim().isEmpty() ? "Default" : smoothing.trim();
    }

    private String curveKey(String setKey, String name) {
        return setKey + "_" + normalizeCurveName(name).toLowerCase().replaceAll("[^a-z0-9_\\-]+", "_");
    }
}
