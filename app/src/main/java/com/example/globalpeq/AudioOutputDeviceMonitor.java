package com.example.globalpeq;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
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
    interface Listener {
        void onOutputDeviceChanged(AudioOutputDevice device);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioDeviceCallback callback;
    private final AudioManager.AudioPlaybackCallback playbackCallback;
    private Listener listener;

    AudioOutputDeviceMonitor(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        callback = new AudioDeviceCallback() {
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
    }

    void start(Listener listener) {
        this.listener = listener;
        audioManager.registerAudioDeviceCallback(callback, handler);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
        }
        notifyCurrentOutput();
    }

    void stop() {
        audioManager.unregisterAudioDeviceCallback(callback);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        }
        listener = null;
    }

    AudioOutputDevice currentOutputDevice() {
        AudioDeviceInfo activePlaybackDevice = currentActivePlaybackOutputDevice();
        if (activePlaybackDevice != null) {
            return describe(activePlaybackDevice);
        }
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

    private AudioDeviceInfo currentActivePlaybackOutputDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        try {
            for (AudioPlaybackConfiguration configuration : audioManager.getActivePlaybackConfigurations()) {
                if (!isRelevantActivePlayback(configuration)) {
                    continue;
                }
                AudioDeviceInfo device = readPlaybackDeviceInfo(configuration);
                if (device != null && device.isSink() && isSelectableOutput(device)) {
                    return device;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private boolean isRelevantActivePlayback(AudioPlaybackConfiguration configuration) {
        if (configuration == null || !isPlaybackActive(configuration)) {
            return false;
        }
        try {
            AudioAttributes attributes = configuration.getAudioAttributes();
            int usage = attributes == null ? -1 : attributes.getUsage();
            return usage == AudioAttributes.USAGE_MEDIA
                    || usage == AudioAttributes.USAGE_GAME;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPlaybackActive(AudioPlaybackConfiguration configuration) {
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("isActive");
            Object value = method.invoke(configuration);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ignored) {
        }
        return readPlaybackPlayerState(configuration) == 2;
    }

    private int readPlaybackPlayerState(AudioPlaybackConfiguration configuration) {
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getPlayerState");
            Object value = method.invoke(configuration);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    private AudioDeviceInfo readPlaybackDeviceInfo(AudioPlaybackConfiguration configuration) {
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getAudioDeviceInfo");
            Object value = method.invoke(configuration);
            if (value instanceof AudioDeviceInfo) {
                return (AudioDeviceInfo) value;
            }
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    List<AudioOutputDevice> availableOutputDevices() {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        Map<String, AudioOutputDevice> ordered = new LinkedHashMap<>();
        Set<String> seenKeys = new HashSet<>();
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSink() || !isSelectableOutput(device)) {
                continue;
            }
            AudioOutputDevice described = describe(device);
            if (!described.isDisplayable()) {
                continue;
            }
            if (!seenKeys.add(described.key)) {
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
            listener.onOutputDeviceChanged(currentOutputDevice());
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
        String type = typeName(device.getType());
        String product = "";
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            CharSequence name = device.getProductName();
            product = name == null ? "" : name.toString().trim();
        }
        String keyProduct = product.isEmpty() ? "default" : product.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
        String key = device.getType() + ":" + keyProduct + "#" + device.getId();
        if (product.isEmpty()) {
            return new AudioOutputDevice(key, type, key);
        }
        return new AudioOutputDevice(
                key,
                String.format(Locale.US, "%s - %s", type, product),
                key);
    }

    private String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Speaker";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth SCO";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "BLE headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "BLE speaker";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return "BLE audio";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB DAC";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset";
            default:
                return "Output " + type;
        }
    }

}
