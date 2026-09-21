package by.viberlead.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM-тесты сборки текста заявки (MessageBuilder) — без Android.
 * Гарантируют главное: каждое непустое поле формы попадает в текст ровно
 * один раз, пустые строки не улетают, телефон всегда в тексте нормализованным.
 */
public class MessageBuilderTest {

    private static FormField f(String key, String label, String type) {
        FormField field = new FormField();
        field.key = key;
        field.label = label;
        field.type = type;
        return field;
    }

    private static List<FormField> standardFields() {
        List<FormField> fields = new ArrayList<>();
        fields.add(f("org", "Организация", "org"));
        fields.add(f("address", "Адрес", "address"));
        fields.add(f("org_phone", "Телефон организации", "phone"));
        fields.add(f("request", "Заявка", "multiselect"));
        fields.add(f("comment", "Комментарий", "multiline"));
        return fields;
    }

    private static Map<String, String> standardValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("org", "БАЗА Дзержинского");
        values.put("address", "пр. Дзержинского, 104");
        values.put("org_phone", "+375291234567");
        values.put("request", "Заправка, Ремонт");
        values.put("comment", "Срочно до обеда");
        return values;
    }

    private static int countOccurrences(String text, String sub) {
        int n = 0, i = 0;
        while ((i = text.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    @Test
    public void allFilledFieldsPresentExactlyOnce() {
        String template = "🔔 Заявка {company}\n🕒 {datetime}\n\n"
                + "🏢 Организация: {org}\n"
                + "📍 Адрес: {address}\n"
                + "📞 Телефон: {org_phone}\n"
                + "🧾 Заявка: {request}\n"
                + "💬 Комментарий: {comment}\n";
        String text = MessageBuilder.build(template, standardFields(), standardValues(),
                "КБ", "375", "Test Device (Android 14)");
        for (Map.Entry<String, String> e : standardValues().entrySet()) {
            assertEquals("Значение «" + e.getValue() + "» должно встретиться ровно 1 раз",
                    1, countOccurrences(text, e.getValue()));
        }
        assertTrue(text.contains("КБ"));
        assertFalse(text.contains("{company}"));
        assertFalse(text.contains("{org}"));
    }

    /** Шаблон без телефона (старый шаблон на устройстве): ensurePhone дописывает номер. */
    @Test
    public void phoneAlwaysPresent() {
        String template = "Заявка {company}\nОрг: {org}";
        String text = MessageBuilder.build(template, standardFields(), standardValues(),
                "КБ", "375", "Test Device");
        assertTrue("нормализованный телефон обязан быть в тексте",
                text.contains("+375291234567"));
        assertEquals(1, countOccurrences(text, "+375291234567"));
    }

    /** Телефон в национальном формате в значении — в текст уходит +375… */
    @Test
    public void phoneNormalizedByEnsure() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("org", "БАЗА");
        values.put("org_phone", "029 123-45-67");
        String text = MessageBuilder.build("Орг: {org}\nТел: {org_phone}",
                standardFields(), values, "КБ", "375", "Test Device");
        assertTrue(text.contains("+375291234567"));
    }

    /** Пустые плейсхолдеры: строка убирается целиком, не улетает «Контакт:» без значения. */
    @Test
    public void emptyPlaceholdersDropped() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("org", "БАЗА");
        values.put("org_phone", "");
        values.put("comment", "");
        String text = MessageBuilder.build("Орг: {org}\nТел: {org_phone}\nКоммент: {comment}",
                standardFields(), values, "КБ", "375", "Test Device");
        assertEquals("Орг: БАЗА", text);
    }

    /**
     * Короткое значение «909» не считается «найдённым» внутри адреса
     * «Дзержинского 104 909» — телефон всё равно дописывается отдельной строкой.
     */
    @Test
    public void shortPhoneNotSwallowedByAddress() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("org", "БАЗА");
        values.put("address", "Дзержинского, 104 909");
        values.put("org_phone", "+37529909");
        String text = MessageBuilder.build("Орг: {org}\nАдрес: {address}",
                standardFields(), values, "КБ", "375", "Test Device");
        assertTrue(text.contains("+37529909"));
    }

    /** Статическая строка-ярлык без плейсхолдера: заполняется или удаляется. */
    @Test
    public void staticLabelLineFilledOrDropped() {
        List<FormField> fields = standardFields();
        fields.add(f("contact", "Контактное лицо", "text"));

        // без значения — строка не отправляется
        Map<String, String> empty = new LinkedHashMap<>();
        empty.put("org", "БАЗА");
        empty.put("contact", "");
        String t1 = MessageBuilder.build("Орг: {org}\n👤 Контакт:",
                fields, empty, "КБ", "375", "Test Device");
        assertFalse(t1.contains("Контакт"));

        // со значением — строка заполняется
        Map<String, String> with = new LinkedHashMap<>();
        with.put("org", "БАЗА");
        with.put("contact", "Иван");
        String t2 = MessageBuilder.build("Орг: {org}\n👤 Контакт:",
                fields, with, "КБ", "375", "Test Device");
        assertTrue(t2.contains("👤 Контакт: Иван"));
    }

    /** Ссылка на точку: если её нет в шаблоне и она есть в значении — дописывается. */
    @Test
    public void addressLinkAppended() {
        Map<String, String> values = standardValues();
        values.put("address_link",
                "https://yandex.ru/maps/?mode=routes&ll=27.55900,53.90060&pt=27.55900,53.90060&z=17");
        String text = MessageBuilder.build("Орг: {org}", standardFields(), values,
                "КБ", "375", "Test Device");
        assertTrue(text.contains("🗺 Точка: " + values.get("address_link")));
        assertEquals(1, countOccurrences(text, values.get("address_link")));
    }

    /** {label:key} рендерится парой «Метка: значение». */
    @Test
    public void labelPlaceholder() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("org", "БАЗА");
        String text = MessageBuilder.build("{label:org}", standardFields(), values,
                "КБ", "375", "Test Device");
        assertTrue(text.contains("Организация: БАЗА"));
    }
}
