package app.nowbarroad;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import org.json.JSONObject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class RoadWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        updateAll(c);
        Schedule.setup(c);
        Schedule.sync(c, false);
    }

    @Override public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        if ("app.nowbarroad.REFRESH".equals(intent.getAction())) Schedule.sync(c, true);
    }

    static void updateAll(Context c) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        JSONObject routes = Config.cached(c).optJSONObject("routes");
        for (int id : manager.getAppWidgetIds(new ComponentName(c, RoadWidget.class))) {
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.road_widget);
            String[] slots = {"morning", "evening"};
            int[] fields = {R.id.morning, R.id.evening};
            for (int i = 0; i < slots.length; i++) {
                JSONObject route = routes == null ? null : routes.optJSONObject(slots[i]);
                JSONObject best = route == null ? null : route.optJSONObject("best");
                String label = route == null ? (i == 0 ? "Утро" : "Вечер") : route.optString("name");
                String text = label + " · " + (best == null ? "нет данных" : best.optInt("minutes") + " мин");
                if (best != null) {
                    String at = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(Schedule.ZONE)
                            .format(Instant.ofEpochSecond((long)route.optDouble("updated_at", 0)));
                    text += "\n" + (Config.stale(route) ? "Устарело · " : "") + at;
                }
                views.setTextViewText(fields[i], text);
                Intent open = route == null ? new Intent(c, MainActivity.class)
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(route.optString("url")));
                views.setOnClickPendingIntent(fields[i], PendingIntent.getActivity(c, 100 + i, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            views.setTextViewText(R.id.refresh, Config.prefs(c).contains("sync_error") ? "↻ Нет связи · повторить" : "↻ Обновить");
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 0,
                    new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            views.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(c, 2,
                    new Intent(c, RoadWidget.class).setAction("app.nowbarroad.REFRESH"),
                    PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(id, views);
        }
    }
}
