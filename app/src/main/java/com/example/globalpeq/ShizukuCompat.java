package com.example.globalpeq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;

final class ShizukuCompat {
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_CLASS = "rikka.shizuku.Shizuku";

    private ShizukuCompat() {
    }

    static boolean isApiAvailable() {
        return findShizukuClass() != null;
    }

    static boolean isManagerInstalled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    static boolean isServiceAvailable() {
        Class<?> clazz = findShizukuClass();
        if (clazz == null) {
            return false;
        }
        try {
            Method method = clazz.getMethod("pingBinder");
            Object value = method.invoke(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    static boolean hasPermission() {
        Class<?> clazz = findShizukuClass();
        if (clazz == null) {
            return false;
        }
        try {
            Method method = clazz.getMethod("checkSelfPermission");
            Object value = method.invoke(null);
            return value instanceof Integer && (Integer) value == PackageManager.PERMISSION_GRANTED;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    static boolean requestPermissionOrOpenManager(Activity activity, int requestCode) {
        if (activity == null) {
            return false;
        }
        if (!isManagerInstalled(activity)) {
            openManagerOrSettings(activity);
            return false;
        }
        if (!isServiceAvailable()) {
            openManagerOrSettings(activity);
            return false;
        }
        if (hasPermission()) {
            return true;
        }
        Class<?> clazz = findShizukuClass();
        if (clazz == null) {
            return false;
        }
        try {
            Method method = clazz.getMethod("requestPermission", int.class);
            method.invoke(null, requestCode);
            return false;
        } catch (ReflectiveOperationException ignored) {
            openManagerOrSettings(activity);
            return false;
        }
    }

    static Process newProcess(String command) {
        Class<?> clazz = findShizukuClass();
        if (clazz == null || command == null || command.trim().isEmpty()) {
            return null;
        }
        try {
            Method method = clazz.getMethod("newProcess", String[].class, String[].class, String.class);
            Object value = method.invoke(null, new Object[]{
                    new String[]{"sh", "-c", command},
                    null,
                    null
            });
            return value instanceof Process ? (Process) value : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    static void openManagerOrSettings(Context context) {
        if (context == null) {
            return;
        }
        PackageManager packageManager = context.getPackageManager();
        Intent launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
            return;
        }
        Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + SHIZUKU_PACKAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (settingsIntent.resolveActivity(packageManager) != null) {
            context.startActivity(settingsIntent);
        }
    }

    static String describeState(Context context) {
        if (!isApiAvailable()) {
            return "Shizuku support is unavailable in this build.";
        }
        if (!isManagerInstalled(context)) {
            return "Install Shizuku first.";
        }
        if (!isServiceAvailable()) {
            return "Start the Shizuku service first.";
        }
        if (!hasPermission()) {
            return "Authorize Shizuku for session mute.";
        }
        return "Shizuku is ready.";
    }

    private static Class<?> findShizukuClass() {
        try {
            return Class.forName(SHIZUKU_CLASS);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }
}
