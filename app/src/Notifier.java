package com.artex.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class Notifier {
    private static final String CHANNEL_ID = "artex_service_v6";
    private static final int NOTIF_ID = 4004;

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "ARTEX Service", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("ARTEX service status and access address");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    public static void show(Context ctx, String title, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(ctx);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        // smallIcon：白色剪影（状态栏/通知左侧）
        int smallId = ctx.getResources().getIdentifier("ic_notify", "drawable", ctx.getPackageName());
        if (smallId == 0) smallId = android.R.drawable.stat_sys_upload_done;

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        // 只用标准两行布局：标题 + 正文。
        // 不用 BigTextStyle —— 那会把通知撑开成大段可展开文字，
        // 和「只显示两行」的要求冲突。
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(smallId)
                .setContentIntent(pi);
        // 注意：不调用 setOngoing(true)。
        // MIUI/HyperOS 会对常驻通知做特殊重构，替换成系统提示 UI（显示机器人图标）。

        // 不设 largeIcon：右侧那块大图就是它。左侧小图标已足够识别，
        // 通知保持两行纯文字更干净。
        nm.notify(NOTIF_ID, b.build());
    }

    public static void cancelAll(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
        } catch (Throwable ignored) {}
    }
}
