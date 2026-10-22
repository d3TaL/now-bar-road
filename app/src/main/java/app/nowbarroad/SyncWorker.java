package app.nowbarroad;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;

public class SyncWorker extends Worker {
    public SyncWorker(Context c, WorkerParameters p) { super(c, p); }

    @Override public Result doWork() {
        Context c = getApplicationContext();
        if (!Config.connected(c)) return Result.success();
        String slot = Schedule.slot();
        boolean manual = getInputData().getBoolean("manual", false);
        try {
            if (manual) {
                JSONObject routes = Config.cached(c).optJSONObject("routes");
                for (String name : new String[]{"morning", "evening"}) {
                    if (routes != null && routes.has(name))
                        Api.request(c, "/v1/refresh", new JSONObject().put("slot", name));
                }
            }
            // Server refreshes the active route independently every five minutes.
            Api.request(c, "/v1/status", null);
            RoadNotification.update(c, false);
            RoadWidget.updateAll(c);
            Schedule.nextAlarm(c);
            return Result.success();
        } catch (Exception e) {
            Config.prefs(c).edit().putString("sync_error", "Не удалось обновить данные с сервера").apply();
            if (slot == null) RoadNotification.cancel(c);
            else RoadNotification.update(c, false);
            RoadWidget.updateAll(c);
            return Result.retry();
        }
    }
}
