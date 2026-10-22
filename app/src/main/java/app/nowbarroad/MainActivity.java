package app.nowbarroad;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText server, token, name, link;
    private Spinner slot;
    private TextView status;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if ("status".equals(key) || "sync_error".equals(key)) runOnUiThread(this::renderStatus);
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            root.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
            return insets;
        });
        title(root, "Now Bar Road", 28);
        title(root, "Самый быстрый общественный транспорт\nПн–Пт · 05:30–07:00 и 15:30–18:00 · Москва", 15);
        title(root, "Подключение", 21);
        server = input(root, "Сервер: https://road.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        token = input(root, "Токен", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        server.setText(Config.prefs(this).getString("server", ""));
        token.setText(Config.prefs(this).getString("token", ""));
        button(root, "Вставить ссылку подключения", () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard.hasPrimaryClip()) {
                CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                importConnection(value.toString());
            }
        });
        button(root, "Сохранить и проверить сервер", () -> {
            try {
                String base = Config.validateServer(server.getText().toString());
                String secret = token.getText().toString().trim();
                if (secret.length() < 24) throw new IllegalArgumentException("Токен должен содержать не менее 24 символов");
                boolean changed = !base.equals(Config.prefs(this).getString("server", ""))
                        || !secret.equals(Config.prefs(this).getString("token", ""));
                android.content.SharedPreferences.Editor edit = Config.prefs(this).edit().putString("server", base).putString("token", secret);
                if (changed) edit.remove("status").remove("sync_error");
                edit.apply();
                RoadNotification.cancel(this);
                RoadWidget.updateAll(this);
                Schedule.setup(this);
                perform(() -> Api.request(this, "/v1/status", null));
            } catch (Exception e) { message(e.getMessage()); }
        });
        title(root, "Маршрут", 21);
        slot = new Spinner(this);
        slot.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Утренний · 05:30–07:00", "Вечерний · 15:30–18:00"}));
        root.addView(slot);
        name = input(root, "Название: на работу / домой", InputType.TYPE_CLASS_TEXT);
        link = input(root, "Ссылка Яндекс Карт с общественным транспортом", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        button(root, "Сохранить маршрут", () -> {
            if (!Config.connected(this)) { message("Сначала подключите сервер"); return; }
            String selected = slot.getSelectedItemPosition() == 0 ? "morning" : "evening";
            String label = name.getText().toString().trim();
            if (label.isEmpty()) label = selected.equals("morning") ? "На работу" : "Домой";
            String finalLabel = label;
            String url = link.getText().toString();
            perform(() -> Api.request(this, "/v1/routes", new JSONObject().put("slot", selected)
                    .put("name", finalLabel).put("url", url)));
        });
        button(root, "Обновить оба маршрута", () -> {
            Schedule.sync(this, true);
            message("Обновление запрошено; источник опрашивается не чаще раза в 5 минут");
        });
        title(root, "Now Bar", 21);
        button(root, "Разрешить уведомления", () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1));
        button(root, "Разрешить точное расписание", () -> openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()))));
        button(root, "Настройки уведомлений и Live Updates", () -> openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        button(root, "Тест Now Bar на 2 минуты", () -> {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                message("Сначала разрешите уведомления"); return;
            }
            RoadNotification.update(this, true);
            message("Тестовое уведомление отправлено. Проверьте Now Bar на экране блокировки");
        });
        button(root, "Скрыть тест", () -> { RoadNotification.cancel(this); RoadNotification.update(this, false); });
        title(root, "Состояние", 21);
        status = title(root, "", 15);
        status.setTextIsSelectable(true);
        title(root, "Время — оценка Яндекс Карт. Учёт пробок и ожидания зависит от источника. "
                + "Показ в Now Bar проверяется на вашей прошивке; системное уведомление не гарантирует его появление.", 13);
        Config.prefs(this).registerOnSharedPreferenceChangeListener(listener);
        renderStatus();
        handleIntent(getIntent());
        Schedule.setup(this);
        if (Config.connected(this)) Schedule.sync(this, false);
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                if (text.contains("nowbarroad://connect")) importConnection(text);
                else { link.setText(text); message("Выберите утренний или вечерний маршрут и сохраните"); }
            }
        } else if (intent.getData() != null) importConnection(intent.getData().toString());
    }

    private void importConnection(String text) {
        try {
            int start = text.indexOf("nowbarroad://connect?");
            if (start < 0) throw new IllegalArgumentException("Нужна ссылка nowbarroad://connect?…");
            Uri uri = Uri.parse(text.substring(start).split("\\s")[0]);
            String base = Config.validateServer(uri.getQueryParameter("server"));
            String secret = uri.getQueryParameter("token");
            if (secret == null || secret.length() < 24) throw new IllegalArgumentException("В ссылке отсутствует токен");
            server.setText(base);
            token.setText(secret);
            message("Параметры заполнены. Нажмите «Сохранить и проверить сервер»");
        } catch (Exception e) { message("Не удалось прочитать ссылку подключения"); }
    }

    private interface Request { JSONObject run() throws Exception; }

    private void perform(Request request) {
        status.setText("Подключение…");
        io.execute(() -> {
            try {
                request.run();
                RoadWidget.updateAll(this);
                RoadNotification.update(this, false);
                runOnUiThread(() -> { renderStatus(); message("Готово"); });
            } catch (Exception e) {
                runOnUiThread(() -> { status.setText("Ошибка: " + e.getMessage()); message(e.getMessage()); });
            }
        });
    }

    private void renderStatus() {
        if (status == null) return;
        StringBuilder text = new StringBuilder();
        JSONObject routes = Config.cached(this).optJSONObject("routes");
        for (String id : new String[]{"morning", "evening"}) {
            JSONObject route = routes == null ? null : routes.optJSONObject(id);
            if (route == null) { text.append(id.equals("morning") ? "Утро" : "Вечер").append(": не настроено\n\n"); continue; }
            JSONObject best = route.optJSONObject("best");
            text.append(route.optString("name")).append(": ");
            if (best == null) text.append("нет расчёта");
            else text.append(best.optInt("minutes")).append(" мин\n").append(best.optString("summary"))
                    .append("\nПешком: ").append(best.optInt("walk_minutes")).append(" мин");
            if (Config.stale(route)) text.append("\nДанные устарели");
            if (!route.isNull("error") && route.has("error")) text.append("\n").append(route.optString("error"));
            text.append("\n\n");
        }
        text.append("Точное расписание: ").append(getSystemService(AlarmManager.class).canScheduleExactAlarms() ? "разрешено" : "нужно разрешение");
        if (Config.prefs(this).contains("sync_error")) text.append("\n").append(Config.prefs(this).getString("sync_error", ""));
        status.setText(text);
    }

    private EditText input(LinearLayout root, String hint, int type) {
        EditText field = new EditText(this); field.setHint(hint); field.setInputType(type);
        root.addView(field, new LinearLayout.LayoutParams(-1, -2)); return field;
    }
    private TextView title(LinearLayout root, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(24, 40, 60)); view.setPadding(0, 18, 0, 10);
        root.addView(view); return view;
    }
    private void button(LinearLayout root, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); root.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private void openSettings(Intent intent) {
        try { startActivity(intent); } catch (ActivityNotFoundException e) { message("Настройка недоступна на этой прошивке"); }
    }
    private void message(String text) { Toast.makeText(this, text == null ? "Ошибка" : text, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() { super.onResume(); renderStatus(); Schedule.setup(this); RoadNotification.update(this, false); }
    @Override protected void onDestroy() {
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(listener);
        io.shutdown(); super.onDestroy();
    }
}
