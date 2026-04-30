package com.android.clipboardguard;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

/**
 * 开机自启动接收器
 *
 * 功能：
 * - 开机后在后台静默运行，不显示任何界面
 * - 延迟等待系统稳定后，发送配置刷新广播通知 Hook 侧
 * - 发送自启动成功通知
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClipboardGuard.Boot";

    private static final long INITIAL_DELAY_MS = 3000L;
    private static final String CHANNEL_ID = "clipboardguard_boot";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.i(TAG, "收到开机广播: " + action);

        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                Log.i(TAG, "等待 " + (INITIAL_DELAY_MS / 1000) + " 秒后发送刷新广播...");
                Thread.sleep(INITIAL_DELAY_MS);

                // 发送完整配置广播（包含 blocklist + 写入规则 + 读取规则）
                PermissionProvider.sendFullConfigBroadcast(appContext);

                Log.i(TAG, "已发送配置刷新广播");
                sendSuccessNotification(appContext);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "初始化被中断");
            } catch (Throwable e) {
                Log.e(TAG, "开机初始化异常: " + e.getMessage(), e);
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
                    Log.w(TAG, "未授予通知权限，跳过");
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
            Log.i(TAG, "已发送自启动通知");

        } catch (Throwable e) {
            Log.e(TAG, "发送通知失败: " + e.getMessage());
        }
    }
}