package by.viberlead.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Описание одного поля формы заявки.
 * Поля задаются JSON-конфигом (assets/fields.json или экран «Настройки»),
 * поэтому список полей можно менять без пересборки APK.
 */
public class FormField {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_PHONE = "phone";
    public static final String TYPE_EMAIL = "email";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_MULTILINE = "multiline";
    public static final String TYPE_SELECT = "select";
    public static final String TYPE_DATE = "date";
    public static final String TYPE_CHECKBOX = "checkbox";
    /** Организация: живой поиск по локальной базе + автоподстановка контактов. */
    public static final String TYPE_ORG = "org";
    /** Выпадающий список с чекбоксами (мультивыбор). */
    public static final String TYPE_MULTISELECT = "multiselect";
    /** Адрес: текстовое поле + кнопка маршрута в Яндекс Навигаторе. */
    public static final String TYPE_ADDRESS = "address";

    public String key;
    public String label;
    public String type = TYPE_TEXT;
    public boolean required;
    public String hint = "";
    public List<String> options = new ArrayList<>();
    public String defaultValue = "";
    /** "device" — подставить модель устройства, "city" — город из настроек, "none" */
    public String prefill = "none";
    /** Куда автоподставлять из карточки организации: address | contact | phone */
    public String orgMap = "";

    public boolean isMultiline() {
        return TYPE_MULTILINE.equals(type);
    }

    public boolean isSelect() {
        return TYPE_SELECT.equals(type);
    }

    public boolean isCheckbox() {
        return TYPE_CHECKBOX.equals(type);
    }

    public boolean isDate() {
        return TYPE_DATE.equals(type);
    }

    public boolean isOrg() {
        return TYPE_ORG.equals(type);
    }

    public boolean isMultiselect() {
        return TYPE_MULTISELECT.equals(type);
    }

    public boolean isAddress() {
        return TYPE_ADDRESS.equals(type);
    }

    /**
     * Телефонное поле: type=phone ИЛИ ключ/маппинг похож на телефон
     * (phone, tel, org_phone…) — нормализация применяется в любом случае.
     */
    public boolean isPhone() {
        if (TYPE_PHONE.equals(type)) return true;
        if ("phone".equals(orgMapTarget())) return true;
        if (key == null) return false;
        String k = key.toLowerCase();
        return k.contains("phone") || k.contains("tel") || k.contains("телеф");
    }

    /** Поле участвует в автоподстановке из базы организаций. */
    public String orgMapTarget() {
        if (orgMap != null && !orgMap.isEmpty()) return orgMap;
        if (key == null) return "";
        String k = key.toLowerCase();
        if (k.contains("address") || k.contains("adres")) return "address";
        if (k.contains("contact") || k.contains("kontak")) return "contact";
        if (k.contains("org_phone") || k.contains("phone_org") || k.contains("orgphone")) return "phone";
        return "";
    }

    public static FormField fromJson(JSONObject o) {
        FormField f = new FormField();
        f.key = o.optString("key", "").trim();
        f.label = o.optString("label", f.key);
        f.type = o.optString("type", TYPE_TEXT).trim().toLowerCase();
        f.required = o.optBoolean("required", false);
        f.hint = o.optString("hint", "");
        f.defaultValue = o.optString("defaultValue", "");
        f.prefill = o.optString("prefill", "none").trim().toLowerCase();
        f.orgMap = o.optString("orgMap", "").trim().toLowerCase();
        JSONArray opts = o.optJSONArray("options");
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                String s = opts.optString(i, "").trim();
                if (!s.isEmpty()) f.options.add(s);
            }
        }
        if (f.key.isEmpty()) {
            // ключ обязателен: без него поле нельзя подставить в шаблон
            f.key = "field_" + Math.abs(f.label.hashCode());
        }
        if (f.label.isEmpty()) f.label = f.key;
        return f;
    }

    public static List<FormField> parseList(String json) throws Exception {
        List<FormField> out = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(fromJson(o));
        }
        return out;
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("label", label);
        o.put("type", type);
        o.put("required", required);
        if (hint != null && !hint.isEmpty()) o.put("hint", hint);
        if (!options.isEmpty()) {
            JSONArray a = new JSONArray();
            for (String s : options) a.put(s);
            o.put("options", a);
        }
        if (defaultValue != null && !defaultValue.isEmpty()) o.put("defaultValue", defaultValue);
        if (prefill != null && !"none".equals(prefill)) o.put("prefill", prefill);
        if (orgMap != null && !orgMap.isEmpty()) o.put("orgMap", orgMap);
        return o;
    }
}
