package by.viberlead.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Автообновление из GitHub Releases:
 *  1) checkLatest — спрашивает GitHub API, какой сейчас latest-релиз,
 *     находит в нём APK, подходящий НАШЕМУ пакету (pro / lite);
 *  2) downloadApk — скачивает APK в filesDir/updates (с прогрессом);
 *  3) launchInstaller — запускает системный установщик.
 *
 * Важное про Android 8+ (API 26): чтобы приложение могло ставить APK,
 * пользователь должен разрешить ему «установку неизвестных приложений»
 * (REQUEST_INSTALL_PACKAGES). launchInstaller сам открывает нужный экран
 * настроек, если разрешения нет.
 *
 * Все сетевые методы вызываются ТОЛЬКО из фоновых потоков.
 */
public final class Updater {

    private static final String API_BASE = "https://api.github.com";

    private Updater() {
    }

    /** Интерфейс прогресса скачивания (вызовы из фонового потока). */
    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    /** Результат проверки: что за версия на GitHub и откуда качать наш APK. */
    public static class Latest {
        public boolean ok;
        public int versionCode;      // из имени релиза («(code N)»); 0, если не распознан
        public String versionName = "";
        public String releaseName = "";
        public String apkName = "";
        public String apkUrl = "";
        public long apkSize;
        public String error = "";

        public String display() {
            return versionName + (versionCode > 0 ? " (code " + versionCode + ")" : "");
        }
    }

    // ------------------------------------------------------------- версии приложения

    public static int localVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String localVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------- проверка GitHub

