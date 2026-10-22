package app.nowbarroad;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import androidx.work.*;

final class Schedule {
    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    static final int[] BOUNDARIES = {330, 420, 930, 1080};

    static String slot() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return null;
        int minute = now.getHour() * 60 + now.getMinute();
        if (minute >= 330 && minute < 420) return "morning";
        if (minute >= 930 && minute < 1080) return "evening";
        return null;
    }

    static long windowEnd() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String slot = slot();
        int end = "morning".equals(slot) ? 420 : 1080;
        return now.truncatedTo(ChronoUnit.DAYS).plusMinutes(end).toInstant().toEpochMilli();
    }

    static void setup(Context c) {
        if (!Config.connected(c)) return;
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("road-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic);
        nextAlarm(c);
    }

    static void nextAlarm(Context c) {
        if (!Config.connected(c)) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime next = null;
        for (int day = 0; day < 8 && next == null; day++) {
            ZonedDateTime date = now.truncatedTo(ChronoUnit.DAYS).plusDays(day);
            if (date.getDayOfWeek().getValue() > 5) continue;
            for (int minute : BOUNDARIES) {
                ZonedDateTime candidate = date.plusMinutes(minute);
                if (candidate.isAfter(now)) { next = candidate; break; }
            }
        }
        if (next == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 1,
                new Intent(c, ScheduleReceiver.class).setAction("app.nowbarroad.BOUNDARY"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = next.toInstant().toEpochMilli();
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); }
    }

    static void sync(Context c, boolean manual) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setInputData(new Data.Builder().putBoolean("manual", manual).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork("road-sync", ExistingWorkPolicy.KEEP, work);
    }
}
