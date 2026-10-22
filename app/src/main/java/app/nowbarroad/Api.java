package app.nowbarroad;

import android.content.Context;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class Api {
    static JSONObject request(Context context, String path, JSONObject body) throws Exception {
        String base = Config.validateServer(Config.prefs(context).getString("server", ""));
        String token = Config.prefs(context).getString("token", "");
        HttpURLConnection conn = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text;
            if (stream == null) throw new IllegalStateException("Сервер ответил HTTP " + code);
            try (InputStream in = stream) {
                byte[] bytes = in.readNBytes(262145);
                if (bytes.length > 262144) throw new IllegalStateException("Слишком большой ответ сервера");
                text = new String(bytes, StandardCharsets.UTF_8);
            }
            JSONObject result;
            try { result = new JSONObject(text); }
            catch (Exception e) { throw new IllegalStateException("Сервер вернул не JSON, HTTP " + code); }
            if (code != 200) throw new IllegalStateException(result.optString("error", "HTTP " + code));
            Config.prefs(context).edit().putString("status", result.toString()).remove("sync_error").apply();
            return result;
        } finally { conn.disconnect(); }
    }
}
