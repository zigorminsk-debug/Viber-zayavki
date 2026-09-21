package by.viberlead.app;

/**
 * Приведение любого введённого телефона к международному формату: +375291234567.
 *
 * Поддерживаемые варианты ввода (пример для кода страны 375, он настраивается):
 *   +375 29 123-45-67   → +375291234567
 *   375291234567        → +375291234567
 *   8 029 123-45-67     → +375291234567  (союзный «8» + ведущий 0 отбрасываются)
 *   029 123 45 67       → +375291234567  (ведущий 0 отбрасывается)
 *   29 123 45 67        → +375291234567  (национальный номер без кода)
 *   00375291234567      → +375291234567  (международный префикс 00)
 *   8 916 123-45-67     → +79161234567   (10 цифр после «8» при коде 375 = номер РФ)
 *   7 916 123-45-67     → +79161234567
 *
 * Если номер не распознан (слишком короткий или необычный), возвращается
 * очищенный ввод без изменений — данные не теряются.
 */
public final class PhoneUtils {

    private PhoneUtils() {
    }

    /** Длина национальной части (без кода страны) для популярных кодов. */
    private static int nationalLength(String cc) {
        if (cc == null) return 9;
        switch (cc) {
            case "7":
                return 10;
            case "380":
            case "375":
                return 9;
            case "373":
            case "374":
            case "370":
            case "371":
                return 8;
            default:
                return 9;
        }
    }

    /** Оставляет цифры и ведущий «+». */
    public static String clean(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        boolean plus = t.startsWith("+");
        String digits = t.replaceAll("\\D", "");
        return (plus ? "+" : "") + digits;
    }

    public static String normalize(String raw, String defaultCountryCode) {
        if (raw == null) return "";
        String src = raw.trim();
        if (src.isEmpty()) return "";
        String cc = defaultCountryCode == null ? "375" : defaultCountryCode.replaceAll("\\D", "");
        if (cc.isEmpty()) cc = "375";
        int nat = nationalLength(cc);

        boolean plus = src.startsWith("+");
        String digits = src.replaceAll("\\D", "");
        if (digits.isEmpty()) return src;

        // 1) уже международный: +375…
        if (plus) {
            return ok(digits) ? "+" + digits : src;
        }
        // 2) международный префикс 00
        if (src.startsWith("00") && digits.length() >= 2) {
            String d = digits.substring(2);
            return ok(d) ? "+" + d : src;
        }
        // 3) код страны без плюса: 375291234567
        if (digits.startsWith(cc) && digits.length() == cc.length() + nat) {
            return "+" + digits;
        }
        // 4) номер РФ/КЗ без плюса: 79161234567
        if (digits.startsWith("7") && digits.length() == 11) {
            return "+" + digits;
        }
        // 5) союзный выход «8»: 80291234567 / 89161234567
        if (digits.startsWith("8") && digits.length() == 11) {
            String rest = digits.substring(1);
            if (rest.startsWith("0")) rest = rest.substring(1);
            if ("375".equals(cc) && rest.length() == 10) return "+7" + rest;
            return "+" + cc + rest;
        }
        // 6) национальный номер с ведущим 0: 0291234567
        if (digits.startsWith("0")) {
            String rest = digits.substring(1);
            if ("375".equals(cc) && rest.length() == 10) return "+7" + rest;
            if (rest.length() == nat) return "+" + cc + rest;
            if (rest.length() >= 7) return "+" + cc + rest;
            return src;
        }
        // 7) национальный номер без кода: 291234567
        if (digits.length() == nat) {
            return "+" + cc + digits;
        }
        // 8) похоже на полный номер с кодом страны без плюса
        if (digits.length() >= 10 && digits.length() <= 15) {
            return "+" + digits;
        }
        // не распознали — не теряем ввод
        return src;
    }

    /**
     * Похоже ли содержимое поля на телефон БЕЗ учёта конфига:
     * только цифры (допускаются +, пробелы, скобки, дефисы, точки), 9–15 цифр.
     * Используется, чтобы нормализация работала даже если в конфиге
     * у поля забыли поставить type=phone или «телефонный» ключ.
     */
    public static boolean looksLikePhone(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        if (t.isEmpty()) return false;
        if (t.startsWith("+")) t = t.substring(1);
        t = t.replaceAll("[\\s\\-().]", "");
        if (!t.matches("\\d{9,15}")) return false;
        return true;
    }

    private static boolean ok(String digits) {
        return digits.length() >= 7 && digits.length() <= 15;
    }

    /** Человекочитаемый вариант для подсказок: +375 29 123-45-67. */
    public static String pretty(String normalized) {
        String d = normalized == null ? "" : normalized.replaceAll("\\D", "");
        if (normalized != null && normalized.startsWith("+") && d.length() == 12 && d.startsWith("375")) {
            return "+375 " + d.substring(3, 5) + " " + d.substring(5, 8)
                    + "-" + d.substring(8, 10) + "-" + d.substring(10, 12);
        }
        return normalized == null ? "" : normalized;
    }
}
