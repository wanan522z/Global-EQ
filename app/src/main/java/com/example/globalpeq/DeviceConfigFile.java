package com.example.globalpeq;

import org.json.JSONException;
import org.json.JSONObject;

final class DeviceConfigFile {
    final AudioOutputDevice device;
    final ProcessingMode processingMode;
    final Preset preset;
    final AdvancedModeConfig advancedModeConfig;

    DeviceConfigFile(AudioOutputDevice device,
                     ProcessingMode processingMode,
                     Preset preset,
                     AdvancedModeConfig advancedModeConfig) {
        this.device = device == null ? new AudioOutputDevice("none", "Output device") : device;
        this.processingMode = processingMode == null ? ProcessingMode.SYSTEM_EQ : processingMode;
        this.preset = preset == null ? Preset.flat(false) : preset;
        this.advancedModeConfig = advancedModeConfig == null ? AdvancedModeConfig.DEFAULT : advancedModeConfig;
    }

    String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("deviceKey", device.key == null ? "" : device.key);
            object.put("deviceLabel", device.label == null ? "Output device" : device.label);
            object.put("processingMode", processingMode.key);
            object.put("preset", new JSONObject(preset.toJson()));
            object.put("advancedModeConfig", new JSONObject(advancedModeConfig.toJson()));
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
        JSONObject presetObject = object.optJSONObject("preset");
        JSONObject advancedObject = object.optJSONObject("advancedModeConfig");
        return new DeviceConfigFile(
                device,
                mode,
                Preset.fromJson(presetObject == null ? null : presetObject.toString()),
                AdvancedModeConfig.fromJson(advancedObject == null ? null : advancedObject.toString())
        );
    }

    static boolean looksLikeDeviceConfig(JSONObject object) {
        return object != null
                && object.has("preset")
                && object.has("deviceKey")
                && object.has("deviceLabel");
    }

    static boolean looksLikePreset(JSONObject object) {
        return object != null
                && (object.has("peqBands")
                || object.has("geqGainsMb")
                || object.has("mode")
                || object.has("pregainMb"));
    }
}
