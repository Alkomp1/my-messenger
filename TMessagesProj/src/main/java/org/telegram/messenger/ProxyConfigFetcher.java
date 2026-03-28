package org.telegram.messenger;

import android.app.Activity;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ProxyConfigFetcher {

    private static final String KEY_JSON = "proxy_config_json";
    private static final String PREFS = "proxy_config_cache";

    /**
     * Синхронный fetch — блокирует поток не дольше timeoutMs мс.
     * Вызывать ДО инициализации ConnectionsManager (в postInitApplication).
     * Пишет прокси только в SharedPreferences, без setProxySettings.
     */
    public static void fetchSync(int timeoutMs) {
        String configUrl = BuildConfig.CONFIG_URL;
        if (configUrl == null || configUrl.isEmpty()) {
            applyCached(false);
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            try {
                URL url = new URL(configUrl);
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
                    String json = sb.toString().trim();
                    // Кэш
                    ApplicationLoader.applicationContext
                            .getSharedPreferences(PREFS, 0)
                            .edit().putString(KEY_JSON, json).apply();
                    // Только SharedPreferences — ConnectionsManager ещё не запущен
                    writePrefProxy(json);
                } else {
                    applyCached(false);
                }
            } catch (Exception ignored) {
                applyCached(false);
            } finally {
                latch.countDown();
            }
        }).start();

        try {
            latch.await(timeoutMs + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    /**
     * Асинхронный fetch — не блокирует поток.
     * Вызывать после старта ConnectionsManager (в DialogsActivity).
     * Применяет прокси через setProxySettings.
     */
    public static void fetchAsync() {
        String configUrl = BuildConfig.CONFIG_URL;
        if (configUrl == null || configUrl.isEmpty()) {
            applyCached(true);
            return;
        }

        new Thread(() -> {
            try {
                URL url = new URL(configUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    String json = sb.toString().trim();
                    // Кэш
                    ApplicationLoader.applicationContext
                            .getSharedPreferences(PREFS, 0)
                            .edit().putString(KEY_JSON, json).apply();
                    applyProxy(json);
                } else {
                    applyCached(true);
                }
            } catch (Exception ignored) {
                applyCached(true);
            }
        }).start();
    }

    /**
     * Если кэш есть — одиночный fetchAsync().
     * Если кэша нет — фоновый ретрай каждые 5 сек до первого успешного фетча.
     * Вызывать после старта ConnectionsManager (в LaunchActivity).
     */
    public static void fetchWithRetry() {
        String cached = ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, 0)
                .getString(KEY_JSON, null);
        if (cached != null) {
            fetchAsync();
            return;
        }
        String configUrl = BuildConfig.CONFIG_URL;
        if (configUrl == null || configUrl.isEmpty()) return;

        new Thread(() -> {
            while (true) {
                try {
                    URL url = new URL(configUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");

                    if (conn.getResponseCode() == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                        }
                        String json = sb.toString().trim();
                        ApplicationLoader.applicationContext
                                .getSharedPreferences(PREFS, 0)
                                .edit().putString(KEY_JSON, json).apply();
                        applyProxy(json);
                        return;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    /** Применить кэшированный конфиг. */
    private static void applyCached(boolean useSetProxySettings) {
        String json = ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, 0)
                .getString(KEY_JSON, null);
        if (json == null) return;
        if (useSetProxySettings) {
            applyProxy(json);
        } else {
            writePrefProxy(json);
        }
    }

    /** Применить прокси через setProxySettings (когда ConnectionsManager уже запущен). */
    private static void applyProxy(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("proxy")) return;
            JSONObject p = root.getJSONObject("proxy");
            String server = p.optString("server", "");
            int port = p.optInt("port", 0);
            String secret = p.optString("secret", "");
            if (server.isEmpty() || port == 0) return;

            ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                    .edit()
                    .putString("proxy_ip", server)
                    .putInt("proxy_port", port)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", secret)
                    .putBoolean("proxy_enabled", true)
                    .putBoolean("proxy_enabled_calls", true)
                    .apply();
            SharedConfig.currentProxy = new SharedConfig.ProxyInfo(server, port, "", "", secret);
            ConnectionsManager.setProxySettings(true, server, port, "", "", secret);
        } catch (Exception e) {
            FileLog.e("ProxyConfigFetcher: apply error", e);
        }
    }

    /** Записать прокси только в SharedPreferences (когда ConnectionsManager ещё не запущен). */
    private static void writePrefProxy(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("proxy")) return;
            JSONObject p = root.getJSONObject("proxy");
            String server = p.optString("server", "");
            int port = p.optInt("port", 0);
            String secret = p.optString("secret", "");
            if (server.isEmpty() || port == 0) return;

            ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                    .edit()
                    .putString("proxy_ip", server)
                    .putInt("proxy_port", port)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", secret)
                    .putBoolean("proxy_enabled", true)
                    .putBoolean("proxy_enabled_calls", true)
                    .apply();
        } catch (Exception ignored) {}
    }
}
