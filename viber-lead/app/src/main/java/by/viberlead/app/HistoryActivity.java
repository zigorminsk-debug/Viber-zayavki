package by.viberlead.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * История отправленных заявок — ПОЛНАЯ, за всё время.
 * Хранение — в отдельной SQLite-базе (HistoryDb): записи фиксируются
 * синхронно и не теряются при убийстве процесса (в прежнем варианте
 * история хранилась в SharedPreferences асинхронно и «помнила» только
 * последние отправки). Старая история из настроек переносится в базу
 * автоматически при первом запуске.
 */
public class HistoryActivity extends Activity {

    private HistoryDb db;
    private ListView list;
    private TextView tvEmpty;
    private HistoryAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_history);
        db = new HistoryDb(this);

        // одноразовая миграция старой истории (SharedPreferences → база)
        int migrated = db.migrateFromLegacy(new Settings(this).takeLegacyHistory());
        if (migrated > 0) {
            Toast.makeText(this, "📦 Старая история перенесена в базу: " + migrated
                    + " зап. — теперь хранится вся", Toast.LENGTH_LONG).show();
        }

        list = findViewById(R.id.history_list);
        tvEmpty = findViewById(R.id.tv_empty);
        adapter = new HistoryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            JSONObject o = adapter.getItem(position);
            if (o == null) return;
            final String text = o.optString("text", "");
            new AlertDialog.Builder(this)
                    .setTitle(o.optString("name", "Заявка"))
                    .setMessage(text)
                    .setPositiveButton(R.string.btn_copy, (d, w) -> MainActivity.copyToClipboard(this, text))
                    .setNegativeButton(R.string.btn_open_viber, (d, w) -> MainActivity.openExternal(this, text))
                    .setNeutralButton(R.string.close, null)
                    .show();
        });

        Button btnBack = findViewById(R.id.btn_back);
        Button btnClear = findViewById(R.id.btn_clear);
        btnBack.setOnClickListener(v -> finish());
        btnClear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.btn_clear)
                .setMessage("Удалить ВСЮ историю (записей: " + db.count() + ")? "
                        + "Действие необратимо.")
                .setPositiveButton("Удалить", (d, w) -> {
                    db.clear();
                    adapter.refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());

        adapter.refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.refresh();
    }

    /** Адаптер списка: строки переиспользуются — любые тысячи записей не тормозят. */
    private class HistoryAdapter extends BaseAdapter {
        private final List<JSONObject> items = new ArrayList<>();

        void refresh() {
            items.clear();
            items.addAll(db.all());
            notifyDataSetChanged();
            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public JSONObject getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(HistoryActivity.this)
                        .inflate(R.layout.item_history, parent, false);
            }
            JSONObject o = getItem(position);
            final String text = o.optString("text", "");
            final String status = o.optString("status", Settings.STATUS_OK);

            TextView date = v.findViewById(R.id.item_date);
            TextView st = v.findViewById(R.id.item_status);
            TextView name = v.findViewById(R.id.item_name);
            TextView body = v.findViewById(R.id.item_text);
            Button copy = v.findViewById(R.id.item_copy);
            Button resend = v.findViewById(R.id.item_resend);

            date.setText(o.optString("date", "—") + "  ·  " + o.optString("detail", ""));
            name.setText(o.optString("name", "Заявка"));
            body.setText(text);

            if (Settings.STATUS_OK.equals(status)) {
                st.setText(R.string.st_ok);
                st.setBackgroundResource(R.drawable.bg_chip_ok);
                st.setTextColor(getResources().getColor(R.color.success));
            } else if (Settings.STATUS_MANUAL.equals(status)) {
                st.setText(R.string.st_manual);
                st.setBackgroundResource(R.drawable.bg_chip_ok);
                st.setTextColor(getResources().getColor(R.color.viber_purple_dark));
            } else {
                st.setText(R.string.st_err);
                st.setBackgroundResource(R.drawable.bg_chip_err);
                st.setTextColor(getResources().getColor(R.color.error));
            }

            copy.setOnClickListener(x -> MainActivity.copyToClipboard(HistoryActivity.this, text));
            resend.setOnClickListener(x -> MainActivity.openExternal(HistoryActivity.this, text));
            return v;
        }
    }
}
