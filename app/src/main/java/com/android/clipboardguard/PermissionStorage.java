package com.android.clipboardguard;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 剪贴板权限持久化存储
 *
 * 存储策略：
 * - 文件只存 BLOCK 条目（勾选/拦截的应用），每行一个包名
 * - 未勾选 → 不在文件里 = 默认放行
 * - write_blocklist.txt / read_blocklist.txt
 *
 * 权限常量：
 *   BLOCK  (0) - 拦截，弹窗询问
 *   IGNORE (1) - 放行，不拦截
 */
public class PermissionStorage {

    private static final String TAG = "ClipboardGuard.Storage";

    public static final int PERMISSION_BLOCK  = 0;
    public static final int PERMISSION_IGNORE = 1;

    public static final int PERMISSION_ASK   = PERMISSION_BLOCK;
    public static final int PERMISSION_ALLOW = PERMISSION_IGNORE;
    public static final int PERMISSION_DENY  = PERMISSION_BLOCK;

    /**
     * 获取权限
     */
    public static int getPermission(Context context, String packageName) {
        Map<String, Integer> all = PermissionProvider.getAllWritePermissionsDirect(context);
        Integer perm = all.get(packageName);
        return perm != null ? perm : -1;
    }

    /**
     * 设置权限
     */
    public static void setPermission(Context context, String packageName, int permission) {
        PermissionProvider.saveWritePermission(context, packageName, permission);
        Log.i(TAG, "保存权限: " + packageName + " -> " + permissionText(permission));
    }

    /**
     * 批量设置权限
     */
    public static void setPermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        PermissionProvider.saveAllWritePermissions(context, permissions);
        PermissionProvider.sendPermissionChangedBroadcastStatic(context);
        Log.i(TAG, "批量保存权限: " + permissions.size() + " 条");
    }

    /**
     * 获取所有权限配置
     */
    public static Map<String, Integer> getAllPermissions(Context context) {
        return PermissionProvider.getAllWritePermissionsDirect(context);
    }

    /**
     * 获取所有放行的包名
     */
    public static Set<String> getAllAllowedPackages(Context context) {
        Set<String> result = new HashSet<>();
        Map<String, Integer> all = PermissionProvider.getAllWritePermissionsDirect(context);
        for (Map.Entry<String, Integer> entry : all.entrySet()) {
            if (entry.getValue() != PERMISSION_BLOCK) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 批量保存写入权限
     */
    public static void batchSavePermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        PermissionProvider.saveAllWritePermissions(context, permissions);
        PermissionProvider.sendPermissionChangedBroadcastStatic(context);
        Log.i(TAG, "批量保存写入权限: " + permissions.size() + " 条");
    }

    /**
     * 批量保存读取权限
     */
    public static void batchSaveReadPermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        PermissionProvider.saveAllReadPermissions(context, permissions);
        PermissionProvider.sendPermissionChangedBroadcastStatic(context);
        Log.i(TAG, "批量保存读取权限: " + permissions.size() + " 条");
    }

    public static String permissionText(int permission) {
        return permission == PERMISSION_IGNORE ? "放行" : "拦截";
    }
}