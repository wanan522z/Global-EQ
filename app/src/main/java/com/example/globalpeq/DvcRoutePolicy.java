package com.example.globalpeq;

import android.media.AudioDeviceInfo;

final class DvcRoutePolicy {
    enum Kind {
        SPEAKER,
        WIRED,
        USB,
        BLUETOOTH,
        UNSUPPORTED
    }

    static final class Decision {
        final Kind kind;
        final int deviceType;
        final boolean allowsDvc;

        Decision(Kind kind, int deviceType, boolean allowsDvc) {
            this.kind = kind;
            this.deviceType = deviceType;
            this.allowsDvc = allowsDvc;
        }

        boolean isUsb() {
            return kind == Kind.USB;
        }
    }

    private DvcRoutePolicy() {
    }

    static Decision evaluate(AudioOutputDevice device) {
        int type = deviceType(device);
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE:
                return new Decision(Kind.SPEAKER, type, true);
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return new Decision(Kind.WIRED, type, true);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return new Decision(Kind.USB, type, true);
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                // Bluetooth routes still need the same runtime volume-curve and effect-placement
                // probes as other outputs, but the route itself must not prevent DVC activation.
                return new Decision(Kind.BLUETOOTH, type, true);
            default:
                // HDMI, docks, remote submix and unknown routes are intentionally opt-in.
                return new Decision(Kind.UNSUPPORTED, type, false);
        }
    }

    static int deviceType(AudioOutputDevice device) {
        if (device == null || device.key == null) {
            return AudioDeviceInfo.TYPE_UNKNOWN;
        }
        int separator = device.key.indexOf(':');
        String raw = separator < 0 ? device.key : device.key.substring(0, separator);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return AudioDeviceInfo.TYPE_UNKNOWN;
        }
    }
}
