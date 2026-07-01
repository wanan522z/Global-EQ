package com.example.globalpeq;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AudioOutputDeviceMonitor {
    private static final long ACTIVE_ROUTE_HOLD_MS = 5000L;
    private static final long ROUTE_POLL_INTERVAL_MS = 1200L;
    private static final int PLAYER_STATE_STARTED = 2;

    interface Listener {
        void onOutputDeviceChanged(AudioOutputDevice device);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioDeviceCallback deviceCallback;
    private final AudioManager.AudioPlaybackCallback playbackCallback;
    private final BluetoothProfile.ServiceListener bluetoothProfileListener;
    private final Runnable routePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (listener == null) {
                return;
            }
            notifyCurrentOutput();
            handler.postDelayed(this, ROUTE_POLL_INTERVAL_MS);
        }
    };
    private Listener listener;
    private BluetoothProfile a2dpProfile;
    private BluetoothProfile headsetProfile;
    private AudioOutputDevice lastPlaybackRoutedDevice;
    private long lastPlaybackRoutedAtMs;
    private String lastDispatchedKey = "";

    AudioOutputDeviceMonitor(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                notifyCurrentOutput();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                notifyCurrentOutput();
            }
        };
        playbackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                notifyCurrentOutput();
            }
        };
        bluetoothProfileListener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = proxy;
                } else if (profile == BluetoothProfile.HEADSET) {
                    headsetProfile = proxy;
                }
                notifyCurrentOutput();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = null;
                } else if (profile == BluetoothProfile.HEADSET) {
                    headsetProfile = null;
                }
                notifyCurrentOutput();
            }
        };
    }

    void start(Listener listener) {
        this.listener = listener;
        lastDispatchedKey = "";
        requestBluetoothProfiles();
        audioManager.registerAudioDeviceCallback(deviceCallback, handler);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
        }
        handler.removeCallbacks(routePollRunnable);
        handler.post(routePollRunnable);
        notifyCurrentOutput();
    }

    void stop() {
        handler.removeCallbacks(routePollRunnable);
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        }
        closeBluetoothProfiles();
        listener = null;
    }

    AudioOutputDevice currentOutputDevice() {
        AudioOutputDevice activeBluetoothDevice = resolveActiveBluetoothOutputDevice();
        if (activeBluetoothDevice != null) {
            return activeBluetoothDevice;
        }
        AudioOutputDevice playbackRouted = resolvePlaybackRoutedOutputDevice();
        if (playbackRouted != null) {
            return playbackRouted;
        }
        AudioOutputDevice recentPlaybackRoute = resolveRecentPlaybackRoutedOutputDevice();
        if (recentPlaybackRoute != null) {
            return recentPlaybackRoute;
        }
        return resolveFallbackOutputDevice();
    }

    private AudioOutputDevice resolveFallbackOutputDevice() {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo best = null;
        for (AudioDeviceInfo device : devices) {
            if (device.isSink() && isExternal(device)) {
                best = device;
                break;
            }
        }
        if (best == null) {
            for (AudioDeviceInfo device : devices) {
                if (device.isSink()) {
                    best = device;
                    break;
                }
            }
        }
        return best == null ? new AudioOutputDevice("none", "No output device") : describe(best);
    }

    private AudioOutputDevice resolveActiveBluetoothOutputDevice() {
        BluetoothDevice activeA2dpDevice = readActiveBluetoothDevice(a2dpProfile);
        AudioOutputDevice resolvedA2dp = resolveBluetoothOutputDevice(
                activeA2dpDevice,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        if (resolvedA2dp != null) {
            rememberPlaybackRoutedDevice(resolvedA2dp);
            return resolvedA2dp;
        }

        BluetoothDevice activeHeadsetDevice = readActiveBluetoothDevice(headsetProfile);
        AudioOutputDevice resolvedHeadset = resolveBluetoothOutputDevice(
                activeHeadsetDevice,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        if (resolvedHeadset != null) {
            rememberPlaybackRoutedDevice(resolvedHeadset);
            return resolvedHeadset;
        }
        return null;
    }

    private AudioOutputDevice resolveBluetoothOutputDevice(BluetoothDevice bluetoothDevice, int type) {
        if (bluetoothDevice == null) {
            return null;
        }
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSink() || !isSelectableOutput(device)) {
                continue;
            }
            if (device.getType() != type && !isBluetoothType(device.getType())) {
                continue;
            }
            if (AudioDeviceIdentity.matchesBluetoothAddress(device, bluetoothDevice)) {
                return describe(device);
            }
        }
        return AudioDeviceIdentity.describeBluetoothDevice(type, bluetoothDevice);
    }

    private BluetoothDevice readActiveBluetoothDevice(BluetoothProfile profile) {
        if (profile == null || !hasBluetoothConnectPermission()) {
            return null;
        }
        try {
            java.lang.reflect.Method method = profile.getClass().getMethod("getActiveDevice");
            Object value = method.invoke(profile);
            return value instanceof BluetoothDevice ? (BluetoothDevice) value : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void requestBluetoothProfiles() {
        if (bluetoothAdapter == null || !hasBluetoothConnectPermission()) {
            return;
        }
        try {
            if (a2dpProfile == null) {
                bluetoothAdapter.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.A2DP);
            }
            if (headsetProfile == null) {
                bluetoothAdapter.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.HEADSET);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void closeBluetoothProfiles() {
        if (bluetoothAdapter == null) {
            return;
        }
        try {
            if (a2dpProfile != null) {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (headsetProfile != null) {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile);
            }
        } catch (RuntimeException ignored) {
        }
        a2dpProfile = null;
        headsetProfile = null;
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    List<AudioOutputDevice> availableOutputDevices() {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        Map<String, AudioOutputDevice> ordered = new LinkedHashMap<>();
        Set<String> seenLabels = new HashSet<>();
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSink() || !isSelectableOutput(device)) {
                continue;
            }
            AudioOutputDevice described = describe(device);
            if (!described.isDisplayable()) {
                continue;
            }
            String labelKey = normalizeLabelKey(described.label);
            if (!seenLabels.add(labelKey)) {
                continue;
            }
            ordered.put(described.key, described);
        }
        return new ArrayList<>(ordered.values());
    }

    String currentOutputLabel() {
        return currentOutputDevice().label;
    }

    private void notifyCurrentOutput() {
        if (listener != null) {
            AudioOutputDevice device = currentOutputDevice();
            String nextKey = device == null || device.key == null ? "" : device.key;
            if (nextKey.equals(lastDispatchedKey)) {
                return;
            }
            lastDispatchedKey = nextKey;
            listener.onOutputDeviceChanged(device);
        }
    }

    private AudioOutputDevice resolvePlaybackRoutedOutputDevice() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        try {
            List<AudioPlaybackConfiguration> configs = audioManager.getActivePlaybackConfigurations();
            if (configs == null) {
                return null;
            }
            AudioOutputDevice best = null;
            for (AudioPlaybackConfiguration configuration : configs) {
                if (!isRelevantActivePlayback(configuration)) {
                    continue;
                }
                int clientUid = readPlaybackClientUid(configuration);
                if (clientUid == android.os.Process.myUid()) {
                    continue;
                }
                AudioDeviceInfo routedDevice = readPlaybackDeviceInfo(configuration);
                if (routedDevice == null || !routedDevice.isSink() || !isSelectableOutput(routedDevice)) {
                    continue;
                }
                AudioOutputDevice described = describe(routedDevice);
                if (isExternal(routedDevice)) {
                    rememberPlaybackRoutedDevice(described);
                    return described;
                }
                if (best == null) {
                    best = described;
                }
            }
            if (best != null) {
                rememberPlaybackRoutedDevice(best);
            }
            return best;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isRelevantActivePlayback(AudioPlaybackConfiguration configuration) {
        if (configuration == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        if (!isPlaybackActive(configuration)) {
            return false;
        }
        try {
            AudioAttributes attributes = configuration.getAudioAttributes();
            if (attributes == null) {
                return false;
            }
            int usage = attributes.getUsage();
            return usage == AudioAttributes.USAGE_MEDIA
                    || usage == AudioAttributes.USAGE_GAME
                    || usage == AudioAttributes.USAGE_UNKNOWN;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPlaybackActive(AudioPlaybackConfiguration configuration) {
        if (configuration == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        boolean activeByState = readPlaybackPlayerState(configuration) == PLAYER_STATE_STARTED;
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("isActive");
            Object value = method.invoke(configuration);
            if (value instanceof Boolean) {
                return (Boolean) value || activeByState;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ignored) {
        }
        return activeByState;
    }

    private int readPlaybackPlayerState(AudioPlaybackConfiguration configuration) {
        if (configuration == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return -1;
        }
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getPlayerState");
            Object value = method.invoke(configuration);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (ReflectiveOperationException ignored) {
            return -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private int readPlaybackClientUid(AudioPlaybackConfiguration configuration) {
        if (configuration == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return -1;
        }
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getClientUid");
            Object value = method.invoke(configuration);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (NoSuchMethodException ignored) {
            return -1;
        } catch (ReflectiveOperationException ignored) {
            return -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private AudioOutputDevice resolveRecentPlaybackRoutedOutputDevice() {
        AudioOutputDevice remembered = lastPlaybackRoutedDevice;
        if (remembered == null) {
            return null;
        }
        long ageMs = android.os.SystemClock.elapsedRealtime() - lastPlaybackRoutedAtMs;
        if (ageMs > ACTIVE_ROUTE_HOLD_MS) {
            return null;
        }
        if (findOutputDeviceByKey(remembered.key) == null) {
            return null;
        }
        return remembered;
    }

    private void rememberPlaybackRoutedDevice(AudioOutputDevice device) {
        if (device == null) {
            return;
        }
        lastPlaybackRoutedDevice = device;
        lastPlaybackRoutedAtMs = android.os.SystemClock.elapsedRealtime();
    }

    private AudioOutputDevice findOutputDeviceByKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSink() || !isSelectableOutput(device)) {
                continue;
            }
            AudioOutputDevice described = describe(device);
            if (key.equals(described.key)) {
                return described;
            }
        }
        return null;
    }

    private AudioDeviceInfo readPlaybackDeviceInfo(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        try {
            return configuration.getAudioDeviceInfo();
        } catch (NoSuchMethodError ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isExternal(AudioDeviceInfo device) {
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return true;
            default:
                return false;
        }
    }

    private boolean isBluetoothType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    private boolean isSelectableOutput(AudioDeviceInfo device) {
        if (device == null || !device.isSink()) {
            return false;
        }
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return true;
            default:
                return false;
        }
    }

    private AudioOutputDevice describe(AudioDeviceInfo device) {
        String key = AudioDeviceIdentity.describeOutputDeviceKey(context, device);
        String label = AudioDeviceIdentity.describeOutputRouteLabel(context, device);
        if (label == null || label.trim().isEmpty()) {
            label = AudioDeviceIdentity.typeName(device.getType());
        }
        return new AudioOutputDevice(key, label);
    }

    private String normalizeLabelKey(String label) {
        if (label == null) {
            return "";
        }
        return label.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
