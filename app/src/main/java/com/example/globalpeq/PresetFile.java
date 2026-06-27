package com.example.globalpeq;

import org.json.JSONException;
import org.json.JSONObject;

final class PresetFile {
    private static final String FILE_TYPE = "global_peq_preset";
    private static final int SCHEMA_VERSION = 1;

    final Preset preset;

    PresetFile(Preset preset) {
        this.preset = preset == null ? Preset.flat(false) : preset;
    }

    String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("fileType", FILE_TYPE);
            object.put("schemaVersion", SCHEMA_VERSION);
            object.put("preset", new JSONObject(preset.toJson()));
            return object.toString(2);
        } catch (JSONException ignored) {
            return "{}";
        }
    }

    static PresetFile fromJson(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        if (FILE_TYPE.equalsIgnoreCase(object.optString("fileType", "")) && object.has("preset")) {
            JSONObject presetObject = object.optJSONObject("preset");
            return new PresetFile(Preset.fromJson(presetObject == null ? null : presetObject.toString()));
        }
        if (looksLikeRawPreset(object)) {
            return new PresetFile(Preset.fromJson(object.toString()));
        }
        throw new JSONException("Not a preset file");
    }

    static boolean looksLikeRawPreset(JSONObject object) {
        return object != null
                && (object.has("peqBands")
                || object.has("geqGainsMb")
                || object.has("mode")
                || object.has("pregainMb"));
    }
}
