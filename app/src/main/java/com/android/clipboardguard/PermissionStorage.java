package com.android.clipboardguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 剪贴板权限持久化存储
 * 记录各应用的权限策略：
 *   BLOCK  (0) - 拦截，每次写剪贴板都弹窗询问（默认）
 *   IGNORE (1) - 放行，直接忽略不拦截（用户主动勾选信任该应用）
 *
 * 存储策略：
 *   1. 优先使用 ContentProvider（SQLite）- 跨进程共享
 *   2. 系统级文件存储 /data/data/<pkg>/files/（system_server 可访问）
 *   3. fallback 到应用自身进程的 SharedPreferences
 *
 * 注：永久允许/拒绝已移除，由弹窗实时决策 + 主界面统一管理
 */
public class PermissionStorage {

    private static final String TAG = "ClipboardGuard.Storage";
    private static final String PREF_NAME = "clipboard_permissions";
    // 系统级备用存储文件名（放在 /data/data/<pkg>/files/ 目录下，system_server 可访问）
    private static final String BACKUP_FILE_NAME = "permissions_backup.txt";

    // 权限状态常量
    public static final int PERMISSION_BLOCK  = 0; // 拦截（每次弹窗询问，默认）
    public static final int PERMISSION_IGNORE = 1; // 放行（不拦截）

    // 兼容旧代码的别名
    public static final int PERMISSION_ASK   = PERMISSION_BLOCK;
    public static final int PERMISSION_ALLOW = PERMISSION_IGNORE;
    public static final int PERMISSION_DENY  = PERMISSION_BLOCK;

    // 内存缓存，减少读取次数
    private static final Map<String, Integer> sCache = new HashMap<>();

    // 模块包名
    private static final String MODULE_PKG = "com.android.clipboardguard";

    /**
     * 获取系统级存储目录（/data/data/<pkg>/files/）
     * 这个目录在 system_server 进程中可以访问
     */
    private static File getSystemDataDir(Context context) {
        // 直接构造系统级路径：/data/data/com.android.clipboardguard/files/
        return new File("/data/data/" + MODULE_PKG + "/files");
    }

    /**
     * 检测是否在模块自身进程（只有自身进程才能访问私有 SharedPreferences）
     */
    private static boolean isModuleProcess(Context context) {
        try {
            String pkg = context.getPackageName();
            return MODULE_PKG.equals(pkg);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 获取指定应用的剪贴板写入权限状态（使用默认权限）
     * 优先级：缓存 → ContentProvider → 系统级文件 → SharedPreferences
     */
    public static int getPermission(Context context, String packageName) {
        return getPermission(context, packageName, PERMISSION_ASK);
    }

    /**
     * 获取指定应用的剪贴板写入权限状态（指定默认值）
     */
    public static int getPermission(Context context, String packageName, int defaultPerm) {
        // 1. 先查内存缓存
        if (sCache.containsKey(packageName)) {
            return sCache.get(packageName);
        }

        // 2. 尝试从 ContentProvider 读取（推荐方式，跨进程共享）
        try {
            int perm = PermissionProvider.queryPermission(context, packageName);
            if (perm >= 0) {
                sCache.put(packageName, perm);
                Log.d(TAG, "从ContentProvider读取: " + packageName + " = " + perm);
                return perm;
            }
        } catch (Throwable e) {
            Log.w(TAG, "ContentProvider查询失败: " + e.getMessage());
        }

        // 3. fallback 到系统级文件存储（/data/data/<pkg>/files/）
        int perm = getFromSystemFile(packageName);
        if (perm >= 0) {
            sCache.put(packageName, perm);
            Log.d(TAG, "从系统文件读取: " + packageName + " = " + perm);
            return perm;
        }

        // 4. 最后 fallback 到 SharedPreferences
        // 在模块自身进程：直接用 MODE_WORLD_READABLE 写，其他进程也能读
        // 在其他进程（如 system_server）：尝试通过 moduleContext 读取
        try {
            SharedPreferences prefs = getPrefs(context);
            int permission = prefs.getInt(packageName, defaultPerm);
            // 仅当有明确记录时才缓存（避免缓存默认值掩盖真实设置）
            if (prefs.contains(packageName)) {
                sCache.put(packageName, permission);
                Log.d(TAG, "从SharedPreferences读取: " + packageName + " = " + permission);
                return permission;
            }
        } catch (Throwable e) {
            Log.w(TAG, "SharedPreferences读取失败: " + e.getMessage());
        }

        // 全部失败，返回默认值
        Log.w(TAG, "所有存储方式均失败，使用默认值: " + defaultPerm);
        return defaultPerm;
    }

    /**
     * 设置指定应用的剪贴板写入权限状态
     * 同步写入多个位置确保可靠性
     */
    public static void setPermission(Context context, String packageName, int permission) {
        // 1. 写入 ContentProvider（主存储）
        try {
            PermissionProvider.savePermission(context, packageName, permission);
            Log.d(TAG, "ContentProvider保存成功: " + packageName);
        } catch (Throwable e) {
            Log.w(TAG, "ContentProvider保存失败: " + e.getMessage());
        }

        // 2. 同时写入系统级文件（system_server 可访问）
        try {
            saveToSystemFile(packageName, permission);
            Log.d(TAG, "系统文件保存成功: " + packageName);
        } catch (Throwable e) {
            Log.w(TAG, "系统文件保存失败: " + e.getMessage());
        }

        // 3. 同时写入 SharedPreferences（使用 WORLD_READABLE，让 system_server 也能读）
        try {
            SharedPreferences prefs = getPrefs(context);
            prefs.edit().putInt(packageName, permission).apply();
            Log.d(TAG, "SharedPreferences保存成功: " + packageName);
        } catch (Throwable e) {
            Log.w(TAG, "SharedPreferences保存失败: " + e.getMessage());
        }

        // 4. 更新内存缓存
        sCache.put(packageName, permission);
        Log.i(TAG, "保存权限: " + packageName + " -> " + permissionText(permission));
    }

    /**
     * 从系统级文件读取权限（/data/data/<pkg>/files/permissions_backup.txt）
     */
    private static int getFromSystemFile(String packageName) {
        File dir = new File("/data/data/" + MODULE_PKG + "/files");
        if (!dir.exists()) {
            return -1;
        }

        File file = new File(dir, BACKUP_FILE_NAME);
        if (!file.exists()) {
            return -1;
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
            String value = props.getProperty(packageName);
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "读取系统文件失败: " + e.getMessage());
        }
        return -1;
    }

    /**
     * 保存到系统级文件
     */
    private static void saveToSystemFile(String packageName, int permission) {
        File dir = new File("/data/data/" + MODULE_PKG + "/files");
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                Log.w(TAG, "无法创建目录: " + dir.getPath());
                return;
            }
        }

