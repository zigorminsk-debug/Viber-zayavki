package by.viberlead.app;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Внешняя копия базы организаций: файл Download/viberlead_orgs.json.
 *
 *  — переживает удаление и переустановку приложения (файл лежит в общей папке
 *    «Загрузки», а не в приватной папке приложения);
 *  — при старте приложения пустая внутренняя база автоматически восстанавливается
 *    из этого файла («автоприсоединение»);
 *  — файлом можно поделиться: кнопка в «Настройках» открывает системный экран
 *    «Поделиться» (JSON читается и на другом устройстве).
 *
 * На Android 10+ запись/чтение идут через MediaStore.Downloads без разрешений;
 * на Android 9 и ниже — через общий каталог Download с разрешением
 * WRITE_EXTERNAL_STORAGE (запрашивается при первом «Поделиться»).
 */
public final class OrgDbSync {

    public static final String FILE_NAME = "viberlead_orgs.json";
    /** Ставится на время импорта, чтобы не перезаписывать файл теми же данными. */
    public static volatile boolean suppress = false;

    private OrgDbSync() {
    }

    /** Асинхронный экспорт после каждого изменения базы. */
    public static void scheduleExport(final Context ctx, final OrgDb db) {
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                export(app, db);
            } catch (Exception ignored) {
            }
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Сериализует всю базу в JSON и кладёт во внешний файл. */
    public static void export(Context ctx, OrgDb db) throws Exception {
        JSONArray arr = new JSONArray();
        List<OrgDb.Org> all = db.all();
        for (OrgDb.Org o : all) {
            JSONObject j = new JSONObject();
            j.put("name", o.name);
            j.put("address", o.address);
            j.put("contact", o.contact);
            j.put("phone", o.phone);
            j.put("lat", o.lat);
            j.put("lon", o.lon);
            arr.put(j);
        }
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("app", "by.viberlead.app");
        root.put("exported_at", System.currentTimeMillis());
        root.put("orgs", arr);
        write(ctx, root.toString().getBytes("UTF-8"));
    }

    /**
     * v2.16: «Принять базу» — слияние из файла: добавляются только организации
     * с новой парой «название + адрес»; совпадения пропускаются.
     * Возвращает {добавлено, пропущено}.
     */
    public static int[] mergeFromJson(OrgDb db, String json) throws Exception {
        String trim = json.trim();
        JSONArray arr;
        if (trim.startsWith("[")) {
            arr = new JSONArray(trim);
        } else {
            JSONObject root = new JSONObject(trim);
            arr = root.optJSONArray("orgs");
            if (arr == null) arr = new JSONArray();
        }
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (OrgDb.Org o : db.all()) existing.add(orgKey(o.name, o.address));
        int added = 0, skipped = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;
            String name = j.optString("name", "").trim();
            String address = j.optString("address", "").trim();
            if (name.isEmpty()) {
                skipped++;
                continue;
            }
            String key = orgKey(name, address);
            if (existing.contains(key)) {
                skipped++;
                continue;
            }
            db.insert(name, address, j.optString("contact", ""), j.optString("phone", ""));
            double lat = j.optDouble("lat", 0), lon = j.optDouble("lon", 0);
            if (lat != 0 || lon != 0) {
                OrgDb.Org o = db.byName(name);
                if (o != null) db.saveCoords(o.id, lat, lon);
            }
            existing.add(key);
            added++;
        }
        return new int[]{added, skipped};
    }

    private static String orgKey(String name, String address) {
        return (name == null ? "" : name.trim().toLowerCase(java.util.Locale.US))
                + "|" + (address == null ? "" : address.trim().toLowerCase(java.util.Locale.US));
    }

    /** Автоприсоединение: если внутренняя база пуста, а внешний файл есть — читаем. */
    public static boolean importIfEmpty(Context ctx, OrgDb db) {
        try {
            if (db.count() > 0) return false;
            byte[] data = read(ctx);
            if (data == null || data.length == 0) return false;
            JSONObject root = new JSONObject(new String(data, "UTF-8"));
            JSONArray arr = root.optJSONArray("orgs");
            if (arr == null) return false;
            suppress = true;
            int n = 0;
            try {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.optJSONObject(i);
                    if (j == null) continue;
                    String name = j.optString("name", "").trim();
                    if (name.isEmpty()) continue;
                    db.upsert(name, j.optString("address", ""),
                            j.optString("contact", ""), j.optString("phone", ""));
                    if (j.optDouble("lat", 0) != 0 || j.optDouble("lon", 0) != 0) {
                        OrgDb.Org o = db.byName(name);
                        if (o != null) db.saveCoords(o.id, j.optDouble("lat", 0), j.optDouble("lon", 0));
                    }
                    n++;
                }
            } finally {
                suppress = false;
            }
            return n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** «Поделиться базой»: свежий экспорт + системный экран отправки файла. */
    public static void share(final Context ctx, final OrgDb db) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                export(app, db);
                Uri uri = shareUri(app);
                if (uri == null) throw new Exception("файл недоступен");
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.putExtra(Intent.EXTRA_SUBJECT, "База организаций ViberLead");
                i.putExtra(Intent.EXTRA_TEXT, "База организаций ViberLead, записей: " + db.count());
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                app.startActivity(Intent.createChooser(i, "Поделиться базой организаций")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (final Exception e) {
                toast(app, "Не удалось поделиться базой: " + e.getMessage());
            }
        }).start();
    }

    /** Есть ли внешний файл (для подписи в настройках). */
    public static boolean externalExists(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return mediaUri(ctx) != null;
            return legacyFile().exists();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------- носитель

    private static void write(Context ctx, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentResolver cr = ctx.getContentResolver();
            Uri uri = mediaUri(ctx);
            if (uri == null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore отклонил создание");
            }
            OutputStream os = cr.openOutputStream(uri, "rwt");
            try {
                os.write(bytes);
            } finally {
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } else {
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) return; // тихий пропуск
            FileOutputStream fos = new FileOutputStream(legacyFile());
            try {
                fos.write(bytes);
            } finally {
                fos.close();
            }
        }
    }

    private static byte[] read(Context ctx) throws Exception {
        InputStream is;
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = mediaUri(ctx);
            if (uri == null) return null;
            is = ctx.getContentResolver().openInputStream(uri);
        } else {
            File f = legacyFile();
            if (!f.exists()) return null;
            is = new java.io.FileInputStream(f);
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private static Uri shareUri(Context ctx) {
        if (Build.VERSION.SDK_INT >= 29) return mediaUri(ctx);
        File f = legacyFile();
        if (!f.exists()) return null;
        return Uri.parse("content://" + ctx.getPackageName() + ".dbfile/" + FILE_NAME);
    }

    private static Uri mediaUri(Context ctx) {
        Cursor c = ctx.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + "=?",
                new String[]{FILE_NAME}, null);
        if (c == null) return null;
        try {
            if (c.moveToFirst()) {
                return ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
            return null;
        } finally {
            c.close();
        }
    }

    private static File legacyFile() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), FILE_NAME);
    }

    private static void toast(final Context ctx, final String msg) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }
}
