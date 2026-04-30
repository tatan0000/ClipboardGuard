package com.android.clipboardguard;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 剪贴板权限持久化存储（2026-04-28 重构 v2 → 纯文本）
 *
 * 存储策略（2026-04-28 更新）：
 * - 文件只存 BLOCK 条目（勾选/拦截的应用）
 * - 未勾选 → 删除记录（不在文件里 = 默认放行）
 * - 存储介质：纯文本文件 /data/data/com.android.clipboardguard/files/blocklist.txt
 * - 跨进程通道：ContentProvider.call()（Binder + clearCallingIdentity）
 * - Hook 侧：启动时全量加载 blockSet（O(1) 内存查表）
 * - 变更同步：App 保存后发广播，Hook 侧刷新 blockSet
 *
 * 权限常量：
 *   BLOCK  (0) - 拦截，弹窗询问
 *   IGNORE (1) - 放行，不拦截（文件里没有 = 默认 IGNORE）
 */
public class PermissionStorage {

    private static final String TAG = "ClipboardGuard.Storage";

    public static final int PERMISSION_BLOCK  = 0;
    public static final int PERMISSION_IGNORE = 1;

    // 旧代码兼容别名
    public static final int PERMISSION_ASK   = PERMISSION_BLOCK;
    public static final int PERMISSION_ALLOW = PERMISSION_IGNORE;
    public static final int PERMISSION_DENY  = PERMISSION_BLOCK;

    /**
     * 获取权限（App 端使用）
     * 通过 ContentProvider.call("getPermission") 读取
     */
    public static int getPermission(Context context, String packageName) {
        int perm = PermissionProvider.queryPermission(context, packageName);
        Log.d(TAG, "getPermission(" + packageName + ") = " + perm);
        return perm;
    }

    /**
     * 设置权限（App 端使用）
     * 通过 ContentProvider.call("setPermission") 写入纯文本文件
     * 写入后自动发广播通知 Hook 侧刷新 blockSet
     */
    public static void setPermission(Context context, String packageName, int permission) {
        PermissionProvider.savePermission(context, packageName, permission);
        Log.i(TAG, "保存权限: " + packageName + " -> " + permissionText(permission));
    }

    /**
     * 批量设置权限（App 端使用）
     * 通过 ContentProvider 批量写入纯文本文件，最后统一发一次广播
     */
    public static void setPermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : permissions.entrySet()) {
            PermissionProvider.savePermission(context, entry.getKey(), entry.getValue());
        }
        // 批量保存后统一通知一次
        PermissionProvider.sendPermissionChangedBroadcastStatic(context);
        Log.i(TAG, "批量保存权限: " + permissions.size() + " 条");
    }

    /**
     * 获取所有权限配置（App 端使用，用于展示列表）
     * 通过 ContentProvider.query("permission_all") 读取
     */
    public static Map<String, Integer> getAllPermissions(Context context) {
        Map<String, Integer> result = new HashMap<>();
        List<String[]> rows = PermissionProvider.getAllPermissionsFromDb(context);
        for (String[] row : rows) {
            if (row.length >= 2) {
                try {
                    result.put(row[0], Integer.parseInt(row[1]));
                } catch (NumberFormatException ignored) {}
            }
        }
        Log.d(TAG, "getAllPermissions 返回 " + result.size() + " 条");
        return result;
    }

    /**
     * 获取所有放行的包名（App 端使用）
     * 文件里只有 BLOCK 条目；不在文件里 = 放行（IGNORE）
     */
    public static Set<String> getAllAllowedPackages(Context context) {
        Set<String> result = new HashSet<>();
        Map<String, Integer> all = getAllPermissions(context);
        for (Map.Entry<String, Integer> entry : all.entrySet()) {
            if (entry.getValue() != PERMISSION_BLOCK) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 清空所有缓存（App 端使用）
     * 注意：这里清空的是文本文件数据，不是 Hook 侧的 blockSet
     */
    public static void invalidateAllCache() {
        Log.w(TAG, "invalidateAllCache 已废弃，请使用 PermissionProvider.delete()");
    }

    public static String permissionText(int permission) {
        return permission == PERMISSION_IGNORE ? "放行" : "拦截";
    }
}
