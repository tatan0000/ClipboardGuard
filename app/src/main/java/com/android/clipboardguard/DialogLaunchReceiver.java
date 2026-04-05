package com.android.clipboardguard;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 广播接收器：收到system_server广播后启动弹窗
 * 弹窗固定在本模块进程中启动（而非目标 App 进程）
 */
public class DialogLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "ClipboardGuard.Receiver";
    public static final String ACTION_SHOW_DIALOG = "com.android.clipboardguard.ACTION_SHOW_DIALOG";

    // 本模块包名
    private static final String MODULE_PKG = "com.android.clipboardguard";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SHOW_DIALOG.equals(intent.getAction())) {
            Log.w(TAG, "未知action: " + intent.getAction());
            return;
        }

        String packageName = intent.getStringExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME);
        String preview = intent.getStringExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW);

        if (packageName == null || packageName.isEmpty()) {
            Log.w(TAG, "packageName为空，丢弃");
            return;
        }

        Log.i(TAG, "收到广播: " + packageName + ", 内容: " + preview);

        // 使用 Handler 延迟启动，确保进程完全初始化
        // 同时使用 applicationContext 避免 Binder 问题
        Context appCtx = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // 明确指定启动本模块的 Activity（不是目标 App 的）
                Intent dialogIntent = new Intent();
                dialogIntent.setComponent(new ComponentName(MODULE_PKG, PermissionDialogActivity.class.getName()));
                dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                dialogIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                dialogIntent.putExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME, packageName);
                dialogIntent.putExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW, preview);
                appCtx.startActivity(dialogIntent);
                Log.i(TAG, "弹窗已启动: " + packageName);
            } catch (Throwable e) {
                Log.e(TAG, "启动弹窗失败: " + e.getMessage(), e);
            }
        }, 300); // 延迟 300ms 启动，确保 ActivityManager 就绪
    }
}