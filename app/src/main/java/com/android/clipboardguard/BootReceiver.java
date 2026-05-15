package com.android.clipboardguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/*
 * 开机自启动接收器
 *
 * 功能：
 * - 监听多个系统广播作为自启动触发（国产 ROM 常拦截 BOOT_COMPLETED）
 * - 收到广播后延迟启动 ConfigSyncService（前台服务）来完成配置同步
 * - 前台服务比 BroadcastReceiver.goAsync() 更可靠（goAsync 最多 10 秒）
 * - 60 秒内只启动一次服务（防止 USER_PRESENT 等多次触发）
 *
 * ★ Hook 侧触发已改为直接启动服务（system_server → startForegroundService），
 *   不再通过广播，彻底绕开 AMS checkBroadcastFromSystem 检查。
 *
 * 系统广播触发列表（任一触发均有效）：
 * - LOCKED_BOOT_COMPLETED：Android 7.0+ 加密解锁后（比 BOOT_COMPLETED 更早）
 * - BOOT_COMPLETED / QUICKBOOT_POWERON：标准开机广播（可能被拦截）
 * - USER_PRESENT：用户首次解锁屏幕（最可靠，国产 ROM 通常不拦截）
 * - TIME_SET / TIMEZONE_CHANGED：时间/时区变化（开机时常触发）
 *
 * 触发时序：
 * 1. Hook 侧直接启动（约 13s，最可靠）
 * 2. BOOT_COMPLETED 触发（约 14s，延迟 5s 后启动 → 约 19s）
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClipboardGuard.Boot";
    /** Android 7.0+ 直接启动模式：加密解锁后发送，比 BOOT_COMPLETED 更早 */
    private static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";

    // 防抖：60 秒内不重复启动服务
    private static volatile long sLastTriggerTime = 0;
    private static final long COOLDOWN_MS = 60_000L;

    // ★ 收到系统广播后，延迟启动服务：14s收到+4s=18s启动
    private static final long TRIGGER_DELAY_MS = 4000L;

    private Handler mainHandler;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        // ★ 系统广播：靠 action 匹配
        boolean isSystemTrigger = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || ACTION_LOCKED_BOOT_COMPLETED.equals(action);

        if (!isSystemTrigger) {
            return;
        }

        XLog.i(TAG, "收到系统广播触发: " + action);

        // 防抖：短时间内不重复启动
        long now = System.currentTimeMillis();
        if (now - sLastTriggerTime < COOLDOWN_MS) {
            XLog.i(TAG, "防抖：距上次启动不足 " + (COOLDOWN_MS / 1000) + "s，跳过");
            return;
        }
        sLastTriggerTime = now;

        // ★ 延迟启动服务（顺延，给系统更多初始化时间）
        mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(() -> {
            XLog.i(TAG, "延迟 " + (TRIGGER_DELAY_MS / 1000) + "s 后启动 ConfigSyncService...");
            startService(context);
        }, TRIGGER_DELAY_MS);
    }

    private void startService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, ConfigSyncService.class);
            serviceIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            XLog.i(TAG, "已启动 ConfigSyncService 前台服务");
        } catch (Throwable e) {
            XLog.e(TAG, "启动 ConfigSyncService 失败: " + e.getMessage(), e);
        }
    }
}
