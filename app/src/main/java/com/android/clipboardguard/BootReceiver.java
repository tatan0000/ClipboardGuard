package com.android.clipboardguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 开机自启动接收器
 *
 * 功能：
 * - 监听多个系统广播作为自启动触发（国产 ROM 常拦截 BOOT_COMPLETED）
 * - 收到广播后启动 ConfigSyncService（前台服务）来完成配置同步
 * - 前台服务比 BroadcastReceiver.goAsync() 更可靠（goAsync 最多 10 秒）
 * - 60 秒内只启动一次服务（防止 USER_PRESENT 等多次触发）
 *
 * 触发 Intent 列表（任一触发均有效）：
 * - BOOT_COMPLETED / QUICKBOOT_POWERON：标准开机广播（可能被拦截）
 * - USER_PRESENT：用户首次解锁屏幕（最可靠，国产 ROM 通常不会拦截）
 * - TIME_SET / TIMEZONE_CHANGED：时间/时区变化（开机时常触发）
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClipboardGuard.Boot";

    // 防抖：60 秒内不重复启动服务
    private static volatile long sLastTriggerTime = 0;
    private static final long COOLDOWN_MS = 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_USER_PRESENT.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            return;
        }

        XLog.i(TAG, "收到触发广播: " + action);

        // 防抖：短时间内不重复启动
        long now = System.currentTimeMillis();
        if (now - sLastTriggerTime < COOLDOWN_MS) {
            XLog.i(TAG, "防抖：距上次启动不足 " + (COOLDOWN_MS / 1000) + "s，跳过");
            return;
        }
        sLastTriggerTime = now;

        // 启动前台服务来同步配置（比 goAsync 更可靠）
        try {
            Intent serviceIntent = new Intent(context, ConfigSyncService.class);
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
