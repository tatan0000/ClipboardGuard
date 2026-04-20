package com.android.clipboardguard;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * 广播接收器：收到 system_server 广播后在本模块进程启动弹窗 Activity
 */
public class DialogLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "ClipboardGuard.Receiver";
    public  static final String ACTION_SHOW_DIALOG = "com.android.clipboardguard.ACTION_SHOW_DIALOG";
    private static final String MODULE_PKG         = "com.android.clipboardguard";
    /** 延迟启动 Activity，确保 ActivityManager 就绪 */
    private static final long   LAUNCH_DELAY_MS    = 300;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SHOW_DIALOG.equals(intent.getAction())) return;

        String pkg     = intent.getStringExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME);
        String preview = intent.getStringExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW);

        if (pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "packageName 为空，丢弃");
            return;
        }
        Log.i(TAG, "收到广播: " + pkg);

        if (!isModuleProcessRunning(context)) {
            Log.w(TAG, "模块进程未运行，丢弃");
            return;
        }

        final Context appCtx = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isModuleProcessRunning(appCtx)) {
                Log.w(TAG, "延迟检查：模块进程已死亡，跳过");
                return;
            }
            try {
                Intent dlg = new Intent();
                dlg.setComponent(new ComponentName(MODULE_PKG, PermissionDialogActivity.class.getName()));
                dlg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                dlg.putExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME, pkg);
                dlg.putExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW, preview);
                appCtx.startActivity(dlg);
                Log.i(TAG, "弹窗已启动: " + pkg);
            } catch (Throwable e) {
                Log.e(TAG, "启动弹窗失败: " + e.getMessage());
            }
        }, LAUNCH_DELAY_MS);
    }

    private boolean isModuleProcessRunning(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (MODULE_PKG.equals(p.processName)) return true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "检查进程失败: " + e.getMessage());
        }
        return false;
    }
}
