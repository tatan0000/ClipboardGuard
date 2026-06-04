package com.android.clipboardguard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/*
 * PermissionCache - Hook 侧内存缓存
 *
 * 开机：ConfigManager.loadFromDataSystem() 从 /data/system/clipboardguard/ 加载
 * 运行时：监听 App 配置广播，ConfigManager 落盘并刷新本缓存
 */
public class PermissionCache {

    private static final String TAG = "ClipboardGuard.PermCache";

    private static final Set<String> sWriteBlockSet = new HashSet<>();
    private static boolean sWriteLoaded = false;

    private static final Set<String> sReadBlockSet = new HashSet<>();
    private static boolean sReadLoaded = false;
    private static volatile boolean sReadBlockedToastEnabled = true;
    private static volatile boolean sLsposedLogEnabled = true;

    private static BroadcastReceiver sRefreshReceiver;

    /** 更新写入拦截名单缓存 */
    public static synchronized void updateFromWriteBlockList(ArrayList<String> blocklist) {
        if (blocklist == null) return;
        sWriteBlockSet.clear();
        sWriteBlockSet.addAll(blocklist);
        sWriteLoaded = true;
        XLog.d(TAG, "updateFromWriteBlockList: 收到 " + blocklist.size() + " 条数据");
    }

    /** 更新读取拦截名单缓存 */
    public static synchronized void updateFromReadBlockList(ArrayList<String> blocklist) {
        if (blocklist == null) return;
        sReadBlockSet.clear();
        sReadBlockSet.addAll(blocklist);
        sReadLoaded = true;
        XLog.d(TAG, "updateFromReadBlockList: 收到 " + blocklist.size() + " 条数据");
    }

    /** 更新全局开关状态（读取拦截 Toast、LSPosed 日志输出） */
    public static synchronized void updateGlobalFlags(boolean readBlockedToastEnabled, boolean lsposedLogEnabled) {
        sReadBlockedToastEnabled = readBlockedToastEnabled;
        sLsposedLogEnabled = lsposedLogEnabled;
    }

    /** 注册配置变更广播接收器，接收 App 侧的配置同步广播 */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static synchronized boolean registerRefreshReceiver(Context context) {
        if (sRefreshReceiver != null) return true;

        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    ConfigManager.applyConfigBroadcast(intent);
                    // 仅 App 打开时（全量同步广播）刷新模块状态 JSON，
                    // 规则编辑等增量变更不触发，避免无意义磁盘写入。
                    if (intent.getBooleanExtra(PermissionProvider.EXTRA_REQUEST_STATUS_UPDATE, false)) {
                        ClipboardHook.reportHookStatus();
                    }
                }
            };

            IntentFilter filter = new IntentFilter(PermissionProvider.ACTION_CONFIG_CHANGED);
            long identity = Binder.clearCallingIdentity();
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    // 注意：这里必须使用 RECEIVER_EXPORTED。
                    // 发送端是 App 进程（任意 UID），接收端是 system_server（UID=1000），
                    // RECEIVER_NOT_EXPORTED 会阻止跨 UID 广播，导致 App 配置变更通知
                    // 被静默丢弃，system_server 侧永远收不到。安全由 PERMISSION_CONFIG_SYNC
                    // 签名级权限保证（仅本模块 App 持有）。
                    context.registerReceiver(
                            receiver,
                            filter,
                            PermissionProvider.PERMISSION_CONFIG_SYNC,
                            null,
                            Context.RECEIVER_EXPORTED
                    );
                } else {
                    context.registerReceiver(
                            receiver,
                            filter,
                            PermissionProvider.PERMISSION_CONFIG_SYNC,
                            null
                    );
                }
                sRefreshReceiver = receiver;
                XLog.i(TAG, "配置变更广播接收器注册成功");
                return true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (NullPointerException npe) {
            XLog.w(TAG, "广播接收器注册失败：系统尚未就绪");
            return false;
        } catch (Throwable e) {
            XLog.e(TAG, "注册广播接收器失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查包名是否不在写入拦截列表中（即应放行）。
     * @return true = 放行（不在拦截列表 或 缓存未加载），false = 应拦截
     */
    public static synchronized boolean isWriteIgnored(String packageName) {
        if (!sWriteLoaded) {
            XLog.w(TAG, "写入缓存未加载，暂时放行: " + packageName);
            return true;
        }
        return !sWriteBlockSet.contains(packageName);
    }

    /**
     * 检查包名是否不在读取拦截列表中（即应放行）。
     * @return true = 放行（不在拦截列表 或 缓存未加载），false = 应拦截
     */
    public static synchronized boolean isReadIgnored(String packageName) {
        if (!sReadLoaded) {
            XLog.w(TAG, "读取缓存未加载，暂时放行: " + packageName);
            return true;
        }
        return !sReadBlockSet.contains(packageName);
    }

    /** 检查写入拦截名单是否已加载 */
    public static synchronized boolean isWriteLoaded() { return sWriteLoaded; }
    /** 检查读取拦截名单是否已加载 */
    public static synchronized boolean isReadLoaded() { return sReadLoaded; }
    /** 检查读取拦截 Toast 是否启用 */
    public static boolean isReadBlockedToastEnabled() { return sReadBlockedToastEnabled; }
    /** 检查 LSPosed 日志输出是否启用 */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isLsposedLogEnabled() { return sLsposedLogEnabled; }
}
