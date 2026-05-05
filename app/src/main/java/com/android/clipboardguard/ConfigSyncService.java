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
 * 3. onStartCommand → 立即发第一次配置广播（Hook 侧 8s 已注册好）
 * 4. 3s 后发第二次配置广播（兜底，约 13s）
 * 5. 广播发完后 → 更新通知为"已完成开机自启动"
 * 6. 延迟 2 秒 → stopSelf()（通知保留，用户手动清除）
 *
 * 通知策略：
 * - 启动阶段通知 ongoing=true（不可清除）
 * - 完成后通知 ongoing=false（用户可手动清除）
 * - 通知使用 IMPORTANCE_LOW + VISIBILITY_SECRET，不打扰用户
 */
public class ConfigSyncService extends Service {

    private static final String TAG = "ClipboardGuard.Sync";
    private static final String CHANNEL_ID = "clipboardguard_sync";
    private static final int NOTIFICATION_ID = 1002;

    // 第一次广播延迟（等 App 进程完全初始化好）
    private static final long FIRST_BROADCAST_DELAY_MS = 2000L;
    // 第二次广播的间隔（第一次后 3s，兜底）
    private static final long SECOND_BROADCAST_DELAY_MS = 3000L;
    // 发完广播后延迟停止服务，让 Hook 侧有时间处理广播
    private static final long STOP_DELAY_MS = 2000L;

    // ★ 防止重复执行：Hook 侧和 BootReceiver 都可能触发
    private static volatile boolean sSyncStarted = false;

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
        // ★ 防止重复执行：Hook 侧 10s 和 BootReceiver 12s 都可能触发
        if (sSyncStarted) {
            XLog.i(TAG, "ConfigSyncService 同步已在进行中，跳过重复触发");
            return START_NOT_STICKY;
        }
        sSyncStarted = true;

        XLog.i(TAG, "ConfigSyncService onStartCommand，开始同步配置");

        new Thread(() -> {
            try {
                // ★ 等 App 进程完全初始化好，延迟 2s 发第一次广播
                XLog.i(TAG, "等待 App 进程初始化，延迟 " + (FIRST_BROADCAST_DELAY_MS/1000) + "s 后发第一次广播...");
                Thread.sleep(FIRST_BROADCAST_DELAY_MS);
                
                XLog.i(TAG, "发送第一次配置广播...");
                PermissionProvider.sendFullConfigBroadcast(this);
                XLog.i(TAG, "已发送第一次配置刷新广播");

                // 3s 后第二次（兜底）
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
    public void onDestroy() {
        super.onDestroy();
        // ★ 服务销毁时重置标志位，允许下次启动重新同步
        sSyncStarted = false;
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
