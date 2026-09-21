package by.viberlead.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран настроек: способ отправки, токен Viber API, deep link, webhook,
 * название компании, шаблон сообщения, JSON-конфиг полей формы, код страны.
 */
public class SettingsActivity extends Activity {

    private static final String[] MODES = {
            Settings.MODE_AUTO, Settings.MODE_API, Settings.MODE_DEEPLINK, Settings.MODE_WEBHOOK
    };

    private Settings settings;

    private Spinner spinnerMode;
    private EditText edToken, edApiUrl, edReceiver, edBroadcast, edPhone, edChatUri,
            edWebhookUrl, edWebhookToken, edCompany, edTemplate, edFields, edPhoneCc,
            edLastContact;
    private CheckBox cbFastSend;
    private TextView tvDbStatus;
    private Spinner spGroupMethod;
    private Spinner spPointMode;
    private CheckBox cbAutoSend;
    private TextView tvAutoPasteStatus;
    private TextView tvAutoPasteDiag;
    private static final int REQ_IMPORT_DB = 802;
    private TextView tvTokenResult, tvPreview, tvFieldsStatus;
    private boolean dirty = false;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        if (BuildConfig.LITE) {
            for (int id : new int[]{R.id.card_mode, R.id.card_api,
                    R.id.card_webhook, R.id.card_fields}) {
                View c = findViewById(id);
                if (c != null) c.setVisibility(View.GONE);
            }
        }
        settings = new Settings(this);

