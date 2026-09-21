package by.viberlead.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Локальная база организаций (SQLite, без внешних зависимостей).
 *
 * Пополняется автоматически: при отправке заявки организация из поля типа «org»
 * вместе с текущими адресом / контактом / телефоном сохраняется (или обновляется).
 * Используется для живого поиска и автоподстановки в форме.
 *
 * v2: телефоны в базе приводятся к международному формату (миграция старых строк).
 */
public class OrgDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "orgs.db";
    private static final int DB_VERSION = 2;
    public static final String TABLE = "organizations";

    public static class Org {
        public long id;
        public String name = "";
        public String address = "";
        public String contact = "";
        public String phone = "";
        public double lat;
        public double lon;

        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (!address.isEmpty()) sb.append(address);
            if (!contact.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(contact);
            }
            if (!phone.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(phone);
            }
            return sb.toString();
        }
    }

    private final Context ctx;

    public OrgDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.ctx = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL COLLATE NOCASE,"
                + "address TEXT DEFAULT '',"
                + "contact TEXT DEFAULT '',"
                + "phone TEXT DEFAULT '',"
                + "lat REAL DEFAULT 0,"
                + "lon REAL DEFAULT 0,"
                + "created_at INTEGER DEFAULT 0,"
                + "updated_at INTEGER DEFAULT 0,"
                + "UNIQUE(name COLLATE NOCASE))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // приводим уже сохранённые телефоны к международному формату
            String cc;
            try {
                cc = new Settings(ctx).getPhoneCc();
            } catch (Exception e) {
                cc = "375";
            }
            Cursor c = db.query(TABLE, new String[]{"_id", "phone"},
                    null, null, null, null, null);
            List<long[]> ids = new ArrayList<>();
            List<String> phones = new ArrayList<>();
            while (c.moveToNext()) {
                ids.add(new long[]{c.getLong(0)});
                phones.add(c.getString(1));
            }
            c.close();
            for (int i = 0; i < ids.size(); i++) {
                String norm = PhoneUtils.normalize(phones.get(i), cc);
                if (!norm.equals(phones.get(i))) {
                    ContentValues v = new ContentValues();
                    v.put("phone", norm);
                    db.update(TABLE, v, "_id = ?", new String[]{String.valueOf(ids.get(i)[0])});
                }
            }
        }
    }

    // ------------------------------------------------------------------ чтение

    /** Живой поиск: совпадение по названию или адресу. Пустой запрос — последние обновлённые. */
    public List<Org> search(String query, int limit) {
        List<Org> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        if (query == null || query.trim().isEmpty()) {
            c = db.query(TABLE, null, null, null, null, null, "updated_at DESC", String.valueOf(limit));
        } else {
            String q = query.trim();
            String like = "%" + q + "%";
            // сначала совпадения по началу названия, затем остальные по алфавиту;
            // кавычки в запросе экранируем удвоением (bind-аргументы в ORDER BY не поддерживаются)
            String safe = q.replace("'", "''");
            c = db.query(TABLE, null, "name LIKE ? OR address LIKE ?",
                    new String[]{like, like}, null, null,
                    "CASE WHEN name LIKE '" + safe + "%' THEN 0 ELSE 1 END, name",
                    String.valueOf(limit));
        }
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    public List<Org> all() {
        return search("", 500);
    }

    public Org byName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, "name COLLATE NOCASE = ?",
                new String[]{name.trim()}, null, null, null, "1");
        Org o = c.moveToNext() ? fromCursor(c) : null;
        c.close();
        return o;
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        int n = c.moveToNext() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    private Org fromCursor(Cursor c) {
        Org o = new Org();
        o.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        o.name = c.getString(c.getColumnIndexOrThrow("name"));
        o.address = c.getString(c.getColumnIndexOrThrow("address"));
        o.contact = c.getString(c.getColumnIndexOrThrow("contact"));
        o.phone = c.getString(c.getColumnIndexOrThrow("phone"));
        o.lat = c.getDouble(c.getColumnIndexOrThrow("lat"));
        o.lon = c.getDouble(c.getColumnIndexOrThrow("lon"));
        return o;
    }

    // ------------------------------------------------------------------ запись

    /**
     * Создаёт или обновляет организацию по названию.
     * Пустые значения не затирают уже сохранённые (чтобы правки в форме
     * не портили базу при повторных заявках). Телефон нормализуется.
     */
    public void upsert(String name, String address, String contact, String phone) {
        if (name == null || name.trim().isEmpty()) return;
        String cc;
        try {
            cc = new Settings(ctx).getPhoneCc();
        } catch (Exception e) {
            cc = "375";
        }
        String normPhone = PhoneUtils.normalize(phone, cc);
        Org old = byName(name);
        ContentValues v = new ContentValues();
        v.put("name", name.trim());
        v.put("address", pick(address, old == null ? "" : old.address));
        v.put("contact", pick(contact, old == null ? "" : old.contact));
        v.put("phone", pick(normPhone, old == null ? "" : old.phone));
        v.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        if (old == null) {
            v.put("created_at", System.currentTimeMillis());
            db.insert(TABLE, null, v);
        } else {
            db.update(TABLE, v, "_id = ?", new String[]{String.valueOf(old.id)});
        }
    
        if (!OrgDbSync.suppress) OrgDbSync.scheduleExport(ctx, this);
    }

    private static String pick(String fresh, String old) {
        return fresh == null || fresh.trim().isEmpty() ? old : fresh.trim();
    }

    /**
     * Чистка старого кэша: координаты вне Минска и Минского района
     * (сохранённые до v2.6) сбрасываются, чтобы точка не уезжала за 900 км.
     */
    public int purgeOutOfBoundsCoords() {
        int n = 0;
        for (Org o : all()) {
            if ((o.lat != 0 || o.lon != 0) && !NavUtils.inMinskRegion(o.lat, o.lon)) {
                saveCoords(o.id, 0, 0);
                n++;
            }
        }
        return n;
    }

    public void saveCoords(long id, double lat, double lon) {
        ContentValues v = new ContentValues();
        v.put("lat", lat);
        v.put("lon", lon);
        getWritableDatabase().update(TABLE, v, "_id = ?", new String[]{String.valueOf(id)});
    
        if (!OrgDbSync.suppress) OrgDbSync.scheduleExport(ctx, this);
    }

    public void update(Org o) {
        ContentValues v = new ContentValues();
        v.put("name", o.name);
        v.put("address", o.address);
        v.put("contact", o.contact);
        v.put("phone", o.phone);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, v, "_id = ?", new String[]{String.valueOf(o.id)});
    
        if (!OrgDbSync.suppress) OrgDbSync.scheduleExport(ctx, this);
    }

    public void insert(String name, String address, String contact, String phone) {
        // upsert сам планирует экспорт — дублировать не нужно
        upsert(name, address, contact, phone);
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "_id = ?", new String[]{String.valueOf(id)});
    
        if (!OrgDbSync.suppress) OrgDbSync.scheduleExport(ctx, this);
    }
}
