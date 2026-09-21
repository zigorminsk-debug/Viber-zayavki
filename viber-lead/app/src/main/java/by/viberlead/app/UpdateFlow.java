package by.viberlead.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * v2.28: сценарий автообновления из GitHub Releases.
 *
 * Автопроверка при запуске (если включена и с последней проверки прошло
 * ≥6 часов) → найдена новая версия → APK скачивается САМ (с прогрессом) →
 * запускается системный установщик (Android в любом случае покажет экран
 * «Установить» — это ограничение ОС, а не приложения).
 *
 * Ручная проверка — «Настройки → Обновление → Проверить новую версию».
 * Для приватного репозитория в настройках нужен токен GitHub.
 */
public final class UpdateFlow {

    private static volatile boolean busy = false;

    private UpdateFlow() {
    }

    // ------------------------------------------------------------- автопроверка

    /**
     * Тихая проверка при старте приложения. Дёшево: если включена и не было
     * проверки за последние 6 часов — фон-тред спрашивает GitHub, иначе сразу
     * возвращаемся. При нахождении новой версии — сразу скачиваем и ставим.
     */
    public static void autoCheckOnStartup(final Activity activity) {
        final Context app = activity.getApplicationContext();
        final Settings s = new Settings(app);
        if (!s.isUpdateAuto() || busy) return;
        long last = s.getLastUpdateCheck();
        if (last > 0 && System.currentTimeMillis() - last < Settings.UPDATE_CHECK_INTERVAL_MS) return;
        new Thread(() -> {
            final Updater.Latest r = Updater.checkLatest(app, s.getUpdateRepo(), s.getUpdateToken());
            s.setLastUpdateCheck(System.currentTimeMillis());
            cleanupOldApks(app);
            new Handler(Looper.getMainLooper()).post(() -> onCheckResult(activity, s, r, true));
        }, "update-check").start();
    }

    // ------------------------------------------------------------- ручная проверка

