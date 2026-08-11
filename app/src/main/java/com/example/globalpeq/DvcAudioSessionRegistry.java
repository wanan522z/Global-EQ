package com.example.globalpeq;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Process-independent registry populated by the manifest audio-effect session receiver.
 *
 * <p>Players are allowed to announce a session before the Global PEQ service exists. Keeping the
 * open-session set in a small dedicated preference file lets the service consume that event later,
 * matching the lifecycle used by player-oriented equalizers.</p>
 */
final class DvcAudioSessionRegistry {
    interface Listener {
        void onAudioSessionsChanged(int preferredSessionId, int closedSessionId);
    }

    private static final String PREFS = "dvc_audio_sessions";
    private static final String OPEN_SESSION_IDS = "open_session_ids";
    private static final String PREFERRED_SESSION_ID = "preferred_session_id";
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private DvcAudioSessionRegistry() {
    }

    static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    static synchronized void recordOpen(Context context, int sessionId, String ownerPackage) {
        if (!isExternalSession(context, sessionId, ownerPackage)) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        LinkedHashSet<String> sessions = readSessionStrings(preferences);
        sessions.add(Integer.toString(sessionId));
        preferences.edit()
                .putStringSet(OPEN_SESSION_IDS, sessions)
                .putInt(PREFERRED_SESSION_ID, sessionId)
                .commit();
        notifyListeners(sessionId, -1);
    }

    static synchronized void recordClose(Context context, int sessionId, String ownerPackage) {
        if (!isExternalSession(context, sessionId, ownerPackage)) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        LinkedHashSet<String> sessions = readSessionStrings(preferences);
        sessions.remove(Integer.toString(sessionId));
        int preferred = preferences.getInt(PREFERRED_SESSION_ID, 0);
        if (preferred == sessionId) {
            preferred = lastValidSessionId(sessions);
        }
        preferences.edit()
                .putStringSet(OPEN_SESSION_IDS, sessions)
                .putInt(PREFERRED_SESSION_ID, preferred)
                .commit();
        notifyListeners(preferred, sessionId);
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().clear().commit();
        notifyListeners(0, -1);
    }

    static synchronized Set<Integer> loadOpenSessionIds(Context context) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (String value : readSessionStrings(preferences(context))) {
            int sessionId = parseSessionId(value);
            if (sessionId > 0) {
                result.add(sessionId);
            }
        }
        return result;
    }

    static synchronized int loadPreferredSessionId(Context context) {
        int preferred = preferences(context).getInt(PREFERRED_SESSION_ID, 0);
        return preferred > 0 ? preferred : 0;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static LinkedHashSet<String> readSessionStrings(SharedPreferences preferences) {
        return new LinkedHashSet<>(preferences.getStringSet(
                OPEN_SESSION_IDS,
                Collections.emptySet()));
    }

    private static boolean isExternalSession(Context context,
                                             int sessionId,
                                             String ownerPackage) {
        return context != null
                && sessionId > 0
                && !context.getPackageName().equals(ownerPackage);
    }

    private static int lastValidSessionId(Set<String> sessions) {
        int selected = 0;
        for (String value : sessions) {
            int sessionId = parseSessionId(value);
            if (sessionId > 0) {
                selected = sessionId;
            }
        }
        return selected;
    }

    private static int parseSessionId(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void notifyListeners(int preferredSessionId, int closedSessionId) {
        for (Listener listener : LISTENERS) {
            listener.onAudioSessionsChanged(preferredSessionId, closedSessionId);
        }
    }
}
