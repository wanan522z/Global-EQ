package com.example.globalpeq;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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
    }

    void start(Listener listener) {
        this.listener = listener;
        audioManager.registerAudioDeviceCallback(callback, handler);
        notifyCurrentOutput();
    }

    void stop() {
        audioManager.unregisterAudioDeviceCallback(callback);
        listener = null;
    }

    AudioOutputDevice currentOutputDevice() {
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

    private String normalizeLabelKey(String label) {
        if (label == null) {
            return "";
        }
        return label.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
