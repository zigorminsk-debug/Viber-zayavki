package by.viberlead.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Логика автообновления: разбор версий и подбор APK под пакет. */
public class UpdateUtilTest {

    @Test
    public void codeFromReleaseName() {
        assertEquals(40, UpdateUtil.codeFromReleaseName("ViberLead v2.28 (code 40)"));
        assertEquals(7, UpdateUtil.codeFromReleaseName("release ( CODE 7 )"));
        assertEquals(0, UpdateUtil.codeFromReleaseName("ViberLead v2.28"));
        assertEquals(0, UpdateUtil.codeFromReleaseName(null));
        assertEquals(0, UpdateUtil.codeFromReleaseName("(code много)"));
    }

    @Test
    public void versionNameFromAsset() {
        assertEquals("2.28", UpdateUtil.versionNameFromAsset("ViberLead-v2.28-pro.apk"));
        assertEquals("2.28", UpdateUtil.versionNameFromAsset("ViberLead-v2.28-lite.apk"));
        assertEquals("2.28-rc1", UpdateUtil.versionNameFromAsset("ViberLead-v2.28-rc1-pro.apk"));
        assertEquals("", UpdateUtil.versionNameFromAsset("other.apk"));
        assertEquals("", UpdateUtil.versionNameFromAsset("ViberLead-v2.28.zip"));
        assertEquals("", UpdateUtil.versionNameFromAsset(null));
    }

    @Test
    public void assetMatchesPackage() {
        // pro-сборка берёт только про-файл
        assertTrue(UpdateUtil.assetMatchesPackage("ViberLead-v2.28-pro.apk", "by.viberlead.app"));
        assertFalse(UpdateUtil.assetMatchesPackage("ViberLead-v2.28-lite.apk", "by.viberlead.app"));
        // lite-сборка — только лайт-файл
        assertTrue(UpdateUtil.assetMatchesPackage("ViberLead-v2.28-lite.apk", "by.viberlead.app.lite"));
        assertFalse(UpdateUtil.assetMatchesPackage("ViberLead-v2.28-pro.apk", "by.viberlead.app.lite"));
        assertFalse(UpdateUtil.assetMatchesPackage("ViberLead-v2.28.txt", "by.viberlead.app"));
        assertFalse(UpdateUtil.assetMatchesPackage(null, "by.viberlead.app"));
    }

    @Test
    public void versionComparison() {
        // по versionCode — основной путь
        assertTrue(UpdateUtil.isNewer(41, 40, "2.29", "2.28"));
        assertFalse(UpdateUtil.isNewer(40, 40, "2.28", "2.28"));
        assertFalse(UpdateUtil.isNewer(39, 40, "9.9", "2.28")); // код старше — не обновляем
        // кода в релизе нет — по versionName
        assertTrue(UpdateUtil.isNewer(0, 40, "2.29", "2.28"));
        assertFalse(UpdateUtil.isNewer(0, 40, "2.28", "2.28"));
    }

    @Test
    public void versionNameIsNewer() {
        assertTrue(UpdateUtil.versionNameIsNewer("2.29", "2.28"));
        assertTrue(UpdateUtil.versionNameIsNewer("2.2.10", "2.2.9"));
        assertTrue(UpdateUtil.versionNameIsNewer("3.0", "2.99"));
        // у lite локальная версия «2.28-lite»: 2.28 не новее, 2.29 новее
        assertFalse(UpdateUtil.versionNameIsNewer("2.28", "2.28-lite"));
        assertTrue(UpdateUtil.versionNameIsNewer("2.29", "2.28-lite"));
        assertFalse(UpdateUtil.versionNameIsNewer("", "2.28"));
        assertFalse(UpdateUtil.versionNameIsNewer(null, "2.28"));
        assertTrue(UpdateUtil.versionNameIsNewer("1.0", null));
    }

    @Test
    public void numericPart() {
        assertEquals(28, UpdateUtil.numericPart("28"));
        assertEquals(28, UpdateUtil.numericPart("28b"));
        assertEquals(0, UpdateUtil.numericPart("beta"));
        assertEquals(0, UpdateUtil.numericPart(null));
        // сверхдлинное не падает
        assertTrue(UpdateUtil.numericPart("99999999999999999999") >= 0);
    }
}
