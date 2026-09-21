package by.viberlead.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Собирает текст заявки из значений полей и шаблона.
 *
 * Плейсхолдеры: {key_поля}, {label:key}, {company}, {datetime}, {date_now},
 * {time}, {device}, {address_link}.
 *
 * Гарантии полноты (заявка не теряет данные):
 *  1) строка шаблона, ВСЕ плейсхолдеры которой пусты, удаляется целиком
 *     (в Viber не улетают пустые «Контакт:»);
 *  2) статические строки-ярлыки без плейсхолдеров («👤 Контакт:») либо
 *     заполняются значением своего поля, либо удаляются, если значение пусто;
 *  3) во время подстановки запоминается, какие ключи РЕАЛЬНО попали в текст
 *     (в т.ч. через нечёткий поиск телефонных ключей {org_phone} ↔ {phone});
 *  4) после сборки КАЖДОЕ непустое значение поля, которого нет в тексте
 *     (ни через свой плейсхолдер, ни парой «Метка: значение»), дописывается
 *     отдельной строкой «📞/🔹 Метка: значение» — в сообщение попадают все
 *     заполненные поля формы, каким бы ни был шаблон на устройстве;
 *  5) финальная страховка: нормализованный телефон (+375…) обязан быть
 *     в тексте, иначе дописывается строкой «📞 Метка: номер».
 */
public class MessageBuilder {

    private static final Pattern PH = Pattern.compile("\\{\\s*([a-zA-Z0-9_.:\\-]+)\\s*\\}");
    /** Метка «телефонный плейсхолдер шаблона отрисован непустым значением». */
    private static final String PHONE_RENDERED = "__phone_rendered__";

    public static String build(Settings s, Map<String, String> values) {
        return build(s.getEffectiveTemplate(), s.getFields(), values,
                s.getCompany(), s.getPhoneCc(), s.deviceInfo());
    }

    /**
     * Чистая JVM-логика сборки сообщения (без Android/Settings) — позволяет
     * проверять гарантии полноты юнит-тестами: все непустые поля попадают в
     * текст ровно по одному разу, телефон всегда нормализован.
     */
    public static String build(String template, List<FormField> fields,
                               Map<String, String> values, String company,
                               String phoneCc, String device) {
        Set<String> rendered = new HashSet<>();
        String text = render(template, fields, values, company, device, rendered);
        text = appendMissing(fields, values, text, rendered);
        return ensurePhone(fields, phoneCc, values, text, rendered);
    }

    public static String buildPreview(Settings s) {
        List<FormField> fields = s.getFields();
        Map<String, String> demo = new LinkedHashMap<>();
        for (FormField f : fields) {
            String v;
            if (f.isCheckbox()) {
                v = "да";
            } else if (!f.options.isEmpty()) {
                v = f.options.get(0);
            } else if (!f.defaultValue.isEmpty()) {
                v = f.defaultValue;
            } else {
                v = exampleFor(f);
            }
            demo.put(f.key, v);
        }
        demo.put("address_link", "https://yandex.ru/maps/?ll=27.559000,53.900600&pt=27.559000,53.900600&z=17");
        return build(s.getEffectiveTemplate(), fields, demo,
                s.getCompany(), s.getPhoneCc(), s.deviceInfo());
    }

    // --------------------------------------------------------------- сборка

    private static String render(String template, List<FormField> fields,
                                 Map<String, String> values, String company,
                                 String device, Set<String> rendered) {
        Map<String, String> ctx = new LinkedHashMap<>();
        if (values != null) ctx.putAll(values);
        ctx.put("company", company);
        ctx.put("device", ctx.get("device") == null || ctx.get("device").isEmpty()
                ? device : ctx.get("device"));
        for (FormField f : fields) ctx.put("__label__" + f.key, f.label);
        Date now = new Date();
        ctx.put("datetime", new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(now));
        ctx.put("date_now", new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(now));
        ctx.put("time", new SimpleDateFormat("HH:mm", Locale.US).format(now));

        StringBuilder out = new StringBuilder();
        for (String line : template.split("\n", -1)) {
            int[] nonEmpty = {0};
            String renderedLine = replacePlaceholders(line, ctx, nonEmpty, rendered);
            boolean hadPlaceholders = PH.matcher(line).find();
            // строка состояла только из пустых плейсхолдеров — убираем целиком
            if (hadPlaceholders && nonEmpty[0] == 0) continue;
            // статическая строка-ярлык без плейсхолдеров: заполнить или убрать
            if (!hadPlaceholders) {
                String fixed = fixStaticLabelLine(line, fields, values, rendered);
                if (fixed == null) continue;
                renderedLine = fixed;
            }
            out.append(renderedLine).append('\n');
        }
        String text = out.toString();
        while (text.endsWith("\n\n")) text = text.substring(0, text.length() - 1);
        return text.trim();
    }

