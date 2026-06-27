package com.example.globalpeq;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DeviceConfigFile {
    private static final String FILE_TYPE = "global_peq_device_config";
    private static final int SCHEMA_VERSION = 2;

    final AudioOutputDevice device;
    final ProcessingMode processingMode;
    final boolean autoSwitchOutput;
    final ModeState systemEqState;
    final ModeState shizukuState;
    final List<Preset> presets;

    // Legacy compatibility fields for older callers and configs.
    final AdvancedModeConfig advancedModeConfig;
    final Preset devicePreset;
    final String activePresetName;

    static final class ModeState {
        final ProcessingMode mode;
        final AdvancedModeConfig advancedModeConfig;
        final Preset devicePreset;
        final String activePresetName;

        ModeState(ProcessingMode mode,
                  AdvancedModeConfig advancedModeConfig,
                  Preset devicePreset,
                  String activePresetName) {
            this.mode = mode == null ? ProcessingMode.SYSTEM_EQ : mode;
            this.advancedModeConfig = advancedModeConfig == null ? AdvancedModeConfig.DEFAULT : advancedModeConfig;
            this.devicePreset = stripRuntimeEnabled(devicePreset);
            String fallbackName = this.devicePreset == null ? "Default" : this.devicePreset.name;
            String normalizedName = activePresetName == null ? "" : activePresetName.trim();
            this.activePresetName = normalizedName.isEmpty() ? fallbackName : normalizedName;
        }

        JSONObject toJsonObject() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("mode", mode.key);
            object.put("advancedModeConfig", new JSONObject(advancedModeConfig.toJson()));
            object.put("devicePreset", new JSONObject(devicePreset.toJson()));
            object.put("activePresetName", activePresetName);
            return object;
        }

        static ModeState fromJsonObject(JSONObject object, ProcessingMode fallbackMode) {
            if (object == null) {
                return defaultState(fallbackMode);
            }
            ProcessingMode mode = ProcessingMode.fromKey(object.optString("mode", fallbackMode.key));
            JSONObject advancedObject = object.optJSONObject("advancedModeConfig");
            JSONObject presetObject = object.optJSONObject("devicePreset");
            if (presetObject == null) {
                presetObject = object.optJSONObject("preset");
            }
            Preset preset = Preset.fromJson(presetObject == null ? null : presetObject.toString());
            String activePresetName = object.optString("activePresetName", preset.name);
            return new ModeState(
                    mode,
                    AdvancedModeConfig.fromJson(advancedObject == null ? null : advancedObject.toString()),
                    preset,
                    activePresetName);
        }

        static ModeState defaultState(ProcessingMode mode) {
            return new ModeState(mode, AdvancedModeConfig.DEFAULT, Preset.flat(false), "Default");
        }
    }

    DeviceConfigFile(AudioOutputDevice device,
                     ProcessingMode processingMode,
                     boolean autoSwitchOutput,
                     ModeState systemEqState,
                     ModeState shizukuState,
                     List<Preset> presets) {
        this.device = device == null ? new AudioOutputDevice("none", "Output device") : device;
        this.processingMode = processingMode == null ? ProcessingMode.SYSTEM_EQ : processingMode;
        this.autoSwitchOutput = autoSwitchOutput;
        this.systemEqState = systemEqState == null
                ? ModeState.defaultState(ProcessingMode.SYSTEM_EQ)
                : new ModeState(
                ProcessingMode.SYSTEM_EQ,
                systemEqState.advancedModeConfig,
                systemEqState.devicePreset,
                systemEqState.activePresetName);
        this.shizukuState = shizukuState == null
                ? ModeState.defaultState(ProcessingMode.SHIZUKU_MUTE)
                : new ModeState(
                ProcessingMode.SHIZUKU_MUTE,
                shizukuState.advancedModeConfig,
                shizukuState.devicePreset,
                shizukuState.activePresetName);
        this.presets = Collections.unmodifiableList(normalizePresets(presets));
        ModeState activeState = stateFor(this.processingMode);
        this.advancedModeConfig = activeState.advancedModeConfig;
        this.devicePreset = activeState.devicePreset;
        this.activePresetName = activeState.activePresetName;
    }

    DeviceConfigFile(AudioOutputDevice device,
                     ProcessingMode processingMode,
                     AdvancedModeConfig advancedModeConfig,
                     Preset devicePreset,
                     List<Preset> presets,
                     String activePresetName,
                     boolean autoSwitchOutput) {
        this(
                device,
                processingMode,
                autoSwitchOutput,
                processingMode == ProcessingMode.SYSTEM_EQ
                        ? new ModeState(ProcessingMode.SYSTEM_EQ, AdvancedModeConfig.DEFAULT, devicePreset, activePresetName)
                        : ModeState.defaultState(ProcessingMode.SYSTEM_EQ),
                processingMode == ProcessingMode.SHIZUKU_MUTE
                        ? new ModeState(ProcessingMode.SHIZUKU_MUTE, advancedModeConfig, devicePreset, activePresetName)
                        : new ModeState(ProcessingMode.SHIZUKU_MUTE, advancedModeConfig, Preset.flat(false), "Default"),
                presets);
    }

    ModeState stateFor(ProcessingMode mode) {
        return mode == ProcessingMode.SHIZUKU_MUTE ? shizukuState : systemEqState;
    }

    String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("fileType", FILE_TYPE);
            object.put("schemaVersion", SCHEMA_VERSION);
            object.put("deviceKey", device.key == null ? "" : device.key);
            object.put("deviceLabel", device.label == null ? "Output device" : device.label);
            object.put("processingMode", processingMode.key);
            object.put("autoSwitchOutput", autoSwitchOutput);
            object.put("systemEqState", systemEqState.toJsonObject());
            object.put("shizukuState", shizukuState.toJsonObject());
            JSONArray presetsArray = new JSONArray();
            for (Preset preset : presets) {
                presetsArray.put(new JSONObject(stripRuntimeEnabled(preset).toJson()));
            }
            object.put("presets", presetsArray);
            return object.toString(2);
        } catch (JSONException ignored) {
            return "{}";
        }
    }

    static DeviceConfigFile fromJson(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        if (!looksLikeDeviceConfig(object)) {
            throw new JSONException("Not a device config");
        }
        AudioOutputDevice device = new AudioOutputDevice(
                object.optString("deviceKey", "none"),
                object.optString("deviceLabel", "Output device")
        );
        ProcessingMode mode = ProcessingMode.fromKey(
                object.optString("processingMode", ProcessingMode.SYSTEM_EQ.key)
        );
        boolean autoSwitchOutput = object.optBoolean("autoSwitchOutput", true);
        List<Preset> presets = readPresetLibrary(object.optJSONArray("presets"));

        JSONObject systemObject = object.optJSONObject("systemEqState");
        JSONObject shizukuObject = object.optJSONObject("shizukuState");
        if (systemObject != null || shizukuObject != null) {
            return new DeviceConfigFile(
                    device,
                    mode,
                    autoSwitchOutput,
                    ModeState.fromJsonObject(systemObject, ProcessingMode.SYSTEM_EQ),
                    ModeState.fromJsonObject(shizukuObject, ProcessingMode.SHIZUKU_MUTE),
                    presets
            );
        }

        JSONObject advancedObject = object.optJSONObject("advancedModeConfig");
        JSONObject devicePresetObject = object.optJSONObject("devicePreset");
        if (devicePresetObject == null) {
            devicePresetObject = object.optJSONObject("preset");
        }
        Preset legacyPreset = Preset.fromJson(devicePresetObject == null ? null : devicePresetObject.toString());
        if (presets.isEmpty() && devicePresetObject != null) {
            presets.add(stripRuntimeEnabled(legacyPreset));
        }
        String activePresetName = object.optString("activePresetName", legacyPreset.name);
        ModeState legacyState = new ModeState(
                mode,
                AdvancedModeConfig.fromJson(advancedObject == null ? null : advancedObject.toString()),
                legacyPreset,
                activePresetName);
        ModeState systemState = mode == ProcessingMode.SYSTEM_EQ
                ? legacyState
                : ModeState.defaultState(ProcessingMode.SYSTEM_EQ);
        ModeState shizukuState = mode == ProcessingMode.SHIZUKU_MUTE
                ? legacyState
                : new ModeState(ProcessingMode.SHIZUKU_MUTE,
                AdvancedModeConfig.fromJson(advancedObject == null ? null : advancedObject.toString()),
                Preset.flat(false),
                "Default");
        return new DeviceConfigFile(device, mode, autoSwitchOutput, systemState, shizukuState, presets);
    }

    static boolean looksLikeDeviceConfig(JSONObject object) {
        if (object == null) {
            return false;
        }
        if (FILE_TYPE.equalsIgnoreCase(object.optString("fileType", ""))) {
            return true;
        }
        return object.has("deviceKey")
                && object.has("deviceLabel")
                && (object.has("devicePreset")
                || object.has("preset")
                || object.has("presets")
                || object.has("systemEqState")
                || object.has("shizukuState"));
    }

    private static List<Preset> readPresetLibrary(JSONArray presetsArray) {
        List<Preset> presets = new ArrayList<>();
        if (presetsArray == null) {
            return presets;
        }
        for (int i = 0; i < presetsArray.length(); i++) {
            JSONObject presetObject = presetsArray.optJSONObject(i);
            if (presetObject == null) {
                continue;
            }
            presets.add(stripRuntimeEnabled(Preset.fromJson(presetObject.toString())));
        }
        return presets;
    }

    private static List<Preset> normalizePresets(List<Preset> source) {
        List<Preset> normalized = new ArrayList<>();
        if (source == null) {
            return normalized;
        }
        for (Preset preset : source) {
            if (preset == null) {
                continue;
            }
            Preset sanitized = stripRuntimeEnabled(preset);
            int existingIndex = indexOfPreset(normalized, sanitized.name);
            if (existingIndex >= 0) {
                normalized.set(existingIndex, sanitized);
            } else {
                normalized.add(sanitized);
            }
        }
        return normalized;
    }

    private static int indexOfPreset(List<Preset> presets, String name) {
        if (presets == null || name == null) {
            return -1;
        }
        for (int i = 0; i < presets.size(); i++) {
            Preset preset = presets.get(i);
            if (preset != null && name.equalsIgnoreCase(preset.name)) {
                return i;
            }
        }
        return -1;
    }

    private static Preset stripRuntimeEnabled(Preset preset) {
        return preset == null ? Preset.flat(false) : preset.withEnabled(false);
    }
}
