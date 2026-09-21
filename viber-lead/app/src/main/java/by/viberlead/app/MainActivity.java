package by.viberlead.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Главный экран: динамическая форма заявки + отправка в Viber.
 *
 * Поддерживаемые типы полей: text, phone, email, number, multiline, select,
 * multiselect (список с чекбоксами), date, checkbox,
 * org (живой поиск по базе организаций + автоподстановка контактов),
 * address (текст + кнопка 🧭 маршрута в Яндекс Навигаторе).
 *
 * Телефоны нормализуются к международному формату мгновенно (ввод/вставка),
 * при потере фокуса и при отправке.
 *
 * Режимы отправки (Настройки): auto / api / deeplink / webhook.
 */
public class MainActivity extends Activity {

    private Settings settings;
    private OrgDb orgDb;
    private HistoryDb historyDb;

    private LinearLayout formContainer;
    private TextView headerTitle, headerSubtitle, notice;
    private JSONObject draftRestore = new JSONObject();
    private String fieldsJsonAtBuild = "";
    private Button btnSend, btnClear, btnSettings, btnHistory, btnOrgs;
    private ScrollView scroll;
    private View overlay;

    private final List<FormField> fields = new ArrayList<>();
    private final Map<String, View> fieldViews = new LinkedHashMap<>();
    private ListPopupWindow orgPopup;
    private OrgSuggestAdapter orgAdapter;
    private volatile boolean sending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new Settings(this);
        orgDb = new OrgDb(this);
        historyDb = new HistoryDb(this);

        formContainer = findViewById(R.id.form_container);
        headerTitle = findViewById(R.id.header_title);
        headerSubtitle = findViewById(R.id.header_subtitle);
        notice = findViewById(R.id.notice);
        btnSend = findViewById(R.id.btn_send);
        btnClear = findViewById(R.id.btn_clear_form);
        btnSettings = findViewById(R.id.btn_settings);
        btnHistory = findViewById(R.id.btn_history);
        btnOrgs = findViewById(R.id.btn_orgs);
        scroll = findViewById(R.id.scroll);
        overlay = findViewById(R.id.overlay);

        btnSend.setOnClickListener(v -> onSendClicked());
        btnSend.setOnLongClickListener(v -> {
            showTextPreview();
            return true;
        });
        btnClear.setOnClickListener(v -> clearForm());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        btnOrgs.setOnClickListener(v -> startActivity(new Intent(this, OrgsActivity.class)));
        findViewById(R.id.footer_site).setOnClickListener(v -> openExternal("https://csl.by/"));
        findViewById(R.id.footer_phone).setOnClickListener(v -> openExternal("tel:+375293371412"));
        findViewById(R.id.footer_email).setOnClickListener(v -> openExternal("mailto:info@csl.by"));

