package com.artex.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * 右侧滑出的空面板框架：模糊底图 + 压暗层 + 纯色侧栏。
 * 目前只放标题，功能项留空待后续添加。
 */
public class PanelActivity extends Activity {

    private static final int SIDEBAR_W_DP = 256;
    private static final int REQ_PICK_ZIP = 2001;
    private static final int REQ_PICK_TOOL = 2003;
    /** MainActivity 抓屏模糊后交给这里当底图（静态传递，不塞大对象进 Intent） */
    private static android.graphics.Bitmap sBackdrop;

    private View panel;
    private View panelDim;
    private boolean panelClosing;

    private int cBg, cText, cDesc, cBorder;
    /** 已安装标记色。深浅色各一档，深色下要提亮才在 #0C0C0F 上看得清 */
    private int cOk;
    private TextView detail;
    private boolean working;
    /** working 时要压暗的容器（工具区），让"正在忙"一眼可见 */
    private View busyDimTarget;

    /**
     * 设置忙碌状态。除了拦住重复操作，还把工具区压暗——
     * 原来只有输出区一行小字，面板其他项看起来都能点，点了才被挡回去。
     */
    private void setWorking(boolean busy) {
        working = busy;
        runOnUiThread(() -> {
            if (busyDimTarget == null) return;
            busyDimTarget.setAlpha(busy ? 0.45f : 1f);
        });
    }
    /** 本地已导入工具的可折叠列表容器（工具行本身） */
    private LinearLayout localToolBox;
    private TextView localToolHeader;
    private ImageView localToolChevron;
    private boolean localExpanded;

    static void setBackdrop(android.graphics.Bitmap b) {
        sBackdrop = b;
    }

    private static android.graphics.Bitmap takeBackdrop() {
        android.graphics.Bitmap b = sBackdrop;
        sBackdrop = null;
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadToolVersions();
        applyPalette();
        applyFullscreen();

        final android.graphics.Bitmap backdrop = takeBackdrop();
        FrameLayout screen = new FrameLayout(this);

        if (backdrop != null) {
            ImageView bg = new ImageView(this);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setImageBitmap(backdrop);
            screen.addView(bg, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        panelDim = new View(this);
        panelDim.setBackgroundColor(0x66000000);
        panelDim.setAlpha(0f);
        panelDim.setOnClickListener(v -> dismissPanel());
        screen.addView(panelDim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 面板内容可能超出一屏（工具列表 27 条），必须可滚动。
        // ScrollView 撑满宽度，fillViewport 保证内容少时底部 spacer 仍能顶住页脚。
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setElevation(dp(8));          // 阴影放在滚动容器上，否则滚动时会被裁掉
        scroll.setBackgroundColor(cBg);
        View content = buildPanel();
        scroll.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel = scroll;
        int sidebarW = Math.min(dp(SIDEBAR_W_DP),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.84f));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                sidebarW, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.RIGHT;
        screen.addView(panel, plp);

        setContentView(screen);

        // 面板隐藏了状态栏，但内容仍要避开刘海与手势条。
        // 隐藏状态下 statusBars insets 可能返回 0，所以和刘海/资源高度取大值。
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                try {
                    top = Math.max(top, insets.getInsets(
                            WindowInsets.Type.displayCutout()).top);
                } catch (Throwable ignored) {}
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
            top = Math.max(top, statusBarHeightPx());
            panel.setPadding(0, top, 0, bottom);
            return insets;
        });
        screen.requestApplyInsets();

        panel.post(() -> {
            panel.setTranslationX(panel.getWidth());
            panel.animate().translationX(0f).setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
            panelDim.animate().alpha(1f).setDuration(220).start();
        });
    }

    private View buildPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(cBg);

        TextView title = new TextView(this);
        title.setText("ARTEX");
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(cText);
        title.setPadding(dp(16), dp(18), dp(16), dp(14));
        box.addView(title);

        View divider = new View(this);
        divider.setBackgroundColor(cBorder);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.leftMargin = dp(16);
        dlp.rightMargin = dp(16);
        box.addView(divider, dlp);

        // 分组标题
        TextView groupTitle = new TextView(this);
        groupTitle.setText("Data");
        groupTitle.setTextSize(11);
        groupTitle.setTextColor(cDesc);
        groupTitle.setPadding(dp(16), dp(16), dp(16), dp(6));
        box.addView(groupTitle);

        box.addView(buildItem("ic_ctl_download", "Backup", v -> startBackup()));
        box.addView(buildItem("ic_ctl_upload", "Restore", v -> confirmRestore()));

        box.addView(buildGroupTitle("Tools"));

        // 工具组只有两层：已安装列表 / 从本地文件 / 从 GitHub 下载。
        // 原来外面还包了一层「导入工具」，那层既不是容器也不是动作，
        // 只是个名字，却让用户点三层才看到东西。
        busyDimTarget = new LinearLayout(this);
        ((LinearLayout) busyDimTarget).setOrientation(LinearLayout.VERTICAL);
        box.addView(busyDimTarget);

        box.addView(buildGroupTitle("Skill"));
        box.addView(buildSkillSection());

        // 过程输出：保留最近几行，否则「校验通过」会被「已安装」立刻盖掉，
        // 出了问题也看不到前一步发生了什么。
        detail = new TextView(this);
        detail.setTextSize(11);
        detail.setTextColor(cDesc);
        detail.setTypeface(android.graphics.Typeface.MONOSPACE);
        detail.setPadding(dp(16), dp(10), dp(16), dp(10));
        detail.setVisibility(View.GONE);
        detail.setTextIsSelectable(true);
        box.addView(detail);

        View spacer = new View(this);
        box.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        box.addView(buildFooter());

        // 工具组现在是常驻可见的（不再是折叠区的子内容），
        // 面板一建好就得把内容填进去 —— 以前靠折叠头点击触发，
        // 折叠头没了之后 refreshToolList 没有任何调用点，整组空白。
        refreshToolList();

