package app.nowbarroad;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;

final class RoadNotification {
    private static final int ID = 41;
    private static final String CHANNEL = "commute";

    static void cancel(Context c) { c.getSystemService(NotificationManager.class).cancel(ID); }

    static void update(Context c, boolean demo) {
        String slot = Schedule.slot();
        if (!demo && slot == null) { cancel(c); return; }
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Время в пути", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
        JSONObject routes = Config.cached(c).optJSONObject("routes");
        JSONObject route = routes == null || slot == null ? null : routes.optJSONObject(slot);
        JSONObject best = route == null ? null : route.optJSONObject("best");
        String time = demo ? "23 мин" : best == null ? "Нет данных" : best.optInt("minutes") + " мин";
        String name = demo ? "Тест Now Bar · пример" : route == null ? "Маршрут" : route.optString("name");
        boolean stale = !demo && (route == null || Config.stale(route)
                || Config.prefs(c).contains("sync_error"));
        String text = demo ? "Метро · тестовые данные, скроется через 2 минуты"
                : best == null ? "Откройте приложение и обновите маршрут"
                : best.optString("summary") + " · пешком " + best.optInt("walk_minutes") + " мин";
        if (stale) text = "Устаревшие данные · " + text;
        Intent open = route == null ? new Intent(c, MainActivity.class)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(route.optString("url")));
        PendingIntent pi = PendingIntent.getActivity(c, 41, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long timeout = demo ? 120000 : Math.max(1, Schedule.windowEnd() - System.currentTimeMillis());
        Notification.Builder builder = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_road).setContentTitle(name + " · " + time)
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_STATUS)
                .setTimeoutAfter(timeout).setShowWhen(true)
                .setWhen(demo || route == null ? System.currentTimeMillis() : (long)(route.optDouble("updated_at", 0) * 1000));
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(stale ? "Нет свежих" : time);
            Bundle extras = new Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            builder.addExtras(extras);
        }
        nm.notify(ID, builder.build());
    }
}
