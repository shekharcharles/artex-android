package com.artex.mobile;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把用户选的二进制安进外挂工具目录，并在导入前后各做一次可用性检查。
 *
 * 静态检查（读 ELF 头）只能保证「文件看起来对」，拦不住依赖缺失、
 * 段表截断、Android 不支持的 syscall 这几类问题——它们都表现为
 * 进程起不来或起来就死。所以校验通过后再试跑一次拿真实结果。
 *
 * 三道静态校验：
 *   1. ELF 魔数
 *   2. e_machine == 0xB7（AArch64）
 *   3. 无 PT_INTERP 且无 PT_DYNAMIC（完全静态）
 *
 * 第 3 条是 Android 的硬要求：Android 用 bionic，不提供 glibc 的
 * ld-linux-aarch64.so.1 和 libc.so.6，动态链接的 glibc 程序必然起不来。
 */
final class ToolImporter {

    /** 单个工具的大小上限：nuclei 解压后 126MB，留一倍余量 */
    private static final long MAX_TOOL_BYTES = 300L * 1024 * 1024;
    /**
     * 每次试跑最多等多久。取 1.8 秒：实测 projectdiscovery 全家桶 300ms 内
     * 就出结果，1.8 秒足够覆盖慢启动的工具，又不至于让「一个工具都探不出来」
     * 的情况（5 个参数）拖到十几秒。批量补探是串行的，这个值直接影响体验。
     */
    private static final long PROBE_TIMEOUT_MS = 1800;
    /** 试跑输出只保留前这么多字符，够看版本号 */
    private static final int PROBE_OUTPUT_LIMIT = 4000;

    interface Callback {
        void onProgress(String msg);
        /**
         * 导入成功。version 可能为空（探测没拿到版本号）。
         */
        void onDone(String name, String version);
        /**
         * 静态校验过了但试跑没通过。用户可以选择仍然导入。
         * pendingFile 是已经落在工具目录里的临时文件，调用方要么
         * acceptPending() 要么 discardPending()，不能就这么放着。
         */
        void onProbeFailed(String name, String reason, File pendingFile);
        void onError(String msg);
    }

    private final Context ctx;
    private final File destDir;
    private final String toolPath;
    private final String homeDir;
    private final String tmpDir;

    ToolImporter(Context ctx, File destDir, String toolPath, String homeDir, String tmpDir) {
        this.ctx = ctx;
        this.destDir = destDir;
        this.toolPath = toolPath;
        this.homeDir = homeDir;
        this.tmpDir = tmpDir;
    }

