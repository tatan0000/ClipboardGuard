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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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
 * 2. onCreate → startForeground()（显示"启动中..."通知）
 * 3. onStartCommand → 延迟 12s / 18s 各发一次配置广播
 * 4. 第二次广播发完后 → 更新通知为"已完成开机自启动"
 * 5. 延迟 2 秒 → stopSelf()（通知保留，用户手动清除）
 *
 * 通知策略：
 * - 通知不可自动清除，需用户手动滑掉
 * - 通知使用 IMPORTANCE_LOW + VISIBILITY_SECRET，不打扰用户
 */
public class ConfigSyncService extends Service {

    private static final String TAG = "ClipboardGuard.Sync";
    private static final String CHANNEL_ID = "clipboardguard_sync";
    private static final int NOTIFICATION_ID = 1002;

    // 第一次发送延迟（等 Hook 侧广播接收器注册好）
    private static final long FIRST_BROADCAST_DELAY_MS = 12000L;
    // 第二次发送的间隔（兜底）
    private static final long SECOND_BROADCAST_DELAY_MS = 6000L;
    // 发完广播后延迟停止服务，让 Hook 侧有时间处理广播
    private static final long STOP_DELAY_MS = 2000L;

    private Handler mainHandler;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        // ★ 服务启动：显示"启动中..."（ongoing，不可清除）
        startForeground(NOTIFICATION_ID, buildNotification("启动中...", true));
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

                // 6s 后第二次（兜底）
                Thread.sleep(SECOND_BROADCAST_DELAY_MS);
                PermissionProvider.sendFullConfigBroadcast(this);
                XLog.i(TAG, "已发送第二次配置刷新广播（兜底）");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                XLog.w(TAG, "配置同步被中断");
            } catch (Throwable e) {
                XLog.e(TAG, "配置同步异常: " + e.getMessage(), e);
            } finally {
                // ★ 广播发完后：更新通知为"已完成开机自启动"（取消 ongoing，允许用户清除）
                mainHandler.post(() -> {
                    updateNotificationFinal("已完成开机自启动");
                });

                // 延迟 STOP_DELAY_MS 后停止服务（通知保留，不自动移除）
                mainHandler.postDelayed(() -> {
                    stopForeground(STOP_FOREGROUND_DETACH);   // 保留通知，但脱离前台服务
                    stopSelf();
                    XLog.i(TAG, "ConfigSyncService 已停止，通知保留");
                }, STOP_DELAY_MS);
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
            channel.setDescription("ClipboardGuard 服务状态");
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text, boolean ongoing) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ClipboardGuard")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(ongoing)        // 启动阶段 true（不可清除），完成后 false（可清除）
                .setAutoCancel(false)       // 点击不自动消失
                .build();
    }

    /**
     * 服务运行中更新通知（ongoing = true，不可清除）
     */
    private void updateNotification(String text) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text, true));
        }
    }

    /**
     * 服务完成前更新通知（ongoing = false，用户可手动清除）
     */
    private void updateNotificationFinal(String text) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text, false));
            XLog.i(TAG, "通知已更新: " + text + " (ongoing=false, 用户可清除)");
        }
    }
}