        try {
            String d = savedInstanceState != null
                    ? savedInstanceState.getString("draft") : null;
            if (d == null || d.trim().isEmpty()) d = settings.getDraftJson();
            draftRestore = new JSONObject(d == null || d.trim().isEmpty() ? "{}" : d);
        } catch (Exception e) {
            draftRestore = new JSONObject();
        }
        rebuildForm();
        restoreOrgDbAsync();
        // v2.28: автообновление с GitHub (тихо: только если включено и
        // с последней проверки прошло ≥6 часов; сеть — в фоновом потоке)
        UpdateFlow.autoCheckOnStartup(this);
    }

    /** База организаций переживает переустановку: восстанавливается из Download. */
    private void restoreOrgDbAsync() {
        final MainActivity self = this;
        new Thread(() -> {
            boolean imported = OrgDbSync.importIfEmpty(self, orgDb);
            final int purged = orgDb.purgeOutOfBoundsCoords();
            if (purged > 0) {
                runOnUiThread(() -> Toast.makeText(self,
                        "🧭 Сброшено старых точек вне Минска и Минского района: " + purged
                                + ". Координаты будут найдены заново.", Toast.LENGTH_LONG).show());
            }
            if (imported) {
                runOnUiThread(() -> Toast.makeText(self,
                        "📦 База организаций восстановлена из файла Download/"
                                + OrgDbSync.FILE_NAME, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        settings.migrateGroupMethodV210(appVersionCode());
        settings.migrateGroupMethodV211(appVersionCode());
        boolean healed = settings.healTemplateIfNeeded(appVersionCode());
        boolean migrated = settings.migrateTemplateV2(appVersionCode());
        if (migrated) {
            notice.setText("🛠 Шаблон обновлён: строка устройства убрана, "
                    + "заголовок — «Заявка <компания>». Название компании меняется "
                    + "в «Настройках».");
            notice.setVisibility(View.VISIBLE);
        } else if (healed) {
            notice.setText("🛠 Сохранённый шаблон сообщения не содержал телефона — "
                    + "восстановлен стандартный. Старый шаблон сохранён в резерв "
                    + "(Настройки → шаблон).");
            notice.setVisibility(View.VISIBLE);
        }

        refreshHeader();
        // v2.27: форму НЕ пересобираем при возврате — введённое сохраняется;
        // пересборка только если конфиг полей изменился (значения сохраняются)
        if (!settings.getFieldsRaw().equals(fieldsJsonAtBuild)) {
            rebuildForm();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        dismissOrgPopup();
        saveDraft();
    }

    /** v2.27: черновик в настройки — переживает смерть процесса. */
    private void saveDraft() {
        try {
            JSONObject o = new JSONObject();
            for (FormField f : fields) o.put(f.key, readValue(f));
            settings.saveDraftJson(o.toString());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- построение

    /** Версия приложения из манифеста — видна на главном экране и на скриншотах. */
    private int appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void refreshHeader() {
        headerTitle.setText(settings.getCompany());
        headerSubtitle.setText(getString(R.string.form_subtitle));
        // предупреждение о дубликатах ключей и сообщения миграций остаются видимыми
        String noticeText = notice.getText().toString();
        if (!noticeText.startsWith("⚠️ В конфиге") && !noticeText.startsWith("🛠")) {
            notice.setVisibility(View.GONE);
        }
    }

    private void rebuildForm() {
        fields.clear();
        fields.addAll(settings.getFields());
        formContainer.removeAllViews();
        fieldViews.clear();
        dismissOrgPopup();

        // v2.27: значения сохраняются: сначала то, что сейчас в полях,
        // затем черновик (после смерти процесса); чисто — только после отправки
        Map<String, String> preserved = new LinkedHashMap<>();
        for (FormField old_f : fields) {
            try {
                preserved.put(old_f.key, readValue(old_f));
            } catch (Exception ignored) {
            }
        }
        JSONObject last = draftRestore;
        fieldsJsonAtBuild = settings.getFieldsRaw();
        List<String> seen = new ArrayList<>();
        List<String> dups = new ArrayList<>();
        for (FormField f : fields) {
            if (seen.contains(f.key)) {
                dups.add(f.key);
                continue; // дубликат ключа: оставляем только первое поле
            }
            seen.add(f.key);
            formContainer.addView(createLabel(f));
            String savedVal = preserved.containsKey(f.key) ? preserved.get(f.key) : "";
            if (savedVal == null) savedVal = "";
            if (savedVal.isEmpty()) savedVal = last.optString(f.key, "");
            if (savedVal.isEmpty() && isContactField(f)) {
                savedVal = settings.getLastContact();
            }
            formContainer.addView(createInput(f, savedVal));
        }
        if (!dups.isEmpty()) {
            notice.setText("⚠️ В конфиге полей дублируются ключи: "
                    + TextUtils.join(", ", dups)
                    + ". Дубликаты скрыты, иначе значения терялись при отправке.");
            notice.setVisibility(View.VISIBLE);
        }
    }

    private TextView createLabel(FormField f) {
        TextView tv = new TextView(this);
        String text = f.label;
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(2);
        tv.setLayoutParams(lp);
        if (f.required) {
            SpannableString ss = new SpannableString(text + " *");
            ss.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.site_accent)),
                    text.length(), text.length() + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new BoldSpan(), text.length(), text.length() + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(ss);
        }
        return tv;
    }

    private static class BoldSpan extends CharacterStyle {
        @Override
        public void updateDrawState(android.text.TextPaint tp) {
            tp.setTypeface(Typeface.create(tp.getTypeface(), Typeface.BOLD));
        }
    }

    private EditText baseEditText(FormField f) {
        EditText et = new EditText(this);
        et.setBackgroundResource(R.drawable.bg_field);
        et.setMinHeight(dp(40));
        et.setPadding(dp(10), dp(7), dp(10), dp(7));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTextColor(getResources().getColor(R.color.text_primary));
        et.setHintTextColor(getResources().getColor(R.color.hint));
        et.setHint(f.hint == null ? "" : f.hint);
        return et;
    }

    private View createInput(FormField f, String saved) {
        View input;
        if (f.isCheckbox()) {
            CheckBox cb = new CheckBox(this);
            cb.setText(f.hint == null || f.hint.isEmpty() ? f.label : f.hint);
            cb.setTextColor(getResources().getColor(R.color.text_primary));
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            cb.setChecked("да".equalsIgnoreCase(saved) || "true".equalsIgnoreCase(saved)
                    || "1".equals(saved) || "on".equalsIgnoreCase(f.defaultValue));
            cb.setLayoutParams(inputLp());
            input = cb;

        } else if (f.isSelect()) {
            List<String> items = new ArrayList<>(f.options);
            if (items.isEmpty()) items.add("—");
            Spinner sp = new Spinner(this);
            sp.setBackgroundResource(R.drawable.bg_field);
            sp.setMinimumHeight(dp(48));
            sp.setLayoutParams(inputLp());
            ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, items);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(ad);
            int idx = items.indexOf(saved);
            if (idx < 0) idx = items.indexOf(f.defaultValue);
            if (idx >= 0) sp.setSelection(idx);
            input = sp;

        } else if (f.isMultiselect()) {
            EditText et = baseEditText(f);
            et.setLayoutParams(inputLp());
            et.setFocusable(false);
            et.setClickable(true);
            final List<String> selected = new ArrayList<>();
            if (!saved.isEmpty()) {
                for (String token : saved.split(",")) {
                    String t = token.trim();
                    if (f.options.contains(t)) selected.add(t);
                }
            }
            et.setTag(selected);
            et.setText(joinList(selected));
            et.setOnClickListener(v -> showMultiselectDialog(f, et));
            input = et;

        } else if (f.isOrg()) {
            EditText et = baseEditText(f);
            et.setLayoutParams(inputLp());
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            et.setText(saved);
            et.addTextChangedListener(new SimpleWatcher(q -> updateOrgSuggestions(et, q)));
            et.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    dismissOrgPopup();
                    autofillFromOrgOnBlur(et);
                }
            });
            input = et;

        } else if (f.isAddress()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(inputLp());

            EditText et = baseEditText(f);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            et.setLayoutParams(etLp);
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
            et.setText(saved);
            et.setOnLongClickListener(v -> {
                openRouteForAddress(et);
                return true;
            });

            Button route = new Button(this);
            route.setText("🧭");
            route.setBackgroundResource(R.drawable.btn_ghost);
            route.setTextColor(getResources().getColor(R.color.viber_purple_dark));
            route.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            route.setAllCaps(false);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(52), dp(48));
            rLp.leftMargin = dp(6);
            route.setLayoutParams(rLp);
            route.setContentDescription("Построить маршрут в Яндекс Навигаторе");
            route.setOnClickListener(v -> openRouteForAddress(et));

            row.addView(et);
            row.addView(makeMicButton(et, f.key));
            row.addView(route);
            input = row;
            fieldViews.put(f.key, et);
            return row;

        } else {
            EditText et = baseEditText(f);
            et.setLayoutParams(inputLp());
            switch (f.type) {
                case FormField.TYPE_PHONE:
                    et.setInputType(InputType.TYPE_CLASS_PHONE);
                    break;
                case FormField.TYPE_EMAIL:
                    et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                    break;
                case FormField.TYPE_NUMBER:
                    et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    break;
                case FormField.TYPE_MULTILINE:
                    et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                    et.setMinLines(2);
                    et.setGravity(Gravity.TOP | Gravity.START);
                    break;
                default:
                    et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                    break;
            }
            if (f.isDate()) {
                et.setInputType(InputType.TYPE_CLASS_TEXT);
                et.setFocusable(false);
                et.setClickable(true);
                et.setOnClickListener(v -> pickDate(et));
            }
            // v2.19: без красных подчёркиваний спеллчекера (в т.ч. на надиктованном)
            et.setInputType(et.getInputType() | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    java.lang.reflect.Method m = android.widget.TextView.class
                            .getMethod("setSpellCheckEnabled", boolean.class);
                    m.invoke(et, false);
                } catch (Exception ignored) {
                }
            }
            String initial = !saved.isEmpty() ? saved : prefillValue(f);
            if (f.isPhone() && !initial.isEmpty()) {
                initial = PhoneUtils.normalize(initial, settings.getPhoneCc());
            }
            et.setText(initial);
            input = et;
        }

        if (input instanceof EditText
                && (f.isPhone()
                    || FormField.TYPE_TEXT.equals(f.type)
                    || FormField.TYPE_PHONE.equals(f.type))) {
            attachPhoneNormalizer((EditText) input, f.isPhone());
        }

        if (input instanceof EditText && !f.isDate() && !f.isMultiselect()) {
            // в fieldViews храним именно EditText: сбор значений и очистка
            // формы работают с полем напрямую, а в форму добавляется строка с микрофоном
            fieldViews.put(f.key, input);
            return wrapWithMic((EditText) input, f.key);
        }

        fieldViews.put(f.key, input);
        return input;
    }

    /** Поле комментария: определяем по ключу или подписи. */
    private boolean isCommentField(FormField f) {
        return (f.key != null && f.key.toLowerCase(java.util.Locale.US).contains("comment"))
                || (f.label != null && f.label.contains("оммент"));
    }

    /** v2.16: микрофон рядом с полем комментария — текст можно надиктовать. */
    private View wrapWithMic(final EditText et, final String fieldKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(inputLp());
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);
        row.addView(et);
        row.addView(makeMicButton(et, fieldKey));
        return row;
    }

    /** v2.21: единая кнопка микрофона для любого текстового поля. */
    private Button makeMicButton(final EditText et, final String fieldKey) {
        Button mic = new Button(this);
        mic.setText("🎤");
        mic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mic.setBackgroundResource(R.drawable.bg_field);
        mic.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(46), dp(40));
        micLp.leftMargin = dp(6);
        mic.setLayoutParams(micLp);
        mic.setContentDescription("Голосовой ввод в это поле");
        mic.setOnClickListener(v -> onMicClick(et, fieldKey));
        return mic;
    }

    private static final int REQ_VOICE = 701;
    private static final int REQ_MIC_PERM = 703;
    private EditText voiceTarget;
    private String voiceTargetKey;
    private android.speech.SpeechRecognizer recognizer;
    private AlertDialog dictationDialog;

    /** v2.18: диктовка внутри приложения (SpeechRecognizer),fallback — внешняя распознавалка. */
    private void onMicClick(EditText target, String fieldKey) {
        voiceTarget = target;
        voiceTargetKey = fieldKey;
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERM);
                return;
            }
            startInAppDictation();
        } else {
            startRecognizerIntent();
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (rc == REQ_MIC_PERM) {
            if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startInAppDictation();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Нужен микрофон")
                        .setMessage("Без разрешения на запись звука диктовка не работает. "
                                + "Разрешение можно выдать: настройки Android → приложения → "
                                + "«Заявки в Viber» → разрешения → микрофон.")
                        .setNeutralButton("Понятно", null)
                        .show();
            }
        }
    }

    private void startInAppDictation() {
        stopInAppDictation();
        recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        android.content.Intent intent = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Продиктуйте текст комментария");
        final TextView live = new TextView(this);
        live.setPadding(dp(22), dp(16), dp(22), dp(16));
        live.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        live.setTextColor(getResources().getColor(R.color.text_primary));
        live.setText("🎤 Говорите — текст появится здесь и вставится в комментарий…");
        dictationDialog = new AlertDialog.Builder(this)
                .setTitle("Голосовой ввод")
                .setView(live)
                .setNegativeButton("Стоп", (d, w) -> stopInAppDictation())
                .setOnCancelListener(d -> stopInAppDictation())
                .show();
        recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle p) {
                live.setText("🎤 Слушаю… говорите.");
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int e, android.os.Bundle p) { }
            @Override public void onPartialResults(android.os.Bundle p) {
                java.util.ArrayList<String> r = p.getStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) {
                    live.setText("🎤 " + r.get(0));
                }
            }
            @Override public void onResults(android.os.Bundle p) {
                java.util.ArrayList<String> r = p.getStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                String said = (r == null || r.isEmpty()) ? "" : r.get(0).trim();
                stopInAppDictation();
                if (!said.isEmpty()) insertSaid(said);
            }
            @Override public void onError(int code) {
                stopInAppDictation();
                // внешняя распознавалка как запасной путь
                startRecognizerIntent();
            }
        });
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            stopInAppDictation();
            startRecognizerIntent();
        }
    }

    private void stopInAppDictation() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        if (dictationDialog != null) {
            try {
                dictationDialog.dismiss();
            } catch (Exception ignored) {
            }
            dictationDialog = null;
        }
    }

    private void startRecognizerIntent() {
        android.content.Intent intent = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Продиктуйте текст комментария");
        try {
            startActivityForResult(intent, REQ_VOICE);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Голосовой ввод недоступен")
                    .setMessage("На устройстве нет приложения распознавания речи. "
                            + "Текст комментария можно ввести с клавиатуры.")
                    .setNeutralButton("Понятно", null)
                    .show();
        }
    }

    /** Дописывает распознанную фразу в своё поле (телефон — только цифры). */
    private void insertSaid(String said) {
        EditText target = resolveTargetEditText();
        if (target == null) return;
        String text = said;
        FormField vf = fieldByKey(voiceTargetKey);
        if (vf != null && vf.isPhone()) {
            text = said.replaceAll("[^\\d+]", "");
            if (text.isEmpty()) return;
        }
        String cur = target.getText().toString().trim();
        String joined = cur.isEmpty() ? text : cur + " " + text;
        target.setText(joined);
        // v2.22: наблюдатели (нормализация телефона) могли изменить длину текста
        int len = target.getText().length();
        target.setSelection(len);
        voiceTarget = target;
    }

    private FormField fieldByKey(String key) {
        if (key == null) return null;
        for (FormField f : fields) if (key.equals(f.key)) return f;
        return null;
    }

    private EditText resolveTargetEditText() {
        EditText target = voiceTarget;
        if (target == null && voiceTargetKey != null) {
            target = findFieldEditText(voiceTargetKey);
        }
        if (target == null) {
            for (FormField f : fields) {
                if (isCommentField(f)) {
                    target = findFieldEditText(f.key);
                    break;
                }
            }
        }
        return target;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || data == null) return;
        java.util.ArrayList<String> res = data.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (res == null || res.isEmpty()) return;
        String said = res.get(0).trim();
        if (!said.isEmpty()) insertSaid(said);
    }

    /** Ищет EditText поля по ключу, распутывая строку с микрофоном. */
    private EditText findFieldEditText(String key) {
        View v = fieldViews.get(key);
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof EditText) return (EditText) c;
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        stopInAppDictation();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("voice_key", voiceTargetKey);
        try {
            JSONObject o = new JSONObject();
            for (FormField f : fields) o.put(f.key, readValue(f));
            out.putString("draft", o.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onRestoreInstanceState(android.os.Bundle in) {
        super.onRestoreInstanceState(in);
        voiceTargetKey = in.getString("voice_key");
    }

    /**
     * Мгновенная нормализация телефона: при вставке/вводе (9+ цифр) и при потере
     * фокуса поле само приводится к +375291234567, курсор остаётся в конце.
     */
    private void attachPhoneNormalizer(final EditText et, final boolean digitsOnly) {
        final boolean[] applying = {false};
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (applying[0]) return;
                String raw = e.toString();
                // v2.26: «только цифры» применяем ИСКЛЮЧИТЕЛЬНО к полю телефона,
                // к прочим текстовым полям (контакт, адрес) не трогаем буквы
                if (digitsOnly) {
                    String digits = raw.replaceAll("[^\\d+]", "");
                    if (!digits.equals(raw)) {
                        applying[0] = true;
                        e.replace(0, raw.length(), digits);
                        applying[0] = false;
                        raw = digits;
                    }
                }
                if (!PhoneUtils.looksLikePhone(raw)) return;
                String n = PhoneUtils.normalize(raw, settings.getPhoneCc());
                if (!n.equals(raw)) {
                    applying[0] = true;
                    et.setText(n);
                    et.setSelection(n.length());
                    applying[0] = false;
                }
            }
        });
        et.setOnFocusChangeListener((v2, hasFocus) -> {
            if (hasFocus) return;
            String cur = et.getText().toString().trim();
            if (cur.isEmpty()) return;
            String n = PhoneUtils.normalize(cur, settings.getPhoneCc());
            if (!n.equals(cur)) et.setText(n);
        });
    }

    private String prefillValue(FormField f) {
        if (f.defaultValue != null && !f.defaultValue.isEmpty()) return f.defaultValue;
        if ("device".equals(f.prefill)) return settings.deviceInfo();
        if ("datetime".equals(f.prefill)) {
            return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(new Date());
        }
        if ("date".equals(f.prefill)) {
            return new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
        }
        return "";
    }

    private void pickDate(final EditText et) {
        Calendar c = Calendar.getInstance();
        String cur = et.getText().toString().trim();
        try {
            if (cur.length() >= 10) {
                Date d = new SimpleDateFormat("dd.MM.yyyy", Locale.US).parse(cur.substring(0, 10));
                if (d != null) c.setTime(d);
            }
        } catch (Exception ignored) {
        }
        new DatePickerDialog(this, (view, y, m, d) ->
                et.setText(String.format(Locale.US, "%02d.%02d.%04d", d, m + 1, y)),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---------------------------------------------------- multiselect (чекбоксы)

    @SuppressWarnings("unchecked")
    private void showMultiselectDialog(FormField f, EditText et) {
        final List<String> current = et.getTag() instanceof List
                ? (List<String>) et.getTag() : new ArrayList<String>();
        final List<String> result = new ArrayList<>(current);
        String[] items = f.options.toArray(new String[0]);
        boolean[] checked = new boolean[items.length];
        for (int i = 0; i < items.length; i++) checked[i] = result.contains(items[i]);

        new AlertDialog.Builder(this)
                .setTitle(f.label + " — отметьте нужное")
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
                    String opt = items[which];
                    if (isChecked) {
                        if (!result.contains(opt)) result.add(opt);
                    } else {
                        result.remove(opt);
                    }
                })
                .setPositiveButton(R.string.ok, (d, w) -> {
                    et.setTag(result);
                    et.setText(joinList(result));
                    markErrorByKey(f.key, false);
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton("Сбросить", (d, w) -> {
                    et.setTag(new ArrayList<String>());
                    et.setText("");
                })
                .show();
    }

    private static String joinList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------- org: живой поиск и база

    private void updateOrgSuggestions(EditText et, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            dismissOrgPopup();
            return;
        }
        final List<OrgDb.Org> res = orgDb.search(q, 8);
        if (res.isEmpty()) {
            dismissOrgPopup();
            return;
        }
        if (orgPopup == null) {
            orgPopup = new ListPopupWindow(this);
            orgPopup.setModal(false);
            orgPopup.setOnItemClickListener((parent, view, position, id) -> {
                OrgDb.Org o = orgAdapter.getItem(position);
                applyOrg(o);
                dismissOrgPopup();
            });
        }
        orgAdapter = new OrgSuggestAdapter(MainActivity.this, res);
        orgPopup.setAnchorView(et);
        orgPopup.setAdapter(orgAdapter);
        orgPopup.setWidth(Math.max(et.getWidth(), dp(220)));
        orgPopup.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (!orgPopup.isShowing()) orgPopup.show();
    }

    private void dismissOrgPopup() {
        if (orgPopup != null && orgPopup.isShowing()) orgPopup.dismiss();
    }

    /** Выбор из подсказок: подставляем название и все связанные поля. */
    private void applyOrg(OrgDb.Org o) {
        FormField orgField = null;
        for (FormField f : fields) if (f.isOrg()) orgField = f;
        EditText orgEdit = null;
        if (orgField != null) {
            View v = fieldViews.get(orgField.key);
            if (v instanceof EditText) {
                orgEdit = (EditText) v;
                orgEdit.setText(o.name);
                orgEdit.setSelection(orgEdit.getText().length());
            }
        }
        fillOrgMappedFields(o, false);
        hideKeyboardAfterOrgPick(orgEdit);
    }

    /** v2.15: после выбора организации клавиатура прячется до клика по следующему полю ввода. */
    private void hideKeyboardAfterOrgPick(EditText orgEdit) {
        if (orgEdit == null) return;
        orgEdit.clearFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(orgEdit.getWindowToken(), 0);
        }
    }

    /**
     * Автоподстановка из карточки организации.
     * onlyEmpty=true — дозаполняем лишь пустые поля (при потере фокуса),
     * чтобы не затирать ручные правки.
     */
    private void fillOrgMappedFields(OrgDb.Org o, boolean onlyEmpty) {
        for (FormField f : fields) {
            String target = f.orgMapTarget();
            if (target.isEmpty()) continue;
            View v = fieldViews.get(f.key);
            if (!(v instanceof EditText)) continue;
            EditText et = (EditText) v;
            String value = "";
            if ("address".equals(target)) value = o.address;
            else if ("contact".equals(target)) value = o.contact;
            else if ("phone".equals(target)) {
                value = PhoneUtils.normalize(o.phone, settings.getPhoneCc());
            }
            if (value == null || value.isEmpty()) continue;
            if (onlyEmpty && !et.getText().toString().trim().isEmpty()) continue;
            et.setText(value);
        }
    }

    /** Потеря фокуса полем организации: если имя совпало с базой — дозаполняем пустое. */
    private void autofillFromOrgOnBlur(EditText orgEt) {
        OrgDb.Org o = orgDb.byName(orgEt.getText().toString());
        if (o != null) fillOrgMappedFields(o, true);
    }

    private OrgDb.Org currentOrgRow() {
        for (FormField f : fields) {
            if (!f.isOrg()) continue;
            View v = fieldViews.get(f.key);
            if (v instanceof EditText) {
                return orgDb.byName(((EditText) v).getText().toString());
            }
        }
        return null;
    }

    /** Поле контакта: ключ/метка про контакт или ФИО. */
    static boolean isContactField(FormField f) {
        String k = (f.key == null ? "" : f.key).toLowerCase(java.util.Locale.US);
        String l = (f.label == null ? "" : f.label).toLowerCase(java.util.Locale.US);
        return k.contains("contact") || k.contains("fio")
                || l.contains("контакт") || l.contains("фио");
    }

    /** Сохраняем/обновляем организацию из формы в базу (база растёт с заявками). */
    private void upsertOrgFromForm(Map<String, String> values) {
        String name = "", address = "", contact = "", phone = "";
        for (FormField f : fields) {
            String v = values.get(f.key);
            if (v == null) continue;
            if (f.isOrg()) name = v;
            String target = f.orgMapTarget();
            if ("address".equals(target)) address = v;
            else if ("contact".equals(target)) contact = v;
            else if ("phone".equals(target)) phone = v;
        }
        if (!name.isEmpty()) orgDb.upsert(name, address, contact, phone);
        if (!contact.isEmpty()) settings.setLastContact(contact);
    }

    /**
     * Ссылка на точку для адреса заявки: координаты из кэша базы организации,
     * иначе разовый геокодинг (результат кэшируется). Без координат — ссылка-поиск.
     */
    /**
     * v2.24: значение строки «Точка» по режиму:
     * 0 — координаты текстом (Viber не рисует карту), без координат — адрес текстом;
     * 1 — ссылка на Яндекс.Карты (Viber покажет карту-превью);
     * 2 — null (строки «Точка» не будет).
     */
    private String buildPointValue(Map<String, String> values) {
        int mode = settings.getPointMode();
        if (mode == 3) return null;
        String address = "";
        String orgName = "";
        for (FormField f : fields) {
            String v = values.get(f.key);
            if (v == null) continue;
            if (f.isOrg()) orgName = v;
            if ((f.isAddress() || "address".equals(f.orgMapTarget())) && !v.isEmpty()) {
                address = v;
            }
        }
        if (address.isEmpty()) return null;
        if (mode == 0) {
            OrgDb.Org oc = orgName.isEmpty() ? null : orgDb.byName(orgName);
            if (oc != null && (oc.lat != 0 || oc.lon != 0)
                    && NavUtils.inMinskRegion(oc.lat, oc.lon)) {
                return String.format(java.util.Locale.US, "%.5f, %.5f", oc.lat, oc.lon);
            }
            double[] ll0 = NavUtils.geocodeAddress(NavUtils.sanitizeForMap(address));
            if (ll0 != null) {
                return String.format(java.util.Locale.US, "%.5f, %.5f", ll0[0], ll0[1]);
            }
            return NavUtils.sanitizeForMap(address);
        }
        if (mode == 2) {
            OrgDb.Org on = orgName.isEmpty() ? null : orgDb.byName(orgName);
            if (on != null && (on.lat != 0 || on.lon != 0)
                    && NavUtils.inMinskRegion(on.lat, on.lon)) {
                return NavUtils.routeLink(on.lat, on.lon);
            }
            double[] ll2 = NavUtils.geocodeAddress(NavUtils.sanitizeForMap(address));
            if (ll2 != null) return NavUtils.routeLink(ll2[0], ll2[1]);
            return NavUtils.routeSearchLink(address);
        }
        return buildAddressLink(values);
    }

    private String buildAddressLink(Map<String, String> values) {
        String address = "";
        String orgName = "";
        for (FormField f : fields) {
            String v = values.get(f.key);
            if (v == null) continue;
            if (f.isOrg()) orgName = v;
            if ((f.isAddress() || "address".equals(f.orgMapTarget())) && !v.isEmpty()) {
                address = v;
            }
        }
        if (address.isEmpty()) return "";
        OrgDb.Org o = orgName.isEmpty() ? null : orgDb.byName(orgName);
        boolean cached = o != null && (o.lat != 0 || o.lon != 0)
                && NavUtils.inMinskRegion(o.lat, o.lon);
        double lat = cached ? o.lat : 0;
        double lon = cached ? o.lon : 0;
        if (lat == 0 && lon == 0) {
            double[] ll = NavUtils.geocodeAddress(NavUtils.sanitizeForMap(address));
            if (ll != null) {
                lat = ll[0];
                lon = ll[1];
                if (o != null) orgDb.saveCoords(o.id, lat, lon);
            }
        }
        return (lat != 0 || lon != 0)
                ? NavUtils.pointLink(lat, lon)
                : NavUtils.searchLink(address);
    }

    /**
     * v2.28: строка «Точка» для ПРЕДПРОСМОТРА (долгое нажатие «Отправить»):
     * только кэш координат базы — никаких сетевых геокодингов, потому что
     * вызывается с UI-потока (сетевой вызов там = NetworkOnMainThreadException).
     */
    private String buildPointValueCached(Map<String, String> values) {
        int mode = settings.getPointMode();
        if (mode == 3) return null;
        String address = "";
        String orgName = "";
        for (FormField f : fields) {
            String v = values.get(f.key);
            if (v == null) continue;
            if (f.isOrg()) orgName = v;
            if ((f.isAddress() || "address".equals(f.orgMapTarget())) && !v.isEmpty()) {
                address = v;
            }
        }
        if (address.isEmpty()) return null;
        OrgDb.Org o = orgName.isEmpty() ? null : orgDb.byName(orgName);
        boolean cached = o != null && (o.lat != 0 || o.lon != 0)
                && NavUtils.inMinskRegion(o.lat, o.lon);
        if (mode == 0) {
            return cached
                    ? String.format(Locale.US, "%.5f, %.5f", o.lat, o.lon)
                    : NavUtils.sanitizeForMap(address);
        }
        if (mode == 2) {
            return cached ? NavUtils.routeLink(o.lat, o.lon)
                    : NavUtils.routeSearchLink(address);
        }
        return cached ? NavUtils.pointLink(o.lat, o.lon)
                : NavUtils.searchLink(address);
    }

    // ------------------------------------------------------------- маршрут 🧭

    private void openRouteForAddress(EditText addressEt) {
        String addr = addressEt.getText().toString().trim();
        final OrgDb.Org o = currentOrgRow();
        boolean cached = o != null && (o.lat != 0 || o.lon != 0)
                && NavUtils.inMinskRegion(o.lat, o.lon);
        double lat = cached ? o.lat : 0;
        double lon = cached ? o.lon : 0;
        NavUtils.openRoute(this, addr, lat, lon, (la, lo) -> {
            if (o != null) orgDb.saveCoords(o.id, la, lo);
        });
    }

    // ------------------------------------------------------------------ чтение

    private String readValue(FormField f) {
        View v = fieldViews.get(f.key);
        if (v == null) return "";
        if (v instanceof CheckBox) return ((CheckBox) v).isChecked() ? "да" : "нет";
        if (v instanceof Spinner) {
            Object o = ((Spinner) v).getSelectedItem();
            String s = o == null ? "" : o.toString();
            return "—".equals(s) ? "" : s;
        }
        if (v instanceof EditText) {
            if (f.isMultiselect() && v.getTag() instanceof List) {
                return joinList(castList(v.getTag()));
            }
            String raw = ((EditText) v).getText().toString()
                    .replaceAll("[\u200B\u200C\u200D\uFEFF\u00A0]", " ").trim();
            if (f.isPhone()
                    || (!FormField.TYPE_NUMBER.equals(f.type) && PhoneUtils.looksLikePhone(raw))) {
                raw = PhoneUtils.normalize(raw, settings.getPhoneCc());
            }
            return raw;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object tag) {
        return (List<String>) tag;
    }

    private void markError(FormField f, boolean error) {
        markErrorByKey(f.key, error);
    }

    private void markErrorByKey(String key, boolean error) {
        View v = fieldViews.get(key);
        if (v instanceof EditText) {
            v.setBackgroundResource(error ? R.drawable.bg_field_error : R.drawable.bg_field);
        }
    }

    private void clearForm() {
        settings.clearDraft();
        draftRestore = new JSONObject();
        for (FormField f : fields) {
            View v = fieldViews.get(f.key);
            if (v instanceof EditText) {
                ((EditText) v).setText("");
                if (v.getTag() instanceof List) {
                    v.setTag(new ArrayList<String>());
                }
                markError(f, false);
            } else if (v instanceof CheckBox) {
                ((CheckBox) v).setChecked(false);
            } else if (v instanceof Spinner) {
                ((Spinner) v).setSelection(0);
            }
        }
        settings.saveLastValues(new JSONObject());
        Toast.makeText(this, "Форма очищена", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ отправка

    private void onSendClicked() {
        if (sending) return;
        dismissOrgPopup();

        Map<String, String> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (FormField f : fields) {
            String v = readValue(f);
            values.put(f.key, v);
            markError(f, f.required && v.isEmpty());
            if (f.required && v.isEmpty()) missing.add(f.label);
        }
        if (!missing.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_fill, TextUtils.join(", ", missing)),
                    Toast.LENGTH_LONG).show();
            View first = null;
            for (FormField f : fields) {
                if (f.required && values.get(f.key).isEmpty()) {
                    first = fieldViews.get(f.key);
                    break;
                }
            }
            if (first != null) {
                final View target = first;
                scroll.post(() -> scroll.smoothScrollTo(0, target.getTop() - dp(120)));
                target.requestFocus();
            }
            return;
        }

        final String personName = firstPersonValue(values);
        final Map<String, String> valuesCopy = new LinkedHashMap<>(values);

        sending = true;
        btnSend.setEnabled(false);
        btnSend.setText(R.string.sending);
        overlay.setVisibility(View.VISIBLE);
        // черновик не сохраняем: при следующем открытии форма всегда чистая
        settings.saveLastValues(new JSONObject());

        final String mode = settings.getMode();
        new Thread(() -> {
            // база организаций пополняется при каждой отправленной заявке
            try {
                upsertOrgFromForm(valuesCopy);
            } catch (Exception ignored) {
            }

            // строка «Точка»: координаты / ссылка / ничего — по настройке
            try {
                String point = buildPointValue(valuesCopy);
                if (point != null && !point.isEmpty()) valuesCopy.put("address_link", point);
            } catch (Exception ignored) {
            }

            final String text = MessageBuilder.build(settings, valuesCopy);
            final JSONObject valuesJson = new JSONObject(valuesCopy);

            final ViberClient.ApiResult apiResult;
            if (Settings.MODE_API.equals(mode) || Settings.MODE_WEBHOOK.equals(mode)
                    || (Settings.MODE_AUTO.equals(mode) && settings.hasToken())) {
                if (Settings.MODE_WEBHOOK.equals(mode)) {
                    apiResult = sendToWebhook(text, valuesJson);
                } else if (!settings.hasToken()) {
                    apiResult = new ViberClient.ApiResult();
                    apiResult.error = "Токен Viber API не задан";
                } else {
                    apiResult = ViberClient.sendText(settings, text);
                }
            } else {
                apiResult = null;
            }

            runOnUiThread(() -> {
                sending = false;
                overlay.setVisibility(View.GONE);
                btnSend.setEnabled(true);
                btnSend.setText(R.string.btn_send);
                handleResult(mode, apiResult, text, personName);
            });
        }, "viber-send").start();
    }

    /**
     * Долгое нажатие на «Отправить»: показать ТОЧНЫЙ текст, который уйдёт
     * в заявку (со значениями текущей формы; ссылка на точку — из кэша
     * координат, без сетевого геокодинга).
     */
    private void showTextPreview() {
        Map<String, String> values = new LinkedHashMap<>();
        for (FormField f : fields) values.put(f.key, readValue(f));
        // v2.28: предпросмотр берёт координаты ТОЛЬКО из кэша — без сетевых
        // запросов: геокодинг с UI-потока падает с NetworkOnMainThreadException
        String point = buildPointValueCached(values);
        if (point != null && !point.isEmpty()) values.put("address_link", point);
        String text = MessageBuilder.build(settings, values);
        new AlertDialog.Builder(this)
                .setTitle("Текст заявки (предпросмотр)")
                .setMessage(text)
                .setPositiveButton(R.string.btn_copy, (d, w) -> copy(text))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private String firstPersonValue(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.US);
            if ((k.contains("name") || k.contains("fio") || k.contains("org") || k.contains("имя"))
                    && !e.getValue().isEmpty()) {
                return e.getValue();
            }
        }
        for (String v : values.values()) if (!v.isEmpty()) return v;
        return "Заявка";
    }

    private ViberClient.ApiResult sendToWebhook(String text, JSONObject valuesJson) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("company", settings.getCompany());
            payload.put("text", text);
            payload.put("datetime", new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(new Date()));
            payload.put("device", settings.deviceInfo());
            payload.put("fields", valuesJson);
        } catch (Exception ignored) {
        }
        return ViberClient.sendWebhook(settings, payload, settings.getWebhookToken());
    }

    /** Тихая очистка формы (без тоста) — после состоявшейся отправки. */
    private void clearFormSilent() {
        for (FormField f : fields) {
            View v = fieldViews.get(f.key);
            if (v instanceof EditText) {
                ((EditText) v).setText("");
                if (v.getTag() instanceof List) v.setTag(new ArrayList<String>());
                markError(f, false);
            } else if (v instanceof CheckBox) {
                ((CheckBox) v).setChecked(false);
            } else if (v instanceof Spinner) {
                ((Spinner) v).setSelection(0);
            }
        }
    }

    private void handleResult(String mode, ViberClient.ApiResult api, String text, String personName) {
        boolean apiOk = api != null && api.success;
        if (api == null || apiOk) {
            clearFormSilent(); // заявка ушла — форма чистая сразу
            settings.clearDraft();
            draftRestore = new JSONObject();
        }

        if (apiOk) {
            boolean isWebhook = Settings.MODE_WEBHOOK.equals(mode);
            historyDb.add(personName, text, Settings.STATUS_OK, api.describe());
            showSuccessDialog(isWebhook ? getString(R.string.sent_webhook) : getString(R.string.sent_api),
                    api.describe(), text);
            return;
        }

        if (Settings.MODE_API.equals(mode)) {
            String err = api == null ? "API недоступен" : api.describe();
            historyDb.add(personName, text, Settings.STATUS_ERR, err);
            new AlertDialog.Builder(this)
                    .setTitle("Не удалось отправить")
                    .setMessage(err + "\n\nПроверьте токен и режим в «Настройках».")
                    .setPositiveButton(R.string.btn_settings, (d, w) ->
                            startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton(R.string.btn_copy, (d, w) -> copy(text))
                    .setNeutralButton(R.string.close, null)
                    .show();
            return;
        }

        // Режим auto / deeplink / резерв webhook — открываем Viber
        String prefix = "";
        if (api != null && !api.success
                && (Settings.MODE_AUTO.equals(mode) || Settings.MODE_WEBHOOK.equals(mode))) {
            prefix = getString(R.string.api_failed_fallback) + api.describe() + "\n\n";
        }
        historyDb.add(personName, text,
                api == null ? Settings.STATUS_MANUAL : Settings.STATUS_ERR,
                api == null ? "deep link" : api.describe());
        openViberOrShare(text, prefix);
    }

    private void showSuccessDialog(String message, String detail, String text) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sent_title)
                .setMessage(message + "\n\n" + detail)
                .setPositiveButton(R.string.btn_another, (d, w) -> clearForm())
                .setNegativeButton(R.string.btn_copy, (d, w) -> copy(text))
                .setNeutralButton(R.string.btn_history, (d, w) ->
                        startActivity(new Intent(this, HistoryActivity.class)))
                .show();
    }

    // ------------------------------------------------------------------ deep link

    private void openViberOrShare(String text, String errorPrefix) {
        List<Uri> candidates = new ArrayList<>();
        List<Boolean> carries = new ArrayList<>();
        String encoded = Uri.encode(text);

        String chatUri = settings.getChatUri();
        if (!chatUri.isEmpty()) {
            try {
                if (chatUri.contains("?")) {
                    candidates.add(Uri.parse(chatUri + (chatUri.contains("text=") ? "" : "&text=" + encoded)));
                } else {
                    candidates.add(Uri.parse(chatUri).buildUpon().appendQueryParameter("text", text).build());
                }
                carries.add(linkCarriesText(chatUri));
            } catch (Exception ignored) {
            }
        }
        // получатель может быть указан ссылкой на чат — тогда уходим сразу туда
        String receiver = settings.getReceiver();
        if (receiver.startsWith("viber://") || receiver.startsWith("http://")
                || receiver.startsWith("https://")) {
            try {
                if (receiver.contains("?")) {
                    candidates.add(Uri.parse(receiver + (receiver.contains("text=") ? "" : "&text=" + encoded)));
                } else {
                    candidates.add(Uri.parse(receiver).buildUpon().appendQueryParameter("text", text).build());
                }
                carries.add(linkCarriesText(receiver));
            } catch (Exception ignored) {
            }
        }

        // быстрая отправка: сначала пробуем запомненный чат получателя
        if (settings.getFastSend()) {
            for (int i = 0; i < candidates.size(); i++) {
                if (carries.get(i)) {
                    if (tryStart(candidates.get(i))) {
                        Toast.makeText(this, "Чат получателя открыт с готовым текстом заявки — "
                                + "нажмите «Отправить» в Viber.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else {
                    // Сохранена ссылка-приглашение в группу: текст через неё не
                    // передаётся, поэтому АВТО = открыть саму группу, текст в буфере.
                    // Окно контактов Viber в этом случае НЕ показывается.
                    copySilent(text);
                    int method = settings.getGroupMethod();
                    if (method == 0) method = 2; // авто: сразу в сохранённую группу
                    if (method == 2) {
                        AutoPasteService.arm(text, settings.getAutoSend());
                        warnIfServiceDead();
                        if (tryStart(candidates.get(i))) {
                            if (Settings.isAutoPasteOn(this)) {
                                Toast.makeText(this, "Группа открыта — текст заявки вставится сам.",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Группа открыта, текст в буфере: удерживайте поле ввода → «Вставить». "
                                        + "Авто-вставку можно включить в «Настройках».",
                                        Toast.LENGTH_LONG).show();
                            }
                            return;
                        }
                    }
                    if (method == 1) {
                        if (!shareToViber(text)) {
                            tryStart(Uri.parse("viber://forward?text=" + Uri.encode(text)));
                        }
                        Toast.makeText(this, "Текст заявки готов в Viber — выберите группу и отправьте.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showGroupSendDialog(text, candidates.get(i));
                    return;
                }
            }
        }

        // быстрая отправка выключена, но сохранена ссылка-приглашение:
        // не показываем окно контактов молча — даём явный выбор
        if (!settings.getFastSend()) {
            for (int i = 0; i < candidates.size(); i++) {
                if (!carries.get(i)) {
                    copySilent(text);
                    showGroupSendDialog(text, candidates.get(i));
                    return;
                }
            }
        }

        if (shareToViber(text)) {
            hintSaveReceiver();
            if (!errorPrefix.isEmpty()) {
                Toast.makeText(this, errorPrefix.trim(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        candidates.add(Uri.parse("viber://forward?text=" + encoded));
        String phone = PhoneUtils.normalize(settings.getPhone(), settings.getPhoneCc());
        if (!phone.isEmpty()) {
            candidates.add(Uri.parse("viber://contact?number=" + Uri.encode(phone) + "&text=" + encoded));
        }

        for (Uri u : candidates) {
            if (tryStart(u)) {
                if (!errorPrefix.isEmpty()) {
                    Toast.makeText(this, errorPrefix.trim(), Toast.LENGTH_LONG).show();
                }
                return;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Viber не открылся")
                .setMessage((errorPrefix.isEmpty() ? "" : errorPrefix)
                        + "Приложение Viber не найдено на устройстве.\n\n"
                        + "Отправьте текст через любой мессенджер («Поделиться») или скопируйте его:\n\n"
                        + text)
                .setPositiveButton("Поделиться", (d, w) -> share(text))
                .setNegativeButton(R.string.btn_copy, (d, w) -> copy(text))
                .setNeutralButton(R.string.close, null)
                .show();
    }

    /**
     * Отправка через системный Intent «Поделиться» прямо в Viber: текст передаётся
     * в EXTRA_TEXT целиком, без ограничений на длину URI (deep link мог обрезать).
     */
    private boolean shareToViber(String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.setPackage("com.viber.voip");
            i.putExtra(Intent.EXTRA_TEXT, text);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Диалог для получателя-группы (ссылка-приглашение): Viber не разрешает
     * фоновую отправку в обычную группу, поэтому даём два явных пути.
     */
    private void showGroupSendDialog(final String text, final Uri inviteUri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(8), pad, 0);

        TextView steps = new TextView(this);
        steps.setTextColor(getResources().getColor(R.color.text_secondary));
        steps.setTextSize(13.5f);
        box.addView(steps);

        TextView body = new TextView(this);
        body.setTextColor(getResources().getColor(R.color.text_primary));
        body.setTextSize(13f);
        body.setText(text);
        body.setBackgroundResource(R.drawable.bg_field);
        int p2 = dp(10);
        body.setPadding(p2, p2, p2, p2);
        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        lp.topMargin = dp(10);
        sc.setLayoutParams(lp);
        box.addView(sc);

        steps.setText("Обычная группа Viber не принимает сообщения в фоне: это запрещает сам Viber "
                + "(даже с токеном API — токен даёт фон только для личных чатов и рассылок). "
                + "Поэтому отправка в группу = текст готов → два касания в Viber.\n\n"
                + "1. «Отправить через Viber» — Viber откроется с готовым текстом (текст ещё и скопирован): "
                + "выберите группу → отправить.\n"
                + "2. «Открыть группу» — откроется группа, текст в буфере: удерживайте поле ввода → «Вставить» → отправить.\n\n"
                + "Выбор запомнится навсегда. Изменить: Настройки → Deep link → «Способ отправки в группу».\n\n"
                + "Текст заявки (уйдёт дословно):");
        new AlertDialog.Builder(this)
                .setTitle("Отправка в группу · v" + appVersion())
                .setView(box)
                .setPositiveButton("1. Отправить через Viber", (d, w) -> {
                    settings.setGroupMethod(1);
                    if (!shareToViber(text)) {
                        tryStart(Uri.parse("viber://forward?text=" + Uri.encode(text)));
                    }
                })
                .setNegativeButton("2. Открыть группу", (d, w) -> {
                    settings.setGroupMethod(2);
                    AutoPasteService.arm(text, settings.getAutoSend());
                    warnIfServiceDead();
                    tryStart(inviteUri);
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    /**
     * Умеет ли ссылка получателя нести текст заявки: deep link Viber с
     * параметром текста — да; http(s)-ссылки (приглашения в группу и т.п.) — нет.
     */
    private static boolean linkCarriesText(String link) {
        if (link == null) return false;
        String l = link.toLowerCase(Locale.US);
        if (!l.startsWith("viber://")) return false;
        return l.contains("chaturi") || l.contains("contact?") || l.contains("forward?")
                || l.contains("text=") || l.contains("chat?");
    }

    /** Подсказка: как запомнить получателя навсегда. */
    private void hintSaveReceiver() {
        if (settings.getChatUri().isEmpty() && settings.getReceiver().isEmpty()) {
            Toast.makeText(this, "Чтобы заявки уходили одному и тому же чату без выбора: "
                    + "скопируйте ссылку группы в Viber и вставьте в «Настройки → Получатель».",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Тихое копирование в буфер обмена (без тоста). */
    private void copySilent(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Заявка", text));
        } catch (Exception ignored) {
        }
    }

    private boolean tryStart(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void share(String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            i.putExtra(Intent.EXTRA_SUBJECT, settings.getCompany());
            startActivity(Intent.createChooser(i, "Отправить заявку через"));
        } catch (Exception e) {
            copy(text);
        }
    }

    public void copy(String text) {
        MainActivity.copyToClipboard(this, text);
    }

    public static void copyToClipboard(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Заявка", text));
            Toast.makeText(ctx, "Текст заявки скопирован", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    public static void openExternal(Context ctx, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("viber://forward?text=" + Uri.encode(text)));
            ctx.startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, text);
            ctx.startActivity(Intent.createChooser(s, "Отправить заявку через"));
        } catch (Exception e) {
            Toast.makeText(ctx, "Не удалось открыть приложение для отправки", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ адаптеры

    /** Двухстрочные подсказки живого поиска организаций. */
    private static class OrgSuggestAdapter extends BaseAdapter {
        private final List<OrgDb.Org> items;
        private final LayoutInflater inflater;
        private final int colorPrimary;
        private final int colorSecondary;

        OrgSuggestAdapter(Context ctx, List<OrgDb.Org> items) {
            this.items = items;
            this.inflater = LayoutInflater.from(ctx);
            this.colorPrimary = ctx.getResources().getColor(R.color.text_primary);
            this.colorSecondary = ctx.getResources().getColor(R.color.text_secondary);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public OrgDb.Org getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            OrgDb.Org o = getItem(position);
            TextView t1 = v.findViewById(android.R.id.text1);
            TextView t2 = v.findViewById(android.R.id.text2);
            t1.setText(o.name);
            t1.setTextColor(colorPrimary);
            t2.setText(o.subtitle().isEmpty() ? "— контактов пока нет —" : o.subtitle());
            t2.setTextColor(colorSecondary);
            return v;
        }
    }

    /** Минимальный TextWatcher: интересует только текст. */
    private interface OnTextChanged {
        void onChanged(String text);
    }

    private static class SimpleWatcher implements TextWatcher {
        private final OnTextChanged cb;

        SimpleWatcher(OnTextChanged cb) {
            this.cb = cb;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
            cb.onChanged(s.toString());
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }

    private LinearLayout.LayoutParams inputLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * v2.26: если служба авто-вставки включена в настройках, но процесс службы
     * не подключён (MIUI убил / Viber обновился), показываем диалог-подсказку.
     */
    private void warnIfServiceDead() {
        if (!Settings.isAutoPasteOn(this) || AutoPasteService.isConnected()) return;
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Служба авто-вставки не запущена")
                .setMessage("Android остановил службу авто-вставки (экономия батареи MIUI "
                        + "или обновление Viber). Откройте спецвозможности: выключите службу "
                        + "«ViberLead» и включите снова — авто-вставка заработает. "
                        + "Сейчас текст останется в буфере обмена.\n"
                        + "Диагностика: " + AutoPasteService.getStatus())
                .setPositiveButton("Открыть спецвозможности", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Настройки Android → Спецвозможности",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show());
    }

    private void openExternal(String uri) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(uri)));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть: " + uri, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
