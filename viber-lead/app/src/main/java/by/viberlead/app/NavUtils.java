package by.viberlead.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Маршрут до адреса в Яндекс Навигаторе + ссылка на точку для сообщения.
 *
 * Навигатор строит маршрут по координатам (yandexnavi://build_route_on_map?lat_to&lon_to),
 * поэтому адрес сначала геокодируется:
 *   1) если у организации в базе уже есть координаты — маршрут строится мгновенно;
 *   2) иначе разовый запрос геокодера (OSM Nominatim), координаты кэшируются в базу;
 *   3) если геокодирование не удалось — открывается Яндекс.Карта с режимом маршрута
 *      (приложение Яндекс.Карт/Навигатора перехватывает ссылку, иначе браузер);
 *   4) крайний резерв — системный выбор карт через geo:-intent.
 */
public class NavUtils {

    public interface CoordsCache {
        void onGeocoded(double lat, double lon);
    }

    private static final String NAVI_PKG = "ru.yandex.yandexnavi";

    public static void openRoute(final Activity activity, final String address,
                                 final double lat, final double lon, final CoordsCache cache) {
        if (address == null || address.trim().isEmpty()) {
            Toast.makeText(activity, "Адрес пуст — некуда строить маршрут", Toast.LENGTH_SHORT).show();
            return;
        }
        if ((lat != 0 || lon != 0) && inMinskRegion(lat, lon)) {
            if (launchNavi(activity, lat, lon)) return;
            fallback(activity, address);
            return;
        }
        // закэшированная точка вне Минского региона — считаем, что кэша нет

        Toast.makeText(activity, "Ищу координаты адреса…", Toast.LENGTH_SHORT).show();
        final String addr = address.trim();
        new Thread(() -> {
            final double[] ll = geocodeAddress(addr);
            activity.runOnUiThread(() -> {
                if (ll != null) {
                    if (cache != null) cache.onGeocoded(ll[0], ll[1]);
                    if (launchNavi(activity, ll[0], ll[1])) return;
                }
                fallback(activity, addr);
            });
        }, "geocode").start();
    }

