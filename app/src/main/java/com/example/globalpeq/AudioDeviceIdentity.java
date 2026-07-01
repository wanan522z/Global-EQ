package com.example.globalpeq;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.os.Build;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class AudioDeviceIdentity {
    private AudioDeviceIdentity() {
    }

    static String typeName(int type) {
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

    static String describeOutputDeviceKey(Context context, AudioDeviceInfo device) {
        if (device == null) {
            return "none";
        }
        String identity = buildIdentitySuffix(context, device);
        return device.getType() + ":" + identity;
    }

    static String describeOutputRouteLabel(Context context, AudioDeviceInfo device) {
        if (device == null) {
            return "";
        }
        String typeName = typeName(device.getType());
        String product = readProductName(context, device);
        if (product.isEmpty() || product.equalsIgnoreCase(typeName)) {
            return typeName;
        }
        return typeName + " - " + product;
    }

    static AudioOutputDevice describeBluetoothDevice(int type, BluetoothDevice device) {
        if (device == null) {
            return new AudioOutputDevice("none", "No output device");
        }
        String address = normalizeAddress(readBluetoothDeviceAddress(device));
        String key = type + ":" + (address.isEmpty() ? "default" : "addr_" + address);
        String name = readBluetoothDeviceDisplayName(device);
        String typeName = typeName(type);
        String label = name.isEmpty() ? typeName : typeName + " - " + name;
        return new AudioOutputDevice(key, label);
    }

    static boolean matchesBluetoothAddress(AudioDeviceInfo device, BluetoothDevice bluetoothDevice) {
        if (device == null || bluetoothDevice == null) {
            return false;
        }
        return normalizeAddress(readAddress(null, device))
                .equals(normalizeAddress(readBluetoothDeviceAddress(bluetoothDevice)));
    }

    private static String buildIdentitySuffix(Context context, AudioDeviceInfo device) {
        String address = normalizeAddress(readAddress(context, device));
        if (!address.isEmpty()) {
            return "addr_" + address;
        }
        String product = slugify(readProductName(context, device));
        if (!product.isEmpty()) {
            return product;
        }
        return "default";
    }

    private static String readProductName(Context context, AudioDeviceInfo device) {
        if (device == null) {
            return "";
        }
        if (context != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
                && isBluetoothType(device.getType())) {
            return "";
        }
        try {
            CharSequence name = device.getProductName();
            return name == null ? "" : name.toString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String readAddress(Context context, AudioDeviceInfo device) {
        if (device == null || !isBluetoothType(device.getType())) {
            return "";
        }
        if (context != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        try {
            String address = device.getAddress();
            String directAddress = address == null ? "" : address.trim();
            if (!directAddress.isEmpty()) {
                return directAddress;
            }
        } catch (RuntimeException ignored) {
        }
        return resolveBondedBluetoothAddress(context, readProductName(context, device));
    }

    private static boolean isBluetoothType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    private static String normalizeAddress(String address) {
        if (address == null) {
            return "";
        }
        return address.trim()
                .toLowerCase(Locale.US)
                .replace(':', '_')
                .replaceAll("[^a-z0-9_]+", "");
    }

    private static String slugify(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
    }

    private static String resolveBondedBluetoothAddress(Context context, String productName) {
        String normalizedProduct = normalizeName(productName);
        if (normalizedProduct.isEmpty()) {
            return "";
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return "";
        }
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices == null || bondedDevices.isEmpty()) {
                return "";
            }
            Set<String> matches = new LinkedHashSet<>();
            for (BluetoothDevice bondedDevice : bondedDevices) {
                if (bondedDevice == null) {
                    continue;
                }
                String name = normalizeName(readBluetoothDeviceName(bondedDevice));
                String alias = normalizeName(readBluetoothDeviceAlias(bondedDevice));
                if (!normalizedProduct.equals(name) && !normalizedProduct.equals(alias)) {
                    continue;
                }
                String address = bondedDevice.getAddress();
                if (address != null && !address.trim().isEmpty()) {
                    matches.add(address.trim());
                }
            }
            return matches.size() == 1 ? matches.iterator().next() : "";
        } catch (SecurityException ignored) {
            return "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String readBluetoothDeviceName(BluetoothDevice device) {
        try {
            return device.getName();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String readBluetoothDeviceAddress(BluetoothDevice device) {
        if (device == null) {
            return "";
        }
        try {
            String address = device.getAddress();
            return address == null ? "" : address.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String readBluetoothDeviceDisplayName(BluetoothDevice device) {
        String alias = readBluetoothDeviceAlias(device);
        if (!alias.trim().isEmpty()) {
            return alias.trim();
        }
        String name = readBluetoothDeviceName(device);
        return name == null ? "" : name.trim();
    }

    private static String readBluetoothDeviceAlias(BluetoothDevice device) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "";
        }
        try {
            return device.getAlias();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }
}