        File file = new File(dir, BACKUP_FILE_NAME);
        Properties props = new Properties();

        // 先读取现有内容
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException ignored) {}
        }

        // 更新或添加
        props.setProperty(packageName, String.valueOf(permission));

        // 写入
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "ClipboardGuard Permissions Backup");
        } catch (IOException e) {
            Log.e(TAG, "写入系统文件失败: " + e.getMessage());
        }
    }

    /**
     * 批量保存权限（主界面 FAB 保存时调用）
     */
    public static void saveAllPermissions(Context context, Map<String, Integer> permissions) {
        for (Map.Entry<String, Integer> entry : permissions.entrySet()) {
            setPermission(context, entry.getKey(), entry.getValue());
        }
        Log.i(TAG, "批量保存 " + permissions.size() + " 个权限");
    }

    /**
     * 重置指定应用的权限（改为每次询问）
     */
    public static void resetPermission(Context context, String packageName) {
        setPermission(context, packageName, PERMISSION_ASK);
    }

    /**
     * 重置所有应用的权限
     */
    public static void resetAllPermissions(Context context) {
        try {
            SharedPreferences prefs = getPrefs(context);
            prefs.edit().clear().apply();
            sCache.clear();
        } catch (Throwable e) {
            Log.e(TAG, "重置所有权限失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有已设置权限的应用列表
     */
    public static Map<String, Integer> getAllPermissions(Context context) {
        Map<String, Integer> result = new HashMap<>();
        try {
            SharedPreferences prefs = getPrefs(context);
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (entry.getValue() instanceof Integer) {
                    result.put(entry.getKey(), (Integer) entry.getValue());
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "获取所有权限失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 使内存缓存失效（主界面修改后调用）
     */
    public static void invalidateCache(String packageName) {
        sCache.remove(packageName);
    }

    public static void invalidateAllCache() {
        sCache.clear();
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences getPrefs(Context context) {
        // 使用 MODE_WORLD_READABLE 让 SharedPreferences 对其他进程（如 system_server）可读
        // 虽然 API 17 起已废弃，但在 Xposed 模块中这是跨进程共享数据的有效手段
        // SELinux 在 untrusted_app 域对此有限制，但 system_server 域通常可以读取
        try {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            // 如果 WORLD_READABLE 被拒绝，fallback 到 MODE_PRIVATE
            Log.w(TAG, "WORLD_READABLE被拒绝，使用MODE_PRIVATE: " + e.getMessage());
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    public static String permissionText(int permission) {
        switch (permission) {
            case PERMISSION_IGNORE: return "放行";
            default:                return "拦截";
        }
    }
}
