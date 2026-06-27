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
            String fallbackName = this.devicePreset.name;
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

        static ModeState fromJsonObject(JSONObject object, ProcessingMode fallbackMode) throws JSONException {
            if (object == null) {
                throw new JSONException("Missing mode state: " + fallbackMode.key);
            }
            ProcessingMode mode = ProcessingMode.fromKey(object.optString("mode", fallbackMode.key));
            JSONObject advancedObject = object.optJSONObject("advancedModeConfig");
            JSONObject presetObject = object.optJSONObject("devicePreset");
            if (presetObject == null) {
                throw new JSONException("Missing devicePreset for mode: " + fallbackMode.key);
            }
            Preset preset = Preset.fromJson(presetObject.toString());
            String activePresetName = object.optString("activePresetName", preset.name);
            return new ModeState(
                    mode,
                    AdvancedModeConfig.fromJson(advancedObject == null ? null : advancedObject.toString()),
                    preset,
                    activePresetName);
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
        this.systemEqState = requireModeState(systemEqState, ProcessingMode.SYSTEM_EQ);
        this.shizukuState = requireModeState(shizukuState, ProcessingMode.SHIZUKU_MUTE);
        this.presets = Collections.unmodifiableList(normalizePresets(presets));
    }

    DeviceConfigFile(AudioOutputDevice device,
                     ProcessingMode processingMode,
                     AdvancedModeConfig advancedModeConfig,
                     Preset activeDevicePreset,
                     List<Preset> presets,
                     String activePresetName,
                     boolean autoSwitchOutput) {
        this(
                device,
                processingMode,
                autoSwitchOutput,
                processingMode == ProcessingMode.SYSTEM_EQ
                        ? new ModeState(ProcessingMode.SYSTEM_EQ, AdvancedModeConfig.DEFAULT, activeDevicePreset, activePresetName)
                        : new ModeState(ProcessingMode.SYSTEM_EQ, AdvancedModeConfig.DEFAULT, Preset.flat(false), "Default"),
                processingMode == ProcessingMode.SHIZUKU_MUTE
                        ? new ModeState(ProcessingMode.SHIZUKU_MUTE, advancedModeConfig, activeDevicePreset, activePresetName)
                        : new ModeState(ProcessingMode.SHIZUKU_MUTE, advancedModeConfig, Preset.flat(false), "Default"),
                presets
        );
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
        validateDeviceConfig(object);
        AudioOutputDevice device = new AudioOutputDevice(
                object.optString("deviceKey", "none"),
                object.optString("deviceLabel", "Output device")
        );
        ProcessingMode mode = ProcessingMode.fromKey(
                object.optString("processingMode", ProcessingMode.SYSTEM_EQ.key)
        );
        boolean autoSwitchOutput = object.optBoolean("autoSwitchOutput", true);
        List<Preset> presets = readPresetLibrary(object.optJSONArray("presets"));
        return new DeviceConfigFile(
                device,
                mode,
                autoSwitchOutput,
                ModeState.fromJsonObject(object.optJSONObject("systemEqState"), ProcessingMode.SYSTEM_EQ),
                ModeState.fromJsonObject(object.optJSONObject("shizukuState"), ProcessingMode.SHIZUKU_MUTE),
                presets
        );
    }

    private static void validateDeviceConfig(JSONObject object) throws JSONException {
        if (object == null) {
            throw new JSONException("Config is empty");
        }
        if (!FILE_TYPE.equalsIgnoreCase(object.optString("fileType", ""))) {
            throw new JSONException("Unsupported config type");
        }
        if (!object.has("systemEqState") || !object.has("shizukuState")) {
            throw new JSONException("Missing mode states");
        }
    }

    private static ModeState requireModeState(ModeState state, ProcessingMode expectedMode) {
        if (state == null) {
            return new ModeState(expectedMode, AdvancedModeConfig.DEFAULT, Preset.flat(false), "Default");
        }
        return new ModeState(
                expectedMode,
                state.advancedModeConfig,
                state.devicePreset,
                state.activePresetName);
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
