package by.viberlead.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Нормализация телефонов в международный формат (код страны по умолчанию 375). */
public class PhoneUtilsTest {

    @Test
    public void normalization() {
        assertEquals("+375291234567", PhoneUtils.normalize("029 123-45-67", "375"));
        assertEquals("+375291234567", PhoneUtils.normalize("+375 29 123-45-67", "375"));
        assertEquals("+375291234567", PhoneUtils.normalize("8 029 123-45-67", "375"));
        assertEquals("+375291234567", PhoneUtils.normalize("375291234567", "375"));
        assertEquals("+375291234567", PhoneUtils.normalize("00375291234567", "375"));
        assertEquals("+375291234567", PhoneUtils.normalize("291234567", "375"));
        assertEquals("+79161234567", PhoneUtils.normalize("8 916 123-45-67", "375"));
        assertEquals("+79161234567", PhoneUtils.normalize("79161234567", "375"));
    }

    @Test
    public void unrecognizedInputNotLost() {
        // слишком короткий и нераспознанный ввод возвращается очищенным
        assertEquals("12345", PhoneUtils.normalize("12345", "375"));
        assertEquals("", PhoneUtils.normalize("", "375"));
        assertEquals("", PhoneUtils.normalize(null, "375"));
    }

    @Test
    public void looksLikePhone() {
        assertTrue(PhoneUtils.looksLikePhone("+375291234567"));
        assertTrue(PhoneUtils.looksLikePhone("029 123-45-67"));
        assertTrue(PhoneUtils.looksLikePhone("375291234567"));
        assertFalse(PhoneUtils.looksLikePhone("12345")); // слишком коротко
        assertFalse(PhoneUtils.looksLikePhone("Дзержинского 104 909")); // есть буквы
        assertFalse(PhoneUtils.looksLikePhone(""));
        assertFalse(PhoneUtils.looksLikePhone(null));
    }
}
