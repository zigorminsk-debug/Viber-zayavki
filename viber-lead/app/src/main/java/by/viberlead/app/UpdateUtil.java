package by.viberlead.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Чистая логика автообновления (без Android) — проверяется юнит-тестами:
 * разбор versionCode из имени релиза, versionName из имени файла APK,
 * подбор APK под наш пакет (pro / lite) и сравнение версий.
 *
 * Формат, на который опирается апдейтер (его задаёт GitHub Actions):
 *   имя релиза:  "ViberLead v2.28 (code 40)"
 *   файлы:       ViberLead-v2.28-pro.apk, ViberLead-v2.28-lite.apk
 */
public final class UpdateUtil {

    private UpdateUtil() {
    }

    /** versionCode из имени релиза: «ViberLead v2.28 (code 40)» → 40; нет — 0. */
    public static int codeFromReleaseName(String name) {
        if (name == null) return 0;
        Matcher m = Pattern.compile("\\(\\s*code\\s+([0-9]+)\\s*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(name);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** versionName из имени файла: «ViberLead-v2.28-lite.apk» → «2.28». */
    public static String versionNameFromAsset(String assetName) {
        if (assetName == null) return "";
        Matcher m = Pattern.compile("ViberLead-v(.+?)-(?:pro|lite)\\.apk$", Pattern.CASE_INSENSITIVE)
                .matcher(assetName);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Подходит ли файл APK под наш пакет: pro-сборка берёт не-lite файл,
     * lite-сборка (…app.lite) — только -lite. Иначе «обновление» ставилось
     * бы как отдельное приложение рядом.
     */
    public static boolean assetMatchesPackage(String assetName, String applicationId) {
        if (assetName == null || applicationId == null) return false;
        String a = assetName.toLowerCase(Locale.US);
        if (!a.endsWith(".apk")) return false;
        boolean lite = applicationId.endsWith(".lite");
        return lite ? a.endsWith("-lite.apk") : !a.endsWith("-lite.apk");
    }

    /**
     * Новая ли версия: прежде всего по versionCode (он строго растёт в CI);
     * если в релизе кода не распознать — по versionName.
     */
    public static boolean isNewer(int remoteCode, int localCode, String remoteName, String localName) {
        if (remoteCode > 0 && localCode > 0) return remoteCode > localCode;
        return versionNameIsNewer(remoteName, localName);
    }

    /** «2.29» новее «2.28»; «2.2.10» новее «2.2.9»; равные и пустые — нет. */
    public static boolean versionNameIsNewer(String remote, String local) {
        if (remote == null || remote.trim().isEmpty()) return false;
        if (local == null || local.trim().isEmpty()) return true;
        String r = remote.trim();
        String l = local.trim();
        if (r.equalsIgnoreCase(l)) return false;
        String[] rp = r.split("[.\\-_]");
        String[] lp = l.split("[.\\-_]");
        int n = Math.max(rp.length, lp.length);
        for (int i = 0; i < n; i++) {
            int rv = i < rp.length ? numericPart(rp[i]) : 0;
            int lv = i < lp.length ? numericPart(lp[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    /** «28» → 28, «28b» → 28, «beta» → 0, сверхдлинное → не падает. */
    public static int numericPart(String s) {
        if (s == null) return 0;
        String d = s.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return 0;
        if (d.length() > 9) d = d.substring(0, 9);
        try {
            return Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
