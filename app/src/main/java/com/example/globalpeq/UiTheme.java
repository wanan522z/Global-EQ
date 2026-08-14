package com.example.globalpeq;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * Single source of truth for the two front-end appearances.
 *
 * <p>The liquid appearance is deliberately the default for both new and existing installs. The
 * classic appearance keeps the previous black/neon presentation intact, while both appearances
 * continue to share the animated fluorescent title renderer.</p>
 */
final class UiTheme {
    private static final String PREFS_NAME = "global_peq_ui";
    private static final String KEY_LIQUID_GLASS = "liquid_glass_enabled";

    private UiTheme() {
    }

    static boolean isLiquidGlass(Context context) {
        return preferences(context).getBoolean(KEY_LIQUID_GLASS, true);
    }

    static void setLiquidGlass(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_LIQUID_GLASS, enabled).apply();
    }

    static void applyWindowAppearance(Activity activity, boolean liquid) {
        Window window = activity.getWindow();
        int paleSurface = liquid ? Color.rgb(245, 249, 255) : Color.rgb(18, 18, 25);
        window.setStatusBarColor(paleSurface);
        window.setNavigationBarColor(paleSurface);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        int flags = window.getDecorView().getSystemUiVisibility();
        if (liquid) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    static int textPrimary(boolean liquid) {
        return liquid ? Color.rgb(24, 34, 52) : Color.rgb(238, 246, 255);
    }

    static int textSecondary(boolean liquid) {
        return liquid ? Color.rgb(73, 88, 111) : Color.rgb(180, 190, 210);
    }

    static int textMuted(boolean liquid) {
        return liquid ? Color.rgb(118, 132, 153) : Color.rgb(142, 154, 168);
    }

    static int textFaint(boolean liquid) {
        return liquid ? Color.rgb(148, 158, 174) : Color.rgb(100, 110, 130);
    }

    static int hint(boolean liquid) {
        return liquid ? Color.argb(150, 86, 103, 128) : Color.argb(120, 220, 230, 245);
    }

    static int fieldFill(boolean liquid) {
        return liquid ? Color.argb(178, 255, 255, 255) : Color.argb(24, 255, 255, 255);
    }

    static int fieldStroke(boolean liquid) {
        return liquid ? Color.argb(170, 183, 201, 225) : Color.argb(52, 255, 255, 255);
    }

    static int popupSurface(boolean liquid) {
        return liquid ? Color.rgb(247, 250, 255) : Color.rgb(22, 26, 38);
    }

    static int controlSurface(boolean liquid) {
        return liquid ? Color.argb(232, 248, 251, 255) : Color.argb(240, 22, 26, 38);
    }

    static int quietLine(boolean liquid) {
        return liquid ? Color.argb(64, 66, 88, 120) : Color.argb(35, 255, 255, 255);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
