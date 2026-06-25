package com.apk.claw.android.agent.langchain.http;

import com.apk.claw.android.utils.XLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * OkHttp 拦截器：将每次请求的原始数据、curl 命令和原始响应写入沙盒缓存目录的独立文件。
 * <p>
 * 文件路径: {cacheDir}/http_logs/yyyyMMdd_HHmmssSSS_{method}.txt
 */
public class FileLoggingInterceptor implements Interceptor {

    private static final String TAG = "FileLoggingInterceptor";

    /** 敏感请求/响应头：写日志时只保留前缀，避免把 token / cookie / api-key 落盘。 */
    private static final java.util.Set<String> SENSITIVE_HEADERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-auth-token", "x-goog-api-key"
    ));

    private static String redactHeaderValue(String name, String value) {
        if (name != null && value != null && SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.US))) {
            return value.length() > 8 ? value.substring(0, 8) + "...[REDACTED]" : "[REDACTED]";
        }
        return value;
    }

    /** 敏感 body 模式：匹配 "key":"value" 或 key=value 形式的密钥/令牌/密码字段。 */
    private static final java.util.regex.Pattern SENSITIVE_BODY_PATTERN =
        java.util.regex.Pattern.compile(
            "(?i)(\"(?:api[_-]?key|apikey|secret|token|password|passwd|pwd|authorization|auth[_-]?token|private[_-]?key|access[_-]?token|refresh[_-]?token)\"\\s*:\\s*\")([^\"]*)(\")"
        );
    private static final java.util.regex.Pattern SENSITIVE_BODY_PATTERN_FORM =
        java.util.regex.Pattern.compile(
            "(?i)((?:api[_-]?key|apikey|secret|token|password|passwd|pwd|authorization|auth[_-]?token|private[_-]?key|access[_-]?token|refresh[_-]?token)=)([^&\\s]*)"
        );

    /** 对 body 中的敏感字段值做脱敏（保留字段名，替换值为 [REDACTED]）。 */
    private static String redactBody(String body) {
        if (body == null || body.isEmpty()) return body;
        String result = SENSITIVE_BODY_PATTERN.matcher(body).replaceAll("$1[REDACTED]$3");
        result = SENSITIVE_BODY_PATTERN_FORM.matcher(result).replaceAll("$1[REDACTED]");
        return result;
    }

    private final File logDir;

    public FileLoggingInterceptor(File cacheDir) {
        this.logDir = new File(cacheDir, "http_logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // 读取请求体
        String requestBodyStr = "";
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            requestBodyStr = buffer.readUtf8();
        }

        // 构建 curl 命令
        String curl = buildCurl(request, requestBodyStr);

        // 执行请求
        long startMs = System.nanoTime();
        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            // 请求失败也记录
            writeToFile(request, requestBodyStr, curl, null, -1, e.toString(), 0);
            throw e;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

        // 读取响应体（需要重新包装以便后续消费）
        String responseBodyStr = "";
        ResponseBody responseBody = response.body();
        if (responseBody != null) {
            MediaType contentType = responseBody.contentType();
            responseBodyStr = responseBody.string();
            // 重新包装 ResponseBody，因为 string() 只能消费一次
            response = response.newBuilder()
                    .body(ResponseBody.create(contentType, responseBodyStr))
                    .build();
        }

        writeToFile(request, redactBody(requestBodyStr), curl, response, response.code(), redactBody(responseBodyStr), durationMs);

        return response;
    }

    private String buildCurl(Request request, String body) {
        StringBuilder sb = new StringBuilder("curl -X ").append(request.method());

        // Headers
        for (int i = 0; i < request.headers().size(); i++) {
            String name = request.headers().name(i);
            String value = request.headers().value(i);
            // 隐藏 Authorization 的具体值
            if ("Authorization".equalsIgnoreCase(name)) {
                value = value.length() > 15 ? value.substring(0, 15) + "..." : value;
            }
            sb.append(" \\\n  -H '").append(name).append(": ").append(value).append("'");
        }

        // Body
        if (body != null && !body.isEmpty()) {
            // 截断过长的 body，curl 里只保留前 2000 字符
            String truncated = body.length() > 2000 ? body.substring(0, 2000) + "...[TRUNCATED]" : body;
            sb.append(" \\\n  -d '").append(truncated.replace("'", "'\\''")).append("'");
        }

        sb.append(" \\\n  '").append(request.url()).append("'");
        return sb.toString();
    }

    private void writeToFile(Request request, String requestBody, String curl,
                             Response response, int statusCode, String responseBody,
                             long durationMs) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(new Date());
            String fileName = timestamp + "_" + request.method() + ".txt";
            File file = new File(logDir, fileName);

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write("==================== REQUEST ====================\n");
                writer.write("URL: " + request.url() + "\n");
                writer.write("Method: " + request.method() + "\n");
                writer.write("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "\n");
                writer.write("\n--- Headers ---\n");
                for (int i = 0; i < request.headers().size(); i++) {
                    String hn = request.headers().name(i);
                    writer.write(hn + ": " + redactHeaderValue(hn, request.headers().value(i)) + "\n");
                }
                writer.write("\n--- Body ---\n");
                writer.write(requestBody.isEmpty() ? "(empty)\n" : requestBody + "\n");

                writer.write("\n==================== CURL ====================\n");
                writer.write(curl + "\n");

                writer.write("\n==================== RESPONSE ====================\n");
                writer.write("Status: " + statusCode + "\n");
                writer.write("Duration: " + durationMs + "ms\n");
                if (response != null) {
                    writer.write("\n--- Headers ---\n");
                    for (int i = 0; i < response.headers().size(); i++) {
                        String hn = response.headers().name(i);
                        writer.write(hn + ": " + redactHeaderValue(hn, response.headers().value(i)) + "\n");
                    }
                }
                writer.write("\n--- Body ---\n");
                writer.write(responseBody.isEmpty() ? "(empty)\n" : responseBody + "\n");
            }

            XLog.d(TAG, "日志已写入: " + file.getAbsolutePath());
        } catch (Exception e) {
            XLog.e(TAG, "写入日志文件失败", e);
        }
    }
}
