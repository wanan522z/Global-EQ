package com.example.globalpeq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.net.Uri;
import android.provider.Settings;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;
import rikka.shizuku.SystemServiceHelper;

final class ShizukuCompat {
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final Map<StateListener, Shizuku.OnBinderReceivedListener> BINDER_RECEIVED_LISTENERS = new HashMap<>();
    private static final Map<StateListener, Shizuku.OnBinderDeadListener> BINDER_DEAD_LISTENERS = new HashMap<>();
    private static final Map<PermissionResultListener, Shizuku.OnRequestPermissionResultListener> PERMISSION_RESULT_LISTENERS = new HashMap<>();

    private ShizukuCompat() {
    }

    interface StateListener {
        void onStateChanged();
    }

    interface PermissionResultListener {
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    static boolean isApiAvailable() {
        return true;
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
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
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
        try {
            Shizuku.requestPermission(requestCode);
            return false;
        } catch (Throwable ignored) {
            openManagerOrSettings(activity);
            return false;
        }
    }

    static Process newProcess(String command) {
        if (command == null || command.trim().isEmpty() || !hasPermission()) {
            return null;
        }
        try {
            Method method = Shizuku.class.getDeclaredMethod(
                    "newProcess",
                    String[].class,
                    String[].class,
                    String.class
            );
            method.setAccessible(true);
            Object process = method.invoke(
                    null,
                    new String[]{"sh", "-c", command},
                    null,
                    null
            );
            return process instanceof Process ? (Process) process : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    static void grantPermissionsAndAppOps(Context context) {
        if (context == null || !hasPermission()) {
            return;
        }
        String packageName = context.getPackageName();
        exec(new String[]{"pm", "grant", packageName, "android.permission.DUMP"});
        exec(new String[]{"appops", "set", packageName, "PROJECT_MEDIA", "allow"});
    }

    static String dumpSystemService(String service) {
        if (service == null || service.trim().isEmpty() || !hasPermission()) {
            return null;
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readPipe = pipe[0];
            ParcelFileDescriptor writePipe = pipe[1];
            android.os.IBinder binder = SystemServiceHelper.getSystemService(service);
            if (binder == null) {
                return null;
            }
            binder.dumpAsync(writePipe.getFileDescriptor(), new String[0]);
            writePipe.close();
            FileInputStream inputStream = new FileInputStream(readPipe.getFileDescriptor());
            InputStreamReader reader = new InputStreamReader(inputStream, "UTF-8");
            String dump = readAll(reader);
            reader.close();
            inputStream.close();
            readPipe.close();
            return dump;
        } catch (Throwable ignored) {
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

    private static void exec(String[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        try {
            Method method = Shizuku.class.getDeclaredMethod(
                    "newProcess",
                    String[].class,
                    String[].class,
                    String.class
            );
            method.setAccessible(true);
            Object value = method.invoke(null, args, null, null);
            if (!(value instanceof ShizukuRemoteProcess)) {
                return;
            }
            ShizukuRemoteProcess process = (ShizukuRemoteProcess) value;
            process.getOutputStream().flush();
            process.getOutputStream().close();
            process.waitFor();
        } catch (Throwable ignored) {
        }
    }

    private static String readAll(InputStreamReader reader) throws java.io.IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            builder.append(buffer, 0, count);
        }
        return builder.toString();
    }

    static void addStateListener(StateListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (BINDER_RECEIVED_LISTENERS) {
            if (BINDER_RECEIVED_LISTENERS.containsKey(listener)) {
                return;
            }
            Shizuku.OnBinderReceivedListener receivedListener = listener::onStateChanged;
            Shizuku.OnBinderDeadListener deadListener = listener::onStateChanged;
            BINDER_RECEIVED_LISTENERS.put(listener, receivedListener);
            BINDER_DEAD_LISTENERS.put(listener, deadListener);
            Shizuku.addBinderReceivedListenerSticky(receivedListener);
            Shizuku.addBinderDeadListener(deadListener);
        }
    }

    static void removeStateListener(StateListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (BINDER_RECEIVED_LISTENERS) {
            Shizuku.OnBinderReceivedListener receivedListener = BINDER_RECEIVED_LISTENERS.remove(listener);
            Shizuku.OnBinderDeadListener deadListener = BINDER_DEAD_LISTENERS.remove(listener);
            if (receivedListener != null) {
                Shizuku.removeBinderReceivedListener(receivedListener);
            }
            if (deadListener != null) {
                Shizuku.removeBinderDeadListener(deadListener);
            }
        }
    }

    static void addPermissionResultListener(PermissionResultListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (PERMISSION_RESULT_LISTENERS) {
            if (PERMISSION_RESULT_LISTENERS.containsKey(listener)) {
                return;
            }
            Shizuku.OnRequestPermissionResultListener wrapped = listener::onRequestPermissionResult;
            PERMISSION_RESULT_LISTENERS.put(listener, wrapped);
            Shizuku.addRequestPermissionResultListener(wrapped);
        }
    }

    static void removePermissionResultListener(PermissionResultListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (PERMISSION_RESULT_LISTENERS) {
            Shizuku.OnRequestPermissionResultListener wrapped = PERMISSION_RESULT_LISTENERS.remove(listener);
            if (wrapped != null) {
                Shizuku.removeRequestPermissionResultListener(wrapped);
            }
        }
    }
}
