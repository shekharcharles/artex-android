package com.artex.mobile;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 极简启动界面：logo + 一行状态 + 底部细进度条。
 *
 * 设计取舍：启动日志只在出问题时才有用，平时占着大半屏幕纯属干扰，
 * 所以默认不显示，长按任意位置弹窗查看；启动失败则整页切成失败视图。
 */
public class BootConsole extends LinearLayout {

    /** 单行状态文字（14sp） */
    private final TextView statusView;
    /** 底部 2dp 细进度条：前景宽度按百分比铺开 */
    private final View progressFill;
    /** 进度条轨道，用来算可用宽度 */
    private final View progressTrack;
    /** 前景条与轨道的叠放容器，宽度基准取自它 */
    private android.widget.FrameLayout barStack;

    /** 进度条固定宽度（dp）——不铺满屏，长度可控 */
    private static final int H_BAR_W = 180;
    /** 进度条粗细（dp） */
    private static final int H_BAR_H = 3;
    private final ImageView logo;
    private final LinearLayout centerBox;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 日志内容始终在内存里，供长按复制 / 失败页展示 */
    private final StringBuilder buffer = new StringBuilder();
    private LogProvider logProvider;

    private int currentPct = 0;
    private ValueAnimator anim;

    public BootConsole(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setClickable(true);   // 需要能收长按

        // ---------- 中间区：logo + 状态 ----------
        centerBox = new LinearLayout(ctx);
        centerBox.setOrientation(VERTICAL);
        centerBox.setGravity(Gravity.CENTER);
        centerBox.setPadding(dp(32), 0, dp(32), 0);

        logo = new ImageView(ctx);
        try {
            logo.setImageResource(getResources().getIdentifier(
                    "launch_logo", "drawable", ctx.getPackageName()));
        } catch (Throwable ignored) {}
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(24);
        centerBox.addView(logo, lp);

        statusView = new TextView(ctx);
        statusView.setText("Starting…");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setGravity(Gravity.CENTER);
        centerBox.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 进度条：紧跟状态文字下方 ----------
        // 放屏幕底部会显得孤立；跟着文字走才有"这条是它的进度"的语义。
        barStack = new android.widget.FrameLayout(ctx);
        progressTrack = new View(ctx);
        barStack.addView(progressTrack, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(3, dp(H_BAR_H))));

