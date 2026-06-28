package com.example.globalpeq;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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
    private static final String TAG = "GlobalPEQ-VB";
    private static final String PREFS = "global_peq";
    private static final String GLOBAL_PRESET = "global_preset";
    private static final String DRAFT_PRESET = "draft_preset";
    private static final String LAST_DEVICE_KEY = "last_device_key";
    private static final String LAST_DEVICE_LABEL = "last_device_label";
    private static final String AUTO_SWITCH_OUTPUT = "auto_switch_output";
    private static final String PROCESSING_MODE = "processing_mode";
    private static final String UI_LANGUAGE = "ui_language";
    private static final String ADVANCED_MODE_CONFIG = "advanced_mode_config";
    private static final String MASTER_ENABLED = "master_enabled";
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
    private static final String MONITOR_CAPTURE_STATUS = "monitor_capture_status";
    private static final String MONITOR_CAPTURE_ACTIVE = "monitor_capture_active";
    private static final String MONITOR_CAPTURE_AUTHORIZED = "monitor_capture_authorized";
    private static final String SHIZUKU_MUTE_STATUS = "shizuku_mute_status";
    private static final String SHIZUKU_MUTE_ACTIVE = "shizuku_mute_active";
    private static final String ACTIVE_PLAYBACK_PACKAGE = "active_playback_package";
    private static final String SERVICE_ACTIVE = "service_active";
    private static final String DEVICE_SEPARATOR = "\t";

    private final Context appContext;
    private final SharedPreferences prefs;

    PresetRepository(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Preset loadGlobalPreset() {
        return stripRuntimeEnabled(Preset.fromJson(prefs.getString(GLOBAL_PRESET, null)));
    }

    void saveGlobalPreset(Preset preset) {
        prefs.edit().putString(GLOBAL_PRESET, stripRuntimeEnabled(preset).toJson()).apply();
    }

    void saveDraftPreset(Preset preset) {
        prefs.edit().putString(DRAFT_PRESET, stripRuntimeEnabled(preset).toJson()).apply();
    }

    Preset loadPreset(AudioOutputDevice device) {
        return loadPreset(device, ProcessingMode.SYSTEM_EQ);
    }

    Preset loadPreset(AudioOutputDevice device, ProcessingMode mode) {
        String json = prefs.getString(deviceKey(device, mode), null);
        if (json == null) {
            json = prefs.getString(legacyDeviceKey(device), null);
        }
        if (json != null) {
            Preset preset = stripRuntimeEnabled(Preset.fromJson(json));
            Log.d(TAG, "repo loadPreset device=" + (device == null ? "null" : device.key)
                    + " mode=" + (mode == null ? "null" : mode.key)
                    + " modeIndex=" + preset.virtualBassModeIndex
                    + " activeAmount=" + preset.virtualBassAmountPercent
                    + " systemAmount=" + preset.systemVirtualBassAmountPercent
                    + " dspAmount=" + preset.dspVirtualBassAmountPercent);
            return preset;
        }
        Preset preset = loadDefaultDevicePreset();
        if (device != null && device.key != null && !device.key.trim().isEmpty()) {
            prefs.edit().putString(deviceKey(device, mode), stripRuntimeEnabled(preset).toJson()).apply();
        }
        return stripRuntimeEnabled(preset);
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

    ProcessingMode loadProcessingMode() {
        return ProcessingMode.fromKey(prefs.getString(PROCESSING_MODE, ProcessingMode.SYSTEM_EQ.key));
    }

    void saveProcessingMode(ProcessingMode mode) {
        prefs.edit()
                .putString(PROCESSING_MODE, mode == null ? ProcessingMode.SYSTEM_EQ.key : mode.key)
                .commit();
    }

    String loadUiLanguage() {
        String value = prefs.getString(UI_LANGUAGE, "en");
        return "zh".equalsIgnoreCase(value) ? "zh" : "en";
    }

    void saveUiLanguage(String language) {
        prefs.edit()
                .putString(UI_LANGUAGE, "zh".equalsIgnoreCase(language) ? "zh" : "en")
                .apply();
    }

    AdvancedModeConfig loadAdvancedModeConfig() {
        return AdvancedModeConfig.fromJson(prefs.getString(ADVANCED_MODE_CONFIG, null));
    }

    void saveAdvancedModeConfig(AdvancedModeConfig config) {
        AdvancedModeConfig safe = config == null ? AdvancedModeConfig.DEFAULT : config;
        prefs.edit()
                .putString(ADVANCED_MODE_CONFIG, safe.toJson())
                .commit();
    }

    String loadMonitorCaptureStatus() {
        return prefs.getString(MONITOR_CAPTURE_STATUS, "Native capture is idle.");
    }

    boolean loadMonitorCaptureActive() {
        return prefs.getBoolean(MONITOR_CAPTURE_ACTIVE, false);
    }

    boolean loadMonitorCaptureAuthorized() {
        return prefs.getBoolean(MONITOR_CAPTURE_AUTHORIZED, false);
    }

    void saveMonitorCaptureStatus(String status, boolean active) {
        prefs.edit()
                .putString(MONITOR_CAPTURE_STATUS, status == null ? "Native capture is idle." : status)
                .putBoolean(MONITOR_CAPTURE_ACTIVE, active)
                .apply();
    }

    void saveMonitorCaptureAuthorized(boolean authorized) {
        prefs.edit()
                .putBoolean(MONITOR_CAPTURE_AUTHORIZED, authorized)
                .apply();
    }

    String loadShizukuMuteStatus() {
        return prefs.getString(SHIZUKU_MUTE_STATUS, "Shizuku mute is idle.");
    }

    boolean loadShizukuMuteActive() {
        return prefs.getBoolean(SHIZUKU_MUTE_ACTIVE, false);
    }

    void saveShizukuMuteStatus(String status, boolean active) {
        prefs.edit()
                .putString(SHIZUKU_MUTE_STATUS, status == null ? "Shizuku mute is idle." : status)
                .putBoolean(SHIZUKU_MUTE_ACTIVE, active)
                .apply();
    }

    boolean loadMasterEnabled() {
        return prefs.getBoolean(MASTER_ENABLED, false);
    }

    void saveMasterEnabled(boolean enabled) {
        prefs.edit().putBoolean(MASTER_ENABLED, enabled).apply();
    }

    String loadActivePlaybackPackage() {
        String value = prefs.getString(ACTIVE_PLAYBACK_PACKAGE, "");
        return value == null ? "" : value;
    }

    void saveActivePlaybackPackage(String packageName) {
        prefs.edit()
                .putString(ACTIVE_PLAYBACK_PACKAGE, packageName == null ? "" : packageName.trim())
                .apply();
    }

    boolean loadServiceActive() {
        return prefs.getBoolean(SERVICE_ACTIVE, false);
    }

    void saveServiceActive(boolean active) {
        prefs.edit().putBoolean(SERVICE_ACTIVE, active).apply();
    }

    void clearRuntimeAudioState(String shizukuStatus) {
        prefs.edit()
                .putString(MONITOR_CAPTURE_STATUS, "Native capture is idle.")
                .putBoolean(MONITOR_CAPTURE_ACTIVE, false)
                .putBoolean(MONITOR_CAPTURE_AUTHORIZED, false)
                .putString(SHIZUKU_MUTE_STATUS, shizukuStatus == null ? "Shizuku mute is idle." : shizukuStatus)
                .putBoolean(SHIZUKU_MUTE_ACTIVE, false)
                .putString(ACTIVE_PLAYBACK_PACKAGE, "")
                .putBoolean(SERVICE_ACTIVE, false)
                .apply();
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
        savePreset(device, ProcessingMode.SYSTEM_EQ, preset);
    }

    void savePreset(AudioOutputDevice device, ProcessingMode mode, Preset preset) {
        Log.d(TAG, "repo savePreset device=" + (device == null ? "null" : device.key)
                + " mode=" + (mode == null ? "null" : mode.key)
                + " modeIndex=" + (preset == null ? -1 : preset.virtualBassModeIndex)
                + " activeAmount=" + (preset == null ? -1 : preset.virtualBassAmountPercent)
                + " systemAmount=" + (preset == null ? -1 : preset.systemVirtualBassAmountPercent)
                + " dspAmount=" + (preset == null ? -1 : preset.dspVirtualBassAmountPercent));
        prefs.edit()
                .putString(deviceKey(device, mode), stripRuntimeEnabled(preset).toJson())
                .putString(LAST_DEVICE_KEY, device.key)
                .putString(LAST_DEVICE_LABEL, device.label)
                .apply();
    }

    void saveNamedPreset(Preset preset) {
        Set<String> names = new HashSet<>(prefs.getStringSet(NAMED_PRESETS, Collections.emptySet()));
        names.add(preset.name);
        prefs.edit()
                .putString(namedPresetKey(preset.name), stripRuntimeEnabled(preset).toJson())
                .putStringSet(NAMED_PRESETS, names)
                .commit();
    }

    void renameNamedPreset(String oldName, Preset renamedPreset) {
        Set<String> names = new HashSet<>(prefs.getStringSet(NAMED_PRESETS, Collections.emptySet()));
        names.remove(oldName);
        names.add(renamedPreset.name);
        prefs.edit()
                .remove(namedPresetKey(oldName))
                .putString(namedPresetKey(renamedPreset.name), stripRuntimeEnabled(renamedPreset).toJson())
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
        return stripRuntimeEnabled(Preset.fromJson(prefs.getString(namedPresetKey(name), null)));
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

    boolean hasDeviceCurveName(String name) {
        return curveNameExists(DEVICE_CURVES, name, false);
    }

    boolean hasTargetCurveName(String name) {
        return curveNameExists(TARGET_CURVES, name, true);
    }

    boolean renameDeviceCurve(String oldName, String newName) {
        return renameCurve(DEVICE_CURVES, oldName, newName, false);
    }

    boolean renameTargetCurve(String oldName, String newName) {
        return renameCurve(TARGET_CURVES, oldName, newName, true);
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
        ProcessingMode mode = loadProcessingMode();
        String json = prefs.getString("preset_" + key + "__" + mode.key, null);
        if (json == null) {
            json = prefs.getString("preset_" + key, null);
        }
        return stripRuntimeEnabled(Preset.fromJson(json));
    }

    private String deviceKey(AudioOutputDevice device, ProcessingMode mode) {
        String safeModeKey = mode == null ? ProcessingMode.SYSTEM_EQ.key : mode.key;
        return "preset_" + device.key + "__" + safeModeKey;
    }

    private String legacyDeviceKey(AudioOutputDevice device) {
        return "preset_" + device.key;
    }

    private String namedPresetKey(String name) {
        return "named_preset_" + name.trim().toLowerCase().replaceAll("[^a-z0-9_\\-]+", "_");
    }

    private Preset loadDefaultDevicePreset() {
        Preset namedDefault = loadNamedPreset("Default");
        if (namedDefault != null) {
            return namedDefault.withName("Default");
        }
        return Preset.flat(false).withName("Default");
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

    private boolean renameCurve(String setKey, String oldName, String newName, boolean targetCurve) {
        String normalizedOld = normalizeCurveName(oldName);
        String normalizedNew = normalizeCurveName(newName);
        if ("Default".equals(normalizedOld) || "Default".equals(normalizedNew)) {
            return false;
        }
        if (curveNameMatches(normalizedOld, normalizedNew)) {
            return true;
        }
        if (curveNameExists(setKey, normalizedNew, targetCurve)) {
            return false;
        }

        FrequencyCurve sourceCurve = targetCurve ? loadTargetCurve(normalizedOld) : loadDeviceCurve(normalizedOld);
        if (sourceCurve == null || sourceCurve.isDefault()) {
            return false;
        }

        FrequencyCurve renamedCurve = sourceCurve.withName(normalizedNew);
        saveCurve(setKey, renamedCurve);
        if (!(targetCurve && isBuiltInTargetCurveName(normalizedOld))) {
            deleteCurve(setKey, normalizedOld);
        }
        replaceCurveReferences(normalizedOld, normalizedNew, targetCurve);
        return true;
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

    private boolean curveNameExists(String setKey, String name, boolean targetCurve) {
        String normalized = normalizeCurveName(name);
        if ("Default".equals(normalized)) {
            return true;
        }
        if (targetCurve && isBuiltInTargetCurveName(normalized)) {
            return true;
        }
        Set<String> names = prefs.getStringSet(setKey, Collections.emptySet());
        for (String existing : names) {
            if (curveNameMatches(existing, normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBuiltInTargetCurveName(String name) {
        String normalized = normalizeCurveName(name);
        return "JM-1".equals(normalized) || "WoodenEarsTarget".equals(normalized);
    }

    private void replaceCurveReferences(String oldName, String newName, boolean targetCurve) {
        SharedPreferences.Editor editor = prefs.edit();
        String selectedKey = targetCurve ? SELECTED_TARGET_CURVE : SELECTED_DEVICE_CURVE;
        String selectedName = prefs.getString(selectedKey, "Default");
        if (curveNameMatches(selectedName, oldName)) {
            editor.putString(selectedKey, newName);
        }

        updateCurveReferenceForPresetKey(editor, GLOBAL_PRESET, oldName, newName, targetCurve);
        updateCurveReferenceForPresetKey(editor, DRAFT_PRESET, oldName, newName, targetCurve);

        String lastDeviceKey = prefs.getString(LAST_DEVICE_KEY, null);
        if (lastDeviceKey != null && !lastDeviceKey.trim().isEmpty()) {
            updateCurveReferenceForPresetKey(editor, "preset_" + lastDeviceKey, oldName, newName, targetCurve);
            updateCurveReferenceForPresetKey(editor, "preset_" + lastDeviceKey + "__" + ProcessingMode.SYSTEM_EQ.key, oldName, newName, targetCurve);
            updateCurveReferenceForPresetKey(editor, "preset_" + lastDeviceKey + "__" + ProcessingMode.SHIZUKU_MUTE.key, oldName, newName, targetCurve);
        }

        Set<String> knownDevices = prefs.getStringSet(KNOWN_DEVICES, Collections.emptySet());
        for (String device : knownDevices) {
            int separator = device.indexOf(DEVICE_SEPARATOR);
            if (separator <= 0) {
                continue;
            }
            String deviceKey = device.substring(0, separator);
            updateCurveReferenceForPresetKey(editor, "preset_" + deviceKey, oldName, newName, targetCurve);
            updateCurveReferenceForPresetKey(editor, "preset_" + deviceKey + "__" + ProcessingMode.SYSTEM_EQ.key, oldName, newName, targetCurve);
            updateCurveReferenceForPresetKey(editor, "preset_" + deviceKey + "__" + ProcessingMode.SHIZUKU_MUTE.key, oldName, newName, targetCurve);
        }

        Set<String> presetNames = prefs.getStringSet(NAMED_PRESETS, Collections.emptySet());
        for (String presetName : presetNames) {
            updateCurveReferenceForPresetKey(editor, namedPresetKey(presetName), oldName, newName, targetCurve);
        }
        editor.commit();
    }

    private void updateCurveReferenceForPresetKey(SharedPreferences.Editor editor, String prefKey, String oldName, String newName, boolean targetCurve) {
        String json = prefs.getString(prefKey, null);
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        Preset preset = Preset.fromJson(json);
        Preset updated = renameCurveReferenceInPreset(preset, oldName, newName, targetCurve);
        if (!updated.toJson().equals(preset.toJson())) {
            editor.putString(prefKey, updated.toJson());
        }
    }

    private Preset renameCurveReferenceInPreset(Preset preset, String oldName, String newName, boolean targetCurve) {
        if (preset == null) {
            return null;
        }
        if (targetCurve) {
            if (!curveNameMatches(preset.targetCurveName, oldName)) {
                return preset;
            }
            return preset.withCurveSettings(
                    preset.deviceCurveName,
                    newName,
                    preset.deviceCurveGainOffsetDb,
                    preset.targetCurveGainOffsetDb,
                    preset.deviceCurveSmoothing,
                    preset.targetCurveSmoothing
            );
        }
        if (!curveNameMatches(preset.deviceCurveName, oldName)) {
            return preset;
        }
        return preset.withCurveSettings(
                newName,
                preset.targetCurveName,
                preset.deviceCurveGainOffsetDb,
                preset.targetCurveGainOffsetDb,
                preset.deviceCurveSmoothing,
                preset.targetCurveSmoothing
        );
    }

    private String normalizeCurveName(String name) {
        return name == null || name.trim().isEmpty() ? "Default" : name.trim();
    }

    private String normalizeCurveSmoothing(String smoothing) {
        return smoothing == null || smoothing.trim().isEmpty() ? "Default" : smoothing.trim();
    }

    private String curveKey(String setKey, String name) {
        return setKey + "_" + curveStorageName(name);
    }

    private boolean curveNameMatches(String left, String right) {
        return curveStorageName(left).equals(curveStorageName(right));
    }

    private String curveStorageName(String name) {
        return normalizeCurveName(name).toLowerCase().replaceAll("[^a-z0-9_\\-]+", "_");
    }

    private Preset stripRuntimeEnabled(Preset preset) {
        return preset == null ? Preset.flat(false) : preset.withEnabled(false);
    }

}