    /** Яндекс Навигатор: маршрут из текущей точки до координат. */
    private static boolean launchNavi(Context ctx, double lat, double lon) {
        String uri = String.format(Locale.US,
                "yandexnavi://build_route_on_map?lat_to=%.6f&lon_to=%.6f", lat, lon);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.setPackage(NAVI_PKG);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception first) {
            try {
                // вдруг навигатор зарегистрирован без package-фильтра
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static void fallback(Activity activity, String address) {
        String enc = Uri.encode(address);
        // Яндекс.Карты сразу в режиме маршрута (приложение перехватывает домен)
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://yandex.ru/maps/?mode=routes&text=" + enc));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(Intent.createChooser(i, "Построить маршрут"));
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Не удалось открыть карту. Установите Яндекс Навигатор или скопируйте адрес.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Ссылка на ТОЧКУ в Яндекс.Картах (открывается и в Навигаторе):
     * получатель заявки тапом строит маршрут от своей позиции.
     */
    public static String pointLink(double lat, double lon) {
        return String.format(Locale.US,
                "https://yandex.ru/maps/?ll=%.5f,%.5f&pt=%.5f,%.5f&z=17", lon, lat, lon, lat);
    }

    /**
     * v2.23: очистка адреса для карты и геокодинга: убираем внутренности
     * (этаж/кабинет/офис), мусор пробелов; город добавляем, если не указан.
     */
    public static String sanitizeForMap(String address) {
        String a = address == null ? "" : address.trim();
        String cut = a.replaceAll("(?i)[,\\s]*\\d+\\s*(эт|этаж|этаже)\\.?", "")
                .replaceAll("(?i)[,\\s]*(каб|кабинет|офис)\\.?\\s*\\d*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[,\\s]+|[,\\s]+$", "");
        if (!cut.isEmpty()) a = cut;
        if (!a.toLowerCase(Locale.US).contains("минск")) a = a + ", Минск";
        return a;
    }

    /**
     * v2.25: кликабельная ссылка, которую перехватывает Яндекс.Навигатор:
     * режим маршрута сразу подставляет координаты как цель маршрута.
     */
    public static String routeLink(double lat, double lon) {
        return String.format(Locale.US,
                "https://yandex.ru/maps/?mode=routes&ll=%.5f,%.5f&pt=%.5f,%.5f&z=17",
                lon, lat, lon, lat);
    }

    /** v2.25: маршрутная ссылка по адресу (Навигатор построит маршрут до точки). */
    public static String routeSearchLink(String address) {
        return "https://yandex.ru/maps/?mode=routes&text="
                + sanitizeForMap(address).replace(" ", "+");
    }

    /** Резервная ссылка: короткий читаемый поиск адреса в Яндекс.Картах. */
    public static String searchLink(String address) {
        return "https://yandex.ru/maps/?text="
                + sanitizeForMap(address).replace(" ", "+");
    }

    /**
     * Варианты запроса: адрес ищется ТОЛЬКО в Минске и Минском районе.
     * Сначала уточнённые запросы (город/район), затем общие; область поиска
     * дополнительно ограничена рамкой Минского региона (viewbox + bounded).
     */
    private static List<String> geocodeQueries(String address) {
        List<String> out = new ArrayList<>();
        String raw = address.trim();
        String exp = expandAddress(raw);
        String low = raw.toLowerCase(java.util.Locale.US);
        if (!low.contains("минск")) {
            out.add(raw + ", Минск");
            out.add(exp + ", Минск");
            out.add(exp + ", Минский район");
        }
        out.add(raw);
        if (!exp.equals(raw)) out.add(exp);
        out.add(exp + ", Беларусь");
        return out;
    }

    /** Рамка Минска и Минского района: точки вне региона отбрасываются. */
    public static boolean inMinskRegion(double lat, double lon) {
        return lat >= 53.55 && lat <= 54.25 && lon >= 26.85 && lon <= 28.25;
    }

    /** «пр.» → «проспект» и т.п. — геокодер плохо понимает сокращения. */
    private static String expandAddress(String s) {
        String r = s;
        String[][] map = {
                {"пр-т", "проспект"}, {"пр.", "проспект"}, {"просп.", "проспект"},
                {"ул.", "улица"}, {"пер.", "переулок"}, {"б-р", "бульвар"}, {"бул.", "бульвар"},
                {"пл.", "площадь"}, {"мкр.", "микрорайон"}, {"мкр-н", "микрорайон"},
                {"ш.", "шоссе"}, {"д.", "дом"}, {"к.", "корпус"},
        };
        for (String[] kv : map) {
            r = r.replace(kv[0], " " + kv[1] + " ");
        }
        return r.replaceAll("\\s+", " ").trim();
    }

    public static double[] geocodeAddress(String address) {
        for (String q : geocodeQueries(address)) {
            double[] ll = geocodeOnce(q);
            if (ll != null) return ll;
        }
        return null;
    }

    /**
     * Геокодинг через публичный OSM Nominatim (без API-ключа).
     * Возвращает {lat, lon} или null.
     */
    private static double[] geocodeOnce(String address) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1"
                    + "&viewbox=26.85,53.55,28.25,54.25&bounded=1&q="
                    + Uri.encode(address));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "ViberLead-Android/2.6 (lead-form app)");
            conn.setRequestProperty("Accept-Language", "ru");
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            char[] buf = new char[2048];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
            JSONArray arr = new JSONArray(sb.toString());
            if (arr.length() == 0) return null;
            JSONObject o = arr.getJSONObject(0);
            double lat = o.getDouble("lat");
            double lon = o.getDouble("lon");
            if (!inMinskRegion(lat, lon)) return null; // точка вне Минского региона
            return new double[]{lat, lon};
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
