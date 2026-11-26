package com.artex.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 GitHub Release 拉工具清单并下载。
 *
 * 清单里带 sha256，下载后必须比对——APP 会执行这些文件，
 * 校验是唯一能防住「仓库被投毒」或「中间人替换」的环节。
 *
 * 下载走 HTTPS，超时给得比较宽（nuclei 126MB，家用宽带要几十秒）。
 */
final class ToolCatalog {

    /** 清单地址。换仓库只改这一行 */
    static final String CATALOG_URL =
            "https://raw.githubusercontent.com/Srysfu/artex-mobile-tools/main/tools.json";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;
    /** 单工具下载上限，和 ToolImporter 保持一致 */
    private static final long MAX_BYTES = 300L * 1024 * 1024;

    static final class Entry {
        String name;
        String version;
        String description;
        String category;
        long size;
        String sha256;
        String url;
    }

    interface FetchCallback {
        void onCatalog(List<Entry> entries);
        void onError(String msg);
    }

    interface DownloadCallback {
        void onProgress(long got, long total);
        void onDone(File file);
        void onError(String msg);
    }

    private final Context ctx;

    ToolCatalog(Context ctx) {
        this.ctx = ctx;
    }

    /** 拉清单。网络操作，必须在后台线程调用 */
    void fetch(final FetchCallback cb) {
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
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
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
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("tools");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.name = o.optString("name");
            e.version = o.optString("version");
            e.description = o.optString("description");
            e.category = o.optString("category", "other");
            e.size = o.optLong("size");
            e.sha256 = o.optString("sha256");
            e.url = o.optString("url");
            if (e.name.isEmpty() || e.url.isEmpty()) continue;
            out.add(e);
        }
        return out;
    }

    /**
     * 下载到 destDir 下的临时名，校验 sha256 后交给调用方。
     * 校验不过就删掉，不会留下半截文件被当成可用工具。
     */
    void download(Entry e, File destDir, DownloadCallback cb) {
        destDir.mkdirs();
        File tmp = new File(destDir, ".downloading.bin");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(e.url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            // GitHub Release 会 302 到 objects.githubusercontent.com
            c.setRequestProperty("Accept", "application/octet-stream");
            int code = c.getResponseCode();
            if (code != 200) {
                cb.onError("Download failed: HTTP " + code);
                return;
            }
            long total = c.getContentLengthLong();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0;
            long lastReport = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > MAX_BYTES) {
                        tmp.delete();
                        cb.onError("File exceeds 300 MB");
                        return;
                    }
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    // 每 2MB 报一次，避免 UI 线程被刷屏
                    if (got - lastReport >= 2 * 1024 * 1024) {
                        lastReport = got;
                        cb.onProgress(got, total);
                    }
                }
            }
            cb.onProgress(got, total);

            String actual = hex(md.digest());
            if (e.sha256 != null && !e.sha256.isEmpty()
                    && !e.sha256.equalsIgnoreCase(actual)) {
                tmp.delete();
                cb.onError("Verification failed: SHA-256 mismatch\n"
                        + "Expected " + e.sha256.substring(0, Math.min(16, e.sha256.length())) + "…\n"
                        + "Actual " + actual.substring(0, 16) + "…");
                return;
            }
            cb.onDone(tmp);
        } catch (Throwable t) {
            if (tmp.exists()) tmp.delete();
            cb.onError(NetErr.describe(t));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        return sb.toString();
    }

}