    private static String replacePlaceholders(String line, Map<String, String> ctx,
                                              int[] nonEmptyOut, Set<String> rendered) {
        Matcher m = PH.matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = m.group(1);
            String key = token.startsWith("label:") ? token.substring("label:".length()) : token;
            String replacement;
            if (token.startsWith("label:")) {
                String v = value(ctx, key, rendered);
                replacement = v.isEmpty() ? "" : (labelFor(ctx, key) + ": " + v);
            } else {
                replacement = value(ctx, token, rendered);
            }
            if (!replacement.isEmpty()) nonEmptyOut[0]++;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Строка вида «👤 Контакт:» БЕЗ плейсхолдера (осталась в сохранённом на
     * устройстве шабоне): если у сопоставленного поля есть значение —
     * дописываем его в эту строку; если значения нет — строка удаляется.
     * Возвращает null, если строку нужно удалить.
     */
    private static String fixStaticLabelLine(String line, List<FormField> fields,
                                             Map<String, String> values, Set<String> rendered) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.endsWith(":") || trimmed.contains("http")) return line;
        String labelPart = trimmed.substring(0, trimmed.length() - 1).trim();
        if (labelPart.length() < 2 || labelPart.length() > 40) return line;
        String nl = normKey(labelPart);
        if (nl.isEmpty()) return line;
        // «Точка:» / «Карта:» — ссылка на точку Яндекса
        if (nl.contains("точк") || nl.contains("карт") || nl.contains("маршр") || nl.contains("ссылк")) {
            String link = values == null ? null : values.get("address_link");
            if (link != null && !link.trim().isEmpty()) return trimmed + " " + link.trim();
            return null;
        }
        for (FormField f : fields) {
            String fl = normKey(f.label);
            String fk = normKey(f.key);
            boolean match = fl.equals(nl) || fk.equals(nl)
                    || (nl.length() >= 4 && fl.startsWith(nl))
                    || (fl.length() >= 4 && nl.startsWith(fl));
            if (!match) continue;
            String v = values == null ? null : values.get(f.key);
            if (v == null || v.trim().isEmpty()) return null;
            v = v.trim();
            rendered.add(normKey(f.key));
            if (isPhoneKey(normKey(f.key))) rendered.add(PHONE_RENDERED);
            return trimmed + " " + v;
        }
        // пустой ярлык, которому нечего показать, — не отправляем
        return null;
    }

    // ------------------------------------------------------- страховки полноты

    /**
     * КАЖДОЕ непустое значение поля обязано попасть в текст: либо оно уже
     * отрисовано своим плейсхолдером (rendered), либо стоит парой
     * «Метка: значение», иначе дописываем отдельной строкой.
     */
    private static String appendMissing(List<FormField> fields, Map<String, String> values,
                                        String text, Set<String> rendered) {
        if (values == null) return text;
        StringBuilder sb = new StringBuilder(text);
        for (FormField f : fields) {
            String v = values.get(f.key);
            if (v == null || v.trim().isEmpty()) continue;
            v = v.trim();
            if (renderedHas(rendered, f.key)) continue;                 // отрисовано шаблоном
            if (sb.indexOf(f.label + ": " + v) >= 0) continue;          // уже есть парой
            sb.append('\n')
                    .append(f.isPhone() ? "📞 " : "🔹 ")
                    .append(f.label).append(": ").append(v);
        }
        // ссылка на точку Яндекса — тоже часть заявки
        String link = values.get("address_link");
        if (link != null && !link.trim().isEmpty() && sb.indexOf(link.trim()) < 0) {
            sb.append("\n🗺 Точка: ").append(link.trim());
        }
        return sb.toString();
    }

    /** Ключ отрисован шаблоном (точно или, для телефонов, нечётким сопоставлением). */
    private static boolean renderedHas(Set<String> rendered, String key) {
        String nk = normKey(key);
        if (rendered.contains(nk)) return true;
        return isPhoneKey(nk) && rendered.contains(PHONE_RENDERED);
    }

    /** Финальная страховка: нормализованный телефон обязан быть в тексте. */
    private static String ensurePhone(List<FormField> fields, String phoneCc,
                                      Map<String, String> values, String text,
                                      Set<String> rendered) {
        if (values == null) return text;
        String phoneValue = null;
        String phoneLabel = "Телефон";
        String phoneKey = null;
        for (FormField f : fields) {
            String v = values.get(f.key);
            if (v == null || v.trim().isEmpty()) continue;
            if (f.isPhone()) {
                phoneValue = v.trim();
                phoneLabel = f.label;
                phoneKey = f.key;
                break;
            }
            if (phoneValue == null && PhoneUtils.looksLikePhone(v)
                    && !FormField.TYPE_NUMBER.equals(f.type)) {
                phoneValue = v.trim();
                phoneLabel = f.label;
                phoneKey = f.key;
            }
        }
        if (phoneValue == null) return text;
        String normalized = PhoneUtils.normalize(phoneValue, phoneCc);
        if (text.contains(normalized)) return text;
        if (renderedHas(rendered, phoneKey) && normalized.equals(phoneValue)) return text;
        return text + "\n📞 " + phoneLabel + ": " + normalized;
    }

    // --------------------------------------------------------------- значение

    private static String exampleFor(FormField f) {
        if (f.isPhone()) return "+375291234567";
        if ("email".equals(f.type)) return "client@mail.by";
        if ("number".equals(f.type)) return "1";
        if (f.isDate()) return new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
        if ("city".equals(f.prefill)) return "Минск";
        if (f.key.toLowerCase().contains("name") || f.key.toLowerCase().contains("fio")) return "Иван Иванов";
        return f.hint != null && !f.hint.isEmpty() ? f.hint : "…";
    }

    /**
     * Значение по ключу; если найдено непустое — ключ помечается в rendered
     * (для телефонных ключей дополнительно ставится общая метка PHONE_RENDERED).
     */
    private static String value(Map<String, String> ctx, String key, Set<String> rendered) {
        String v = ctx.get(key);
        String usedKey = v == null ? null : key;
        if (v == null) {
            for (Map.Entry<String, String> e : ctx.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key)) {
                    v = e.getValue();
                    usedKey = e.getKey();
                    break;
                }
            }
        }
        // нечёткий поиск для телефонных ключей: org_phone ↔ phone ↔ telefon ↔ тел
        if (v == null || v.isEmpty()) {
            String nk = normKey(key);
            if (isPhoneKey(nk)) {
                for (Map.Entry<String, String> e : ctx.entrySet()) {
                    if (e.getKey().startsWith("__label__")) continue;
                    String nk2 = normKey(e.getKey());
                    if (!isPhoneKey(nk2)) continue;
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        v = e.getValue();
                        usedKey = e.getKey();
                        break;
                    }
                }
            }
        }
        if (v == null) return "";
        v = v.replaceAll("[\u200B\u200C\u200D\uFEFF\u00A0]", " ").trim();
        if (!v.isEmpty() && usedKey != null) {
            rendered.add(normKey(usedKey));
            if (isPhoneKey(normKey(usedKey))) rendered.add(PHONE_RENDERED);
        }
        return v;
    }

    private static String normKey(String k) {
        return k == null ? "" : k.toLowerCase(Locale.US).replaceAll("[^a-z0-9а-яё]", "");
    }

    private static boolean isPhoneKey(String normalized) {
        return normalized.contains("phone") || normalized.contains("tel")
                || normalized.contains("телеф") || normalized.contains("моб");
    }

    private static String labelFor(Map<String, String> ctx, String key) {
        String l = ctx.get("__label__" + key);
        return l == null ? key : l;
    }
}
