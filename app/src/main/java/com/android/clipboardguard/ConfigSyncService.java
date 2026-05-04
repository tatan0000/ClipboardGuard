package com.android.clipboardguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

/**
 * 开机配置同步前台服务
 *
 * 由 BootReceiver 启动，在 App 进程中运行。
 * 前台服务比 BroadcastReceiver.goAsync() 更可靠（goAsync 最多 10 秒）。
 *
 * 生命周期：
 * 1. BootReceiver 收到开机广播 → startForegroundService()
 * 2. onCreate → startForeground()（显示通知，避免被系统杀掉）
 * 3. onStartCommand → 子线程延迟发配置广播（12s / 15s）
 * 4. 发完广播 → stopSelf()
 */
public class ConfigSyncService extends Service {

    private static final String TAG = "ClipboardGuard.Sync";
    private static final String CHANNEL_ID = "clipboardguard_sync";
    private static final int NOTIFICATION_ID = 1002;

    // 第一次发送延迟（等 Hook 侧广播接收器注册好）
    private static final long FIRST_BROADCAST_DELAY_MS = 12000L;
    // 第二次发送的间隔（兜底，6s 后再试一次）
    private static final long SECOND_BROADCAST_DELAY_MS = 6000L;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("剪贴板保护服务启动中..."));
        XLog.i(TAG, "ConfigSyncService 前台服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        XLog.i(TAG, "ConfigSyncService onStartCommand，开始延迟同步配置");

        new Thread(() -> {
            try {
                // 等待 Hook 侧广播接收器注册好（Hook 在 5s 和 8s 注册，我们等 12s）
                XLog.i(TAG, "等待 " + (FIRST_BROADCAST_DELAY_MS / 1000) + "s 后发送第一次广播...");
                Thread.sleep(FIRST_BROADCAST_DELAY_MS);

                // 发送第一次完整配置广播
                PermissionProvider.sendFullConfigBroadcast(this);
                XLog.i(TAG, "已发送第一次配置刷新广播");

                // 3s 后第二次（兜底）
                Thread.sleep(SECOND_BROADCAST_DELAY_MS);
                PermissionProvider.sendFullConfigBroadcast(this);
                XLog.i(TAG, "已发送第二次配置刷新广播（兜底）");

                // 更新通知为成功状态
                updateNotification("剪贴板保护服务已启动");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                XLog.w(TAG, "配置同步被中断");
            } catch (Throwable e) {
                XLog.e(TAG, "配置同步异常: " + e.getMessage(), e);
            } finally {
                // 延迟 1 秒后停止服务，让用户看到成功通知
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                XLog.i(TAG, "ConfigSyncService 已停止");
            }
        }, "ClipboardGuard-SyncService").start();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "剪贴板护卫",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("剪贴板护卫服务状态通知");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ClipboardGuard")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Throwable e) {
            XLog.e(TAG, "更新通知失败: " + e.getMessage());
        }
    }
}