    /** 已导入的工具：按名字排序，跳过隐藏的临时文件 */
    static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && f.length() > 0 && !f.getName().startsWith(".")) out.add(f);
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /**
     * 清理半途而废的临时文件。
     *
     * 导入时先写 .importing.bin、下载时先写 .downloading.bin，检查全过
     * 才改名。如果用户在这些文件还在的时候退出面板、或进程被杀，
     * 文件会留在目录里——list() 用「.」前缀过滤掉了它们不显示，
     * 但一个 nuclei 就 126MB，堆着白占空间。
     *
     * 只删一小时以前的：正在进行的导入/下载不会被误伤。
     */
    static void pruneStale(File dir) {
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return;
        long cutoff = System.currentTimeMillis() - 60L * 60 * 1000;
        for (File f : fs) {
            if (!f.isFile() || !f.getName().startsWith(".")) continue;
            if (f.lastModified() < cutoff) f.delete();
        }
    }

    void run(Uri uri, String displayName, Callback cb) {
        File tmp = null;
        try {
            destDir.mkdirs();
            String name = sanitize(baseName(displayName));
            if (name.isEmpty()) {
                cb.onError("Unable to determine filename");
                return;
            }
            // 先落到临时名，各项检查通过再改名。直接写成目标名的话，
            // 中途失败会留下半截文件，下次 list() 会把它当成可用工具列出来。
            tmp = new File(destDir, ".importing.bin");
            boolean tooBig = copyTo(uri, tmp);
            if (tooBig) {
                tmp.delete();
                cb.onError("File exceeds 300 MB");
                return;
            }
            if (tmp.length() == 0) {
                tmp.delete();
                cb.onError("File is empty");
                return;
            }
            Verify v = verify(tmp);
            if (!v.ok) {
                tmp.delete();
                cb.onError(v.reason);
                return;
            }
            cb.onProgress("Static validation passed (AArch64 static binary)");
            tmp.setExecutable(true, false);

            Probe pr = probe(tmp);
            if (!pr.usable) {
                // 不直接删：有些工具要配置文件才肯启动，用户可能知道
                // 自己那个工具就该这么报错。交给上层决定。
                cb.onProbeFailed(name, pr.detail, tmp);
                return;
            }
            if (!pr.version.isEmpty()) {
                cb.onProgress("Test run passed: version " + pr.version);
            } else {
                cb.onProgress("Test run passed");
            }
            File dst = accept(tmp, name);
            if (dst == null) {
                cb.onError("Rename failed");
                return;
            }
            cb.onProgress("Import completed: " + name + " (" + mb(dst.length()) + ")");
            cb.onDone(name, pr.version);
        } catch (Throwable t) {
            if (tmp != null && tmp.exists()) tmp.delete();
            cb.onError(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
        }
    }

    /** 把临时文件收成正式名。同名的旧版本直接换掉，避免留下两份 */
    File accept(File pending, String name) {
        File dst = new File(destDir, name);
        if (dst.exists() && !dst.delete()) return null;
        if (pending.renameTo(dst)) return dst;
        // renameTo 在跨文件系统/文件被占用时会失败，退成拷贝
        try {
            copyFile(pending, dst);
            pending.delete();
            dst.setExecutable(true, false);
            return dst;
        } catch (Throwable t) {
            dst.delete();
            return null;
        }
    }

    /**
     * 单独探一个已经装好的工具，给「重新检测」用。
     * 返回的版本号可能为空——能跑起来但输出里没有可识别的版本串。
     */
    Probe probeExisting(File f) { return probeExisting(f, false); }

    Probe probeExisting(File f, boolean quick) {
        if (f == null || !f.exists()) return Probe.fail("File not found");
        f.setExecutable(true, false);
        return probe(f, quick);
    }

    /**
     * 收下一个刚从网络下好的文件：静态校验 → chmod → Test run → 改名。
     *
     * 下载路径不能只跑 probe：一个 x86 或 glibc 动态的二进制被 exec 时
     * 只会报 "Unable to execute"，用户看到的是「试跑未通过」，会往工具本身想，
     * 而实际原因是文件根本不是给这台机器的。所以这里和手动导入
     * 走完全相同的三道校验。
     */
    Install installDownloaded(File file, String name) {
        if (file == null || !file.exists()) return Install.fail("File not found");
        Verify v = verify(file);
        if (!v.ok) {
            file.delete();
            return Install.fail(v.reason);
        }
        file.setExecutable(true, false);
        Probe pr = probe(file);
        if (!pr.usable) {
            file.delete();
            return Install.fail("Test run failed: " + pr.detail);
        }
        File dst = accept(file, name);
        if (dst == null) {
            file.delete();
            return Install.fail("Rename failed");
        }
        return Install.ok(dst, pr.version);
    }

    static final class Install {
        final boolean ok;
        final File file;
        final String version;
        final String reason;

        Install(boolean ok, File file, String version, String reason) {
            this.ok = ok;
            this.file = file;
            this.version = version;
            this.reason = reason;
        }

        static Install ok(File f, String ver) { return new Install(true, f, ver, ""); }
        static Install fail(String why) { return new Install(false, null, "", why); }
    }

    static final class Probe {
        final boolean usable;
        final String version;
        final String detail;

        Probe(boolean usable, String version, String detail) {
            this.usable = usable;
            this.version = version;
            this.detail = detail;
        }

        static Probe ok(String version, String detail) { return new Probe(true, version, detail); }
        static Probe fail(String detail) { return new Probe(false, "", detail); }
    }

    /**
     * 依次拿 -version / --version / -h / --help 试。
     *
     * 判定标准：
     *   start() 抛异常          → 不可用（exec 失败，多半是缺解释器或缺库）
     *   正常退出，有输出        → 可用，顺手抓版本号
     *   正常退出，无输出        → 继续试下一个参数
     *   超时没退出              → 可用（能加载运行，只是等输入）
     *   立刻死且完全无输出      → 不可用（链接问题的典型表现）
     */
    Probe probe(File binary) { return probe(binary, false); }

    /**
     * @param quick true 时只试 -version / --version。
     *              批量补探用 quick：27 个工具若每个都试满 5 个参数，
     *              最坏要 27 × 5 × 1.8 ≈ 4 分钟。而 -version / --version
     *              能覆盖绝大多数（gau、dalfox 都靠 --version），
     *              剩下几个探不到就留空，用户点「重新检测」时再走完整探测。
     */
    Probe probe(File binary, boolean quick) {
        String[][] argsList = quick
                ? new String[][]{ {"-version"}, {"--version"} }
                : new String[][]{
                        {"-version"}, {"--version"},
                        // 少数 cobra 程序（gitleaks）不认 -version，只认子命令
                        {"version"},
                        {"-h"}, {"--help"},
                };
        String lastDetail = "";
        String firstRanHint = "";
        boolean everRan = false;
        for (String[] args : argsList) {
            Process p;
            try {
                ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
                for (String a : args) pb.command().add(a);
                pb.redirectErrorStream(true);
                pb.directory(destDir);
                pb.environment().put("PATH", toolPath);
                pb.environment().put("HOME", homeDir);
                pb.environment().put("TMPDIR", tmpDir);
                p = pb.start();
            } catch (Throwable t) {
                // exec 本身失败：这就是明确的不可用信号
                return Probe.fail("Unable to execute: " + execErr(t));
            }
            everRan = true;
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}

            boolean exited = waitFor(p, PROBE_TIMEOUT_MS);
            String out = drain(p, PROBE_OUTPUT_LIMIT);
            if (!exited) {
                p.destroyForcibly();
                // 起得来、只是不肯自己退——对探测而言这就是好消息
                return Probe.ok(pickVersion(out), "Started successfully");
            }
            if (!out.trim().isEmpty()) {
                String ver = pickVersion(out);
                if (!ver.isEmpty()) {
                    return Probe.ok(ver, "Test run " + String.join(" ", args));
                }
                // produced output without a version number：说明二进制能跑，但还没找到最合适的参数。
                // 记下来继续试后面的（gau 的 -version 是报错，--version 才给版本；
                // dalfox 的 -version 报错，--version 才正常）。全部试完仍没版本号
                // 就退回这条，至少判定为可用。
                if (firstRanHint.isEmpty()) {
                    firstRanHint = "Test run " + String.join(" ", args) + " produced output without a version number";
                }
                continue;
            }
            lastDetail = "Test run " + String.join(" ", args) + " produced no output";
        }
        if (!everRan) return Probe.fail("Unable to execute");
        if (!firstRanHint.isEmpty()) return Probe.ok("", firstRanHint);
        return Probe.fail(lastDetail);
    }

    /**
     * 从输出里挑一行版本信息，挑不到就返回空。
     *
     * 要处理几种真实输出：
     *   "[\u001b[34mINF\u001b[0m] Nuclei Engine Version: v3.11.1"  ANSI 色码要去掉
     *   "8.30.1"                                              只有数字也算
     *   "      --version             show gau version"          这是帮助文本，得跳过
     */
    static String pickVersion(String out) {
        if (out == null) return "";
        for (String raw : out.split("\n")) {
            String line = raw.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "").trim();
            if (line.isEmpty()) continue;
            // 帮助里的选项说明行：带 "--" 前缀且以文字结尾，不是真的版本输出
            if (line.startsWith("-") && !line.matches("-\\s*v?\\d.*")) continue;
            String low = line.toLowerCase();
            boolean looksLikeVersion = low.contains("version")
                    || line.matches(".*\\bv?\\d+\\.\\d+\\.\\d+.*");
            if (!looksLikeVersion) continue;
            // 纯粹罗列帮助文本（"display gitleaks version"）也跳过
            if (low.contains("display ") || low.contains("show ")) continue;
            // 错误提示里也可能带 version 字样（"unknown shorthand flag: 'v' in -version"），
            // 那不是版本信息
            if (low.startsWith("unknown ") || low.startsWith("error")
                    || low.contains("flag:") || low.contains("invalid ")) continue;
            // Go flag 包的报错也走这条路（ffuf / gost 的 -version 不支持时）：
            // "flag provided but not defined: -version"
            if (low.startsWith("flag provided") || low.contains("not defined")) continue;
            // ligolo-proxy 会先问一句 y/N 再打版本，把问句当版本号没意义
            if (low.contains("? ") && low.contains("(y/n)")) continue;
            if (line.length() > 80) line = line.substring(0, 80);
            return line;
        }
        return "";
    }

    /**
     * exec 失败时的简短原因。
     *
     * 不走 NetErr：这里的 Throwable 是 ProcessBuilder.start() 抛的
     * IOException（ENOENT/EACCES），不是网络异常，套上"A proxy may be required"
     * 之类的话反而误导。
     */
    private static String execErr(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /** 等进程结束；超时返回 false 且不强杀（调用方决定） */
    private static boolean waitFor(Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException still) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return false; }
            }
        }
        return false;
    }

    /**
     * 读进程输出，最多 limit 字符。
     * 只在进程已退出/已强杀后调用，避免在 read 上永久阻塞。
     */
    private static String drain(Process p, int limit) {
        StringBuilder sb = new StringBuilder();
        try {
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[8192];
            while (is.available() > 0) {
                int n = is.read(buf);
                if (n <= 0) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.length() >= limit) break;
            }
        } catch (Throwable ignored) {}
        return sb.length() > limit ? sb.substring(0, limit) : sb.toString();
    }

    /** 流式拷贝，不进内存。返回 true 表示超了大小上限、已中止 */
    private boolean copyTo(Uri uri, File dst) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("Unable to read selected file");
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[128 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_TOOL_BYTES) return true;
                out.write(buf, 0, n);
            }
            return false;
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private void copyFile(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String mb(long b) {
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    private static String baseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String sanitize(String n) {
        String s = n.replace('/', '_').replace('\\', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_').trim();
        if (s.isEmpty()) return s;
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    static final class Verify {
        final boolean ok;
        final String reason;
        Verify(boolean ok, String reason) { this.ok = ok; this.reason = reason; }
    }

    /**
     * 读 ELF 头与 program header。
     * 只读前 16KB 就够判断——program header table 紧跟在 ELF header 后面。
     */
    static Verify verify(File f) {
        byte[] head = new byte[16384];
        int got;
        try (InputStream in = new java.io.FileInputStream(f)) {
            got = in.read(head);
        } catch (Throwable t) {
            return new Verify(false, "Read failed: " + t);
        }
        if (got < 64) return new Verify(false, "File is too small to be executable");
        if (head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') {
            return new Verify(false, "Not an ELF executable");
        }
        if (head[4] != 2) return new Verify(false, "Not a 64-bit executable");

        int machine = u16(head, 18);
        if (machine != 0xB7) {
            String arch;
            if (machine == 0x3E) arch = "x86-64";
            else if (machine == 0x28) arch = "32-bit ARM";
            else arch = "0x" + Integer.toHexString(machine);
            return new Verify(false, "Architecture is " + arch + ", but AArch64 (arm64) is required");
        }

        long phoff = u64(head, 32);
        int phentsize = u16(head, 54);
        int phnum = u16(head, 56);
        if (phnum <= 0 || phnum > 128) return new Verify(false, "Invalid ELF header");

        // 读 PT_INTERP 的路径和 PT_DYNAMIC 是否存在。
        // 动态链接不一定是坏事：Android 自己的二进制（gogo）用
        // /system/bin/linker64 + bionic，能正常跑；glibc 的
        // ld-linux-aarch64.so.1 才必然失败。必须看 interp 具体是什么。
        String interp = null;
        boolean hasDynamic = false;
        for (int i = 0; i < phnum; i++) {
            long off = phoff + (long) i * phentsize;
            if (off + 8 > got) break;          // 头部没读够，放弃判断
            int type = (int) u32(head, (int) off);
            if (type == 3) {                   // PT_INTERP
                long pOff = u64(head, (int) off + 8);
                long pSz = Math.min(u64(head, (int) off + 32), 512);
                if (pOff + pSz <= got && pSz > 0) {
                    int end = (int) pOff;
                    while (end < pOff + pSz && head[end] != 0) end++;
                    interp = new String(head, (int) pOff, end - (int) pOff,
                            java.nio.charset.StandardCharsets.UTF_8);
                } else if (pSz > 0) {
                    interp = "?";              // 头部没读全，只标存在
                }
            }
            if (type == 2) hasDynamic = true;  // PT_DYNAMIC
        }
        if (interp != null) {
            if (interp.contains("ld-linux")) {
                return new Verify(false,
                        "Requires glibc (" + interp + "); Android does not provide this dynamic linker; "
                                + "Use a musl or statically linked build");
            }
            // /system/bin/linker64 等 Android 原生 interp：能跑，放行
            if (!interp.startsWith("/system/")) {
                return new Verify(false,
                        "Dynamic linker is " + interp + ", not Android linker64; "
                                + "Use a statically linked build");
            }
        }
        return new Verify(true, "");
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFFL);
        return v;
    }
}
