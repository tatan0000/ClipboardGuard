package com.android.clipboardguard;

import android.content.Context;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 剪贴板权限持久化存储
 *
 * 唯一存储后端：ContentProvider（SQLite），跨进程共享。
 * 内存缓存加速读取，写入时同步更新缓存。
 *
 * 权限常量：
 *   BLOCK  (0) - 拦截，弹窗询问（默认）
 *   IGNORE (1) - 放行，不拦截
 */
public class PermissionStorage {

    private static final String TAG = "ClipboardGuard.Storage";

    public static final int PERMISSION_BLOCK  = 0;
    public static final int PERMISSION_IGNORE = 1;

    // 旧代码兼容别名
    public static final int PERMISSION_ASK   = PERMISSION_BLOCK;
    public static final int PERMISSION_ALLOW = PERMISSION_IGNORE;
    public static final int PERMISSION_DENY  = PERMISSION_BLOCK;

    /** 内存缓存（线程安全） */
    private static final Map<String, Integer> sCache =
            Collections.synchronizedMap(new HashMap<>());

    private PermissionStorage() {}

    /** 获取权限（缓存 → ContentProvider，未设置返回默认放行） */
    public static int getPermission(Context context, String packageName) {
        Integer cached = sCache.get(packageName);
        if (cached != null) return cached;

        try {
            int perm = PermissionProvider.queryPermission(context, packageName);
            if (perm >= 0) {
                sCache.put(packageName, perm);
                return perm;
            }
        } catch (Throwable e) {
            Log.w(TAG, "ContentProvider查询失败: " + e.getMessage());
        }
        return PERMISSION_IGNORE;
    }

    /** 设置权限：写入 ContentProvider 并更新缓存 */
    public static void setPermission(Context context, String packageName, int permission) {
        try {
            PermissionProvider.savePermission(context, packageName, permission);
        } catch (Throwable e) {
            Log.w(TAG, "ContentProvider保存失败: " + e.getMessage());
        }
        sCache.put(packageName, permission);
        Log.i(TAG, "保存权限: " + packageName + " -> " + permissionText(permission));
    }

    /** 使单个包名缓存失效 */
    public static void invalidateCache(String packageName) {
        sCache.remove(packageName);
    }

    /** 清空所有缓存 */
    public static void invalidateAllCache() {
        sCache.clear();
    }

    public static String permissionText(int permission) {
        return permission == PERMISSION_IGNORE ? "放行" : "拦截";
    }
}
