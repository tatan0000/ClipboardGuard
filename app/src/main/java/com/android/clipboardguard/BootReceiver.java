package com.android.clipboardguard;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

/**
 * 开机自启动接收器
 *
 * 功能：
 * - 收到开机广播后延迟发送配置广播，等 Hook 侧广播接收器注册好
 * - 15s 发送第一次，18s 第二次（兜底，防止 Hook 侧广播接收器还没注册）
 * - 仅开机时发送通知，App 正常打开和保存配置只发广播不弹通知
 *
 * 时序说明：
 * - Hook 侧在延迟 5s 和 8s 注册广播接收器
 * - BootReceiver 在 15s 后发广播，确保 Hook 侧已注册
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClipboardGuard.Boot";

    // 第一次发送延迟（等 Hook 侧广播接收器注册好）
    private static final long FIRST_BROADCAST_DELAY_MS = 15000L;
    // 第二次发送的间隔（兜底）
    private static final long SECOND_BROADCAST_DELAY_MS = 3000L;
    private static final String CHANNEL_ID = "clipboardguard_boot";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        XLog.i(TAG, "收到开机广播: " + action);

        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                // 等待 Hook 侧广播接收器注册好（Hook 在 5s 和 8s 注册，我们等 15s）
                XLog.i(TAG, "等待 " + (FIRST_BROADCAST_DELAY_MS / 1000) + "s 后发送第一次广播...");
                Thread.sleep(FIRST_BROADCAST_DELAY_MS);

                // 发送第一次完整配置广播
                PermissionProvider.sendFullConfigBroadcast(appContext);
                XLog.i(TAG, "已发送第一次配置刷新广播");

                // 3s 后第二次（兜底）+ 发送通知
                Thread.sleep(SECOND_BROADCAST_DELAY_MS);
                PermissionProvider.sendFullConfigBroadcast(appContext);
                XLog.i(TAG, "已发送第二次配置刷新广播（兜底）");

                sendSuccessNotification(appContext);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                XLog.w(TAG, "初始化被中断");
            } catch (Throwable e) {
                XLog.e(TAG, "开机初始化异常: " + e.getMessage(), e);
            } finally {
                pendingResult.finish();
            }
        }, "ClipboardGuard-BootInit").start();
    }

    /**
     * 发送自启动成功通知
     */
    private void sendSuccessNotification(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    XLog.w(TAG, "未授予通知权限，跳过");
                    return;
                }
            }

            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "剪贴板护卫",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("剪贴板护卫服务状态通知");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("ClipboardGuard")
                    .setContentText("剪贴板保护服务已启动")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

            nm.notify(NOTIFICATION_ID, builder.build());
            XLog.i(TAG, "已发送自启动通知");

        } catch (Throwable e) {
            XLog.e(TAG, "发送通知失败: " + e.getMessage());
        }
    }
}
