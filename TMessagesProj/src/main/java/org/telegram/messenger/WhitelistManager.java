package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class WhitelistManager {

    private static final String PREFS = "whitelist_cache";
    private static final String KEY_REMOTE_JSON = "whitelist_remote_json";
    private static final String KEY_LAST_FETCH = "whitelist_last_fetch";
    private static final long FETCH_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    private static volatile Set<String> allowedFolders = null;
    private static volatile Set<String> allowedChannels = null;
    private static volatile Set<String> allowedBots = null;

    public static boolean isLoaded() {
        return allowedChannels != null && allowedBots != null;
    }

    /** Фильтрация активна когда bundled-файл загружен. */
    public static boolean isActive() {
        return isLoaded();
    }

    public static boolean isChannelAllowed(String username) {
        Set<String> channels = allowedChannels;
        if (channels == null) return false;
        if (username == null || username.isEmpty()) return false;
        return channels.contains(username.toLowerCase());
    }

    public static boolean isFolderAllowed(String name) {
        Set<String> folders = allowedFolders;
        if (folders == null || name == null) return false;
        return folders.contains(name);
    }

    public static boolean isBotAllowed(String username) {
        Set<String> bots = allowedBots;
        if (bots == null) return false;
        if (username == null || username.isEmpty()) return false;
        return bots.contains(username.toLowerCase());
    }

    /**
     * Читает assets/whitelist.json синхронно (быстро, без сети).
     * Вызывать в LaunchActivity.onCreate() до инициализации UI.
     */
    public static void loadBundled() {
        try {
            InputStream is = ApplicationLoader.applicationContext.getAssets().open("whitelist.json");
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            parse(sb.toString(), false);  // загружаем свежий bundled
            applyRemoteCache();           // мержим кэш remote поверх
        } catch (Exception e) {
            FileLog.e("WhitelistManager: loadBundled error", e);
        }
    }

    /** Мержит сохранённый кэш remote из SharedPreferences. */
    private static void applyRemoteCache() {
        String json = ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, 0)
                .getString(KEY_REMOTE_JSON, null);
        if (json != null) parse(json, true);
    }

    /**
     * Раз в день фоново проверяет remote URL (BuildConfig.WHITELIST_URL).
     * Если remote содержит данные — мержит и уведомляет UI.
     * Не блокирует вызывающий поток.
     */
    public static void checkRemoteIfNeeded() {
        String whitelistUrl = BuildConfig.WHITELIST_URL;
        if (whitelistUrl == null || whitelistUrl.isEmpty()) return;

        long last = ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, 0)
                .getLong(KEY_LAST_FETCH, 0);
        if (System.currentTimeMillis() - last < FETCH_INTERVAL_MS) return;

        new Thread(() -> {
            String json = fetch(whitelistUrl, 5000);
            if (json == null) return;

            // Обновляем время последнего fetch в любом случае
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, 0)
                    .edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply();

            if (!hasRemoteData(json)) return; // remote пустой → ничего не делаем

            String old = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, 0)
                    .getString(KEY_REMOTE_JSON, null);
            if (json.equals(old)) return; // не изменился

            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, 0)
                    .edit().putString(KEY_REMOTE_JSON, json).apply();

            parse(json, true); // мержим новые каналы поверх bundled

            AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.whitelistUpdated)
            );
        }).start();
    }

    private static boolean hasRemoteData(String json) {
        try {
            JSONObject whitelist = new JSONObject(json).getJSONObject("whitelist");
            JSONArray ch = whitelist.optJSONArray("channels");
            JSONArray fo = whitelist.optJSONArray("folders");
            JSONArray bo = whitelist.optJSONArray("bots");
            return (ch != null && ch.length() > 0)
                || (fo != null && fo.length() > 0)
                || (bo != null && bo.length() > 0);
        } catch (Exception e) { return false; }
    }

    private static String fetch(String urlStr, int timeoutMs) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                return sb.toString().trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Парсит JSON whitelist.
     * merge=false — заменяет наборы; merge=true — дополняет существующие.
     */
    private static void parse(String json, boolean merge) {
        try {
            JSONObject whitelist = new JSONObject(json).getJSONObject("whitelist");

            Set<String> f = merge && allowedFolders  != null ? new HashSet<>(allowedFolders)  : new HashSet<>();
            Set<String> c = merge && allowedChannels != null ? new HashSet<>(allowedChannels) : new HashSet<>();
            Set<String> b = merge && allowedBots     != null ? new HashSet<>(allowedBots)     : new HashSet<>();

            JSONArray fa = whitelist.optJSONArray("folders");
            if (fa != null) for (int i = 0; i < fa.length(); i++) f.add(fa.getString(i));

            JSONArray ca = whitelist.optJSONArray("channels");
            if (ca != null) for (int i = 0; i < ca.length(); i++) c.add(ca.getString(i).toLowerCase());

            JSONArray ba = whitelist.optJSONArray("bots");
            if (ba != null) for (int i = 0; i < ba.length(); i++) b.add(ba.getString(i).toLowerCase());

            allowedFolders  = Collections.unmodifiableSet(f);
            allowedChannels = Collections.unmodifiableSet(c);
            allowedBots     = Collections.unmodifiableSet(b);
        } catch (Exception e) {
            FileLog.e("WhitelistManager: parse error", e);
        }
    }
}
