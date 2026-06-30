package com.example.globalpeq;

final class ShizukuStatusSummary {
    enum Kind {
        STANDBY,
        AUTH_REQUIRED,
        PICK_APP,
        CAPTURE_AUTH_REQUIRED,
        WAITING_PLAYBACK,
        MUTED_REPLAY,
        UNMUTED_REPLAY,
        CAPTURE_ONLY,
        CAPTURE_MISSING_SKIP_MUTE
    }

    final Kind kind;
    final String playbackPackage;
    final String mutedPackage;
    final String replayPackage;

    ShizukuStatusSummary(Kind kind,
                         String playbackPackage,
                         String mutedPackage,
                         String replayPackage) {
        this.kind = kind == null ? Kind.STANDBY : kind;
        this.playbackPackage = normalize(playbackPackage);
        this.mutedPackage = normalize(mutedPackage);
        this.replayPackage = normalize(replayPackage);
    }

    static ShizukuStatusSummary resolve(ProcessingMode mode,
                                        boolean presetEnabled,
                                        AdvancedModeConfig config,
                                        ShizukuRuntimeState state,
                                        boolean shizukuGranted) {
        AdvancedModeConfig safeConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        ShizukuRuntimeState safeState = state == null ? ShizukuRuntimeState.DEFAULT : state;
        if (mode != ProcessingMode.SHIZUKU_MUTE || !presetEnabled) {
            return new ShizukuStatusSummary(
                    Kind.STANDBY,
                    bestPlaybackPackage(safeState),
                    safeState.activeMutedPackage,
                    bestReplayPackage(safeState));
        }
        if (!shizukuGranted) {
            return new ShizukuStatusSummary(
                    Kind.AUTH_REQUIRED,
                    bestPlaybackPackage(safeState),
                    safeState.activeMutedPackage,
                    bestReplayPackage(safeState));
        }
        String playbackPackage = bestPlaybackPackage(safeState);
        String replayPackage = bestReplayPackage(safeState);
        String mutedPackage = normalize(safeState.activeMutedPackage);
        String captureStatus = normalize(safeState.captureStatus);
        boolean muteVerified = safeState.muteActive && !isMuteVerificationUntrusted(safeState.activeOutputRoute);
        if (safeState.captureActive && muteVerified) {
            return new ShizukuStatusSummary(
                    Kind.MUTED_REPLAY,
                    playbackPackage,
                    mutedPackage,
                    replayPackage);
        }
        if (safeState.captureActive) {
            return new ShizukuStatusSummary(
                    safeConfig.allowReplayWithoutMute ? Kind.UNMUTED_REPLAY : Kind.CAPTURE_ONLY,
                    playbackPackage,
                    mutedPackage,
                    replayPackage);
        }
        if (!playbackPackage.isEmpty()) {
            return new ShizukuStatusSummary(
                    Kind.CAPTURE_MISSING_SKIP_MUTE,
                    playbackPackage,
                    mutedPackage,
                    replayPackage);
        }
        if (captureStatus.contains("not authorized")
                || captureStatus.contains("authorization")
                || captureStatus.contains("Authorize again")) {
            return new ShizukuStatusSummary(
                    Kind.CAPTURE_AUTH_REQUIRED,
                    playbackPackage,
                    mutedPackage,
                    replayPackage);
        }
        if ("Choose an app to monitor.".equals(captureStatus) || safeConfig.monitoredAppPackage.isEmpty()) {
            return new ShizukuStatusSummary(
                    Kind.PICK_APP,
                    playbackPackage,
                    mutedPackage,
                    replayPackage);
        }
        return new ShizukuStatusSummary(
                Kind.WAITING_PLAYBACK,
                playbackPackage,
                mutedPackage,
                replayPackage);
    }

