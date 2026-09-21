package by.viberlead.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ПОЛНАЯ история отправленных заявок — в отдельной SQLite-базе.
 *
 * Почему не SharedPreferences, как раньше: история хранилась одной JSON-строкой
 * в настройках и писалась через apply() АСИНХРОННО — когда Android убивал
 * процесс (на MIUI — регулярно), ещё не записанные записи терялись, и
 * «история помнила только последнюю отправку». Плюс был искусственный
 * предел 200 записей. В базе каждая запись фиксируется в файле
 * синхронно (до возвращения из метода) и история хранится за всё время,
 * без лимитов.
 */
public class HistoryDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "history.db";
    private static final int DB_VERSION = 1;
    public static final String TABLE = "history";

    public HistoryDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL DEFAULT 0,"
                + "date TEXT NOT NULL DEFAULT '',"
                + "name TEXT NOT NULL DEFAULT '',"
                + "text TEXT NOT NULL DEFAULT '',"
                + "status TEXT NOT NULL DEFAULT 'ok',"
                + "detail TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_history_ts ON " + TABLE + " (ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 — изменений схемы пока нет
    }

    /**
     * Одна запись = одна заявка. Синхронная фиксация: строка уже в файле
     * к моменту, когда этот метод вернётся, — убийство процесса её не сотрёт.
     */
    public void add(String name, String text, String status, String detail) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis());
        v.put("date", new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(new Date()));
        v.put("name", name == null ? "" : name);
        v.put("text", text == null ? "" : text);
        v.put("status", status == null ? Settings.STATUS_OK : status);
        v.put("detail", detail == null ? "" : detail);
        getWritableDatabase().insert(TABLE, null, v);
    }

    /** Все записи, новые сверху. */
    public List<JSONObject> all() {
        List<JSONObject> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, "_id DESC");
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("ts", c.getLong(c.getColumnIndexOrThrow("ts")));
                o.put("date", c.getString(c.getColumnIndexOrThrow("date")));
                o.put("name", c.getString(c.getColumnIndexOrThrow("name")));
                o.put("text", c.getString(c.getColumnIndexOrThrow("text")));
                o.put("status", c.getString(c.getColumnIndexOrThrow("status")));
                o.put("detail", c.getString(c.getColumnIndexOrThrow("detail")));
                out.add(o);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return out;
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try {
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    /**
     * Одноразовая миграция старой истории из SharedPreferences
     * (JSON-массив, новые сверху): если база пуста — все старые записи
     * переносятся в хронологическом порядке. Возвращает число перенесённых.
     */
    public int migrateFromLegacy(List<JSONObject> legacyNewestFirst) {
        if (legacyNewestFirst == null || legacyNewestFirst.isEmpty()) return 0;
        if (count() > 0) return 0; // история уже заполняется из БД — старые не смешиваем
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int n = 0;
        try {
            for (int i = legacyNewestFirst.size() - 1; i >= 0; i--) {
                JSONObject o = legacyNewestFirst.get(i);
                if (o == null) continue;
                ContentValues v = new ContentValues();
                v.put("ts", o.optLong("ts", 0));
                v.put("date", o.optString("date", "—"));
                v.put("name", o.optString("name", ""));
                v.put("text", o.optString("text", ""));
                v.put("status", o.optString("status", Settings.STATUS_OK));
                v.put("detail", o.optString("detail", ""));
                db.insert(TABLE, null, v);
                n++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }
}
