package app.nowbarroad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Schedule.slot() == null) RoadNotification.cancel(context);
        else RoadNotification.update(context, false);
        Schedule.setup(context);
        Schedule.sync(context, false);
        RoadWidget.updateAll(context);
    }
}
