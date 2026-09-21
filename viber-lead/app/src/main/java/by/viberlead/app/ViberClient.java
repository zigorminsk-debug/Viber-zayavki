package by.viberlead.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Клиент Viber REST API (Public Account / Business).
 * Без внешних библиотек — только HttpURLConnection и org.json из Android SDK.
 *
 * Методы:
 *   POST {api}/send_message      — личное сообщение подписчику (нужен receiver)
 *   POST {api}/broadcast_message — рассылка подписчикам (broadcast_list, пусто = всем)
 *   POST {api}/get_account_info  — проверка токена
 *
 * ВАЖНО: Viber API умеет писать только тем, кто подписался на ваш Public Account
 * (или в чат с бизнес-ботом). Произвольную «группу» по номеру телефона API недоступен —
 * для этого в приложении есть режим «Открыть Viber» (deep link).
 */
public class ViberClient {

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;

    public static class ApiResult {
        public boolean success;
        public int httpCode;
        public int status = -1;
        public String statusMessage = "";
        public String messageToken = "";
        public String rawBody = "";
        public String error = "";

        public String describe() {
            if (success) {
                return "status=0, message_token=" + messageToken;
            }
            StringBuilder sb = new StringBuilder();
            if (!error.isEmpty()) sb.append(error);
            if (httpCode > 0) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("HTTP ").append(httpCode);
            }
            if (status >= 0) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("Viber status=").append(status);
            }
            if (!statusMessage.isEmpty()) {
                if (sb.length() > 0) sb.append(": ");
                sb.append(statusMessage);
            }
            return sb.length() == 0 ? "неизвестная ошибка" : sb.toString();
        }
    }

    /** Проверяет токен: get_account_info. */
    public static ApiResult checkToken(String apiUrl, String token) {
        return call(apiUrl, "/get_account_info", token, new JSONObject());
    }

    /**
     * Отправляет текст заявки в Viber.
     * Если задан receiver — send_message, иначе broadcast_message.
     */
    public static ApiResult sendText(Settings s, String text) {
        String receiver = s.getReceiver();
        if (!receiver.isEmpty()) {
            JSONObject body = new JSONObject();
            put(body, "receiver", receiver);
            put(body, "min_api_version", 1);
            put(body, "sender", sender(s));
            JSONObject msg = new JSONObject();
            put(msg, "type", "text");
            put(msg, "text", text);
            put(body, "message", msg);
            return call(s.getApiUrl(), "/send_message", s.getToken(), body);
        }

        JSONObject body = new JSONObject();
        List<String> bl = s.getBroadcastList();
        if (!bl.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String id : bl) arr.put(id);
            put(body, "broadcast_list", arr);
        }
        put(body, "min_api_version", 1);
        put(body, "sender", sender(s));
        JSONObject msg = new JSONObject();
        put(msg, "type", "text");
        put(msg, "text", text);
        put(body, "message", msg);
        return call(s.getApiUrl(), "/broadcast_message", s.getToken(), body);
    }

    private static JSONObject sender(Settings s) {
        JSONObject sender = new JSONObject();
        put(sender, "name", s.getCompany());
        put(sender, "avatar", "");
        return sender;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    /** Универсальный POST c заголовком X-Viber-Auth-Token. */
    public static ApiResult call(String apiUrl, String path, String token, JSONObject body) {
        ApiResult r = new ApiResult();
        HttpURLConnection conn = null;
        try {
            String base = apiUrl;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            URL url = new URL(base + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("X-Viber-Auth-Token", token.trim());
            }

            byte[] payload = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            r.httpCode = conn.getResponseCode();
            InputStream is = (r.httpCode >= 200 && r.httpCode < 400)
                    ? conn.getInputStream() : conn.getErrorStream();
            r.rawBody = readAll(is);

            if (r.rawBody != null && !r.rawBody.isEmpty()) {
                try {
                    JSONObject resp = new JSONObject(r.rawBody);
                    r.status = resp.optInt("status", -1);
                    r.statusMessage = resp.optString("status_message", "");
                    r.messageToken = resp.optString("message_token", "");
                    r.success = (r.status == 0);
                    if (!r.success) {
                        r.error = "Viber вернул ошибку";
                        if (r.status == 2) r.error = "Токен недействителен (status=2)";
                        if (r.status == 8) r.error = "Приёмник не найден / не подписан (status=8)";
                        if (r.status == 13) r.error = "Токен не для этого аккаунта (status=13)";
                    }
                    return r;
                } catch (Exception parseErr) {
                    r.error = "Ответ не похож на JSON: " + truncate(r.rawBody, 200);
                    r.success = false;
                    return r;
                }
            }
            r.success = r.httpCode >= 200 && r.httpCode < 300;
            if (!r.success) r.error = "Пустой ответ сервера";
            return r;
        } catch (java.net.UnknownHostException e) {
            r.error = "Не удалось найти сервер (нет интернета?)";
            return r;
        } catch (java.net.SocketTimeoutException e) {
            r.error = "Сервер не ответил вовремя (таймаут)";
            return r;
        } catch (javax.net.ssl.SSLException e) {
            r.error = "Ошибка TLS: " + e.getMessage();
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Отправка JSON на произвольный webhook (n8n, Make, Zapier, свой сервер). */
    public static ApiResult sendWebhook(Settings s, JSONObject payload, String webhookTokenHeader) {
        ApiResult r = new ApiResult();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(s.getWebhookUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (webhookTokenHeader != null && !webhookTokenHeader.trim().isEmpty()) {
                conn.setRequestProperty("X-Auth-Token", webhookTokenHeader.trim());
            }
            byte[] b = payload.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(b.length);
            OutputStream os = conn.getOutputStream();
            os.write(b);
            os.flush();
            os.close();
            r.httpCode = conn.getResponseCode();
            InputStream is = (r.httpCode >= 200 && r.httpCode < 400)
                    ? conn.getInputStream() : conn.getErrorStream();
            r.rawBody = readAll(is);
            r.success = r.httpCode >= 200 && r.httpCode < 300;
            if (!r.success) r.error = "Webhook вернул HTTP " + r.httpCode;
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            char[] buf = new char[2048];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
