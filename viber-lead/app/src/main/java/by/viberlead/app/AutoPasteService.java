package by.viberlead.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.AccessibilityService;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Служба специальных возможностей: после того как приложение открыло группу
 * Viber, служба находит поле ввода сообщения и сама вставляет текст заявки
 * (ACTION_SET_TEXT), а при включённой опции — нажимает кнопку отправки.
 *
 * Работает 30 секунд после «взвода» (кнопка «Отправить» в форме) и
 * только в окне com.viber.voip. Без взвода служба ничего не трогает.
 */
public class AutoPasteService extends AccessibilityService {

    private static volatile String pendingText = null;
    private static volatile boolean pendingSend = false;
    private static volatile long armedAt = 0;
    private static volatile boolean connected = false;

    /** Подключена ли служба к системе прямо сейчас (в нашем процессе). */
    public static boolean isConnected() {
        return connected;
    }

    private static volatile String status = "служба не взводилась";
    private static volatile int lastScanNodes = 0;
    private static volatile String lastScanClasses = "";

    /** Диагностика последнего цикла авто-вставки (видна в настройках). */
    public static String getStatus() {
        return status;
    }

    private static String now() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** Взводит авто-вставку: следующий открытый чат Viber получит текст. */
    public static void arm(String text, boolean autoSend) {
        pendingText = text;
        pendingSend = autoSend;
        armedAt = System.currentTimeMillis();
        lastScanNodes = 0;
        lastScanClasses = "";
        status = "взвод " + now() + (connected ? ", служба подключена" : ", СЛУЖБА НЕ ПОДКЛЮЧЕНА");
    }

    public static boolean armed() {
        return pendingText != null && System.currentTimeMillis() - armedAt < 30000;
    }

    @Override
    public void onServiceConnected() {
        connected = true;
        super.onServiceConnected();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!armed()) {
            // окно взвода истекло без вставки: фиксируем диагноз по последнему скану
            if (pendingText != null && lastScanNodes > 0
                    && System.currentTimeMillis() - armedAt < 60000) {
                status = "поле не найдено (" + now() + "): узлов " + lastScanNodes
                        + ", классы: " + lastScanClasses;
                pendingText = null;
            }
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.viber.voip".contentEquals(pkg)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String text = pendingText;
        if (text == null) return;
        if (pasteIntoEditText(root, text)) {
            status = "вставлено " + now();
            pendingText = null;
            if (pendingSend) {
                pendingSend = false;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    AccessibilityNodeInfo r2 = getRootInActiveWindow();
                    if (r2 != null) {
                        boolean clicked = clickSendButton(r2);
                        status = clicked ? "вставлено и отправлено " + now()
                                : "вставлено " + now() + ", кнопка отправки не найдена";
                    }
                }, 500);
            }
        }
    }

    /** Ищет редактируемый EditText и ставит в него текст заявки. */
    private static boolean pasteIntoEditText(AccessibilityNodeInfo root, String text) {
        AccessibilityNodeInfo field = findFirstEditable(root);
        if (field == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        try {
            field.recycle();
        } catch (Exception ignored) {
        }
        return ok;
    }

    private static AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        int guard = 0;
        int scanned = 0;
        java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
        AccessibilityNodeInfo hintFallback = null;
        while (!stack.isEmpty() && guard++ < 1500) {
            AccessibilityNodeInfo n = stack.pop();
            if (n == null) continue;
            scanned++;
            CharSequence cls = n.getClassName();
            String clsLow = cls == null ? "" : cls.toString().toLowerCase(java.util.Locale.US);
            if (classes.size() < 5 && (n.isFocusable() || n.isClickable()) && !clsLow.isEmpty()) {
                classes.add(clsLow);
            }
            boolean editClass = clsLow.contains("edittext");
            // v2.26: ловим и подклассы EditText, и кастомные редактируемые узлы Viber
            if (n.isEnabled() && ((editClass && (n.isEditable() || n.isFocusable()))
                    || (!editClass && n.isEditable() && n.isFocusable()))) {
                lastScanNodes = scanned;
                lastScanClasses = String.join(", ", classes);
                return n;
            }
            // v2.27: запасной кандидат — узел с подсказкой поля ввода (hint)
            if (hintFallback == null && n.isEnabled() && n.isFocusable()
                    && n.getHintText() != null && clsLow.contains("text")) {
                hintFallback = n;
            }
            int cnt = n.getChildCount();
            for (int i = 0; i < cnt; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        lastScanNodes = scanned;
        lastScanClasses = String.join(", ", classes);
        return hintFallback;
    }

    /** Ищет кнопку отправки по подписи (Отправить / Send) и нажимает её. */
    private static boolean clickSendButton(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 400) {
            AccessibilityNodeInfo n = stack.pop();
            if (n == null) continue;
            if (n.isClickable()) {
                CharSequence desc = n.getContentDescription();
                CharSequence txt = n.getText();
                CharSequence id = n.getViewIdResourceName();
                String d = (desc == null ? "" : desc.toString())
                        + " " + (txt == null ? "" : txt.toString())
                        + " " + (id == null ? "" : id.toString());
                d = d.toLowerCase(Locale.US);
                if (d.contains("отправ") || d.contains("send")) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
            int cnt = n.getChildCount();
            for (int i = 0; i < cnt; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