    String compactText(boolean chinese) {
        switch (kind) {
            case AUTH_REQUIRED:
                return chinese ? "待授权" : "Authorize";
            case PICK_APP:
                return chinese ? "待选应用" : "Pick app";
            case CAPTURE_AUTH_REQUIRED:
                return chinese ? "待录屏" : "Capture auth";
            case WAITING_PLAYBACK:
                return chinese ? "待播放" : "Waiting";
            case MUTED_REPLAY:
                return chinese ? "静音回放" : "Muted replay";
            case UNMUTED_REPLAY:
                return chinese ? "原声回放" : "Replay on";
            case CAPTURE_ONLY:
                return chinese ? "仅捕获" : "Capture only";
            case CAPTURE_MISSING_SKIP_MUTE:
                return chinese ? "未捕获" : "No capture";
            case STANDBY:
            default:
                return chinese ? "待机" : "Standby";
        }
    }

    String detailText(boolean chinese) {
        switch (kind) {
            case AUTH_REQUIRED:
                return chinese ? "Shizuku 尚未授权。" : "Shizuku authorization is required.";
            case PICK_APP:
                return chinese ? "先选择要监听的应用。" : "Choose the app to monitor first.";
            case CAPTURE_AUTH_REQUIRED:
                return chinese ? "原声捕获尚未授权。" : "Native capture authorization is required.";
            case WAITING_PLAYBACK:
                return chinese ? "等待原声开始播放。" : "Waiting for the source playback to start.";
            case MUTED_REPLAY:
                return chinese ? "已静音源应用，正在回放处理后音频。" : "Source audio is muted and processed replay is active.";
            case UNMUTED_REPLAY:
                return chinese ? "无法静音源应用，仍在回放处理后音频，请自行拉低原声音量。" : "The source app could not be muted, so processed replay stays on.";
            case CAPTURE_ONLY:
                return chinese ? "无法静音源应用，已停止处理后回放。" : "The source app could not be muted, so processed replay stays off.";
            case CAPTURE_MISSING_SKIP_MUTE:
                return chinese ? "还没抓到原声回放，已跳过静音。" : "Original playback has not been captured yet, so mute is skipped.";
            case STANDBY:
            default:
                return chinese ? "Shizuku 模式待机中。" : "Shizuku Mode is standing by.";
        }
    }

    private static String bestPlaybackPackage(ShizukuRuntimeState state) {
        if (state == null) {
            return "";
        }
        return orderPackages(
                state.activePlaybackPackage,
                state.activeReplayPackage,
                state.activeMutedPackage);
    }

    private static String bestReplayPackage(ShizukuRuntimeState state) {
        if (state == null) {
            return "";
        }
        if (!normalize(state.activeReplayPackage).isEmpty()) {
            return orderPackages(
                    state.activeReplayPackage,
                    state.activePlaybackPackage,
                    state.activeMutedPackage);
        }
        if (state.captureActive && !normalize(state.activePlaybackPackage).isEmpty()) {
            return orderPackages(
                    state.activePlaybackPackage,
                    state.activeMutedPackage);
        }
        if (state.captureActive && !normalize(state.activeMutedPackage).isEmpty()) {
            return orderPackages(
                    state.activeMutedPackage,
                    state.activePlaybackPackage);
        }
        return "";
    }

    private static String orderPackages(String primary, String... references) {
        java.util.LinkedHashSet<String> remaining = splitPackages(primary);
        if (remaining.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        if (references != null) {
            for (String reference : references) {
                for (String packageName : splitPackages(reference)) {
                    if (remaining.remove(packageName)) {
                        ordered.add(packageName);
                    }
                }
            }
        }
        ordered.addAll(remaining);
        return joinPackages(ordered);
    }

    private static java.util.LinkedHashSet<String> splitPackages(String packages) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        String normalized = normalize(packages);
        if (normalized.isEmpty()) {
            return result;
        }
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String packageName = normalize(part);
            if (!packageName.isEmpty()) {
                result.add(packageName);
            }
        }
        return result;
    }

    private static String joinPackages(java.util.LinkedHashSet<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String packageName : packages) {
            String normalized = normalize(packageName);
            if (normalized.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(normalized);
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isMuteVerificationUntrusted(String route) {
        String normalized = normalize(route).toLowerCase(java.util.Locale.US);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.contains("aaudio")
                || normalized.contains("opensl")
                || normalized.contains("sles")
                || normalized.contains("hw source")
                || normalized.contains("soundpool")
                || normalized.contains("playertype");
    }
}
