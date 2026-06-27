package com.example.globalpeq;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DeviceConfigFile {
    private static final String FILE_TYPE = "global_peq_device_config";
    private static final int SCHEMA_VERSION = 1;

    final AudioOutputDevice device;
    final ProcessingMode processingMode;
    final AdvancedModeConfig advancedModeConfig;
    final Preset devicePreset;
    final List<Preset> presets;
    final String activePresetName;
    final boolean autoSwitchOutput;

    DeviceConfigFile(AudioOutputDevice device,
                     ProcessingMode processingMode,
                     AdvancedModeConfig advancedModeConfig,
                     Preset devicePreset,
                     List<Preset> presets,
                     String activePresetName,
                     boolean autoSwitchOutput) {
        this.device = device == null ? new AudioOutputDevice("none", "Output device") : device;
        this.processingMode = processingMode == null ? ProcessingMode.SYSTEM_EQ : processingMode;
        this.advancedModeConfig = advancedModeConfig == null ? AdvancedModeConfig.DEFAULT : advancedModeConfig;
        this.devicePreset = devicePreset == null ? Preset.flat(false) : devicePreset;
        this.presets = Collections.unmodifiableList(normalizePresets(presets));
        this.activePresetName = activePresetName == null ? "" : activePresetName.trim();
        this.autoSwitchOutput = autoSwitchOutput;
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
            object.put("activePresetName", activePresetName);
            object.put("devicePreset", new JSONObject(devicePreset.toJson()));
            object.put("advancedModeConfig", new JSONObject(advancedModeConfig.toJson()));
            JSONArray presetsArray = new JSONArray();
            for (Preset preset : presets) {
                presetsArray.put(new JSONObject(preset.toJson()));
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
        JSONObject advancedObject = object.optJSONObject("advancedModeConfig");
        JSONObject devicePresetObject = object.optJSONObject("devicePreset");
        if (devicePresetObject == null) {
            devicePresetObject = object.optJSONObject("preset");
        }
        JSONArray presetsArray = object.optJSONArray("presets");
        List<Preset> presets = new ArrayList<>();
        if (presetsArray != null) {
            for (int i = 0; i < presetsArray.length(); i++) {
                JSONObject presetObject = presetsArray.optJSONObject(i);
                if (presetObject == null) {
                    continue;
                }
                presets.add(Preset.fromJson(presetObject.toString()));
            }
        } else if (devicePresetObject != null) {
            presets.add(Preset.fromJson(devicePresetObject.toString()));
        }
        Preset devicePreset = Preset.fromJson(devicePresetObject == null ? null : devicePresetObject.toString());
        String activePresetName = object.optString("activePresetName", devicePreset.name);
        return new DeviceConfigFile(
                device,
                mode,
                AdvancedModeConfig.fromJson(advancedObject == null ? null : advancedObject.toString()),
                devicePreset,
                presets,
                activePresetName,
                object.optBoolean("autoSwitchOutput", true)
        );
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
                && (object.has("devicePreset") || object.has("preset") || object.has("presets"));
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
            int existingIndex = indexOfPreset(normalized, preset.name);
            if (existingIndex >= 0) {
                normalized.set(existingIndex, preset);
            } else {
                normalized.add(preset);
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
}
