package com.artex.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    private static final String TAG = "ARTEX";
    private static final int PORT = 8787;
    private WebView webView;
    private BootConsole console;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int REQ_SCAN = 2002;

    /** 桌面长按「扫描」快捷方式 */
    private static final String ACTION_SCAN = "com.artex.mobile.action.SCAN";

    /** 页面是否已加载完（扫码结果要等它就绪后再填） */
    private volatile boolean pageLoaded;
    /** 后端还没就绪时先存着，onReady 之后再填进新建任务 */
    private volatile String pendingShare;
    /** 上次按返回键的时间，用于"Press back again to exit"判定 */
    private long lastBackTime;
    private ArtExService svc;
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 去掉系统标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 只隐藏状态栏：内容延伸到状态栏区域，导航栏保持正常占位
        applyImmersiveFullscreen();

        root = new FrameLayout(this);
        webView = new WebView(this);
        console = new BootConsole(this);
        // 状态栏区域露出的是容器底色，必须与页面背景一致
        applyContainerBackground();

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(console, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // setDecorFitsSystemWindows(false) 后内容会延伸到系统栏与挖孔区，
        // 这里补内边距：底部避开导航栏，顶部避开摄像头挖孔。
        // 只让出挖孔实际占的那点高度，顶部其余部分仍由界面背景铺满，不会出现黑边。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int top = insets.getInsets(WindowInsets.Type.displayCutout()).top;
                int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                    v.setPadding(0, top, 0, bottom);
                }
            }
            return insets;
        });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 让前端主题跟随系统深浅色。前端把主题存在 cookie theme_mode 里
        // （light / dark / system，默认 light），theme-boot 首屏渲染前读它；
        // system 模式下前端自己用 matchMedia 监听系统变化，不需要重载页面。
        applyThemeModeToWeb();
        webView.addJavascriptInterface(new PageBridge(), "ArtexBridge");
        // 与手机浏览器一致：尊重页面 viewport meta（width=device-width），响应式布局生效
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        webView.setInitialScale(100);   // 强制 100% 缩放（浏览器语义）
        installTwoFingerSwipe(webView);
        installDownloadListener();

        webView.setWebChromeClient(new WebChromeClient() {
            // 支持网页里的 <input type=file> 上传
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    String[] accept = params != null ? params.getAcceptTypes() : null;
                    intent.setType("*/*");
                    if (accept != null && accept.length == 1 && accept[0] != null && !accept[0].isEmpty()) {
                        intent.setType(accept[0]);
                    }
                    startActivityForResult(Intent.createChooser(intent, "Select file"), FILE_CHOOSER_REQ);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Unable to open file picker", e);
                    filePathCallback = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                Log.e(TAG, "Resource error " + req.getUrl() + ": " + err.getDescription());
                if (req.isForMainFrame()) {
                    runOnUiThread(() -> console.log("[ERR] Page load failed " + req.getUrl() + ": " + err.getDescription()));
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                final String ps = pendingShare;
                if (ps != null) {
                    pendingShare = null;
                    view.postDelayed(() -> injectShare(ps), 600);
                }
                // 高度/单位兼容修复 + 禁止缩放
                String js = "(function(){" +
                    "if(window.__artexFit)return;window.__artexFit=1;" +
                    "function style(id,css){if(document.getElementById(id))return;var s=document.createElement('style');s.id=id;s.textContent=css;document.head.appendChild(s);}" +
                    // 视口：user-scalable=no + maximum-scale=1 禁止缩放
                    "var VP='width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no';" +
                    "function fixVP(){var vp=document.querySelector('meta[name=viewport]');" +
                    "if(vp){if(vp.getAttribute('content')!==VP)vp.setAttribute('content',VP);}" +
                    "else{var e=document.createElement('meta');e.name='viewport';e.setAttribute('content',VP);document.head.appendChild(e);}}" +
                    "fixVP();" +
                    // 页面底色推给原生：前端切主题不重载页面，靠每秒轮询同步。
                    // 多点采样——页面顶部可能本就没元素，只取一点会一无所获。
                    "window.__artexSyncBg=function(){var c='';" +
                    "try{var pts=[[5,5],[Math.floor(window.innerWidth/2),Math.floor(window.innerHeight/2)]," +
                    "[window.innerWidth-5,Math.floor(window.innerHeight/2)]," +
                    "[Math.floor(window.innerWidth/2),window.innerHeight-5]];" +
                    "for(var i=0;i<pts.length&&!c;i++){var el=document.elementFromPoint(pts[i][0],pts[i][1]);" +
                    "while(el){var bg=getComputedStyle(el).backgroundColor;" +
                    "if(bg&&bg!=='transparent'&&bg!=='rgba(0, 0, 0, 0)'){c=bg;break;}el=el.parentElement;}}" +
                    "if(!c){var b=document.body?getComputedStyle(document.body).backgroundColor:'';" +
                    "if(b&&b!=='transparent'&&b!=='rgba(0, 0, 0, 0)')c=b;}}catch(e){}" +
                    "if(!c)return;" +
                    "if(c!==window.__artexBg){window.__artexBg=c;" +
                    // 把该色补给 html/body：页面顶部本身没有元素时靠它铺满，
                    // 否则会露出原生 window 背景（跟系统深浅色，与前端主题不一致）
                    "try{var s=document.getElementById('artex-bgfill');" +
                    "if(!s){s=document.createElement('style');s.id='artex-bgfill';document.head.appendChild(s);}" +
                    "s.textContent='html,body{background-color:'+c+'!important}';}catch(e){}" +
                    "try{if(window.ArtexBridge)window.ArtexBridge.setBackground(c);}catch(e){}}};" +
                    // 溢出裁剪：只处理「真的溢出」且 overflow 为 hidden/clip 的容器，
                    // 改成可横向滚动。
                    //
                    // 读 scrollWidth/clientWidth 会强制同步布局（reflow），文档里几百个
                    // div 时每秒全扫一遍就是每秒一次全量 reflow，滚动会发涩。
                    // 所以改成 MutationObserver 驱动 + 1.5 秒节流：DOM 没变就不扫。
                    "window.__artexFixOverflow=function(){try{" +
                    "var els=document.querySelectorAll('div,table,pre,section,main');" +
                    "for(var i=0;i<els.length;i++){var el=els[i];if(el.__artexOv)continue;" +
                    "if(el.scrollWidth>el.clientWidth+2){var ov=getComputedStyle(el).overflowX;" +
                    "if(ov==='hidden'||ov==='clip'){el.__artexOv=1;el.style.overflowX='auto';" +
                    "el.style.webkitOverflowScrolling='touch';}}}}catch(e){}};" +
                    // 节流调度：连续变更只在停止变更 400ms 后扫一次，且最短间隔 1.5s
                    "window.__artexOvSchedule=function(){if(window.__artexOvPending)return;" +
                    "window.__artexOvPending=1;setTimeout(function(){" +
                    "window.__artexOvPending=0;" +
                    "var now=Date.now();" +
                    "if(now-(window.__artexOvLast||0)<1500)return;" +
                    "window.__artexOvLast=now;window.__artexFixOverflow();},400);};" +
                    // DOM 变化才触发，首次挂上后立刻扫一遍
                    "try{if(window.MutationObserver&&!window.__artexOvObs){" +
                    "window.__artexOvObs=new MutationObserver(function(){window.__artexOvSchedule();});" +
                    "window.__artexOvObs.observe(document.body,{childList:true,subtree:true});" +
                    "window.__artexOvSchedule();}}catch(e){}" +
                    // 移动端侧边栏：选完菜单项自动收起。上游在移动端把它关成 Sheet，
                    // 还特意隐藏了关闭按钮（[&>button]:hidden），所以点完会一直挡着内容。
                    // 先发 Escape（Radix Dialog 默认响应），160ms 后未关掉再补点关闭按钮。
                    "window.__artexNavClose=function(){" +
                    "if(window.__artexNavCloseOn)return;window.__artexNavCloseOn=1;" +
                    "document.addEventListener('click',function(e){try{" +
                    "var t=e.target;if(!t||!t.closest)return;" +
                    "var sheet=t.closest('[data-slot=sidebar][data-mobile=true]');if(!sheet)return;" +
                    "if(!t.closest('a[href],[data-slot^=sidebar-menu]'))return;" +
                    "setTimeout(function(){try{document.dispatchEvent(new KeyboardEvent('keydown'," +
                    "{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true}));}catch(x){}" +
                    "setTimeout(function(){try{var b=sheet.querySelector('[data-slot=sheet-close]');" +
                    "if(b)b.click();}catch(x){}},160);},40);" +
                    "}catch(err){}},true);};" +
                    "window.__artexNavClose();" +
                    // CSS：touch-action 不含 pinch-zoom
                    "function fixAll(){" +
                    "style('artex-fit','html,body{height:100%!important;min-height:100%!important;touch-action:pan-x pan-y!important}'" +
                    "+'[data-slot=sidebar-inset]{min-height:100vh!important}');" +
                    // 侧边面板：上游写了桌面 min-width（480/420px），手机只有 412px，会溢出到左边
                    "style('artex-sheet-fit','@media (max-width:700px){[data-slot=sheet-content]{" +
                    "min-width:0!important;width:100%!important;max-width:100%!important}}');" +
                    // 长串断行：URL/payload/哈希不会自动换行，窄屏下顶破容器被裁；代码块除外
                    "style('artex-wrap','@media (max-width:700px){p,span,a,label,li,dt,dd,td,th," +
                    "h1,h2,h3,h4,h5,h6,div{overflow-wrap:anywhere!important}" +
                    ":is(pre,code,kbd,samp){overflow-wrap:normal!important}pre{overflow-x:auto!important}}');" +
                    "if(!(window.CSS&&CSS.supports&&CSS.supports('height','100svh')&&CSS.supports('height','100dvh'))){" +
                    "style('artex-unit-fix','.h-svh,.h-dvh,.h-lvh{height:100vh!important}.min-h-svh,.min-h-dvh,.min-h-lvh{min-height:100vh!important}');}" +
                    // 工具卡片描述放开 + 顶部栏收紧。必须无条件执行 —— 曾误放进上面的 if(!svh) 里，
                    // 现代 WebView 都支持 svh，整段被跳过，连续三版修复无效
                    "style('artex-toolcard-desc','p.line-clamp-1.h-4{display:block!important;" +
                    "-webkit-line-clamp:unset!important;line-clamp:unset!important;height:auto!important;" +
                    "max-height:none!important;overflow:visible!important;-webkit-box-orient:initial!important}" +
                    "header .flex.shrink-0{flex-shrink:1!important;min-width:0!important;gap:0.25rem!important}" +
                    "header>div{gap:0.375rem!important;padding-left:0.5rem!important;padding-right:0.5rem!important}" +
                    "header .font-medium.text-muted-foreground.text-xs{display:none!important}');" +
                    // html 底色跟随 body，避免顶部出现异色带
                    "var _b=document.body;if(_b){var _bg=getComputedStyle(_b).backgroundColor;" +
                    "var _none=!_bg||_bg==='rgba(0, 0, 0, 0)'||_bg==='transparent';" +
                    "var _want=_none?'transparent':_bg;" +
                    "if(document.documentElement.style.backgroundColor!==_want){" +
                    "document.documentElement.style.setProperty('background-color',_want,'important');}}" +
                    "}" +
                    "fixAll();window.__artexSyncBg();window.__artexFixOverflow();" +
                    // 每秒只做三件轻量事；溢出修复已改由 MutationObserver 驱动
                    "setInterval(function(){fixVP();fixAll();window.__artexSyncBg();},1000);" +
                    // 触摸层：多指手势直接吞掉
                    "document.addEventListener('touchstart',function(e){if(e.touches.length>1)e.preventDefault();},{passive:false});" +
                    "document.addEventListener('touchmove',function(e){if(e.touches.length>1)e.preventDefault();},{passive:false});" +
                    "document.addEventListener('gesturestart',function(e){e.preventDefault();});" +
                    // 主题跟随系统：兜底覆盖 cookie（用户之前可能手动设成 light/dark），
                    // 并把当前系统深浅色同步到 class。前端在 system 模式下还会自己
                    // 监听 matchMedia 变化，这里只在首次进入时对齐一次。
                    "try{" +
                    "document.cookie='theme_mode=system; Max-Age=604800; Path=/';" +
                    "var mq=window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)');" +
                    "var dk=!!(mq&&mq.matches);" +
                    "document.documentElement.classList.toggle('dark',dk);" +
                    "document.documentElement.setAttribute('data-theme-mode','system');" +
                    "document.documentElement.style.colorScheme=dk?'dark':'light';" +
                    "}catch(e){}" +
                    "})()";
                view.evaluateJavascript(js, null);
            }

        });

        beginBoot();
        // 桌面长按「扫描」进来的
        handleShortcut(getIntent());
    }

    /** 启动（或失败后重试）：整段逻辑抽出来，供失败页的「重试」按钮复用 */
    private void beginBoot() {
        Notifier.cancelAll(this);   // 清掉上次遗留的通知，避免重复
        Notifier.ensureChannel(this);
        svc = new ArtExService(this);
        console.setLongPressCopy(svc::getFullLog);
        svc.start(new ArtExService.StatusListener() {
            @Override
            public void onStatus(String msg) {
                console.logStatus(msg);
                console.progressFromStatus(msg);
            }

            @Override
            public void onReady() {
                String ip = getLocalIp();
                String lan = ip != null ? "http://" + ip + ":" + PORT : null;
                runOnUiThread(() -> {
                    console.complete();
                    console.log("[LAN] " + (lan != null ? lan : "(LAN IP unavailable)"));
                    // 通知只留两行：标题 ARTEX，正文 Service running。
                    // 访问地址仍打在控制台日志里，需要时能查到。
                    Notifier.show(MainActivity.this, "ARTEX", "Service running");

                    console.setVisibility(View.GONE);
                    webView.loadUrl("http://127.0.0.1:" + PORT + "/login");
                });
            }

            @Override
            public void onError(String msg) {
                String full = svc.getFullLog();
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        File f = new File(dir, "artex-log.txt");
                        try (FileOutputStream fo = new FileOutputStream(f)) {
                            fo.write(full.getBytes());
                        }
                    }
                } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    console.log("[FAIL] " + msg);
                    console.setVisibility(View.VISIBLE);
                    // 整页切成失败视图：错误摘要 + 可滚动完整日志 + 复制/重试
                    console.showFailure(msg, svc::getFullLog, MainActivity.this::restartBoot);
                    Notifier.show(MainActivity.this, "ARTEX startup failed", msg);
                });
            }
        });
    }

    /**
     * 返回键：优先在网页内后退（包括前端 SPA 的 pushState 历史），
     * 已经退到底了才两秒内连按两次退出，避免误触直接把 APP 关掉。
     */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime <= 2000) {
            super.onBackPressed();
            return;
        }
        lastBackTime = now;
        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
    }

    /**
     * 桌面长按图标的快捷方式。
     *
     * 快捷方式是一发指令，必须当场消费掉：action 留在 Activity 的 intent 里，
     * 之后任何一次 Activity 重建（旋转、深浅色、从后台回来）都会在 onCreate
     * 的 handleShortcut(getIntent()) 里被重放一遍，变成用户没点也扫码。
     * 消费方式是处理完把 action 置空并重新 setIntent。
     */
    private void handleShortcut(Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (!ACTION_SCAN.equals(a)) return;
        intent.setAction(null);
        setIntent(intent);          // 把清掉 action 的副本存回去，后续重建不会再重放
        startScan();
    }

    /** 扫码创建任务（相机与后端无关，冷启动时也可立即扫；结果等前端就绪后再填） */
    private void startScan() {
        try {
            final Intent it = new Intent(this, ScanActivity.class);
            // 从 onCreate 触发时窗口尚未就绪，延后一帧再拉起
            getWindow().getDecorView().post(() -> startActivityForResult(it, REQ_SCAN));
        } catch (Throwable t) {
            Toast.makeText(this, "Unable to start scanner: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 取第一个 http(s) 链接；没有则取首行（可能是裸域名） */
    private String extractTarget(String text) {
        if (text == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"'<>]+").matcher(text);
        if (m.find()) return m.group();
        int nl = text.indexOf('\n');
        String first = (nl >= 0 ? text.substring(0, nl) : text).trim();
        return first.length() > 512 ? first.substring(0, 512) : first;
    }

    /** 打开「新建任务」抽屉并预填目标地址；找不到按钮则退化为复制到剪贴板 */
    private void injectShare(String url) {
        runOnUiThread(() -> {
            webView.loadUrl("http://127.0.0.1:" + PORT + "/function/tasks");
            webView.postDelayed(() -> webView.evaluateJavascript(shareJs(url), value -> {
                if (value == null || value.contains("nobtn") || value.contains("err")) {
                    copyToClipboard(url);
                    Toast.makeText(this, "Target address copied. Paste it into a new task.",
                            Toast.LENGTH_LONG).show();
                }
            }), 2500);
        });
    }

    /**
     * 注入 JS：点「新建任务」按钮 → 700ms 后把地址写进「测试对象」文本框。
     * 用原型 setter 赋值再派发 input 事件，React 才认这是用户输入。
     */
    private String shareJs(String url) {
        String q = org.json.JSONObject.quote(url);
        return "(function(){try{"
                + "var u=" + q + ";"
                + "var bs=[].slice.call(document.querySelectorAll('button'));"
                + "var b=bs.filter(function(x){return x.textContent&&"
                + "x.textContent.indexOf('\\u65b0\\u5efa\\u4efb\\u52a1')>=0;})[0];"
                + "if(!b){return 'nobtn';}"
                + "b.click();"
                + "setTimeout(function(){"
                + "var ts=[].slice.call(document.querySelectorAll('textarea'));"
                + "var t=ts.filter(function(x){return (x.placeholder||'')"
                + ".indexOf('\\u6d4b\\u8bd5\\u5bf9\\u8c61')>=0;})[0];"
                + "if(!t){return;}"
                + "var s=Object.getOwnPropertyDescriptor("
                + "window.HTMLTextAreaElement.prototype,'value').set;"
                + "s.call(t,u);"
                + "t.dispatchEvent(new Event('input',{bubbles:true}));"
                + "},700);"
                + "return 'ok';}catch(e){return 'err';}})()";
    }

    private void copyToClipboard(String url) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("artex-target", url));
        } catch (Throwable ignored) {}
    }

    /** 失败页的「重试」：先把启动界面恢复成进度形态，再重新播一遍 */
    private void restartBoot() {
        try {
            // 重建 console，把上次的失败视图清掉
            FrameLayout parent = root;
            if (parent != null) {
                parent.removeView(console);
                console = new BootConsole(this);
                parent.addView(console, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // stop() 会等 PG 真正退出（最长 25 秒），绝不能放主线程
            new Thread(() -> {
                try {
                    ArtExService.getOrCreate(MainActivity.this).stop();
                } catch (Throwable ignored) {}
                runOnUiThread(MainActivity.this::beginBoot);
            }, "retry-stop").start();
        } catch (Throwable t) {
            console.log("[FAIL] Retry failed: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
                String url = extractTarget(text == null ? "" : text.trim());
                if (url.isEmpty()) {
                    Toast.makeText(this, "No valid address detected", Toast.LENGTH_SHORT).show();
                } else if (pageLoaded) {
                    injectShare(url);
                } else {
                    pendingShare = url;   // 后端还没就绪，等 onPageFinished 再填
                }
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQ) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    /**
     * 接管网页下载（任务 ZIP、单文件下载）。
     *
     * 前端用 <a download> 触发浏览器下载，但 WebView 没装 DownloadListener
     * 时会静默丢弃，表现就是点了没反应。
     *
     * 交给系统 DownloadManager 而不是自己在进程里下：大文件不进内存
     * （任务 ZIP 能到几百 MB，自己拼 byte[] 会 OOM），断点续传和下载通知
     * 也都由系统负责。
     *
     * 认证：后端 extractToken 认 Authorization / cookie artex_token / ?token=。
     * 前端生成的是裸链接，靠登录时种的 cookie。WebView 的 cookie 不在系统
     * 下载器里，必须显式塞过去，否则拿到的是 401。
     */
    private void installDownloadListener() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, len) -> {
            try {
                String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                String name = stampFileName(guessFileName(url, contentDisposition));

                android.app.DownloadManager.Request req =
                        new android.app.DownloadManager.Request(Uri.parse(url));
                if (cookie != null && !cookie.isEmpty()) {
                    req.addRequestHeader("Cookie", cookie);
                }
                if (mimetype != null && !mimetype.isEmpty()) req.setMimeType(mimetype);
                req.setTitle(name);
                req.setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS, name);

                android.app.DownloadManager dm = (android.app.DownloadManager)
                        getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) {
                    Toast.makeText(this, "System download manager unavailable", Toast.LENGTH_SHORT).show();
                    return;
                }
                dm.enqueue(req);
                Toast.makeText(this, "Starting download: " + name, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Download failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 在扩展名前插入时间戳：task-12.zip → task-12-20261120-2115.zip
     *
     * 同名文件重复下载时 DownloadManager 会自己加 -1/-2 后缀，但那样排不出
     * 先后顺序，也不好认。带时间戳一眼就知道是哪一次下的。
     */
    private String stampFileName(String name) {
        if (name == null || name.isEmpty()) name = "artex-download";
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        int dot = name.lastIndexOf('.');
        // 没扩展名，或点在最前面（.gitignore 这种隐藏文件）→ 直接拼在末尾
        if (dot <= 0) return name + "-" + stamp;
        return name.substring(0, dot) + "-" + stamp + name.substring(dot);
    }

    /**
     * 推断保存的文件名。Content-Disposition 优先（后端 setFileDisposition 设了
     * attachment; filename=xxx.zip），拿不到就退到 URL 末段，再不行用时间戳。
     */
    private String guessFileName(String url, String contentDisposition) {
        if (contentDisposition != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("filename\\s*=\\s*\"?([^\"\\n;]+)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(contentDisposition);
            if (m.find()) {
                String n = m.group(1).trim();
                if (!n.isEmpty()) return sanitizeFileName(n);
            }
        }
        try {
            String p = Uri.parse(url).getLastPathSegment();
            if (p != null && !p.isEmpty()) return sanitizeFileName(p);
        } catch (Throwable ignored) {}
        return "artex-download-" + System.currentTimeMillis();
    }

    /** 去掉非法字符，避免 DownloadManager 拒绝 */
    private String sanitizeFileName(String n) {
        String s = n.replace('/', '_').replace('\\', '_')
                .replace(':', '_').replace('*', '_')
                .replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_')
                .replace('|', '_').trim();
        if (s.isEmpty()) return "artex-download-" + System.currentTimeMillis();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /**
     * 多指手势：双指右滑拉出页面侧边栏，双指左滑拉出原生面板；
     * 其余多指（如捏合缩放）一律吞掉。
     *
     * 必须做在原生侧：多指事件在 OnTouchListener 就被消费了，注入到页面的 JS
     * 根本收不到。单指一律放行，所以页面滚动、点击、表格横滑都不受影响。
     */
    private void installTwoFingerSwipe(WebView target) {
        final float[] sx = {0f};
        final float[] sy = {0f};
        final boolean[] tracking = {false};
        final boolean[] fired = {false};
        final float minPx = 60 * getResources().getDisplayMetrics().density;

        target.setOnTouchListener((v, e) -> {
            final int action = e.getActionMasked();
            // 每帧按「当前有几根手指」实时判断，不能记住历史状态：
            // 否则抬走一指后，剩下那指的滑动会被继续吞掉；手掌误触一次后，
            // 在全部手指抬起前单指也都划不动。
            final boolean multi = e.getPointerCount() > 1;

            if (multi) {
                if (action == android.view.MotionEvent.ACTION_POINTER_DOWN || !tracking[0]) {
                    tracking[0] = true;
                    fired[0] = false;
                    sx[0] = avgX(e);
                    sy[0] = avgY(e);
                } else if (action == android.view.MotionEvent.ACTION_MOVE && !fired[0]) {
                    float dx = avgX(e) - sx[0];
                    float dy = avgY(e) - sy[0];
                    if (dx > minPx && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        fired[0] = true;
                        openPageSidebar();
                    } else if (dx < -minPx && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        fired[0] = true;
                        openPanel();
                    }
                }
            } else {
                tracking[0] = false;
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    fired[0] = false;
                }
            }
            return multi;    // 只消费「当前多指」的事件，单指永不消费
        });
    }

    private float avgX(android.view.MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
        return s / e.getPointerCount();
    }

    private float avgY(android.view.MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }

    /** 点页面始终渲染的 SidebarTrigger 来拉出前端侧边栏；已展开就不动 */
    private void openPageSidebar() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{if(window.innerWidth>768)return;"
                        + "if(document.querySelector('[data-slot=sidebar][data-mobile=true]'))return;"
                        + "var t=document.querySelector('[data-slot=sidebar-trigger]');"
                        + "if(t)t.click();}catch(e){}})()", null);
    }

    /** 双指左滑：先抓一张模糊底图，再从右侧抽出原生面板 */
    private void openPanel() {
        final int w = getWindow().getDecorView().getWidth();
        final int h = getWindow().getDecorView().getHeight();
        final android.os.Handler main = new android.os.Handler(getMainLooper());
        new Thread(() -> {
            final android.graphics.Bitmap bmp = captureBlurredBackdrop(w, h);
            main.post(() -> {
                PanelActivity.setBackdrop(bmp);
                startActivity(new Intent(this, PanelActivity.class));
                overridePendingTransition(0, 0);
            });
        }, "panel-backdrop").start();
    }

    /**
     * 抓当前窗口的缩略图并做模糊，给面板当磨砂玻璃底图。
     * 先缩到 1/12 再模糊，所以很快；任何一步失败就返回 null（面板退化成纯半透明）。
     */
    private android.graphics.Bitmap captureBlurredBackdrop(int w, int h) {
        if (android.os.Build.VERSION.SDK_INT < 26 || w <= 0 || h <= 0) return null;
        android.graphics.Bitmap small = null;
        android.os.HandlerThread ht = null;
        try {
            small = android.graphics.Bitmap.createBitmap(
                    Math.max(1, w / 12), Math.max(1, h / 12),
                    android.graphics.Bitmap.Config.ARGB_8888);
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            ht = new android.os.HandlerThread("pixelcopy");
            ht.start();
            android.view.PixelCopy.request(getWindow(), small,
                    result -> latch.countDown(), new android.os.Handler(ht.getLooper()));
            if (!latch.await(700, java.util.concurrent.TimeUnit.MILLISECONDS)) return null;
            blurInPlace(small, 7);
            return small;
        } catch (Throwable t) {
            if (small != null) small.recycle();
            return null;
        } finally {
            if (ht != null) ht.quitSafely();
        }
    }

    /** 就地做两轮横向+纵向均值模糊；只在小图上跑，代价可忽略 */
    private static void blurInPlace(android.graphics.Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (radius < 1 || w < 2 || h < 2) return;
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            boxH(px, tmp, w, h, radius);
            boxV(tmp, px, w, h, radius);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[row + clamp(i, 0, w - 1)];
                a += p >>> 24;
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((a / n) << 24) | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
                int out = src[row + clamp(x - r, 0, w - 1)];
                int in = src[row + clamp(x + r + 1, 0, w - 1)];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[clamp(i, 0, h - 1) * w + x];
                a += p >>> 24;
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((a / n) << 24) | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
                int out = src[clamp(y - r, 0, h - 1) * w + x];
                int in = src[clamp(y + r + 1, 0, h - 1) * w + x];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 供页面回传底色的 JS 桥（前端切换主题时不会重新加载页面，只能靠它推送） */
    public class PageBridge {
        @android.webkit.JavascriptInterface
        public void setBackground(String cssColor) {
            final int color = parseCssColor(cssColor);
            if (color != 0) runOnUiThread(() -> applyBackgroundColor(color));
        }
    }

    /** 解析页面推来的 CSS 颜色，形如 "rgb(255, 255, 255)" */
    private int parseCssColor(String css) {
        if (css == null) return 0;
        String t = css.replace("\"", "").trim();
        if (!t.startsWith("rgb")) return 0;
        try {
            String nums = t.substring(t.indexOf('(') + 1, t.indexOf(')'));
            String[] p = nums.split(",");
            int r = Integer.parseInt(p[0].trim());
            int g = Integer.parseInt(p[1].trim());
            int b = Integer.parseInt(p[2].trim());
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 取本机局域网 IPv4（用于提示浏览器访问地址） */
    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                java.net.NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 状态栏颜色 + 图标明暗随系统深色/浅色模式自适应 */
    /**
     * 只隐藏状态栏，导航栏保持正常。
     * 必须同时挂在 onWindowFocusChanged 上 —— 切回前台时系统会清掉隐藏状态。
     */
    /**
     * 把主题模式写进 WebView cookie：theme_mode=system。
     *
     * WebView 里的 prefers-color-scheme 由系统 uiMode 决定，所以 system 模式
     * 等于「跟随手机深色/浅色」。前端 theme-boot.tsx 会读这个 cookie，
     * 并注册 media.addEventListener('change') 响应运行时的切换。
     *
     * 只覆盖这一项，不动 theme_preset 等其他偏好。
     */
    private void applyThemeModeToWeb() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String base = "http://127.0.0.1:" + PORT;
            // 7 天有效期，与前端 setClientCookie 的默认值一致
            cm.setCookie(base, "theme_mode=system; Max-Age=604800; Path=/");
            cm.flush();
        } catch (Throwable ignored) {}
    }

    private void applyImmersiveFullscreen() {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();

        // 刘海/挖孔屏：不设会出现黑边
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 必须为 false：否则内容不会延伸到系统栏区域，隐藏状态栏后
            // 那一块会露出原生 window 背景（跟系统深浅色走，与前端主题不一致）。
            // 代价是内容也会延伸到导航栏下方——由 inset 监听补底部内边距。
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars());
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
        if (hasFocus) applyImmersiveFullscreen();
    }

    /**
     * 状态栏隐藏后，该区域露出的是容器底色。
     * 不设的话会露出 WebView 默认的白底，和深色界面形成一条色带。
     */
    private void applyContainerBackground() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? 0xFF0B0B0F : 0xFFFFFFFF;
        if (root != null) root.setBackgroundColor(bg);
        if (webView != null) webView.setBackgroundColor(bg);
        if (console != null) console.setBackgroundColor(bg);
    }

    /** 把页面底色铺到各处，并按亮度选择状态栏图标的明暗 */
    private void applyBackgroundColor(int color) {
        try {
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(color));
        } catch (Throwable ignored) {}
        try {
            getWindow().setStatusBarColor(color);
        } catch (Throwable ignored) {}

        int lum = ((color >> 16) & 0xFF) * 299
                + ((color >> 8) & 0xFF) * 587
                + (color & 0xFF) * 114;
        boolean lightBg = lum >= 128000;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (lightBg) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);

        if (root != null) root.setBackgroundColor(color);
        if (webView != null) webView.setBackgroundColor(color);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersiveFullscreen();
        applyContainerBackground();
    }

    @Override
    protected void onDestroy() {
        if (svc != null) svc.stop();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
