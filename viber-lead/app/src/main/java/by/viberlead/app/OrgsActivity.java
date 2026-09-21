package by.viberlead.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Справочник организаций: просмотр, ручное добавление, правка, удаление,
 * маршрут в Яндекс Навигаторе. База пополняется автоматически при отправке заявок.
 */
public class OrgsActivity extends Activity {

    private OrgDb db;
    private Settings settings;
    private LinearLayout container;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_orgs);
        db = new OrgDb(this);
        settings = new Settings(this);
        container = findViewById(R.id.orgs_container);
        tvEmpty = findViewById(R.id.tv_empty);

        Button btnBack = findViewById(R.id.btn_back);
        Button btnAdd = findViewById(R.id.btn_add);
        btnBack.setOnClickListener(v -> finish());
        btnAdd.setOnClickListener(v -> showEditDialog(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        container.removeAllViews();
        List<OrgDb.Org> items = db.all();
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final OrgDb.Org o : items) {
            View v = inflater.inflate(R.layout.item_org, container, false);
            TextView name = v.findViewById(R.id.org_name);
            TextView addr = v.findViewById(R.id.org_address);
            TextView contacts = v.findViewById(R.id.org_contacts);
            Button route = v.findViewById(R.id.org_route);
            Button edit = v.findViewById(R.id.org_edit);
            Button del = v.findViewById(R.id.org_delete);

            name.setText(o.name);
            addr.setText(o.address.isEmpty() ? "адрес не указан" : "📍 " + o.address);
            StringBuilder c = new StringBuilder();
            if (!o.contact.isEmpty()) c.append("👤 ").append(o.contact);
            if (!o.phone.isEmpty()) {
                if (c.length() > 0) c.append("   ");
                c.append("📞 ").append(o.phone);
            }
            contacts.setText(c.toString());
            contacts.setVisibility(c.length() == 0 ? View.GONE : View.VISIBLE);

            route.setOnClickListener(x -> NavUtils.openRoute(this, o.address, o.lat, o.lon,
                    (lat, lon) -> db.saveCoords(o.id, lat, lon)));
            edit.setOnClickListener(x -> showEditDialog(o));
            del.setOnClickListener(x -> new AlertDialog.Builder(this)
                    .setTitle("Удалить организацию?")
                    .setMessage(o.name + "\n" + o.address)
                    .setPositiveButton("Удалить", (d, w) -> {
                        db.delete(o.id);
                        render();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show());

            container.addView(v);
        }
    }

    /** Диалог добавления/правки карточки организации. */
    private void showEditDialog(final OrgDb.Org existing) {
        final EditText edName = new EditText(this);
        final EditText edAddr = new EditText(this);
        final EditText edContact = new EditText(this);
        final EditText edPhone = new EditText(this);
        for (EditText e : new EditText[]{edName, edAddr, edContact, edPhone}) {
            e.setBackgroundResource(R.drawable.bg_field);
            e.setPadding(dp(12), dp(11), dp(12), dp(11));
            e.setTextColor(getResources().getColor(R.color.text_primary));
        }
        edName.setHint("Название организации *");
        edAddr.setHint("Адрес (улица, дом)");
        edContact.setHint("Контактное лицо");
        edPhone.setHint("Телефон");
        if (existing != null) {
            edName.setText(existing.name);
            edAddr.setText(existing.address);
            edContact.setText(existing.contact);
            edPhone.setText(existing.phone);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        addLabeled(box, edName, "Название *");
        addLabeled(box, edAddr, "Адрес");
        addLabeled(box, edContact, "Контактное лицо");
        addLabeled(box, edPhone, "Телефон");

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Новая организация" : "Правка организации")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String name = edName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Укажите название", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String phone = PhoneUtils.normalize(
                            edPhone.getText().toString(), settings.getPhoneCc());
                    if (existing == null) {
                        db.upsert(name,
                                edAddr.getText().toString().trim(),
                                edContact.getText().toString().trim(),
                                phone);
                    } else {
                        existing.name = name;
                        existing.address = edAddr.getText().toString().trim();
                        existing.contact = edContact.getText().toString().trim();
                        existing.phone = phone;
                        db.update(existing);
                    }
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addLabeled(LinearLayout box, EditText input, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setPadding(0, dp(10), 0, dp(4));
        box.addView(tv);
        box.addView(input);
    }

    private int dp(int value) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
