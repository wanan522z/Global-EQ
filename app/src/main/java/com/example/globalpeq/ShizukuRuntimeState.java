package com.example.globalpeq;

import org.json.JSONException;
import org.json.JSONObject;

final class ShizukuRuntimeState {
    static final ShizukuRuntimeState DEFAULT = new ShizukuRuntimeState(
            "Native capture is idle.",
            false,
            "Shizuku mute is idle.",
            false,
            "",
            "",
            "",
            "",
            "",
            "",
            ""
    );

    final String captureStatus;
    final boolean captureActive;
    final String muteStatus;
    final boolean muteActive;
    final String activeOutputRoute;
    final String activePlaybackPackage;
    final String activeMutedPackage;
    final String activeReplayPackage;
    final String activePlaybackSessionIds;
    final String desiredMutedSessionIds;
    final String activeMutedSessionIds;

    ShizukuRuntimeState(String captureStatus,
                        boolean captureActive,
                        String muteStatus,
                        boolean muteActive,
                        String activeOutputRoute,
                        String activePlaybackPackage,
                        String activeMutedPackage,
                        String activeReplayPackage,
                        String activePlaybackSessionIds,
                        String desiredMutedSessionIds,
                        String activeMutedSessionIds) {
        this.captureStatus = normalize(captureStatus, "Native capture is idle.");
        this.captureActive = captureActive;
        this.muteStatus = normalize(muteStatus, "Shizuku mute is idle.");
        this.muteActive = muteActive;
        this.activeOutputRoute = normalize(activeOutputRoute, "");
        this.activePlaybackPackage = normalize(activePlaybackPackage, "");
        this.activeMutedPackage = normalize(activeMutedPackage, "");
        this.activeReplayPackage = normalize(activeReplayPackage, "");
        this.activePlaybackSessionIds = normalize(activePlaybackSessionIds, "");
        this.desiredMutedSessionIds = normalize(desiredMutedSessionIds, "");
        this.activeMutedSessionIds = normalize(activeMutedSessionIds, "");
    }

    ShizukuRuntimeState withCaptureStatus(String status, boolean active) {
        return new ShizukuRuntimeState(
                status,
                active,
                muteStatus,
                muteActive,
                activeOutputRoute,
                activePlaybackPackage,
                activeMutedPackage,
                activeReplayPackage,
                activePlaybackSessionIds,
                desiredMutedSessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withMuteStatus(String status, boolean active) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                status,
                active,
                activeOutputRoute,
                activePlaybackPackage,
                activeMutedPackage,
                activeReplayPackage,
                activePlaybackSessionIds,
                desiredMutedSessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withActiveOutputRoute(String route) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                muteStatus,
                muteActive,
                route,
                activePlaybackPackage,
                activeMutedPackage,
                activeReplayPackage,
                activePlaybackSessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withActivePlaybackPackage(String packageName) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                muteStatus,
                muteActive,
                activeOutputRoute,
                packageName,
                activeMutedPackage,
                activeReplayPackage,
                activePlaybackSessionIds,
                desiredMutedSessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withActiveMutedPackage(String packageName) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                muteStatus,
                muteActive,
                activeOutputRoute,
                activePlaybackPackage,
                packageName,
                activeReplayPackage,
                activePlaybackSessionIds,
                desiredMutedSessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withActiveReplayPackage(String packageName) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                muteStatus,
                muteActive,
                activeOutputRoute,
                activePlaybackPackage,
                activeMutedPackage,
                packageName,
                activePlaybackSessionIds,
                desiredMutedSessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withActivePlaybackSessionIds(String sessionIds) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                muteStatus,
                muteActive,
                activeOutputRoute,
                activePlaybackPackage,
                activeMutedPackage,
                activeReplayPackage,
                sessionIds,
                desiredMutedSessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withDesiredMutedSessionIds(String sessionIds) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                muteStatus,
                muteActive,
                activeOutputRoute,
                activePlaybackPackage,
                activeMutedPackage,
                activeReplayPackage,
                activePlaybackSessionIds,
                sessionIds,
                activeMutedSessionIds
        );
    }

    ShizukuRuntimeState withActiveMutedSessionIds(String sessionIds) {
        return new ShizukuRuntimeState(
                captureStatus,
                captureActive,
                muteStatus,
                muteActive,
                activeOutputRoute,
                activePlaybackPackage,
                activeMutedPackage,
                activeReplayPackage,
                activePlaybackSessionIds,
                desiredMutedSessionIds,
                sessionIds
        );
    }

    String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("captureStatus", captureStatus);
            object.put("captureActive", captureActive);
            object.put("muteStatus", muteStatus);
            object.put("muteActive", muteActive);
            object.put("activeOutputRoute", activeOutputRoute);
            object.put("activePlaybackPackage", activePlaybackPackage);
            object.put("activeMutedPackage", activeMutedPackage);
            object.put("activeReplayPackage", activeReplayPackage);
            object.put("activePlaybackSessionIds", activePlaybackSessionIds);
            object.put("desiredMutedSessionIds", desiredMutedSessionIds);
            object.put("activeMutedSessionIds", activeMutedSessionIds);
        } catch (JSONException ignored) {
            return "{}";
        }
        return object.toString();
    }

    static ShizukuRuntimeState fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return DEFAULT;
        }
        try {
            JSONObject object = new JSONObject(json);
            return new ShizukuRuntimeState(
                    object.optString("captureStatus", DEFAULT.captureStatus),
                    object.optBoolean("captureActive", DEFAULT.captureActive),
                    object.optString("muteStatus", DEFAULT.muteStatus),
                    object.optBoolean("muteActive", DEFAULT.muteActive),
                    object.optString("activeOutputRoute", DEFAULT.activeOutputRoute),
                    object.optString("activePlaybackPackage", DEFAULT.activePlaybackPackage),
                    object.optString("activeMutedPackage", DEFAULT.activeMutedPackage),
                    object.optString("activeReplayPackage", DEFAULT.activeReplayPackage),
                    object.optString("activePlaybackSessionIds", DEFAULT.activePlaybackSessionIds),
                    object.optString("desiredMutedSessionIds", DEFAULT.desiredMutedSessionIds),
                    object.optString("activeMutedSessionIds", DEFAULT.activeMutedSessionIds)
            );
        } catch (JSONException ignored) {
            return DEFAULT;
        }
    }

    private static String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
