package by.viberlead.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Все настройки приложения хранятся в SharedPreferences.
 * Значения по умолчанию берутся из assets/fields.json и assets/message_template.txt,
 * а на случай недоступности assets продублированы в коде.
 */
public class Settings {

    private static final String PREFS = "viber_lead_prefs";
    private static final String KEY_INIT = "initialized_v1";
    /**
     * Версия встроенной конфигурации (поля + шаблон).
     * При обновлении приложения конфигурация из assets перечитывается принудительно,
     * чтобы новые поля гарантированно появлялись на главном экране.
     */
    public static final int ASSETS_CONFIG_VERSION = 3;
    private static final String KEY_CFG_VERSION = "assets_cfg_version";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_API = "api";
    public static final String MODE_DEEPLINK = "deeplink";
    public static final String MODE_WEBHOOK = "webhook";

    public static final String STATUS_OK = "ok";
    public static final String STATUS_MANUAL = "manual";
    public static final String STATUS_ERR = "err";

    private static final String K_MODE = "mode";
    private static final String K_TOKEN = "token";
    private static final String K_API_URL = "api_url";
    private static final String K_RECEIVER = "receiver";
    private static final String K_BROADCAST_LIST = "broadcast_list";
    private static final String K_CHAT_URI = "chat_uri";
    private static final String K_PHONE = "phone";
    private static final String K_PHONE_CC = "phone_cc";
    private static final String K_WEBHOOK_URL = "webhook_url";
    private static final String K_WEBHOOK_TOKEN = "webhook_token";
    private static final String K_COMPANY = "company";
    private static final String K_TEMPLATE = "template";
    private static final String K_TEMPLATE_BACKUP = "template_backup";
    private static final String K_HEAL_CODE = "heal_version_code";
    private static final String K_MIGRATE_V2 = "migrate_v2_code";
    private static final String K_LAST_CONTACT = "last_contact";
    private static final String K_FAST_SEND = "fast_send";
    private static final String K_GROUP_METHOD = "group_send_method";
    private static final String K_MIGRATE_210 = "migrate_210_code";
    private static final String K_MIGRATE_211 = "migrate_211_code";
    private static final String K_AUTO_SEND = "auto_send_click";
    private static final String K_POINT_MODE = "point_mode_v225";
    private static final String K_DRAFT = "draft_json_v227";
    private static final String K_FIELDS = "fields_json";
    private static final String K_HISTORY = "history_json";
    private static final String K_LAST_VALUES = "last_values";

    private static final String DEFAULT_API_URL = "https://chatapi.viber.com/pa";

    /** Резерв на случай, если assets прочитать не удалось. */
    private static final String FALLBACK_FIELDS = "["
            + "{\"key\":\"org\",\"label\":\"Организация\",\"type\":\"org\",\"required\":true,\"hint\":\"Начните вводить — база подскажет\"},"
            + "{\"key\":\"address\",\"label\":\"Адрес\",\"type\":\"address\",\"required\":true,\"hint\":\"Улица, дом (кнопка 🧭 — маршрут)\"},"
            + "{\"key\":\"contact\",\"label\":\"Контактное лицо\",\"type\":\"text\",\"required\":false,\"hint\":\"К кому обращаться\"},"
            + "{\"key\":\"org_phone\",\"label\":\"Телефон организации\",\"type\":\"phone\",\"required\":false,\"hint\":\"+375 …\"},"
            + "{\"key\":\"request\",\"label\":\"Заявка\",\"type\":\"multiselect\",\"required\":true,"
            + "\"options\":[\"Заправка\",\"Ремонт\",\"Сброс ошибок\",\"Диагностика\"]},"
            + "{\"key\":\"comment\",\"label\":\"Комментарий\",\"type\":\"multiline\",\"required\":false,\"hint\":\"Коротко о задаче\"}"
            + "]";

    private final SharedPreferences sp;
    private final Context ctx;

    public Settings(Context context) {
        this.ctx = context.getApplicationContext();
        this.sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        initDefaultsIfNeeded();
    }

