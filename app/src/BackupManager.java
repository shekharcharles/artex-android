package com.artex.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键备份：停服务 → 打包 → 存到「下载」目录。
 *
 * 必须先停 PostgreSQL 再打包 pgdata —— 运行中直接拷数据目录会拿到不一致的备份。
 * 用「拷贝数据目录」而非 pg_dump，是因为包内只带了 postgres/initdb/psql，
 * 没有 pg_dump；迁移场景两端 PG 版本一致，直接拷目录即可完整还原。
 *
 * 不含 SharedPreferences（网桥池、开关、实测状态）—— 那些属于设备配置，
 * 不随数据迁移。
 */
public class BackupManager {

    public interface Callback {
        void onProgress(String msg);
        void onDone(String displayPath);
        void onError(String msg);
    }

    private final Context ctx;
    private final File filesDir;
    private Callback cb;
    private int files;

    public BackupManager(Context ctx) {
        this.ctx = ctx;
        this.filesDir = ctx.getFilesDir();
    }

    /** 同步执行（调用方需放在后台线程） */
    public void run(ArtExService svc, boolean lite, Callback cb) {
        try {
            cb.onProgress("Stopping service to ensure data consistency…");
            if (svc != null) svc.stop();
            waitPgExit(cb);

            String name = "artex-backup-" + (lite ? "lite-" : "")
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + ".zip";

            cb.onProgress(lite ? "Packaging data (compact: excluding task files)…" : "Packaging data…");
            OutputStream out = openOutput(name);
            long bytes;
            try {
                bytes = writeZip(out, lite, cb);
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {}
            }

            cb.onProgress("Finished. Total: " + (bytes / 1024 / 1024) + " MB");
            cb.onDone(lastWhere != null ? lastWhere : name);
        } catch (Throwable t) {
            Log.e("ARTEX", "Backup failed", t);
            cb.onError(t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    /** 等 postmaster.pid 消失，确认 PG 已真正退出 */
    private void waitPgExit(Callback cb) {
        File pid = new File(filesDir, "pgdata/postmaster.pid");
        for (int i = 0; i < 40; i++) {
            if (!pid.exists()) return;
            if (i % 6 == 0) cb.onProgress("Waiting for database to stop…");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        Log.w("ARTEX", "Database shutdown timed out; continuing packaging");
    }

    /** 输出流：API 29+ 走 MediaStore（免权限），旧版本写公共 Download 目录 */
    /** 上次成功的输出位置，供 onDone 展示真实路径 */
    private String lastWhere;

    /**
     * 输出流。三级回退：
     *  1) MediaStore → 下载/ARTEX 备份/（Android 10+ 免权限；但本包 targetSdk=27，
     *     旧规则下仍可能需要 WRITE_EXTERNAL_STORAGE，所以必须能退）
     *  2) 公共 Download 目录（Android 9-，或有权限时）
     *  3) 应用私有外部目录 files/backup（一定写得进去，用户能从 MT 取出）
     */
    private OutputStream openOutput(String name) throws Exception {
        // 1) MediaStore
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/ARTEX 备份");
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        lastWhere = "下载/ARTEX 备份/" + name;
                        return os;
                    }
                }
                Log.w("ARTEX", "MediaStore file creation failed; using public Downloads directory");
            } catch (Throwable t) {
                Log.w("ARTEX", "MediaStore write failed: " + t);
            }
        }

        // 2) 公共 Download 目录
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "ARTEX 备份");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Directory creation failed");
            File f = new File(dir, name);
            OutputStream os = new FileOutputStream(f);
            lastWhere = f.getAbsolutePath();
            return os;
        } catch (Throwable t) {
            Log.w("ARTEX", "Public Downloads write failed: " + t);
        }

        // 3) 应用私有外部目录（/sdcard/Android/data/<pkg>/files/backup）
        File dir = ctx.getExternalFilesDir("backup");
        if (dir == null) dir = new File(ctx.getFilesDir(), "backup");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Unable to create backup directory: " + dir);
        File f = new File(dir, name);
        lastWhere = f.getAbsolutePath();
        return new FileOutputStream(f);
    }

    private long writeZip(OutputStream out, boolean lite, Callback cb) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(9);   // 默认是 6；备份不赶时间，压到最紧
        long total = 0;
        files = 0;
        this.cb = cb;

        // 清单
        zip.putNextEntry(new ZipEntry("manifest.json"));
        String manifest = "{\n"
                + "  \"app\": \"ARTEX Android\",\n"
                + "  \"version\": \"" + appVersion() + "\",\n"
                + "  \"created_at\": \"" + new Date().toString() + "\",\n"
                + "  \"android_sdk\": " + Build.VERSION.SDK_INT + ",\n"
                + "  \"contents\": [\"pgdata（数据库）\", \"artex/data（工作空间）\",\n"
                + "                \"artex/config.json（配置）\", \"artex/skills（技能）\"]\n"
                + "}\n";
        zip.write(manifest.getBytes("UTF-8"));
        zip.closeEntry();

        // 数据库 + 工作空间 + 配置
        total += addDir(zip, new File(filesDir, "pgdata"), "pgdata");
        total += addFile(zip, new File(filesDir, "artex/config.json"), "artex/config.json");
        total += addDir(zip, new File(filesDir, "artex/skills"), "artex/skills");
        // 精简模式只跳过任务工作目录（里面是下载/解包的样本，占比极大）；
        // 其余子目录统统包含，这样将来新增目录也不会漏。
        total += addDirSkip(zip, new File(filesDir, "artex/data"), "artex/data",
                lite ? "tasks" : null);

        zip.finish();
        return total;
    }

    /** 与 addDir 相同，但跳过指定名字的一级子目录（null 表示不跳） */
    private long addDirSkip(ZipOutputStream zip, File dir, String prefix, String skipName)
            throws Exception {
        if (!dir.exists()) return 0;
        ZipEntry de = new ZipEntry(prefix + "/");
        de.setTime(dir.lastModified());
        zip.putNextEntry(de);
        zip.closeEntry();

        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            if (skipName != null && skipName.equals(c.getName())) continue;
            String rel = prefix + "/" + c.getName();
            if (c.isDirectory()) total += addDir(zip, c, rel);
            else total += addFile(zip, c, rel);
        }
        return total;
    }

    /** 递归写入目录（写目录条目 + 文件，包含空目录） */
    private long addDir(ZipOutputStream zip, File dir, String prefix) throws Exception {
        if (!dir.exists()) return 0;
        // 必须先写目录条目：PG 数据目录里有一批空目录（pg_notify、pg_dynshmem …），
        // 只写文件的话它们会凭空消失，恢复后 PG 会因缺目录而拒绝启动。
        ZipEntry de = new ZipEntry(prefix + "/");
        de.setTime(dir.lastModified());
        zip.putNextEntry(de);
        zip.closeEntry();

        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            String rel = prefix + "/" + c.getName();
            if (c.isDirectory()) total += addDir(zip, c, rel);
            else total += addFile(zip, c, rel);
        }
        return total;
    }

    private long addFile(ZipOutputStream zip, File f, String entryName) throws Exception {
        if (!f.isFile()) return 0;
        zip.putNextEntry(new ZipEntry(entryName));
        long n = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                zip.write(buf, 0, r);
                n += r;
            }
        }
        zip.closeEntry();
        if (++files % 200 == 0 && cb != null) cb.onProgress("Packaged " + files + " files…");
        return n;
    }

    /** 从系统读应用版本号 */
    private String appVersion() {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