        spinnerMode = findViewById(R.id.spinner_mode);
        edToken = findViewById(R.id.ed_token);
        edApiUrl = findViewById(R.id.ed_api_url);
        edReceiver = findViewById(R.id.ed_receiver);
        edBroadcast = findViewById(R.id.ed_broadcast_list);
        edPhone = findViewById(R.id.ed_phone);
        edPhoneCc = findViewById(R.id.ed_phone_cc);
        edChatUri = findViewById(R.id.ed_chat_uri);
        edWebhookUrl = findViewById(R.id.ed_webhook_url);
        edWebhookToken = findViewById(R.id.ed_webhook_token);
        edCompany = findViewById(R.id.ed_company);
        edLastContact = findViewById(R.id.ed_last_contact);
        tvDbStatus = findViewById(R.id.tv_db_status);
        findViewById(R.id.btn_share_orgs).setOnClickListener(v -> onShareOrgs());
        cbFastSend = findViewById(R.id.cb_fast_send);
        spGroupMethod = findViewById(R.id.sp_group_method);
        ArrayAdapter<String> gm = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{
                "Авто: сохранённая группа открывается сразу (иначе окно Viber с текстом)",
                "1. Окно Viber с готовым текстом",
                "2. Открыть группу + буфер обмена"});
        gm.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPointMode = findViewById(R.id.sp_point_mode);
        ArrayAdapter<String> pm = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{
                "Точка: координаты (компактно, без карты в Viber, не кликабельно)",
                "Точка: ссылка на Яндекс.Карты (кликабельно, Viber нарисует карту)",
                "Точка: ссылка в Яндекс.Навигатор (маршрут с координатами, кликабельно)",
                "Точка: не добавлять в заявку"});
        pm.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPointMode.setAdapter(pm);
        spGroupMethod.setAdapter(gm);
        findViewById(R.id.btn_token_help).setOnClickListener(v -> showTokenHelp());
        cbAutoSend = findViewById(R.id.cb_auto_send);
        tvAutoPasteStatus = findViewById(R.id.tv_autopaste_status);
        tvAutoPasteDiag = findViewById(R.id.tv_autopaste_diag);
        findViewById(R.id.btn_autopaste).setOnClickListener(v -> {
            if (Settings.isAutoPasteAvailable(this)) {
                showAutoPasteHelp();
            } else {
                showNoServiceBuildHelp();
            }
        });
        findViewById(R.id.btn_app_info).setOnClickListener(v -> openAppInfo());
        findViewById(R.id.btn_import_db).setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(android.content.Intent.createChooser(i,
                        "Выберите файл базы организаций (JSON)"), REQ_IMPORT_DB);
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show();
            }
        });
        edTemplate = findViewById(R.id.ed_template);
        edFields = findViewById(R.id.ed_fields);
        tvTokenResult = findViewById(R.id.tv_token_result);
        tvPreview = findViewById(R.id.tv_preview);
        tvFieldsStatus = findViewById(R.id.tv_fields_status);

        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.mode_auto));
        labels.add(getString(R.string.mode_api));
        labels.add(getString(R.string.mode_deeplink));
        labels.add(getString(R.string.mode_webhook));
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(ad);

        Button btnBack = findViewById(R.id.btn_back);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnCheck = findViewById(R.id.btn_check_token);
        Button btnPreview = findViewById(R.id.btn_preview);
        Button btnExample = findViewById(R.id.btn_fields_example);
        Button btnReset = findViewById(R.id.btn_fields_reset);

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());
        btnCheck.setOnClickListener(v -> checkToken());
        btnPreview.setOnClickListener(v -> showPreview());
        btnExample.setOnClickListener(v -> edFields.setText(pretty(exampleFields())));
        btnReset.setOnClickListener(v -> {
            settings.resetFields();
            edFields.setText(pretty(settings.getDefaultFieldsAsset()));
            Toast.makeText(this, "Вставлены поля по умолчанию", Toast.LENGTH_SHORT).show();
        });

        loadValues();

        // живая валидация JSON полей + флаг несохранённых изменений
        edFields.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (loading) return;
                dirty = true;
                validateFieldsLive();
            }
        });
        loading = false;
        validateFieldsLive();
    }

    /** Мгновенная подсветка состояния JSON прямо под редактором полей. */
    private void validateFieldsLive() {
        String json = edFields.getText().toString().trim();
        if (json.isEmpty()) {
            tvFieldsStatus.setVisibility(View.VISIBLE);
            tvFieldsStatus.setTextColor(getResources().getColor(R.color.error));
            tvFieldsStatus.setText("⚠️ Список полей пуст — форма останется со старыми полями");
            return;
        }
        try {
            List<FormField> parsed = FormField.parseList(json);
            if (parsed.isEmpty()) throw new Exception("ни одного поля");
            StringBuilder names = new StringBuilder();
            for (FormField f : parsed) {
                if (names.length() > 0) names.append(", ");
                names.append(f.label);
            }
            tvFieldsStatus.setVisibility(View.VISIBLE);
            tvFieldsStatus.setTextColor(getResources().getColor(R.color.success));
            tvFieldsStatus.setText("✅ JSON корректен · полей: " + parsed.size() + " · " + names);
        } catch (Exception e) {
            tvFieldsStatus.setVisibility(View.VISIBLE);
            tvFieldsStatus.setTextColor(getResources().getColor(R.color.error));
            tvFieldsStatus.setText("⚠️ Ошибка JSON: " + e.getMessage()
                    + " — изменения НЕ применятся, пока не исправите");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAutoPasteStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_DB || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(data.getData());
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            String json = new String(bos.toByteArray(), "UTF-8");
            OrgDb db = new OrgDb(this);
            int[] res = OrgDbSync.mergeFromJson(db, json);
            OrgDbSync.scheduleExport(this, db);
            refreshDbStatus();
            new AlertDialog.Builder(this)
                    .setTitle("База принята")
                    .setMessage("Добавлено новых организаций: " + res[0]
                            + "\nПропущено (уже есть с теми же названием и адресом): " + res[1])
                    .setNeutralButton("Готово", null)
                    .show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Не читается")
                    .setMessage("Файл не похож на базу организаций ViberLead: " + e.getMessage())
                    .setNeutralButton("Закрыть", null)
                    .show();
        }
    }

    private void openAppInfo() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "Откройте: Настройки Android → Все приложения → «Заявки в Viber»",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showAutoPasteHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Как включить авто-вставку")
                .setMessage("1. В открывшихся спецвозможностях Android перейдите на вкладку "
                        + "«Загруженные приложения» / «Загруженные службы». На Xiaomi путь такой: "
                        + "Настройки → Расширенные настройки → Специальные возможности → вкладка «Загруженные».\n"
                        + "2. Найдите «ViberLead» / «Заявки в Viber», откройте и включите переключатель.\n"
                        + "3. Если Android пишет «Ограниченная настройка»: нажмите в этом приложении "
                        + "кнопку «О приложении…», затем в карточке приложения ⋮ (справа сверху) → "
                        + "«Разрешить ограниченные настройки», и повторите шаги 1–2.")
                .setPositiveButton("Открыть спецвозможности", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Настройки Android → Спецвозможности", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("О приложении", (d, w) -> openAppInfo())
                .setNeutralButton("Закрыть", null)
                .show();
    }

    private void showNoServiceBuildHelp() {
        new AlertDialog.Builder(this)
                .setTitle("В этой сборке нет авто-вставки")
                .setMessage("Установлена стандартная сборка: в ней нет службы авто-вставки, поэтому "
                        + "её и нет в спецвозможностях Android. Авто-вставка есть в отдельной сборке "
                        + "ViberLead-autopaste:\n"
                        + "1. Play Маркет → профиль → Play Защита → шестерёнка → выключить "
                        + "«Сканировать приложения с Play Защитой»;\n"
                        + "2. установить файл ViberLead-v2.14-autopaste.apk;\n"
                        + "3. здесь нажать «Включить авто-вставку» и пройти шаги из подсказки.")
                .setNeutralButton("Понятно", null)
                .show();
    }

    private void refreshAutoPasteStatus() {
        if (!Settings.isAutoPasteAvailable(this)) {
            tvAutoPasteStatus.setText("В этой сборке службы авто-вставки нет: Google Play Защита "
                    + "блокирует установку APK со спецвозможностями. Авто-вставка есть в отдельной "
                    + "сборке ViberLead-autopaste (инструкция в README).");
            tvAutoPasteStatus.setTextColor(getResources().getColor(R.color.text_secondary));
            return;
        }
        boolean on = Settings.isAutoPasteOn(this);
        tvAutoPasteStatus.setText(on
                ? "Служба авто-вставки ВКЛЮЧЕНА ✓"
                : "Служба авто-вставки выключена. Нажмите кнопку ниже и разрешите службу «ViberLead» в списке спецвозможностей.");
        tvAutoPasteStatus.setTextColor(getResources().getColor(
                on ? R.color.success : R.color.text_secondary));
        tvAutoPasteDiag.setText("Диагностика: служба "
                + (AutoPasteService.isConnected() ? "подключена" : "НЕ подключена")
                + "; " + AutoPasteService.getStatus()
                + ". Если авто-вставка не сработала — пришлите скриншот этой строки.");
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            new AlertDialog.Builder(this)
                    .setTitle("Несохранённые изменения")
                    .setMessage("Сохранить настройки перед выходом?")
                    .setPositiveButton("Сохранить", (d, w) -> save())
                    .setNegativeButton("Выйти без сохранения", (d, w) -> finish())
                    .setNeutralButton(R.string.cancel, null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    private void loadValues() {
        String mode = settings.getMode();
        int idx = 0;
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) idx = i;
        spinnerMode.setSelection(idx);

        edToken.setText(settings.getToken());
        edApiUrl.setText(settings.getApiUrl());
        edReceiver.setText(settings.getReceiver());
        edBroadcast.setText(android.text.TextUtils.join(",", settings.getBroadcastList()));
        edPhone.setText(settings.getPhone());
        edPhoneCc.setText(settings.getPhoneCc());
        edChatUri.setText(settings.getChatUri());
        edWebhookUrl.setText(settings.getWebhookUrl());
        edWebhookToken.setText(settings.getWebhookToken());
        edCompany.setText(settings.getCompany());
        edLastContact.setText(settings.getLastContact());
        cbFastSend.setChecked(settings.getFastSend());
        cbAutoSend.setChecked(settings.getAutoSend());
        refreshAutoPasteStatus();
        spGroupMethod.setSelection(Math.min(2, Math.max(0, settings.getGroupMethod())));
        spPointMode.setSelection(Math.min(3, Math.max(0, settings.getPointMode())));
        refreshDbStatus();
        edTemplate.setText(settings.getTemplate());
        edFields.setText(pretty(settings.getFieldsRaw()));
    }

    private int fieldsOk = 0;

    private void onShareOrgs() {
        if (android.os.Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 77);
            return;
        }
        OrgDbSync.share(this, new OrgDb(this));
        refreshDbStatus();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 77 && res.length > 0
                && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            OrgDbSync.share(this, new OrgDb(this));
            refreshDbStatus();
        }
    }

    private void showTokenHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Токен Viber API")
                .setMessage("Что даёт токен: фоновую отправку БЕЗ открытия Viber — заявка уходит серверу Viber сама. "
                        + "Важно: Viber API доставляет сообщения личным получателям и рассылкам (пользователям, "
                        + "открывшим чат с вашим Public Account). В обычную группу («КБ траст») Viber не разрешает "
                        + "писать через API даже с токеном — для группы остаётся путь «текст готов → два касания».\n\n"
                        + "Как получить токен:\n"
                        + "1) partners.viber.com → создайте Public Account (бот) вашей компании;\n"
                        + "2) в управлении аккаунтом скопируйте токен (X-Viber-Auth-Token);\n"
                        + "3) вставьте его в поле «Токен» выше и укажите получателя;\n"
                        + "4) проверьте кнопкой «Проверить токен».\n\n"
                        + "Для группы «КБ траст» удобнее текущий способ: окно Viber с готовым текстом.")
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void refreshDbStatus() {
        try {
            OrgDb db = new OrgDb(this);
            String where = OrgDbSync.externalExists(this)
                    ? "Файл Download/" + OrgDbSync.FILE_NAME + " создан ✓"
                    : "Файл будет создан при следующей отправке заявки";
            tvDbStatus.setText("Записей в базе: " + db.count() + ". " + where + ".");
        } catch (Exception e) {
            tvDbStatus.setText("База организаций: " + e.getMessage());
        }
    }

    private void save() {
        fieldsOk = 0;
        settings.setMode(MODES[Math.max(0, spinnerMode.getSelectedItemPosition())]);
        settings.setToken(text(edToken));
        settings.setApiUrl(text(edApiUrl));
        settings.setReceiver(text(edReceiver));
        settings.setBroadcastList(text(edBroadcast));
        settings.setPhoneCc(text(edPhoneCc));
        settings.setPhone(text(edPhone));
        settings.setChatUri(text(edChatUri));
        settings.setWebhookUrl(text(edWebhookUrl));
        settings.setWebhookToken(text(edWebhookToken));
        settings.setCompany(text(edCompany));
        settings.setLastContact(text(edLastContact));
        settings.setFastSend(cbFastSend.isChecked());
        settings.setGroupMethod(spGroupMethod.getSelectedItemPosition());
        settings.setPointMode(spPointMode.getSelectedItemPosition());
        settings.setAutoSend(cbAutoSend.isChecked());
        settings.setTemplate(edTemplate.getText().toString());

        String fieldsJson = edFields.getText().toString().trim();
        if (!fieldsJson.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(fieldsJson);
                if (arr.length() == 0) throw new Exception("пустой массив");
                // проверяем, что каждый элемент — объект с типом поля
                List<FormField> parsed = FormField.parseList(arr.toString());
                if (parsed.isEmpty()) throw new Exception("не найдено ни одного поля");
                settings.setFieldsRaw(pretty(arr.toString()));
                fieldsOk = parsed.size();
            } catch (Exception e) {
                // ВАЖНО: не делаем вид, что всё сохранено — остаёмся на экране
                new AlertDialog.Builder(this)
                        .setTitle("Поля формы НЕ сохранены")
                        .setMessage(getString(R.string.set_fields_invalid, e.getMessage())
                                + "\n\nИсправьте JSON и нажмите «Сохранить» снова.\n"
                                + "Остальные настройки можно сохранить и с ошибкой в полях — "
                                + "тогда нажмите «Выйти без сохранения» и сохраните позже.")
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }
        }

        dirty = false;
        Toast.makeText(this,
                fieldsOk > 0
                        ? "Настройки сохранены · полей на форме: " + fieldsOk
                        : getString(R.string.set_saved),
                Toast.LENGTH_LONG).show();
        finish();
    }

    private void checkToken() {
        final String token = text(edToken);
        final String apiUrl = text(edApiUrl);
        if (token.isEmpty()) {
            showTokenResult("Сначала вставьте токен", false);
            return;
        }
        showTokenResult("Проверка…", null);
        new Thread(() -> {
            final ViberClient.ApiResult r = ViberClient.checkToken(
                    apiUrl.isEmpty() ? "https://chatapi.viber.com/pa" : apiUrl, token);
            runOnUiThread(() -> {
                if (r.success) {
                    String name = "";
                    try {
                        JSONObject o = new JSONObject(r.rawBody);
                        name = o.optString("name", "");
                    } catch (Exception ignored) {
                    }
                    showTokenResult(getString(R.string.token_ok, name.isEmpty() ? "—" : name), true);
                } else {
                    showTokenResult(getString(R.string.token_bad, r.describe()), false);
                }
            });
        }, "viber-check").start();
    }

    private void showTokenResult(String msg, Boolean ok) {
        tvTokenResult.setVisibility(View.VISIBLE);
        tvTokenResult.setText(msg);
        if (ok == null) {
            tvTokenResult.setTextColor(getResources().getColor(R.color.text_secondary));
        } else {
            tvTokenResult.setTextColor(getResources().getColor(ok ? R.color.success : R.color.error));
        }
    }

    private void showPreview() {
        // применяем текущие значения из полей экрана, чтобы предпросмотр был честным
        settings.setCompany(text(edCompany));
        settings.setTemplate(edTemplate.getText().toString());
        String fieldsJson = edFields.getText().toString().trim();
        if (!fieldsJson.isEmpty()) {
            try {
                settings.setFieldsRaw(new JSONArray(fieldsJson).toString());
            } catch (Exception ignored) {
            }
        }
        String preview = MessageBuilder.buildPreview(settings);
        tvPreview.setVisibility(View.VISIBLE);
        tvPreview.setText(preview);
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_preview)
                .setMessage(preview)
                .setPositiveButton(R.string.btn_copy, (d, w) -> MainActivity.copyToClipboard(this, preview))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    /** Пример конфига полей — вставляется по кнопке «Вставить пример». */
    private static String exampleFields() {
        return "["
                + "{\"key\":\"org\",\"label\":\"Организация\",\"type\":\"org\",\"required\":true,\"hint\":\"Начните вводить — база подскажет\"},"
                + "{\"key\":\"address\",\"label\":\"Адрес\",\"type\":\"address\",\"required\":true,\"orgMap\":\"address\"},"
                + "{\"key\":\"contact\",\"label\":\"Контактное лицо\",\"type\":\"text\",\"required\":false,\"orgMap\":\"contact\"},"
                + "{\"key\":\"org_phone\",\"label\":\"Телефон организации\",\"type\":\"phone\",\"required\":false,\"orgMap\":\"phone\"},"
                + "{\"key\":\"request\",\"label\":\"Заявка\",\"type\":\"multiselect\",\"required\":true,"
                + "\"options\":[\"Заправка\",\"Ремонт\",\"Сброс ошибок\",\"Диагностика\"]},"
                + "{\"key\":\"name\",\"label\":\"Имя\",\"type\":\"text\",\"required\":false},"
                + "{\"key\":\"budget\",\"label\":\"Бюджет, BYN\",\"type\":\"number\",\"required\":false},"
                + "{\"key\":\"date\",\"label\":\"Желаемая дата\",\"type\":\"date\",\"required\":false},"
                + "{\"key\":\"callback\",\"label\":\"Нужен обратный звонок\",\"type\":\"checkbox\",\"required\":false},"
                + "{\"key\":\"comment\",\"label\":\"Комментарий\",\"type\":\"multiline\",\"required\":false,\"hint\":\"Коротко о задаче\"}"
                + "]";
    }

    private static String pretty(String json) {
        try {
            Object o = json.trim().startsWith("[") ? new JSONArray(json) : new JSONObject(json);
            if (o instanceof JSONArray) return ((JSONArray) o).toString(2);
            return ((JSONObject) o).toString(2);
        } catch (Exception e) {
            return json;
        }
    }
}