    /**
     * latest-релиз репозитория. Нужен токен, только если репозиторий приватный
     * (публичный читается и без него). Вызывать из фонового потока.
     */
    public static Latest checkLatest(Context ctx, String repo, String token) {
        Latest r = new Latest();
        HttpURLConnection conn = null;
        try {
            String cleanRepo = repo == null ? "" : repo.trim().replaceAll("^/+", "").replaceAll("/+$", "");
            if (cleanRepo.isEmpty()) {
                r.error = "Репозиторий не задан (Настройки → Обновление)";
                return r;
            }
            URL url = new URL(API_BASE + "/repos/" + cleanRepo + "/releases/latest");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "ViberLead-Android/" + localVersionName(ctx));
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "token " + token.trim());
            }
            int code = conn.getResponseCode();
            if (code == 401) {
                r.error = "Токен GitHub не принят (401). Проверьте токен в Настройки → Обновление.";
                return r;
            }
            if (code == 403 || code == 429) {
                r.error = "GitHub ограничил запросы (лимит частоты). Повторите через несколько минут.";
                return r;
            }
            if (code == 404) {
                r.error = "GitHub: 404 — репозиторий не найден или в нём ещё нет релизов. "
                        + "Для приватного репозитория нужен токен GitHub "
                        + "(Настройки → Обновление → Токен).";
                return r;
            }
            if (code != 200) {
                r.error = "GitHub ответил HTTP " + code;
                return r;
            }
            String body = readAll(conn.getInputStream());
            JSONObject rel = new JSONObject(body);
            r.releaseName = rel.optString("name", rel.optString("tag_name", ""));
            r.versionCode = UpdateUtil.codeFromReleaseName(r.releaseName);

            String pkg = ctx.getPackageName();
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (!UpdateUtil.assetMatchesPackage(name, pkg)) continue;
                    r.apkName = name;
                    r.apkUrl = a.optString("browser_download_url", "");
                    r.apkSize = a.optLong("size", 0);
                    r.versionName = UpdateUtil.versionNameFromAsset(name);
                    break;
                }
            }
            if (r.apkUrl.isEmpty() || r.apkName.isEmpty()) {
                r.error = "В последнем релизе нет APK для этой сборки (" + pkg + ")";
                return r;
            }
            // versionName из релиза, если в имени файла не распознал
            if (r.versionName.isEmpty()) {
                r.versionName = rel.optString("tag_name", "")
                        .replaceFirst("(?i)^v", "");
            }
            r.ok = true;
            return r;
        } catch (Exception e) {
            r.error = "Сетевая ошибка: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " — " + e.getMessage());
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------- скачивание

    /** Каталог, куда кладём скачанный APK. */
    public static File apkDir(Context ctx) {
        return new File(ctx.getFilesDir(), "updates");
    }

    /**
     * Скачивает APK по url в filesDir/updates/{destName}. Возвращает путь
     * к файлу или null при ошибке. Вызывать из фонового потока.
     */
    public static File downloadApk(Context ctx, String url, String destName,
                                   long knownSize, Progress progress) {
        try {
            File dir = apkDir(ctx);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File safeName = new File(dir, sanitizeName(destName));
            File tmp = new File(dir, safeName.getName() + ".part");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ViberLead-Android/" + localVersionName(ctx));
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            long total = conn.getContentLengthLong();
            if (total <= 0) total = knownSize;
            InputStream is = conn.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
            os.close();
            is.close();
            conn.disconnect();
            if (done < 512) { // не может быть нормальный APK меньше, чем это
                tmp.delete();
                return null;
            }
            if (safeName.exists()) safeName.delete();
            if (!tmp.renameTo(safeName)) {
                // на некоторых ФС renameTo падает — копируем вручную
                copyFile(tmp, safeName);
                tmp.delete();
            }
            return safeName;
        } catch (Exception e) {
            return null;
        }
    }

    /** Скачать APK с авторизацией по токену (для приватного репозитория). */
    public static File downloadApkAuth(Context ctx, String url, String token,
                                       String destName, long knownSize, Progress progress) {
        try {
            File dir = apkDir(ctx);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File safeName = new File(dir, sanitizeName(destName));
            File tmp = new File(dir, safeName.getName() + ".part");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ViberLead-Android/" + localVersionName(ctx));
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "token " + token.trim());
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            long total = conn.getContentLengthLong();
            if (total <= 0) total = knownSize;
            InputStream is = conn.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
            os.close();
            is.close();
            conn.disconnect();
            if (done < 512) {
                tmp.delete();
                return null;
            }
            if (safeName.exists()) safeName.delete();
            if (!tmp.renameTo(safeName)) {
                copyFile(tmp, safeName);
                tmp.delete();
            }
            return safeName;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------- установка

    /**
     * Запускает системный установщик для apk. На Android 8+ сначала проверяет
     * разрешение REQUEST_INSTALL_PACKAGES; если нет — открывает страницу
     * «Неизвестные источники» для нашего пакета и возвращает false.
     */
    public static boolean launchInstaller(Context ctx, File apk) {
        if (apk == null || !apk.exists()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            PackageManager pm = ctx.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                openUnknownSources(ctx);
                return false;
            }
        }
        Uri uri;
        try {
            // UpdateFileProvider (авторитет «<пакет>.update») отдаёт APK из
            // filesDir/updates/ — file://-ссылки система запрещает с Android 7
            uri = Uri.parse("content://" + ctx.getPackageName() + ".update/"
                    + android.net.Uri.encode(apk.getName()));
        } catch (Exception e) {
            return false;
        }
        Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            // fallback: открыть как файл в «Файлах» — пользователь сам поставит
            Intent v = new Intent(Intent.ACTION_VIEW);
            v.setDataAndType(uri, "application/vnd.android.package-archive");
            v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(ctx instanceof android.app.Activity)) v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(Intent.createChooser(v, "Установить обновлённую версию"));
                return true;
            } catch (Exception e2) {
                Toast.makeText(ctx, "Не удалось открыть установщик: " + e2.getMessage(),
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
    }

    /** Открыть настройки «неизвестные источники» для нашего пакета (API 26+). */
    public static void openUnknownSources(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()));
                if (!(ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } else {
                Intent i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                if (!(ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Настройки Android → Безопасность → Неизвестные источники",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------- служебное

    /** Оставляет в имени файла только безопасные символы. */
    public static String sanitizeName(String name) {
        if (name == null) return "update.apk";
        String s = name.replaceAll("[^A-Za-z0-9._\\-]", "_");
        if (s.length() > 80) s = s.substring(s.length() - 80);
        return s;
    }

    private static void copyFile(File from, File to) {
        try {
            InputStream is = new java.io.FileInputStream(from);
            OutputStream os = new FileOutputStream(to);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            is.close();
        } catch (Exception ignored) {
        }
    }

    private static String readAll(InputStream is) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