        progressFill = new View(ctx);
        android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                0, Math.max(3, dp(H_BAR_H)));
        flp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        barStack.addView(progressFill, flp);

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                dp(H_BAR_W), Math.max(3, dp(H_BAR_H)));
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        barLp.topMargin = dp(18);
        centerBox.addView(barStack, barLp);

        // 中间块垂直居中，上下等分空白
        LayoutParams clp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        addView(centerBox, clp);

        // 长按查看完整日志
        setOnLongClickListener(v -> {
            showLogDialog();
            return true;
        });

        applyTheme();
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTheme();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean isDark() {
        if (getContext() == null) return true;
        return (getContext().getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 底色与文字随系统深浅色切换；品牌色只用在中性灰阶上 */
    private void applyTheme() {
        if (getContext() == null) return;
        int bg, fg, subtle;
        if (isDark()) {
            bg = 0xFF0C0C0F;
            fg = 0xFFF2F4F7;
            subtle = 0x33FFFFFF;
        } else {
            bg = 0xFFF7F8FA;
            fg = 0xFF14181D;
            subtle = 0x1A000000;
        }
        setBackgroundColor(bg);
        statusView.setTextColor(fg);
        progressTrack.setBackgroundColor(subtle);

        GradientDrawable fill = new GradientDrawable();
        fill.setColor(fg);
        progressFill.setBackground(fill);
    }

    /** 追加一行日志（只在内存里，不再往屏幕上刷） */
    public void log(String line) {
        synchronized (buffer) {
            buffer.append(line).append('\n');
            // 上限防膨胀：启动日志偶尔会有 PG/后端的重复输出
            if (buffer.length() > 400_000) {
                buffer.delete(0, buffer.length() - 300_000);
            }
        }
    }

    /** 设置单行状态文字 */
    public void logStatus(String msg) {
        if (msg == null) return;
        log("> " + msg);
        ui.post(() -> statusView.setText(msg));
    }

    /** 进度：平滑过渡到目标百分比，避免阶段跳变 */
    public void setProgress(int percent) {
        final int target = Math.max(0, Math.min(100, percent));
        ui.post(() -> {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofInt(currentPct, target);
            anim.setDuration(320);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                currentPct = (int) a.getAnimatedValue();
                applyFillWidth();
            });
            anim.start();
        });
    }

    private void applyFillWidth() {
        int total = barStack != null ? barStack.getWidth() : progressTrack.getWidth();
        if (total <= 0) {
            // 还没布局完，等下一帧
            (barStack != null ? barStack : progressTrack).post(this::applyFillWidth);
            return;
        }
        ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
        lp.width = Math.max(0, total * currentPct / 100);
        progressFill.setLayoutParams(lp);
    }

    /**
     * 由状态文字推断进度。
     * 文案改过一次（去掉了"首次较慢"这类说法），这里必须同步，
     * 否则所有阶段都匹配不上、进度条永远停在 0。
     */
    public void progressFromStatus(String msg) {
        int p = -1;
        if (msg == null) return;
        if (msg.contains("准备运行文件") || msg.contains("更新运行文件")) p = 3;
        else if (msg.contains("释放 PG 库")) p = 8;
        else if (msg.contains("释放 Python 标准库")) {
            int idx = lastNumber(msg);
            p = idx > 0 ? Math.min(45, 10 + idx / 25) : 30;
        } else if (msg.contains("释放 PG 数据文件")) {
            int idx = lastNumber(msg);
            p = idx > 0 ? Math.min(45, 10 + idx / 25) : 20;
        } else if (msg.contains("释放") && msg.contains("库")) p = 15;
        else if (msg.contains("释放工具")) p = 18;
        else if (msg.contains("检查数据库") || msg.contains("初始化数据库")) p = 48;
        else if (msg.contains("清理上次残留")) p = 46;
        else if (msg.contains("initdb")) {
            if (msg.contains("creating subdirectories")) p = 54;
            else if (msg.contains("max_connections")) p = 58;
            else if (msg.contains("shared_buffers")) p = 62;
            else if (msg.contains("time zone")) p = 66;
            else if (msg.contains("bootstrap")) p = 72;
            else if (msg.contains("post-bootstrap")) p = 80;
            else if (msg.contains("syncing")) p = 86;
            else p = 55;
        } else if (msg.contains("启动 PostgreSQL")) p = 88;
        // 「重新启动服务」本身就包含「启动服务」，后者是前者的子串，
        // 所以只需判断前者；写成两个 || 的后一个永远走不到。
        else if (msg.contains("启动服务")) p = 92;
        else if (msg.contains("服务启动失败")) p = 90;
        else if (msg.contains("加载界面")) p = 99;
        else if (msg.contains("准备界面")) p = 95;
        else if (msg.contains("等待") || msg.contains("仍在启动")) p = 96;
        if (p >= 0) setProgress(p);
    }

    private int lastNumber(String s) {
        int i = s.lastIndexOf('(');
        int j = s.lastIndexOf(')');
        if (i < 0 || j < i) return -1;
        try {
            return Integer.parseInt(s.substring(i + 1, j).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 启动成功：进度到 100，状态切成就绪 */
    public void complete() {
        setProgress(100);
        ui.post(() -> statusView.setText("Ready"));
    }

    public void setLongPressCopy(final LogProvider provider) {
        this.logProvider = provider;
    }

    /**
     * 启动失败：整页切成失败视图。
     * 标题 + 错误信息 + 可滚动日志 + 复制/重试按钮。
     */
    public void showFailure(String message, final LogProvider provider, Runnable onRetry) {
        this.logProvider = provider;
        ui.post(() -> {
            removeAllViews();
            setOnLongClickListener(null);
            setClickable(false);

            int fg = isDark() ? 0xFFF2F4F7 : 0xFF14181D;
            int dim = isDark() ? 0x99FFFFFF : 0x99000000;
            int red = 0xFFFF6B6B;

            ScrollView scroll = new ScrollView(getContext());
            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(VERTICAL);
            col.setPadding(dp(24), dp(48), dp(24), dp(24));

            TextView title = new TextView(getContext());
            title.setText("Startup failed");
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(red);
            col.addView(title);

            TextView err = new TextView(getContext());
            err.setText(message == null ? "Unknown error" : message);
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            err.setTextColor(dim);
            err.setPadding(0, dp(10), 0, dp(20));
            err.setLineSpacing(dp(3), 1f);
            col.addView(err);

            // 完整日志
            String full = provider != null ? provider.get() : buffer.toString();
            TextView logs = new TextView(getContext());
            logs.setText(full);
            logs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            logs.setTypeface(Typeface.MONOSPACE);
            logs.setTextColor(fg);
            logs.setTextIsSelectable(true);
            logs.setLineSpacing(dp(2), 1f);
            logs.setPadding(dp(14), dp(14), dp(14), dp(14));
            GradientDrawable box = new GradientDrawable();
            box.setCornerRadius(dp(10));
            box.setColor(isDark() ? 0xFF14161A : 0xFFFFFFFF);
            logs.setBackground(box);
            col.addView(logs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 按钮行
            LinearLayout btns = new LinearLayout(getContext());
            btns.setOrientation(HORIZONTAL);
            btns.setPadding(0, dp(18), 0, 0);
            btns.setGravity(Gravity.END);

            Button copy = new Button(getContext());
            copy.setText("Copy log");
            copy.setAllCaps(false);
            copy.setOnClickListener(v -> copyLog(provider));

            Button retry = new Button(getContext());
            retry.setText("Retry");
            retry.setAllCaps(false);
            if (onRetry != null) {
                retry.setOnClickListener(v -> onRetry.run());
            } else {
                retry.setEnabled(false);
            }

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(10);
            btns.addView(copy);
            btns.addView(retry, blp);
            col.addView(btns);

            scroll.addView(col);
            addView(scroll, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        });
    }

    /** 长按弹出的日志对话框 */
    private void showLogDialog() {
        String full = logProvider != null ? logProvider.get() : buffer.toString();
        TextView tv = new TextView(getContext());
        tv.setText(full);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setTextColor(isDark() ? 0xFFE8EAED : 0xFF1A1F24);

        ScrollView sv = new ScrollView(getContext());
        sv.addView(tv);

        new AlertDialog.Builder(getContext())
                .setTitle("Startup log")
                .setView(sv)
                .setPositiveButton("Copy all", (d, w) -> copyLog(logProvider))
                .setNegativeButton("Close", null)
                .show();
    }

    private void copyLog(LogProvider provider) {
        try {
            String full = provider != null ? provider.get() : buffer.toString();
            ClipboardManager cm = (ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("artex-log", full));
            Toast.makeText(getContext(),
                    "Copied " + full.length() + " characters", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    public interface LogProvider {
        String get();
    }
}
