package com.artex.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 GitHub 拉 skill 清单，Downloading zip 后交给后端安装。
 *
 * 为什么不自己解压写目录：后端有 POST /api/skills/upload，
 * 它会做 zip 解压（含 bzip2/zstd 兼容）、SKILL.md 校验、中文名处理、
 * 覆盖策略。壳里重写一遍只会和后端行为漂移。
 *
 * 代价是这条路要 JWT：上传和删除都走 /api/skills*，都必须带 token。
 * token 从 WebView 的 CookieManager 里取（登录时种的 artex_token）。
 */
final class SkillCatalog {

    static final String CATALOG_URL =
            "https://raw.githubusercontent.com/Srysfu/artex-mobile-tools/main/skills.json";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    static final class Entry {
        String name;
        String version;
        String description;
        long size;
        String sha256;
        String url;
    }

    interface FetchCallback {
        void onCatalog(List<Entry> entries);
        void onError(String msg);
    }

    interface InstallCallback {
        void onProgress(String msg);
        void onDone(String name);
        void onError(String msg);
    }

    private final Context ctx;
    private final String apiBase;
    private final String token;

    SkillCatalog(Context ctx, String apiBase, String token) {
        this.ctx = ctx;
        this.apiBase = apiBase;
        this.token = token;
    }

    /** 拉清单。网络操作，后台线程调用 */
    void fetch(FetchCallback cb) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(CATALOG_URL).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) {
                cb.onError("Catalog request failed: HTTP " + code);
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            List<Entry> list = parse(sb.toString());
            if (list.isEmpty()) {
                cb.onError("Catalog is empty");
                return;
            }
            cb.onCatalog(list);
        } catch (Throwable t) {
            cb.onError(NetErr.describe(t));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static List<Entry> parse(String json) throws Exception {
        List<Entry> out = new ArrayList<>();
        JSONArray arr = new JSONObject(json).optJSONArray("skills");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.name = o.optString("name");
            e.version = o.optString("version");
            e.description = o.optString("description");
            e.size = o.optLong("size");
            e.sha256 = o.optString("sha256");
            e.url = o.optString("url");
            if (e.name.isEmpty() || e.url.isEmpty()) continue;
            out.add(e);
        }
        return out;
    }

    /**
     * Downloading zip → 校验 sha256 → POST /api/skills/upload。
     * @param overwrite 已存在时是否覆盖（后端用 ?overwrite=true 控制）
     */
    void install(Entry e, boolean overwrite, InstallCallback cb) {
        File tmp = null;
        try {
            cb.onProgress("Downloading " + e.name + "…");
            tmp = new File(ctx.getCacheDir(), ".skill-" + e.name + ".zip");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            HttpURLConnection c = (HttpURLConnection) new URL(e.url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) {
                cb.onError("Download failed: HTTP " + code);
                return;
            }
            long total = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        cb.onError("Skill package exceeds 20 MB");
                        return;
                    }
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String actual = hex(md.digest());
            if (e.sha256 != null && !e.sha256.isEmpty()
                    && !e.sha256.equalsIgnoreCase(actual)) {
                cb.onError("Verification failed: SHA-256 mismatch");
                return;
            }
            cb.onProgress("Uploading to backend…");
            String resp = upload(tmp, e.name, overwrite);
            if (resp == null) {
                cb.onError("Upload failed");
                return;
            }
            cb.onDone(e.name);
        } catch (Throwable t) {
            cb.onError(NetErr.describe(t));
        } finally {
            if (tmp != null && tmp.exists()) tmp.delete();
        }
    }

    /** multipart/form-data 上传，字段名固定为 file（前端也是这么调的） */
    private String upload(File zip, String name, boolean overwrite) throws Exception {
        String boundary = "----artex" + System.currentTimeMillis();
        String url = apiBase + "/api/skills/upload" + (overwrite ? "?overwrite=true" : "");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }

        try (OutputStream out = c.getOutputStream()) {
            StringBuilder head = new StringBuilder();
            head.append("--").append(boundary).append("\r\n");
            head.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(name).append(".zip\"\r\n");
            head.append("Content-Type: application/zip\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));

            try (InputStream in = new java.io.FileInputStream(zip)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        // 后端创建资源返回 201 Created，只看 200 会把成功当失败
        if (code != 200 && code != 201) {
            String msg = body;
            try {
                JSONObject o = new JSONObject(body);
                if (o.has("error")) msg = o.optString("error");
            } catch (Throwable ignored) {}
            throw new Exception("Backend rejected request (HTTP " + code + "）：" + msg);
        }
        return body;
    }

    /** 删除已装 skill */
    boolean delete(String name) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apiBase + "/api/skills/" + name).openConnection();
            c.setRequestMethod("DELETE");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = c.getResponseCode();
            return code == 200 || code == 204;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 后端已装的 skill 名字集合。拿不到时返回 null（调用方按"Unknown"处理） */
    java.util.Set<String> installedNames() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apiBase + "/api/skills").openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream());
            java.util.Set<String> out = new java.util.HashSet<>();
            JSONArray arr = new JSONObject(body).optJSONArray("skills");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o.optString("name"));
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        return sb.toString();
    }
}
