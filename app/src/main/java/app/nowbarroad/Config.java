package app.nowbarroad;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONObject;

final class Config {
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("road", Context.MODE_PRIVATE);
    }

    static String validateServer(String value) {
        String base = value.trim().replaceAll("/+$", "");
        Uri uri = Uri.parse(base);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("Нужен адрес вида https://road.example.com без пути");
        }
        return base;
    }

    static boolean connected(Context c) {
        return !prefs(c).getString("server", "").isEmpty() && !prefs(c).getString("token", "").isEmpty();
    }

    static JSONObject cached(Context c) {
        try { return new JSONObject(prefs(c).getString("status", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    static boolean stale(JSONObject route) {
        return route.optBoolean("stale", true)
                || System.currentTimeMillis() / 1000.0 - route.optDouble("updated_at", 0) > 600;
    }
}