    /**
     * Ручная проверка из «Настройки → Обновление»: статус пишется в
     * statusView, по итогу — диалог с результатом.
     */
    public static void manualCheck(final Activity activity, final TextView statusView) {
        if (busy) {
            Toast.makeText(activity, "Проверка обновления уже выполняется", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        if (statusView != null) statusView.setText("Проверяю новую версию…");
        final Context app = activity.getApplicationContext();
        final Settings s = new Settings(app);
        new Thread(() -> {
            final Updater.Latest r = Updater.checkLatest(app, s.getUpdateRepo(), s.getUpdateToken());
            s.setLastUpdateCheck(System.currentTimeMillis());
            cleanupOldApks(app);
            new Handler(Looper.getMainLooper()).post(() -> {
                busy = false;
                if (statusView != null && !activity.isFinishing()) {
                    if (!r.ok) {
                        statusView.setText("⚠️ " + r.error);
                    } else {
                        statusView.setText("Последняя версия на GitHub: " + r.display()
                                + (isNewerThanLocal(app, r) ? " — доступна!" : " — у вас актуальная"));
                    }
                }
                if (!activity.isFinishing()) onCheckResult(activity, s, r, false);
            });
        }, "update-check").start();
    }

    // ------------------------------------------------------------- общий результат

    private static boolean isNewerThanLocal(Context app, Updater.Latest r) {
        return UpdateUtil.isNewer(r.versionCode, Updater.localVersionCode(app),
                r.versionName, Updater.localVersionName(app));
    }

    private static void onCheckResult(Activity activity, Settings s, Updater.Latest r, boolean auto) {
        if (!r.ok) {
            if (auto) {
                // Тихий режим: не дёргаем за каждую сетевую ошибку. Единственный
                // полезный сигнал — 404 (приватный репозиторий без токена).
                if (r.error.contains("404")) {
                    Toast.makeText(activity,
                            "📦 Автообновление: репозиторий приватный — введите токен GitHub "
                                    + "(Настройки → Обновление → Токен)",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                new AlertDialog.Builder(activity)
                        .setTitle("Обновление")
                        .setMessage(r.error + "\n\nРепозиторий: " + s.getUpdateRepo())
                        .setNeutralButton(R.string.close, null)
                        .show();
            }
            return;
        }
        if (!isNewerThanLocal(activity, r)) {
            if (!auto) {
                new AlertDialog.Builder(activity)
                        .setTitle("Обновление")
                        .setMessage("У вас актуальная версия: " + Updater.localVersionName(activity))
                        .setNeutralButton(R.string.close, null)
                        .show();
            }
            return;
        }
        // новая версия найдена
        if (auto) {
            // автообновление: сразу скачиваем и открываем установщик
            startDownload(activity, s, r);
        } else {
            new AlertDialog.Builder(activity)
                    .setTitle("Доступна новая версия")
                    .setMessage("На GitHub: " + r.display()
                            + "\nУ вас: " + Updater.localVersionName(activity)
                            + (Updater.localVersionCode(activity) > 0
                                ? " (code " + Updater.localVersionCode(activity) + ")" : "")
                            + "\n\nСкачать новый APK и открыть установщик?")
                    .setPositiveButton("Скачать и установить", (d, w) -> startDownload(activity, s, r))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    // ------------------------------------------------------------- скачивание

    private static void startDownload(final Activity activity, final Settings s,
                                      final Updater.Latest r) {
        if (busy) return;
        busy = true;

        final TextView tv = new TextView(activity);
        final int pad = dp(activity, 20);
        tv.setPadding(pad, dp(activity, 16), pad, dp(activity, 6));
        tv.setTextSize(14);
        tv.setText("Скачиваю " + r.apkName + "…");
        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), 0);
        box.addView(tv);
        box.addView(bar);

        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("Обновление до " + r.display())
                .setView(box)
                .setCancelable(false)
                .show();

        final Context app = activity.getApplicationContext();
        final String token = s.getUpdateToken();
        new Thread(() -> {
            final File apk = Updater.downloadApkAuth(app, r.apkUrl, token, r.apkName, r.apkSize,
                    (done, total) -> new Handler(Looper.getMainLooper()).post(() -> {
                        if (total > 0) {
                            bar.setProgress((int) (done * 1000 / total));
                            tv.setText(String.format(Locale.US, "Скачиваю… %d%% (%s / %s)",
                                    done * 100 / total, fmtKb(done), fmtKb(total)));
                        } else {
                            tv.setText("Скачиваю… " + fmtKb(done));
                        }
                    }));
            new Handler(Looper.getMainLooper()).post(() -> {
                busy = false;
                try {
                    dlg.dismiss();
                } catch (Exception ignored) {
                }
                if (activity.isFinishing()) return;
                if (apk == null) {
                    Toast.makeText(activity,
                            "Не удалось скачать обновление. Попробуйте позже: Настройки → Обновление.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                installDownloaded(activity, apk);
            });
        }, "update-download").start();
    }

    // ------------------------------------------------------------- установка

    /**
     * Установить уже скачанный APK (кнопка «Установить скачанную версию»).
     */
    public static void installDownloaded(Activity activity) {
        File dir = Updater.apkDir(activity);
        File best = null;
        String bestVn = "";
        String localName = Updater.localVersionName(activity);
        String[] names = dir.list();
        if (names != null) {
            for (String n : names) {
                if (!n.toLowerCase(Locale.US).endsWith(".apk")) continue;
                String vn = UpdateUtil.versionNameFromAsset(n);
                if (vn.isEmpty() || !UpdateUtil.versionNameIsNewer(vn, localName)) continue;
                if (best == null || UpdateUtil.versionNameIsNewer(vn, bestVn)) {
                    best = new File(dir, n);
                    bestVn = vn;
                }
            }
        }
        installDownloaded(activity, best);
    }

    /**
     * Запустить установщик для конкретного APK. Если Android 8+ ещё не дал
     * разрешение «устанавливать неизвестные приложения» — открываем страницу
     * разрешения и даём кнопку «Разрешил — установить» (файл уже скачан,
     * повтор будет в один тап).
     */
    private static void installDownloaded(Activity activity, File apk) {
        if (apk == null) {
            Toast.makeText(activity, "Скачанной новой версии нет — сначала проверьте обновление.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Updater.launchInstaller(activity, apk)) return;
        new AlertDialog.Builder(activity)
                .setTitle("Нужно разрешение")
                .setMessage("Android 8+ требует разрешить этому приложению «устанавливать "
                        + "неизвестные приложения». Открываю страницу настроек: включите "
                        + "переключатель для «" + activity.getString(R.string.app_name) + "», "
                        + "затем нажмите кнопку ниже.\n\nНовая версия уже скачана ("
                        + apk.getName() + ") — установка в один тап.")
                .setPositiveButton("Я разрешил — установить", (d, w) -> {
                    if (!Updater.launchInstaller(activity, apk)) {
                        Updater.openUnknownSources(activity);
                        Toast.makeText(activity,
                                "Разрешение ещё не включено — повторите на странице настроек",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
        Updater.openUnknownSources(activity);
    }

    // ------------------------------------------------------------- служебное

    /** Удаляет скачанные APK, которые не новее текущей версии (чистим после проверки). */
    static void cleanupOldApks(Context ctx) {
        try {
            File dir = Updater.apkDir(ctx);
            String[] names = dir.list();
            if (names == null) return;
            String localName = Updater.localVersionName(ctx);
            for (String n : names) {
                String vn = UpdateUtil.versionNameFromAsset(n);
                if (!vn.isEmpty() && !UpdateUtil.versionNameIsNewer(vn, localName)) {
                    new File(dir, n).delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String fmtKb(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f МБ", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%d КБ", Math.max(1, bytes / 1024));
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