    private void initDefaultsIfNeeded() {
        int storedVersion = sp.getInt(KEY_CFG_VERSION, 1);
        if (storedVersion < ASSETS_CONFIG_VERSION) {
            // Обновление приложения: принудительно применяем новую встроенную
            // конфигурацию полей/шаблона, чтобы новые поля появились на форме.
            sp.edit()
                    .putString(K_FIELDS, readAsset("fields.json", FALLBACK_FIELDS))
                    .putString(K_TEMPLATE, readAsset("message_template.txt", ""))
                    .putInt(KEY_CFG_VERSION, ASSETS_CONFIG_VERSION)
                    .putBoolean(KEY_INIT, true)
                    .apply();
            return;
        }
        if (sp.getBoolean(KEY_INIT, false)) return;
        SharedPreferences.Editor e = sp.edit();
        e.putString(K_MODE, MODE_AUTO);
        e.putString(K_API_URL, DEFAULT_API_URL);
        e.putString(K_COMPANY, "КБ");
        e.putString(K_FIELDS, readAsset("fields.json", FALLBACK_FIELDS));
        e.putString(K_TEMPLATE, readAsset("message_template.txt", ""));
        e.putBoolean(KEY_INIT, true);
        e.apply();
    }

    private String readAsset(String name, String fallback) {
        try {
            InputStream is = ctx.getAssets().open(name);
            byte[] buf = new byte[Math.max(is.available(), 1024)];
            StringBuilder sb = new StringBuilder();
            int read;
            while ((read = is.read(buf)) > 0) sb.append(new String(buf, 0, read, "UTF-8"));
            is.close();
            String s = sb.toString().trim();
            return s.isEmpty() ? fallback : s;
        } catch (Exception ex) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- режим

    public String getMode() {
        if (BuildConfig.LITE) return MODE_DEEPLINK;
        return sp.getString(K_MODE, MODE_AUTO);
    }

    public void setMode(String v) {
        sp.edit().putString(K_MODE, v).apply();
    }

    public String getModeTitle() {
        switch (getMode()) {
            case MODE_API:
                return "Только Viber API";
            case MODE_DEEPLINK:
                return "Открытие Viber";
            case MODE_WEBHOOK:
                return "Webhook";
            default:
                return "Авто: API → Viber";
        }
    }

    /** Короткая подпись на шапке: что произойдёт при нажатии «Отправить». */
    public String getModeBadge() {
        switch (getMode()) {
            case MODE_API:
                return hasToken() ? "⚡ Viber API" : "⚡ Viber API (токен не задан)";
            case MODE_DEEPLINK:
                return "📲 Открытие Viber";
            case MODE_WEBHOOK:
                return hasWebhookUrl() ? "🔗 Webhook" : "🔗 Webhook (URL не задан)";
            default:
                return hasToken() ? "⚡ Авто: API, резерв — Viber" : "📲 Авто: токен не задан → откроем Viber";
        }
    }

    // ------------------------------------------------------------- viber api

    public String getToken() {
        return trim(sp.getString(K_TOKEN, ""));
    }

    public void setToken(String v) {
        sp.edit().putString(K_TOKEN, trim(v)).apply();
    }

    public boolean hasToken() {
        return !getToken().isEmpty();
    }

    public String getApiUrl() {
        String u = trim(sp.getString(K_API_URL, DEFAULT_API_URL));
        if (u.isEmpty()) u = DEFAULT_API_URL;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public void setApiUrl(String v) {
        sp.edit().putString(K_API_URL, trim(v)).apply();
    }

    public String getReceiver() {
        return trim(sp.getString(K_RECEIVER, ""));
    }

    public void setReceiver(String v) {
        sp.edit().putString(K_RECEIVER, trim(v)).apply();
    }

    public List<String> getBroadcastList() {
        List<String> out = new ArrayList<>();
        String raw = trim(sp.getString(K_BROADCAST_LIST, ""));
        if (raw.isEmpty()) return out;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public void setBroadcastList(String v) {
        sp.edit().putString(K_BROADCAST_LIST, trim(v)).apply();
    }

    // ------------------------------------------------------------- deep link

    public String getChatUri() {
        return trim(sp.getString(K_CHAT_URI, ""));
    }

    public void setChatUri(String v) {
        sp.edit().putString(K_CHAT_URI, trim(v)).apply();
    }

    public String getPhone() {
        return trim(sp.getString(K_PHONE, ""));
    }

    /** Телефон получателя для deep link хранится уже в международном формате. */
    public void setPhone(String v) {
        String n = PhoneUtils.normalize(v, getPhoneCc());
        sp.edit().putString(K_PHONE, n).apply();
    }

    /** Код страны по умолчанию (без «+») для нормализации телефонов. */
    public String getPhoneCc() {
        String cc = trim(sp.getString(K_PHONE_CC, "375")).replaceAll("\\D", "");
        return cc.isEmpty() ? "375" : cc;
    }

    public void setPhoneCc(String v) {
        String cc = trim(v).replaceAll("\\D", "");
        sp.edit().putString(K_PHONE_CC, cc.isEmpty() ? "375" : cc).apply();
    }

    // --------------------------------------------------------------- webhook

    public String getWebhookUrl() {
        return trim(sp.getString(K_WEBHOOK_URL, ""));
    }

    public void setWebhookUrl(String v) {
        sp.edit().putString(K_WEBHOOK_URL, trim(v)).apply();
    }

    public boolean hasWebhookUrl() {
        return getWebhookUrl().startsWith("http");
    }

    public String getWebhookToken() {
        return trim(sp.getString(K_WEBHOOK_TOKEN, ""));
    }

    public void setWebhookToken(String v) {
        sp.edit().putString(K_WEBHOOK_TOKEN, trim(v)).apply();
    }

    // -------------------------------------------------------------- сообщение

    public String getCompany() {
        String c = trim(sp.getString(K_COMPANY, ""));
        return c.isEmpty() ? "Новая заявка" : c;
    }

    public void setCompany(String v) {
        sp.edit().putString(K_COMPANY, trim(v)).apply();
    }

    public String getTemplate() {
        return sp.getString(K_TEMPLATE, "");
    }

    public void setTemplate(String v) {
        sp.edit().putString(K_TEMPLATE, v == null ? "" : v).apply();
    }

    // ------------------------------------------------------------ поля формы

    /** v2.27: черновик формы: живёт до отправки, переживает сворачивание и смерть процесса. */
    public String getDraftJson() {
        return sp.getString(K_DRAFT, "");
    }

    public void saveDraftJson(String json) {
        sp.edit().putString(K_DRAFT, json == null ? "" : json).apply();
    }

    public void clearDraft() {
        sp.edit().remove(K_DRAFT).apply();
    }

    public String getFieldsRaw() {
        String s = sp.getString(K_FIELDS, FALLBACK_FIELDS);
        return (s == null || s.trim().isEmpty()) ? FALLBACK_FIELDS : s;
    }

    public void setFieldsRaw(String json) {
        sp.edit().putString(K_FIELDS, json).apply();
    }

    public List<FormField> getFields() {
        try {
            List<FormField> f = FormField.parseList(getFieldsRaw());
            if (!f.isEmpty()) return f;
        } catch (Exception ignored) {
        }
        try {
            return FormField.parseList(FALLBACK_FIELDS);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void resetFields() {
        setFieldsRaw(readAsset("fields.json", FALLBACK_FIELDS));
    }

    public String getDefaultFieldsAsset() {
        return readAsset("fields.json", FALLBACK_FIELDS);
    }

    /**
     * Шаблон сообщения: из настроек, иначе из assets, иначе генерируется
     * автоматически по списку полей («🔹 Метка: {ключ}»).
     */
    /**
     * Самолечение сохранённого шаблона: если в нём нет ни телефонного
     * плейсхолдера, ни статической строки телефона — такой шаблон не может
     * передать телефон, сохраняем его в резерв и возвращаем стандартный.
     */
    public boolean healTemplateIfNeeded(int currentVersionCode) {
        int done = sp.getInt(K_HEAL_CODE, 0);
        if (done >= currentVersionCode) return false;
        sp.edit().putInt(K_HEAL_CODE, currentVersionCode).apply();
        String t = sp.getString(K_TEMPLATE, "");
        if (t == null || t.trim().isEmpty()) return false;
        String low = t.toLowerCase(Locale.US);
        boolean hasPhonePh = java.util.regex.Pattern
                .compile("\\{[^}]*(phone|tel|телеф|моб)[^}]*\\}").matcher(low).find();
        boolean hasPhoneStatic = low.contains("\uD83D\uDCDE") || low.contains("телефон");
        if (hasPhonePh || hasPhoneStatic) return false;
        sp.edit().putString(K_TEMPLATE_BACKUP, t).putString(K_TEMPLATE, "").apply();
        return true;
    }

    /**
     * Миграция v2.0: из сохранённого шаблона убирается строка устройства
     * ({device}), заголовок «НОВАЯ ЗАЯВКА — …» заменяется на «Заявка {company}»,
     * компания по умолчанию «Новая заявка» → «КБ».
     */
    public boolean migrateTemplateV2(int currentVersionCode) {
        int done = sp.getInt(K_MIGRATE_V2, 0);
        if (done >= currentVersionCode) return false;
        sp.edit().putInt(K_MIGRATE_V2, currentVersionCode).apply();
        boolean changed = false;
        String t = sp.getString(K_TEMPLATE, "");
        if (t != null && !t.trim().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String line : t.split("\n", -1)) {
                String tr = line.trim();
                if (tr.contains("{device}")) {
                    String rest = tr.replace("{device}", "")
                            .replaceAll("[\\p{So}\\p{Cs}\\s:.\\-]", "");
                    if (rest.isEmpty()) { changed = true; continue; } // строка устройства не нужна
                }
                if (tr.toUpperCase(Locale.US).contains("НОВАЯ ЗАЯВКА")) {
                    sb.append("🔔 Заявка {company}").append('\n');
                    changed = true;
                    continue;
                }
                sb.append(line).append('\n');
            }
            if (changed) {
                String nt = sb.toString();
                while (nt.endsWith("\n\n")) nt = nt.substring(0, nt.length() - 1);
                sp.edit().putString(K_TEMPLATE, nt.trim()).apply();
            }
        }
        if ("Новая заявка".equals(sp.getString(K_COMPANY, ""))) {
            sp.edit().putString(K_COMPANY, "КБ").apply();
            changed = true;
        }
        return changed;
    }

    /**
     * v2.25: строка «Точка» в заявке.
     * 0 = координаты текстом (компактно, без карты в Viber, не кликабельно);
     * 1 = ссылка на Яндекс.Карты (кликабельно, Viber рисует карту-превью);
     * 2 = ссылка-маршрут в Яндекс.Навигатор (кликабельно, координаты сразу
     *     подставляются в маршрут; по умолчанию);
     * 3 = не добавлять строку «Точка».
     */
    public int getPointMode() {
        return sp.getInt(K_POINT_MODE, 2);
    }

    public void setPointMode(int v) {
        sp.edit().putInt(K_POINT_MODE, v).apply();
    }

    /** Авто-нажатие кнопки отправки в Viber после авто-вставки (по умолчанию вкл). */
    public boolean getAutoSend() {
        return sp.getBoolean(K_AUTO_SEND, true);
    }

    public void setAutoSend(boolean v) {
        sp.edit().putBoolean(K_AUTO_SEND, v).apply();
    }

    /** Входит ли служба авто-вставки в эту сборку (в обычной — нет из-за Play Защиты). */
    public static boolean isAutoPasteAvailable(android.content.Context ctx) {
        try {
            ctx.getPackageManager().getServiceInfo(
                    new android.content.ComponentName(ctx, AutoPasteService.class), 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Включена ли служба авто-вставки в настройках Android. */
    public static boolean isAutoPasteOn(android.content.Context ctx) {
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            return enabled.contains(ctx.getPackageName() + "/" + AutoPasteService.class.getName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Миграция v2.10: у кого способ «1» сохранился как дефолт v2.9, но при этом
     * есть сохранённая ссылка-приглашение — переключаем на «2» (открывать группу),
     * чтобы не открывалось окно контактов Viber.
     */
    public void migrateGroupMethodV210(int currentVersionCode) {
        int done = sp.getInt(K_MIGRATE_210, 0);
        if (done >= currentVersionCode) return;
        sp.edit().putInt(K_MIGRATE_210, currentVersionCode).apply();
        String r = trim(sp.getString(K_RECEIVER, ""));
        boolean httpReceiver = r.startsWith("http://") || r.startsWith("https://");
        if (sp.getInt(K_GROUP_METHOD, 0) == 1 && httpReceiver) {
            sp.edit().putInt(K_GROUP_METHOD, 2).apply();
        }
    }

    /**
     * Миграция v2.11: способ «1» (окно Viber с выбором контактов), сохранённый
     * ещё старыми версиями, переключается на «2» (открыть сохранённую группу),
     * если ссылка-приглашение сохранена в ЛЮБОМ из полей: «Получатель» (receiver)
     * или «Ссылка на чат» (chat_uri). Окно контактов больше не открывается.
     */
    public void migrateGroupMethodV211(int currentVersionCode) {
        int done = sp.getInt(K_MIGRATE_211, 0);
        if (done >= currentVersionCode) return;
        sp.edit().putInt(K_MIGRATE_211, currentVersionCode).apply();
        String r = trim(sp.getString(K_RECEIVER, ""));
        String c = trim(sp.getString(K_CHAT_URI, ""));
        boolean http = r.startsWith("http://") || r.startsWith("https://")
                || c.startsWith("http://") || c.startsWith("https://");
        if (sp.getInt(K_GROUP_METHOD, 0) == 1 && http) {
            sp.edit().putInt(K_GROUP_METHOD, 2).apply();
        }
    }

    /**
     * Способ отправки в группу: 0 — авто (сохранённая группа открывается сразу,
     * иначе окно Viber с текстом), 1 — окно Viber с готовым текстом,
     * 2 — открыть группу + буфер обмена.
     */
    public int getGroupMethod() {
        return sp.getInt(K_GROUP_METHOD, 0);
    }

    public void setGroupMethod(int v) {
        sp.edit().putInt(K_GROUP_METHOD, v).apply();
    }

    /**
     * «Быстрая отправка»: заявка уходит в запомненный чат получателя без
     * экрана выбора чата (ссылка на чат / токен API). По умолчанию включено.
     */
    public boolean getFastSend() {
        return sp.getBoolean(K_FAST_SEND, true);
    }

    public void setFastSend(boolean v) {
        sp.edit().putBoolean(K_FAST_SEND, v).apply();
    }

    /** Контакт по умолчанию: подставляется в форму следующей заявки. */
    public String getLastContact() {
        return trim(sp.getString(K_LAST_CONTACT, ""));
    }

    public void setLastContact(String v) {
        sp.edit().putString(K_LAST_CONTACT, trim(v)).apply();
    }

    public String getTemplateBackup() {
        return sp.getString(K_TEMPLATE_BACKUP, "");
    }

    public String getEffectiveTemplate() {
        String t = getTemplate();
        if (t != null && t.trim().length() > 0) return t;
        t = readAsset("message_template.txt", "");
        if (t != null && t.trim().length() > 0) return t;
        return buildAutoTemplate(getFields());
    }

    public static String buildAutoTemplate(List<FormField> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔔 Заявка {company}\n");
        sb.append("🕒 {datetime}\n\n");
        String[] icons = {"👤", "📞", "📃", "📍", "📅", "💬", "🏢", "✉️", "🔹", "🔸"};
        int i = 0;
        for (FormField f : fields) {
            sb.append(icons[i % icons.length]).append(' ').append(f.label)
                    .append(": {").append(f.key).append("}\n");
            i++;
        }
        boolean hasAddress = false;
        for (FormField f : fields) {
            if (f.isAddress() || "address".equals(f.orgMapTarget())) hasAddress = true;
        }
        if (hasAddress) sb.append("\n🗺 Точка: {address_link}\n");
        return sb.toString();
    }

    // --------------------------------------------------------------- история

    /**
     * v2.28: история живёт в отдельной SQLite-базе (HistoryDb) — записи
     * фиксируются синхронно и не теряются при убийстве процесса, лимитов
     * нет. Здесь остался только одноразовый вывод СТАРОЙ истории из
     * SharedPreferences для миграции в базу (возвращает список новых-сверху
     * и стирает ключ, если что-то было).
     */
    public List<JSONObject> takeLegacyHistory() {
        try {
            JSONArray arr = new JSONArray(sp.getString(K_HISTORY, "[]"));
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
            if (!out.isEmpty()) sp.edit().remove(K_HISTORY).apply();
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ------------------------------------------- запомненные значения полей

    public void saveLastValues(JSONObject values) {
        sp.edit().putString(K_LAST_VALUES, values.toString()).apply();
    }

    public JSONObject getLastValues() {
        try {
            return new JSONObject(sp.getString(K_LAST_VALUES, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------- служебное

    public String deviceInfo() {
        return Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
    }

    public static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