        return box;
    }

    /**
     * 给折叠头左侧加一个功能图标。
     * 三处折叠头（已安装 / 从 GitHub 下载 / Skill 库）都调这个，
     * 保证图标尺寸、间距、着色一致。
     */
    private void prependIcon(LinearLayout head, String iconName, int sizeDp) {
        int id = getResources().getIdentifier(iconName, "drawable", getPackageName());
        if (id == 0) return;
        ImageView icon = new ImageView(this);
        icon.setImageResource(id);
        icon.setColorFilter(cText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.rightMargin = dp(10);
        // 插到最前面（head 里第一个是标题）
        head.addView(icon, 0, lp);
    }

    /** 分组小标题：「数据」「工具」「Skill」 */
    private View buildGroupTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(cDesc);
        tv.setPadding(dp(16), dp(16), dp(16), dp(6));
        return tv;
    }

    /**
     * 重画工具列表。每行是「工具名 + 体积 + 版本」，点击进详情。
     *
     * 列表为空时给一行提示 + 「导入新工具」，不为空时把导入入口放列表末尾，
     * 这样展开后从上往下读就是「有哪些 → 再加一个」的自然顺序。
     */
    private void refreshToolList() {
        if (busyDimTarget == null) return;
        LinearLayout container = (LinearLayout) busyDimTarget;
        container.removeAllViews();
        File dir = ArtExService.extToolsDir(this);
        // 顺手清掉上次中断遗留的临时文件（导入 126MB 的 nuclei 中途退出很常见）
        ToolImporter.pruneStale(dir);
        java.util.List<File> tools = ToolImporter.list(dir);

        // 工具被删掉后，它的版本号不该还留在 prefs 里。
        // 不清的话这份记录只增不减，而且重装同一工具时会短暂显示旧版本。
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (File f : tools) alive.add(f.getName());
        for (String k : new java.util.ArrayList<>(toolVersions.keySet())) {
            if (!alive.contains(k)) forgetToolVersion(k);
        }

        // 三层结构：已安装列表（可折叠）/ 从本地文件（动作）/ 从 GitHub 下载（可折叠）
        container.addView(buildLocalToolSection(tools));
        container.addView(buildToolAction("Import", this::pickToolFile));
        container.addView(buildDownloadSection());

        // 重建后把展开状态还原回去
        if (dlExpanded && catalogCache != null) {
            renderDownloadList(catalogCache);
            if (dlChevron != null) dlChevron.setRotation(180f);
        }

        // 首次展开时，把还没探过的工具补探一遍。老版本导入的工具没记版本号，
        // 不做这一步列表里就只剩体积，看起来像功能坏了。
        probeMissingVersions(tools);
    }

    /**
     * 本地已导入工具的可折叠列表。
     *
     * 工具会越装越多（32 个全装下来能滚几十屏），平铺在「导入工具」里
     * 会把下面的导入入口、GitHub 下载、Skill 管理全都顶到很远的地方。
     * 单独做一层折叠，收起后只占一行。
     *
     * 默认展开：刚装完工具的人要能立刻看到它进列表了。
     */
    private View buildLocalToolSection(java.util.List<File> tools) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        // 空列表时不折叠，直接给一行提示 —— 折叠一个空列表没有意义，
        // 用户点开只会看到"Nothing here"。有工具时统一走折叠头，
        // 保持三处折叠区外观一致。
        if (tools.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No tools installed");
            empty.setTextSize(12);
            empty.setTextColor(cDesc);
            empty.setPadding(dp(16), dp(2), dp(16), dp(10));
            wrap.addView(empty);
            return wrap;
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(16), dp(10));
        head.setClickable(true);
        head.setFocusable(true);
        makeRipple(head);

        prependIcon(head, "ic_ctl_installed", 15);

        localToolHeader = new TextView(this);
        localToolHeader.setText("Installed · " + tools.size());
        localToolHeader.setTextSize(13);
        localToolHeader.setTextColor(cText);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(localToolHeader, tlp);

        localToolChevron = new ImageView(this);
        int chevId = getResources().getIdentifier("ic_ctl_chevron", "drawable", getPackageName());
        if (chevId != 0) {
            localToolChevron.setImageResource(chevId);
            localToolChevron.setColorFilter(cDesc);
            head.addView(localToolChevron, new LinearLayout.LayoutParams(dp(13), dp(13)));
        }
        wrap.addView(head);

        localToolBox = new LinearLayout(this);
        localToolBox.setOrientation(LinearLayout.VERTICAL);
        for (File f : tools) localToolBox.addView(buildToolRow(f));
        localToolBox.setVisibility(localExpanded ? View.VISIBLE : View.GONE);
        if (localToolChevron != null) {
            localToolChevron.setRotation(localExpanded ? 180f : 0f);
        }
        wrap.addView(localToolBox);

        head.setOnClickListener(v -> {
            localExpanded = !localExpanded;
            localToolBox.setVisibility(localExpanded ? View.VISIBLE : View.GONE);
            if (localToolChevron != null) {
                localToolChevron.animate().rotation(localExpanded ? 180f : 0f)
                        .setDuration(160).start();
            }
        });

        return wrap;
    }

    /** 补探没有版本号的工具，串行跑（并行起进程在低端机上容易卡） */
    private void probeMissingVersions(java.util.List<File> tools) {
        if (probeRunning) return;
        final java.util.List<File> todo = new java.util.ArrayList<>();
        for (File f : tools) {
            if (!toolVersions.containsKey(f.getName())) todo.add(f);
        }
        if (todo.isEmpty()) return;
        probeRunning = true;
        final ArtExService svc = ArtExService.getOrCreate(getApplicationContext());
        final File dir = ArtExService.extToolsDir(this);
        new Thread(() -> {
            try {
                ToolImporter ti = new ToolImporter(PanelActivity.this, dir,
                        svc.probePath(), svc.probeHome(), svc.probeTmp());
                for (File f : todo) {
                    // 批量补探用 quick 模式：只试 -version / --version。
                    // 探不到就留空，用户点「重新检测」再走完整探测——
                    // 27 个工具每个都试满 5 个参数，首次展开要等几分钟。
                    ToolImporter.Probe pr = ti.probeExisting(f, true);
                    // 探测失败也写进去（空串），避免每次展开都重试同一个坏文件
                    saveToolVersion(f.getName(), pr.usable ? pr.version : "");
                }
            } finally {
                probeRunning = false;
            }
            runOnUiThread(PanelActivity.this::refreshToolList);
        }, "tool-probe-all").start();
    }

    /** 工具列表里的一行：名字 / 体积 / 版本 */
    private View buildToolRow(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(9), dp(16), dp(9));
        row.setClickable(true);
        makeRipple(row);
        row.setFocusable(true);
        row.setOnClickListener(v -> showToolActions(f));

        // 这个列表里每一项都是"Installed"，整行染绿反而失去区分度。
        // 用一个小绿点做标记：扫一眼知道这是可用状态，又不至于满屏绿。
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dg =
                new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(cOk);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.rightMargin = dp(9);
        row.addView(dot, dotLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(13);
        name.setTextColor(cText);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);

        TextView meta = new TextView(this);
        String ver = toolVersions.get(f.getName());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US, "%.1f MB", f.length() / 1048576.0));
        if (ver != null && !ver.isEmpty()) sb.append(" · ").append(ver);
        meta.setText(sb.toString());
        meta.setTextSize(10);
        meta.setTextColor(cDesc);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(2);
        texts.addView(meta, mlp);

        row.addView(texts, tlp);
        return row;
    }

    /** 列表底部的一条操作行，缩进和工具行对齐 */
    /**
     * 操作行做成描边按钮。
     *
     * 之前是纯文字，和折叠头一样是 16dp 缩进 + 13sp，唯一区别是右边有没有箭头，
     * 扫一眼分不出哪个能点、哪个能展开。描边把这个区别变成结构性的。
     *
     * 宽度按文字自适应（不撑满），按钮只包住自己的字。
     */
    private View buildToolAction(String label, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(label);
        // 字号和工具行 / 折叠头一致（13sp）——按钮是列表里的一个操作项
        tv.setTextSize(13);
        tv.setTextColor(cText);
        tv.setGravity(Gravity.CENTER);
        // TextView 默认带字体上下留白，在这么小的框里会显得框比字大一圈
        tv.setIncludeFontPadding(false);
        // 内边距只留贴边的量：横向 6dp、纵向 2dp
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        tv.setClickable(true);
        tv.setFocusable(true);

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(4));
        bg.setStroke(Math.max(1, dp(1)), cBorder);
        bg.setColor(0x00000000);
        try {
            int pressed = isDarkPalette() ? 0x0FFFFFFF : 0x08000000;
            tv.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(pressed), bg, bg));
        } catch (Throwable t) {
            tv.setBackground(bg);
        }

        tv.setOnClickListener(v -> onClick.run());

        // 左侧 16dp：和「已安装」「从 GitHub 下载」这些折叠头对齐
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(dp(16), dp(8), dp(16), dp(8));
        wrap.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 底部：GitHub 图标 + @Srysfu，居中，点击打开主页 */
    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(10), dp(16), dp(16));
        footer.setClickable(true);
        makeRipple(footer);
        footer.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Srysfu")));
            } catch (Throwable t) {
                Toast.makeText(PanelActivity.this, "Unable to open browser",
                        Toast.LENGTH_SHORT).show();
            }
        });

        ImageView gh = new ImageView(this);
        int ghId = getResources().getIdentifier("ic_github", "drawable", getPackageName());
        if (ghId != 0) {
            gh.setImageResource(ghId);
            // 矢量图默认是深色填充，这里统一成次要文字色，跟随深浅主题
            gh.setColorFilter(cDesc);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(14), dp(14));
            ilp.rightMargin = dp(6);
            footer.addView(gh, ilp);
        }

        TextView name = new TextView(this);
        name.setText("@Srysfu");
        name.setTextSize(12);
        name.setTextColor(cDesc);
        footer.addView(name);

        return footer;
    }

    /**
     * 给可点击行加按压反馈。
     *
     * 面板里十几个可点元素原来都没有 background，按下去屏幕上毫无变化，
     * 用户要等输出区更新才知道点上了。Ripple 是最轻量的一致做法。
     * 深色主题用白色 10% 叠加，浅色用黑色 5%——都在不刺眼的范围内。
     */
    private void makeRipple(View v) {
        try {
            int pressed = isDarkPalette() ? 0x0FFFFFFF : 0x08000000;
            // 第三个参数（mask）给个实心 Drawable 才能把涟漪限制在视图边界内。
            // 传 null 是无边界涟漪，会扩散到整行之外，看着像半个屏幕在闪。
            v.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(pressed), null,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        } catch (Throwable ignored) {}
    }

    /** 当前是不是深色配色（按背景亮度判断，不依赖系统 flags） */
    private boolean isDarkPalette() {
        int r = (cBg >> 16) & 0xFF, g = (cBg >> 8) & 0xFF, b = cBg & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 128;
    }

    /** 一行功能项：图标 + 文字，点击整行都响应 */
    private View buildItem(String iconName, String label, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        makeRipple(row);
        row.setFocusable(true);
        row.setOnClickListener(onClick);

        int id = getResources().getIdentifier(iconName, "drawable", getPackageName());
        if (id != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(id);
            // 矢量图里写死了描边色，用 tint 拉到当前主题的文字色
            icon.setColorFilter(cText);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(17), dp(17));
            ilp.rightMargin = dp(10);
            row.addView(icon, ilp);
        }

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(cText);
        row.addView(tv);

        return row;
    }

    /** 输出区保留的行数。再多会挤掉工具列表的可见区域 */
    private static final int DETAIL_MAX_LINES = 6;
    private final java.util.ArrayDeque<String> detailLines = new java.util.ArrayDeque<>();

    /**
     * 往输出区追加一行。
     *
     * 原来是「覆盖式」的：下载时 18% → 100% → 校验通过 → 已安装，
     * 用户只能看到最后一句，前一步的关键信息（比如 sha256 结果）全丢。
     * 改成保留最近 6 行，旧的从顶部滚出。
     */
    private void appendDetail(String s) {
        runOnUiThread(() -> {
            if (detail == null) return;
            if (s == null || s.isEmpty()) return;
            // 进度行会连发很多条，同前缀的只保留最后一条，不刷屏
            String head = s.length() > 12 ? s.substring(0, 12) : s;
            if (!detailLines.isEmpty()) {
                String last = detailLines.peekLast();
                if (last != null && last.length() > 12
                        && last.substring(0, 12).equals(head)) {
                    detailLines.pollLast();
                }
            }
            detailLines.addLast(s);
            while (detailLines.size() > DETAIL_MAX_LINES) detailLines.pollFirst();
            detail.setVisibility(View.VISIBLE);
            detail.setText(android.text.TextUtils.join("\n", detailLines));
        });
    }

    /** 备份：先问完整还是精简，再在后台跑 */
    private void startBackup() {
        if (busyForNewTask()) return;
        new AlertDialog.Builder(this)
                .setTitle("Backup")
                .setItems(new String[]{
                        "Full backup (including task files)",
                        "Compact backup (database and records only, excluding task files)"
                }, (d, which) -> {
                    final boolean lite = (which == 1);
                    setWorking(true);
                    appendDetail(lite ? "Starting compact backup…" : "Starting backup…");
                    final ArtExService svc = ArtExService.getOrCreate(PanelActivity.this);
                    new Thread(() -> new BackupManager(PanelActivity.this)
                            .run(svc, lite, new BackupManager.Callback() {
                                @Override public void onProgress(String msg) {
                                    appendDetail(msg);
                                }

                                @Override public void onDone(String path) {
                                    // 备份前 stop 过服务（不停库拷目录会拿到
                                    // 半写的 WAL，恢复时 PG 可能直接拒绝启动），
                                    // 这里必须拉起来，否则用户回页面全是连接失败。
                                    appendDetail("Backup completed:\n" + path);
                                    appendDetail("Restarting service…");
                                    restartService();
                                    runOnUiThread(() -> {
                                        try {
                                            Toast.makeText(PanelActivity.this,
                                                    "Backup completed", Toast.LENGTH_SHORT).show();
                                        } catch (Throwable ignored) {}
                                    });
                                }

                                @Override public void onError(String msg) {
                                    // 失败时服务同样是停的，也得拉起来
                                    appendDetail("Backup failed: " + msg);
                                    appendDetail("Restarting service…");
                                    restartService();
                                }
                            }), "backup").start();
                })
                .show();
    }

    /** 恢复：二次确认 → SAF 选 zip → 后台执行 */
    private void confirmRestore() {
        if (busyForNewTask()) return;
        new AlertDialog.Builder(this)
                .setTitle("Restore backup")
                .setMessage("The selected backup will replace all current data (database and workspace).\n\n"
                        + "Current data will be backed up and automatically restored if recovery fails.\n\nContinue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Select backup file", (d, w) -> {
                    try {
                        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        it.addCategory(Intent.CATEGORY_OPENABLE);
                        it.setType("*/*");
                        startActivityForResult(it, REQ_PICK_ZIP);
                    } catch (Throwable t) {
                        appendDetail("Unable to open file picker: " + t);
                    }
                })
                .show();
    }

    /** 工具版本号缓存：试跑一次要几秒，列表滚动时不该反复探 */
    /**
     * 版本号缓存。内存 + SharedPreferences 双写：探测一次要 2.5 秒起，
     * 重启后重探 27 个工具是纯浪费。首次启动从 prefs 恢复，
     * 之后每次探测成功都写回去。
     */
    private final java.util.Map<String, String> toolVersions = new java.util.HashMap<>();
    private static final String PREFS_TOOL_VER = "tool_versions";

    private void loadToolVersions() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS_TOOL_VER, MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) toolVersions.put(e.getKey(), (String) v);
            }
        } catch (Throwable ignored) {}
    }

    private void saveToolVersion(String name, String version) {
        toolVersions.put(name, version);
        try {
            getSharedPreferences(PREFS_TOOL_VER, MODE_PRIVATE).edit()
                    .putString(name, version).apply();
        } catch (Throwable ignored) {}
    }

    private void forgetToolVersion(String name) {
        toolVersions.remove(name);
        try {
            getSharedPreferences(PREFS_TOOL_VER, MODE_PRIVATE).edit()
                    .remove(name).apply();
        } catch (Throwable ignored) {}
    }
    /** 补探进行中，避免展开/收起反复触发 */
    private boolean probeRunning;
    /** GitHub 下载区块的折叠容器 + 标题 + 箭头 */
    private LinearLayout dlListBox;
    private TextView dlHeaderLabel;
    private ImageView dlChevron;
    /** 下载区块展开状态，刷新时保留 */
    private boolean dlExpanded;

    /** Skill 区块的折叠容器 / 标题 / 箭头 */
    private LinearLayout skillListBox;
    private TextView skillHeaderLabel;
    private ImageView skillChevron;
    private boolean skillExpanded;
    /** skill 清单缓存 */
    private java.util.List<SkillCatalog.Entry> skillCatalogCache;
    /** 后端已装 skill 名（拿不到时为 null） */
    private java.util.Set<String> installedSkills;
    private boolean skillBusy;

    /** 点某个已导入的工具：重新检测 / 删除 */
    private void showToolActions(File f) {
        String ver = toolVersions.get(f.getName());
        String sub = ver == null ? "Not checked" : (ver.isEmpty() ? "Checked; version unavailable" : ver);
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage("Version: " + sub + "\n"
                        + "Path: " + f.getAbsolutePath())
                .setNeutralButton("Delete", (d, w) -> confirmDeleteTool(f))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Check again", (d, w) -> recheckTool(f))
                .show();
    }

    private void confirmDeleteTool(File f) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + f.getName() + "？")
                .setMessage("The imported tool will be removed from PATH and will no longer be available to the backend.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dd, ww) -> {
                    if (f.delete()) {
                        forgetToolVersion(f.getName());
                        appendDetail("Tool deleted: " + f.getName());
                    } else {
                        appendDetail("Deletion failed: " + f.getName());
                    }
                    refreshToolList();
                })
                .show();
    }

    /** 重新试跑一个已装好的工具，顺带刷新版本号 */
    private void recheckTool(File f) {
        if (busyForNewTask()) return;
        setWorking(true);
        appendDetail("Checking tool: " + f.getName());
        final ArtExService svc = ArtExService.getOrCreate(getApplicationContext());
        final File dir = ArtExService.extToolsDir(this);
        new Thread(() -> {
            ToolImporter ti = new ToolImporter(this, dir,
                    svc.probePath(), svc.probeHome(), svc.probeTmp());
            try {
                ToolImporter.Probe pr = ti.probeExisting(f);
                if (pr.usable) {
                    if (!pr.version.isEmpty()) saveToolVersion(f.getName(), pr.version);
                    appendDetail(pr.version.isEmpty()
                            ? "Available (version unavailable)"
                            : "Available: " + pr.version);
                } else {
                    forgetToolVersion(f.getName());
                    appendDetail("Unavailable: " + pr.detail);
                }
            } finally {
                setWorking(false);
            }
            runOnUiThread(PanelActivity.this::refreshToolList);
        }, "tool-check").start();
    }

    /** 缓存拉到的清单，避免每次打开都请求一次 GitHub */
    private java.util.List<ToolCatalog.Entry> catalogCache;

    /** 后端 API 根地址。壳和后端同机，端口写死 */
    private static final String API_BASE = "http://127.0.0.1:8787";

    /**
     * 从 WebView 的 cookie 里取 JWT。
     *
     * 后端 extractToken 认 Authorization / cookie artex_token / ?token=，
     * 登录时前端已经把 artex_token 写进 cookie，直接复用最省事，
     * 不需要壳自己保存密码或再走一次登录接口。
     */
    private String currentToken() {
        try {
            String cookie = android.webkit.CookieManager.getInstance()
                    .getCookie(API_BASE);
            if (cookie == null) return "";
            for (String part : cookie.split(";")) {
                String p = part.trim();
                if (p.startsWith("artex_token=")) {
                    return p.substring("artex_token=".length());
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** Skill 区块折叠头 */
    private View buildSkillSection() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(16), dp(10));
        head.setClickable(true);
        head.setFocusable(true);
        makeRipple(head);

        prependIcon(head, "ic_ctl_skill", 15);

        skillHeaderLabel = new TextView(this);
        skillHeaderLabel.setText("Skill catalog");
        skillHeaderLabel.setTextSize(13);
        skillHeaderLabel.setTextColor(cText);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(skillHeaderLabel, tlp);

        skillChevron = new ImageView(this);
        int chevId = getResources().getIdentifier("ic_ctl_chevron", "drawable", getPackageName());
        if (chevId != 0) {
            skillChevron.setImageResource(chevId);
            skillChevron.setColorFilter(cDesc);
            head.addView(skillChevron, new LinearLayout.LayoutParams(dp(13), dp(13)));
        }
        wrap.addView(head);

        skillListBox = new LinearLayout(this);
        skillListBox.setOrientation(LinearLayout.VERTICAL);
        skillListBox.setVisibility(View.GONE);
        wrap.addView(skillListBox);

        head.setOnClickListener(v -> {
            skillExpanded = !skillExpanded;
            if (skillExpanded) {
                refreshSkillList();
            } else {
                skillListBox.setVisibility(View.GONE);
            }
            if (skillChevron != null) {
                skillChevron.animate().rotation(skillExpanded ? 180f : 0f)
                        .setDuration(160).start();
            }
        });

        return wrap;
    }

    /**
     * 渲染 skill 列表。
     *
     * 每行直接显示 SKILL.md 里的 description —— 这是 AI 判断
     * 「何时加载这个 skill」的唯一依据，也是用户判断它干嘛的依据。
     * 只显示名字的话，两个 skill 在列表里看不出任何区别。
     */
    private void refreshSkillList() {
        if (skillListBox == null) return;
        skillListBox.setVisibility(View.VISIBLE);
        skillCatalogCache = null;
        skillListBox.removeAllViews();
        skillHeaderLabel.setText("Skill catalog · Loading…");

        final String token = currentToken();
        new Thread(() -> {
            SkillCatalog sc = new SkillCatalog(this, API_BASE, token);
            final java.util.Set<String> installed = sc.installedNames();
            sc.fetch(new SkillCatalog.FetchCallback() {
                @Override public void onCatalog(java.util.List<SkillCatalog.Entry> entries) {
                    skillCatalogCache = entries;
                    installedSkills = installed;
                    runOnUiThread(() -> renderSkillList(entries));
                }

                @Override public void onError(String msg) {
                    runOnUiThread(() -> {
                        if (skillListBox == null) return;
                        skillListBox.removeAllViews();
                        skillHeaderLabel.setText("Skill catalog · Failed");
                        TextView tv = new TextView(PanelActivity.this);
                        tv.setText(msg);
                        tv.setTextSize(11);
                        tv.setTextColor(cDesc);
                        tv.setPadding(dp(30), dp(4), dp(16), dp(10));
                        skillListBox.addView(tv);
                    });
                }
            });
        }, "skill-fetch").start();
    }

    private void renderSkillList(java.util.List<SkillCatalog.Entry> entries) {
        if (skillListBox == null) return;
        skillListBox.removeAllViews();
        int installedCount = 0;
        for (final SkillCatalog.Entry e : entries) {
            boolean on = installedSkills != null && installedSkills.contains(e.name);
            if (on) installedCount++;
            skillListBox.addView(buildSkillRow(e, on));
        }
        if (skillHeaderLabel != null) {
            skillHeaderLabel.setText("Skill catalog · " + installedCount + "/" + entries.size());
        }
    }

    /** 一行 skill：名字 + 版本 + 长描述 */
    private View buildSkillRow(final SkillCatalog.Entry e, boolean installed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(30), dp(8), dp(16), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        makeRipple(row);

        TextView name = new TextView(this);
        name.setText(e.name + (e.version.isEmpty() ? "" : "  v" + e.version)
                + (installed ? "  (Installed)" : ""));
        name.setTextSize(12);
        // 已装的用绿色，比原来"压暗成次要色"醒目 —— 装没装才是要看的信息
        name.setTextColor(installed ? cOk : cText);
        row.addView(name);

        TextView desc = new TextView(this);
        // 描述不截断：它就是"这个 skill 干嘛的"，截了等于没显示
        desc.setText(e.description);
        desc.setTextSize(10);
        desc.setTextColor(cDesc);
        desc.setMaxLines(4);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        row.addView(desc, dlp);

        row.setOnClickListener(v -> showSkillActions(e, installed));
        return row;
    }

    private void showSkillActions(final SkillCatalog.Entry e, boolean installed) {
        if (installed) {
            new AlertDialog.Builder(this)
                    .setTitle(e.name + "  v" + e.version)
                    .setMessage(e.description)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Delete", (d, w) -> uninstallSkill(e))
                    .setPositiveButton("Reinstall", (d, w) -> installSkill(e, true))
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(e.name + "  v" + e.version)
                    .setMessage(e.description + "\n\nInstall into the backend skills directory?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Install", (d, w) -> installSkill(e, false))
                    .show();
        }
    }

    /**
     * skill 操作和工具操作共用一个忙碌判据。
     *
     * 原来 working（工具）和 skillBusy（skill）互不感知：正在下 126MB 的
     * nuclei 时点 skill 安装会被放行，两个后台任务同时往输出区写，
     * 进度行互相覆盖、看不出到底在干什么。
     */
    private boolean busyForNewTask() {
        if (working || skillBusy) {
            appendDetail("Working…");
            return true;
        }
        return false;
    }

    private void installSkill(final SkillCatalog.Entry e, final boolean overwrite) {
        if (busyForNewTask()) return;
        skillBusy = true;
        appendDetail("Installing skill: " + e.name);
        final String token = currentToken();
        if (token.isEmpty()) {
            skillBusy = false;
            appendDetail("Installation failed: not signed in (artex_token cookie missing)");
            return;
        }
        new Thread(() -> {
            try {
                new SkillCatalog(this, API_BASE, token).install(e, overwrite,
                        new SkillCatalog.InstallCallback() {
                            @Override public void onProgress(String msg) {
                                appendDetail(msg);
                            }

                            @Override public void onDone(String name) {
                                appendDetail("Skill installed: " + name);
                                // 装完刷新，让「已安装」标记立刻更新
                                final String t2 = currentToken();
                                java.util.Set<String> now =
                                        new SkillCatalog(PanelActivity.this, API_BASE, t2)
                                                .installedNames();
                                if (now != null) installedSkills = now;
                                runOnUiThread(() -> {
                                    if (skillCatalogCache != null) renderSkillList(skillCatalogCache);
                                    try {
                                        Toast.makeText(PanelActivity.this,
                                                "Installed: " + name, Toast.LENGTH_SHORT).show();
                                    } catch (Throwable ignored) {}
                                });
                            }

                            @Override public void onError(String msg) {
                                appendDetail("Installation failed: " + msg);
                            }
                        });
            } finally {
                skillBusy = false;
            }
        }, "skill-install").start();
    }

    private void uninstallSkill(final SkillCatalog.Entry e) {
        if (busyForNewTask()) return;
        skillBusy = true;
        appendDetail("Deleting skill: " + e.name);
        final String token = currentToken();
        new Thread(() -> {
            try {
                boolean ok = new SkillCatalog(this, API_BASE, token).delete(e.name);
                if (ok) {
                    appendDetail("Deleted: " + e.name);
                    if (installedSkills != null) installedSkills.remove(e.name);
                    runOnUiThread(() -> {
                        if (skillCatalogCache != null) renderSkillList(skillCatalogCache);
                    });
                } else {
                    appendDetail("Deletion failed: " + e.name);
                }
            } finally {
                skillBusy = false;
            }
        }, "skill-delete").start();
    }

    /**
     * GitHub 下载区块：折叠头 + 清单列表。
     *
     * 和工具列表一样做成内联的，不再弹对话框 —— 27 条在对话框里
     * 不能滚动、看不清版本号，也没法和已装列表对照着看。
     */
    private View buildDownloadSection() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(16), dp(10));
        head.setClickable(true);
        makeRipple(head);
        head.setFocusable(true);

        prependIcon(head, "ic_ctl_remote", 15);

        dlHeaderLabel = new TextView(this);
        dlHeaderLabel.setText("Download from GitHub");
        dlHeaderLabel.setTextSize(13);
        dlHeaderLabel.setTextColor(cText);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(dlHeaderLabel, tlp);

        dlChevron = new ImageView(this);
        int chevId = getResources().getIdentifier("ic_ctl_chevron", "drawable", getPackageName());
        if (chevId != 0) {
            dlChevron.setImageResource(chevId);
            dlChevron.setColorFilter(cDesc);
            head.addView(dlChevron, new LinearLayout.LayoutParams(dp(13), dp(13)));
        }
        wrap.addView(head);

        dlListBox = new LinearLayout(this);
        dlListBox.setOrientation(LinearLayout.VERTICAL);
        dlListBox.setVisibility(View.GONE);
        wrap.addView(dlListBox);

        head.setOnClickListener(v -> {
            dlExpanded = !dlExpanded;
            if (dlExpanded) {
                showDownloadList();
            } else {
                dlListBox.setVisibility(View.GONE);
            }
            if (dlChevron != null) {
                dlChevron.animate().rotation(dlExpanded ? 180f : 0f)
                        .setDuration(160).start();
            }
        });

        return wrap;
    }

    /**
     * 从 GitHub 拉清单并列出可下载的工具。
     *
     * 分批显示：已装的高亮标出，未装的给「下载」。一次装完 27 个不太现实
     * （888MB），所以不做「全选」，让用户按需点。
     */
    private void showDownloadList() {
        if (dlListBox == null) return;
        if (catalogCache != null) {
            renderDownloadList(catalogCache);
            if (dlChevron != null) dlChevron.setRotation(180f);
            return;
        }
        dlListBox.removeAllViews();
        dlListBox.setVisibility(View.VISIBLE);
        dlHeaderLabel.setText("Download from GitHub · Loading…");
        appendDetail("Fetching tool catalog…");
        new Thread(() -> new ToolCatalog(this).fetch(new ToolCatalog.FetchCallback() {
            @Override public void onCatalog(java.util.List<ToolCatalog.Entry> entries) {
                catalogCache = entries;
                appendDetail("Catalog loaded: " + entries.size() + " tools");
                runOnUiThread(() -> renderDownloadList(entries));
            }

            @Override public void onError(String msg) {
                appendDetail("Unable to load catalog: " + msg);
                runOnUiThread(() -> {
                    if (dlListBox == null) return;
                    dlListBox.removeAllViews();
                    dlHeaderLabel.setText("Download from GitHub · Failed");
                    TextView tv = new TextView(PanelActivity.this);
                    tv.setText(msg);
                    tv.setTextSize(11);
                    tv.setTextColor(cDesc);
                    tv.setPadding(dp(30), dp(4), dp(16), dp(10));
                    dlListBox.addView(tv);
                });
            }
        }), "catalog-fetch").start();
    }

    /** 分类的展示顺序和中文名。顺序按攻击流程排：先摸清面再打 */
    private static final String[][] CATEGORY_ORDER = {
            {"recon", "资产测绘"},
            {"probe", "探测指纹"},
            {"discover", "内容发现"},
            {"scan", "漏洞扫描"},
            {"tunnel", "隧道穿透"},
            {"util", "Other"},
    };

    /**
     * 把清单渲染成面板里的行，按分类分组。
     *
     * 27 项平铺按字母序排，找 nuclei 要滚到中间；而清单里本来就带
     * category 字段，按攻击面分组后「要找漏洞扫描工具」就是一眼的事。
     */
    private void renderDownloadList(java.util.List<ToolCatalog.Entry> entries) {
        if (dlListBox == null) return;
        dlListBox.removeAllViews();
        dlListBox.setVisibility(View.VISIBLE);
        final File dir = ArtExService.extToolsDir(this);

        int installedCount = 0;
        java.util.Map<String, java.util.List<ToolCatalog.Entry>> grouped =
                new java.util.HashMap<>();
        for (ToolCatalog.Entry e : entries) {
            boolean installed = new File(dir, e.name).exists();
            if (installed) installedCount++;
            String cat = e.category == null || e.category.isEmpty() ? "other" : e.category;
            java.util.List<ToolCatalog.Entry> bucket = grouped.get(cat);
            if (bucket == null) {
                bucket = new java.util.ArrayList<>();
                grouped.put(cat, bucket);
            }
            bucket.add(e);
        }

        for (String[] pair : CATEGORY_ORDER) {
            java.util.List<ToolCatalog.Entry> bucket = grouped.remove(pair[0]);
            if (bucket == null || bucket.isEmpty()) continue;
            addCategoryHeader(pair[1], bucket.size());
            for (ToolCatalog.Entry e : bucket) {
                dlListBox.addView(buildDownloadRow(e, new File(dir, e.name).exists()));
            }
        }
        // 清单里出现表外分类时不吞掉，补在末尾
        for (java.util.Map.Entry<String, java.util.List<ToolCatalog.Entry>> rest : grouped.entrySet()) {
            if (rest.getValue().isEmpty()) continue;
            addCategoryHeader(rest.getKey(), rest.getValue().size());
            for (ToolCatalog.Entry e : rest.getValue()) {
                dlListBox.addView(buildDownloadRow(e, new File(dir, e.name).exists()));
            }
        }

        if (dlHeaderLabel != null) {
            dlHeaderLabel.setText("Download from GitHub · " + installedCount + "/" + entries.size());
        }
        // 面板变长了，滚到底部按钮
        if (dlListBox.getParent() != null) {
            dlListBox.post(() -> {
                try {
                    ((android.widget.ScrollView) panel).smoothScrollTo(0, dlListBox.getBottom());
                } catch (Throwable ignored) {}
            });
        }
    }

    /** 清单里的分类小标题 */
    private void addCategoryHeader(String label, int count) {
        TextView tv = new TextView(this);
        tv.setText(label + " · " + count);
        tv.setTextSize(10);
        tv.setTextColor(cDesc);
        tv.setPadding(dp(30), dp(10), dp(16), dp(3));
        dlListBox.addView(tv);
    }

    /** 下载清单里的一行：名字/体积/版本 + 描述，已装标勾 */
    private View buildDownloadRow(final ToolCatalog.Entry e, boolean installed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(30), dp(8), dp(16), dp(8));
        row.setClickable(true);
        makeRipple(row);
        row.setFocusable(true);
        row.setOnClickListener(v -> confirmDownload(e));

        if (installed) {
            TextView tick = new TextView(this);
            tick.setText("\u2713");
            tick.setTextSize(11);
            tick.setTextColor(cOk);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.rightMargin = dp(6);
            row.addView(tick, tlp);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView name = new TextView(this);
        StringBuilder head = new StringBuilder(e.name);
        head.append("  ").append(String.format(java.util.Locale.US,
                "%.1f MB", e.size / 1048576.0));
        if (!e.version.isEmpty()) head.append("  v").append(e.version);
        name.setText(head.toString());
        name.setTextSize(12);
        name.setTextColor(installed ? cDesc : cText);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);

        TextView desc = new TextView(this);
        desc.setText(e.description + (installed ? "(Installed)" : ""));
        desc.setTextSize(10);
        desc.setTextColor(installed ? cOk : cDesc);
        desc.setSingleLine(true);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp2.topMargin = dp(1);
        texts.addView(desc, dlp2);

        row.addView(texts, ltp);
        return row;
    }

    /**
     * 点下载项后的处理。
     *
     * 未安装的直接下——体积和版本已经在列表里看过，误点也就是多下一个文件，
     * 面板里随时能删。已安装的才弹确认：覆盖是破坏性操作，值得多问一句。
     */
    private void confirmDownload(ToolCatalog.Entry e) {
        final File dir = ArtExService.extToolsDir(this);
        boolean installed = new File(dir, e.name).exists();
        if (!installed) {
            startDownload(e);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(e.name + "  v" + e.version)
                .setMessage(e.description + "\n\n"
                        + "Size: " + String.format(java.util.Locale.US,
                        "%.1f MB", e.size / 1048576.0)
                        + "\n\nThis tool is already installed. Downloading again will overwrite the existing file.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (d, w) -> startDownload(e))
                .show();
    }

    private void startDownload(ToolCatalog.Entry e) {
        if (busyForNewTask()) return;
        setWorking(true);
        appendDetail("Starting download: " + e.name);
        final File dir = ArtExService.extToolsDir(this);
        final ArtExService svc = ArtExService.getOrCreate(getApplicationContext());
        final ToolImporter ti = new ToolImporter(this, dir,
                svc.probePath(), svc.probeHome(), svc.probeTmp());
        new Thread(() -> new ToolCatalog(this).download(e, dir,
                new ToolCatalog.DownloadCallback() {
                    private long lastPct = -1;

                    @Override public void onProgress(long got, long total) {
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct >= 0 && pct == lastPct) return;
                        lastPct = pct;
                        appendDetail("Downloading " + e.name + "  "
                                + String.format(java.util.Locale.US, "%.1f/%.1f MB",
                                got / 1048576.0, total > 0 ? total / 1048576.0 : 0)
                                + (pct >= 0 ? "  " + pct + "%" : ""));
                    }

                    @Override public void onDone(File file) {
                        try {
                            appendDetail("Download complete; SHA-256 verified");
                            // 走完整链路：静态校验 → 试跑 → 改名。
                            // 只跑 probe 的话，架构不对的二进制会被报成
                            // 「试跑未通过」，误导用户去查工具本身。
                            ToolImporter.Install in = ti.installDownloaded(file, e.name);
                            if (!in.ok) {
                                appendDetail("Installation failed: " + in.reason);
                                return;
                            }
                            if (in.version != null && !in.version.isEmpty()) {
                                saveToolVersion(e.name, in.version);
                            }
                            appendDetail("Installed: " + e.name
                                    + (in.version == null || in.version.isEmpty()
                                    ? "" : "  " + in.version));
                            runOnUiThread(() -> {
                                // 顺序不能反：refreshToolList 会重建整个工具区，
                                // 下载区（含 dlListBox 引用）也跟着重建，
                                // 必须先重建再渲染，否则刷的是旧的那个容器
                                refreshToolList();
                                if (catalogCache != null) renderDownloadList(catalogCache);
                                try {
                                    Toast.makeText(PanelActivity.this,
                                            "Download complete: " + e.name, Toast.LENGTH_SHORT).show();
                                } catch (Throwable ignored) {}
                            });
                        } finally {
                            // 无论成功失败都必须放开，否则 working 卡在 true，
                            // 之后所有操作都被「正在执行，请稍候…」挡住
                            setWorking(false);
                        }
                    }

                    @Override public void onError(String msg) {
                        try {
                            appendDetail("Download failed: " + msg);
                        } finally {
                            setWorking(false);
                        }
                    }
                }), "tool-download").start();
    }

    /** 打开文件选择器。只收裸二进制，zip 请先解压 */
    private void pickToolFile() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_PICK_TOOL);
        } catch (Throwable t) {
            appendDetail("Unable to open file picker: " + t);
        }
    }

    /** 导入：后台跑，进度写面板输出区 */
    private void importTool(Uri uri) {
        if (busyForNewTask()) return;
        setWorking(true);
        String name = "";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx);
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {}
        final String displayName = name;
        appendDetail("Starting import" + (name.isEmpty() ? "…" : "：" + name));
        final File dir = ArtExService.extToolsDir(this);
        final ArtExService svc = ArtExService.getOrCreate(getApplicationContext());
        final ToolImporter ti = new ToolImporter(this, dir,
                svc.probePath(), svc.probeHome(), svc.probeTmp());
        new Thread(() -> ti.run(uri, displayName, new ToolImporter.Callback() {
            @Override public void onProgress(String msg) {
                appendDetail(msg);
            }

            @Override public void onDone(String imported, String version) {
                setWorking(false);
                if (version != null && !version.isEmpty()) {
                    saveToolVersion(imported, version);
                }
                appendDetail("Tool added to PATH and available to the backend");
                runOnUiThread(PanelActivity.this::refreshToolList);
                runOnUiThread(() -> {
                    try {
                        Toast.makeText(PanelActivity.this,
                                "Import successful: " + imported, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                });
            }

            @Override public void onProbeFailed(String toolName, String reason, File pending) {
                appendDetail("Test run failed: " + reason);
                runOnUiThread(() -> new AlertDialog.Builder(PanelActivity.this)
                        .setTitle("Test run failed")
                        .setMessage(reason + "\n\n"
                                + "Some tools require configuration files to start.\n"
                                + "Import anyway?")
                        .setNegativeButton("Discard", (d, w) -> {
                            pending.delete();
                            setWorking(false);
                            appendDetail("Discarded: " + toolName);
                        })
                        .setPositiveButton("Import anyway", (d, w) -> {
                            File dst = ti.accept(pending, toolName);
                            setWorking(false);
                            if (dst != null) {
                                appendDetail("Imported without validation: " + toolName
                                        + " (" + String.format(java.util.Locale.US,
                                        "%.1f MB", dst.length() / 1048576.0) + ")");
                            } else {
                                appendDetail("Forced import failed: " + toolName);
                            }
                            refreshToolList();
                        })
                        .setCancelable(false)
                        .show());
            }

            @Override public void onError(String msg) {
                try {
                    appendDetail("Import failed: " + msg);
                } finally {
                    setWorking(false);
                }
            }
        }), "tool-import").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_TOOL) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importTool(data.getData());
            }
            return;
        }
        if (requestCode != REQ_PICK_ZIP || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        setWorking(true);
        appendDetail("Starting restore…");
        final ArtExService svc = ArtExService.getOrCreate(this);
        new Thread(() -> new RestoreManager(PanelActivity.this)
                .run(svc, uri, new RestoreManager.Callback() {
                    @Override public void onProgress(String msg) {
                        appendDetail(msg);
                    }

                    @Override public void onDone() {
                        setWorking(false);
                        appendDetail("Restore complete; restarting service…");
                        restartService();
                    }

                    @Override public void onError(String msg) {
                        setWorking(false);
                        appendDetail("Restore failed (rolled back): " + msg);
                        restartService();
                    }
                }), "restore").start();
    }

    /** 备份/恢复都停过服务，结束后重新拉起（复用同一实例） */
    /**
     * 备份/恢复都停过服务，结束后要重新拉起。
     * 传一个 listener 而不是 null：启动失败时用户能在面板上看到原因，
     * 而不是只看到 APP 静默死掉。
     */
    private void restartService() {
        try {
            ArtExService.getOrCreate(getApplicationContext())
                    .start(new ArtExService.StatusListener() {
                        @Override public void onStatus(String msg) {
                            appendDetail(msg);
                        }

                        @Override public void onReady() {
                            setWorking(false);
                            appendDetail("Service restored and ready");
                        }

                        @Override public void onError(String msg) {
                            setWorking(false);
                            appendDetail("Restart failed: " + msg);
                        }
                    });
        } catch (Throwable t) {
            setWorking(false);
            appendDetail("Service restart failed: " + t);
        }
    }

    private void dismissPanel() {
        if (panelClosing) return;
        panelClosing = true;
        if (panel == null) {
            finish();
            return;
        }
        panel.animate().translationX(panel.getWidth()).setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(this::finish).start();
        if (panelDim != null) panelDim.animate().alpha(0f).setDuration(200).start();
    }

    @Override
    public void onBackPressed() {
        dismissPanel();
    }

    /** 系统状态栏高度（隐藏状态栏时 insets 可能给 0，用资源值兜底） */
    private int statusBarHeightPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return dp(24);
    }

    private void applyPalette() {
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            cBg = Color.parseColor("#0C0C0F");
            cText = Color.parseColor("#FFFFFF");
            cDesc = 0xB3FFFFFF;
            cBorder = 0x33FFFFFF;
            cOk = Color.parseColor("#4ADE80");   // 深底上要偏亮，纯 #22C55E 偏闷
        } else {
            cBg = Color.parseColor("#F7F8FA");
            cText = Color.parseColor("#000000");
            cDesc = 0xB3000000;
            cBorder = 0x22000000;
            cOk = Color.parseColor("#15803D");   // 浅底上要偏深，否则对比度不够
        }
    }

    private void applyFullscreen() {
        Window w = getWindow();
        if (w == null) return;
        View decor = w.getDecorView();

        w.setStatusBarColor(cBg);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.statusBars());
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyPalette();
        applyFullscreen();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
