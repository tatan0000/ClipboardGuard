package com.android.clipboardguard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限数据 ContentProvider
 *
 * 存储层：纯文本文件（/data/data/com.android.clipboardguard/files/blocklist.txt）
 * - 每行一个包名，只存需要拦截的包
 * - 明文格式，方便调试
 * - 2000 条 ≈ 200KB，加载到 HashSet 只需 5-10ms
 *
 * 跨进程通道：ContentProvider call()（Binder，绕过 uid 安全检查）
 * Hook 侧缓存：blockSet（启动时全量加载，变更时广播刷新）
 *
 * 广播 ACTION_PERMISSION_CHANGED：App 保存权限后发出，Hook 侧收到后刷新 blockSet
 */
public class PermissionProvider extends ContentProvider {

    private static final String TAG = "ClipboardGuard.Provider";
    public static final  String AUTHORITY = "com.android.clipboardguard.provider";

    /** App 保存权限后发出此广播，Hook 侧收到后刷新内存 ignoreSet */
    public static final String ACTION_PERMISSION_CHANGED =
            "com.android.clipboardguard.PERMISSION_CHANGED";

    // URI 类型
    private static final int URI_PERMISSION_PKG = 1;
    private static final int URI_PERMISSION_ALL = 2;
    private static final int URI_PENDING_PKG    = 3;
    private static final int URI_QUERY_ALL      = 4;
    private static final int URI_DELETE_ALL     = 5;
    private static final int URI_LOG_ALL        = 6;
    private static final int URI_LOG_INSERT     = 7;
    private static final int URI_LOG_CLEAR      = 8;

    // 列名
    public static final String COL_PACKAGE    = "package_name";
    public static final String COL_PERMISSION = "permission";
    public static final String COL_DECISION   = "decision";
    public static final String COL_REMEMBER   = "remember";
    public static final String COL_ACTION     = "action";
    public static final String COL_CONTENT    = "content";
    public static final String COL_TIMESTAMP  = "timestamp";

    // call() 方法键值
    public static final String CALL_METHOD_GET        = "getPermission";
    public static final String CALL_METHOD_SET        = "setPermission";
    public static final String CALL_METHOD_GET_ALL    = "getAllPermissions";   // 新增：全量拉取
    public static final String CALL_METHOD_SET_ALL    = "setAllPermissions";    // 新增：批量保存
    public static final String CALL_METHOD_GET_PENDING = "getPending";
    public static final String CALL_METHOD_REFRESH    = "refresh";             // 新增：通知刷新
    public static final String CALL_METHOD_TRIM       = "trim";                // 整理（纯文本文件无需整理，直接返回）
    public static final String CALL_KEY_PACKAGE       = "pkg";
    public static final String CALL_KEY_PERMISSION    = "perm";
    public static final String CALL_KEY_RESULT        = "result";
    public static final String CALL_KEY_DECISION      = "decision";
    /** getAllPermissions 返回的 Bundle key，值为 String[]，格式 ["pkg1","1","pkg2","0",...] */
    public static final String CALL_KEY_ALL_DATA      = "all_data";

    // 纯文本文件路径（只存 BLOCK/拦截 的包名）
    private static final String BLOCKLIST_FILE = "/data/data/com.android.clipboardguard/files/blocklist.txt";

    // 内存缓存：避免每次都读文件
    private static Set<String> sCachedBlockList;
    private static long sLastModified = 0;

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(AUTHORITY, "permission/*",     URI_PERMISSION_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission",       URI_PERMISSION_ALL);
        sUriMatcher.addURI(AUTHORITY, "pending/*",        URI_PENDING_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission_all",   URI_QUERY_ALL);
        sUriMatcher.addURI(AUTHORITY, "permission_reset", URI_DELETE_ALL);
        sUriMatcher.addURI(AUTHORITY, "log_all",          URI_LOG_ALL);
        sUriMatcher.addURI(AUTHORITY, "log",              URI_LOG_INSERT);
        sUriMatcher.addURI(AUTHORITY, "log_clear",        URI_LOG_CLEAR);
    }

    // ──────────────────────────── 日志 SQLite（仅用于日志功能，不存权限）────────────────────────────

    private static final String PACKAGE_NAME = "com.android.clipboardguard";

    private static class LogDbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME    = "clipboardguard.db";
        private static final int    DB_VERSION = 2;

        LogDbHelper(Context context) {
            super(context, "/data/data/" + PACKAGE_NAME + "/databases/" + DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pending (" +
                    "package_name TEXT PRIMARY KEY, decision INTEGER NOT NULL, " +
                    "remember INTEGER NOT NULL DEFAULT 0, timestamp INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS clipboard_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT NOT NULL, " +
                    "action TEXT NOT NULL, content TEXT, timestamp INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // v1 -> v2: 权限表迁移到纯文本文件，此处删除旧 permission 表
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS permission");
                onCreate(db);
            }
        }
    }

    private LogDbHelper mDbHelper;

    // ──────────────────────────── ContentProvider 生命周期 ────────────────────────────

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        mDbHelper = new LogDbHelper(ctx);
        // 初始化时加载一次 blocklist 到内存缓存
        loadBlockListFromFile();
        Log.i(TAG, "PermissionProvider 初始化完成，blocklist 条数=" + (sCachedBlockList != null ? sCachedBlockList.size() : 0));
        return true;
    }

    // ──────────────────────────── call() ────────────────────────────

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) extras = new Bundle();
        long identity = Binder.clearCallingIdentity();
        try {
            switch (method) {
                case CALL_METHOD_GET: {
                    String pkg = extras.getString(CALL_KEY_PACKAGE);
                    if (pkg == null) return null;
                    Bundle result = new Bundle();
                    result.putInt(CALL_KEY_RESULT, getPermission(pkg));
                    return result;
                }

                case CALL_METHOD_SET: {
                    String pkg = extras.getString(CALL_KEY_PACKAGE);
                    if (pkg == null) return null;
                    int permission = extras.getInt(CALL_KEY_PERMISSION, 0);
                    setPermission(pkg, permission);
                    Log.d(TAG, "call()写入: " + pkg + " -> " + permission);
                    return new Bundle();
                }

                case CALL_METHOD_SET_ALL: {
                    // 批量保存：传入 ["pkg1","0","pkg2","1",...] 格式
                    String[] allData = extras.getStringArray(CALL_KEY_ALL_DATA);
                    if (allData != null) {
                        Map<String, Integer> perms = new HashMap<>(allData.length / 2);
                        for (int i = 0; i < allData.length - 1; i += 2) {
                            perms.put(allData[i], Integer.parseInt(allData[i + 1]));
                        }
                        saveAllPermissions(perms);
                        Log.i(TAG, "批量保存完成: " + perms.size() + " 条");
                    }
                    return new Bundle();
                }

                case CALL_METHOD_GET_ALL: {
                    // 全量返回所有权限，供 Hook 侧一次性加载到 HashSet
                    Map<String, Integer> all = getAllPermissionsMap();
                    // 格式：["pkg1","1","pkg2","0", ...]
                    String[] flat = new String[all.size() * 2];
                    int i = 0;
                    for (Map.Entry<String, Integer> e : all.entrySet()) {
                        flat[i++] = e.getKey();
                        flat[i++] = String.valueOf(e.getValue());
                    }
                    Bundle result = new Bundle();
                    result.putStringArray(CALL_KEY_ALL_DATA, flat);
                    Log.d(TAG, "getAllPermissions 返回 " + all.size() + " 条");
                    return result;
                }

                case CALL_METHOD_GET_PENDING: {
                    String pkg = extras.getString(CALL_KEY_PACKAGE);
                    if (pkg == null) return null;
                    Bundle result = new Bundle();
                    try (Cursor c = mDbHelper.getReadableDatabase().query(
                            "pending", null,
                            "package_name = ?", new String[]{pkg},
                            null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            result.putInt(CALL_KEY_DECISION, c.getInt(c.getColumnIndexOrThrow("decision")));
                            result.putInt(CALL_KEY_RESULT, 1);
                        } else {
                            result.putInt(CALL_KEY_RESULT, -1);
                        }
                    }
                    return result;
                }

                case CALL_METHOD_REFRESH: {
                    // App 端调用此方法，Hook 侧会收到并刷新 ignoreSet
                    // Hook 侧通过 registerReceiver 监听 ACTION_PERMISSION_CHANGED，
                    // 所以这里仍然走广播路线（已在 sendPermissionChangedBroadcast 中修复）
                    Bundle result = new Bundle();
                    result.putBoolean("refreshed", true);
                    return result;
                }

                case CALL_METHOD_TRIM: {
                    // 纯文本文件无需整理，trim 操作直接返回
                    Log.i(TAG, "trim: 纯文本文件无需整理");
                    return new Bundle();
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "call()处理失败: " + e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    // ──────────────────────────── 纯文本文件读写 ────────────────────────────

    /**
     * 从内存缓存读取单个权限
     * BLOCK(0) = 在 blocklist 中，IGNORE(1)/无记录 = 不在 blocklist 中
     */
    private int getPermission(String packageName) {
        if (sCachedBlockList == null) loadBlockListFromFile();
        return sCachedBlockList.contains(packageName)
            ? PermissionStorage.PERMISSION_BLOCK
            : -1;
    }

    /**
     * 设置单个权限并更新内存缓存
     */
    private void setPermission(String packageName, int permission) {
        if (sCachedBlockList == null) loadBlockListFromFile();
        if (permission == PermissionStorage.PERMISSION_BLOCK) {
            if (sCachedBlockList.add(packageName)) {
                saveBlockListToFile(sCachedBlockList);
            }
        } else {
            if (sCachedBlockList.remove(packageName)) {
                saveBlockListToFile(sCachedBlockList);
            }
        }
    }

    /**
     * 获取所有权限的 Map（供 ContentProvider.call 返回）
     */
    private Map<String, Integer> getAllPermissionsMap() {
        if (sCachedBlockList == null) loadBlockListFromFile();
        Map<String, Integer> result = new HashMap<>();
        for (String pkg : sCachedBlockList) {
            result.put(pkg, PermissionStorage.PERMISSION_BLOCK);
        }
        return result;
    }

    /**
     * 批量保存所有权限（全量覆盖）
     */
    private void saveAllPermissions(Map<String, Integer> permissions) {
        Set<String> blockList = new HashSet<>();
        for (Map.Entry<String, Integer> e : permissions.entrySet()) {
            if (e.getValue() == PermissionStorage.PERMISSION_BLOCK) {
                blockList.add(e.getKey());
            }
        }
        saveBlockListToFile(blockList);
        sCachedBlockList = blockList;
    }

    /**
     * 从文本文件加载 blocklist 到内存缓存
     */
    private synchronized void loadBlockListFromFile() {
        File file = new File(BLOCKLIST_FILE);
        sCachedBlockList = new HashSet<>();
        if (!file.exists()) {
            sLastModified = 0;
            return;
        }
        sLastModified = file.lastModified();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    sCachedBlockList.add(line);
                }
            }
            Log.d(TAG, "loadBlockList: 从文件加载 " + sCachedBlockList.size() + " 条");
        } catch (IOException e) {
            Log.e(TAG, "loadBlockList 失败: " + e.getMessage());
        }
    }

    /**
     * 将 blocklist 保存到文本文件
     */
    private synchronized void saveBlockListToFile(Set<String> blockList) {
        File file = new File(BLOCKLIST_FILE);
        try {
            // 确保目录存在
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                for (String pkg : blockList) {
                    writer.write(pkg);
                    writer.newLine();
                }
            }
            sLastModified = file.lastModified();
            Log.d(TAG, "saveBlockList: 写入 " + blockList.size() + " 条");
        } catch (IOException e) {
            Log.e(TAG, "saveBlockList 失败: " + e.getMessage());
        }
    }

    // ──────────────────────────── query（兼容旧代码）────────────────────────────

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        int match = sUriMatcher.match(uri);

        if (match == URI_PERMISSION_PKG) {
            String pkg = uri.getLastPathSegment();
            if (pkg == null) return null;
            MatrixCursor result = new MatrixCursor(new String[]{COL_PACKAGE, COL_PERMISSION});
            result.addRow(new Object[]{pkg, getPermission(pkg)});
            return result;
        }

        if (match == URI_QUERY_ALL) {
            Map<String, Integer> all = getAllPermissionsMap();
            MatrixCursor result = new MatrixCursor(new String[]{COL_PACKAGE, COL_PERMISSION});
            for (Map.Entry<String, Integer> e : all.entrySet()) {
                result.addRow(new Object[]{e.getKey(), e.getValue()});
            }
            return result;
        }

        if (match == URI_PENDING_PKG) {
            String pkg = uri.getLastPathSegment();
            if (pkg == null) return null;
            Cursor c = mDbHelper.getReadableDatabase().query("pending", null,
                    "package_name = ?", new String[]{pkg}, null, null, null);
            if (c != null && c.moveToFirst()) {
                MatrixCursor result = new MatrixCursor(new String[]{COL_PACKAGE, COL_DECISION, COL_REMEMBER});
                result.addRow(new Object[]{
                        pkg,
                        c.getInt(c.getColumnIndexOrThrow(COL_DECISION)),
                        c.getInt(c.getColumnIndexOrThrow(COL_REMEMBER))
                });
                c.close();
                return result;
            }
            if (c != null) c.close();
            return null;
        }

        if (match == URI_LOG_ALL) {
            return mDbHelper.getReadableDatabase()
                    .query("clipboard_log", null, null, null, null, null, "timestamp DESC LIMIT 100");
        }

        return null;
    }

    // ──────────────────────────── insert ────────────────────────────

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values == null) return null;
        int match = sUriMatcher.match(uri);

        if (match == URI_PERMISSION_ALL) {
            String pkg  = values.getAsString(COL_PACKAGE);
            Integer perm = values.getAsInteger(COL_PERMISSION);
            if (pkg != null && perm != null) {
                setPermission(pkg, perm);
            }
        }

        if (match == URI_PENDING_PKG) {
            String pkg      = values.getAsString(COL_PACKAGE);
            Integer decision = values.getAsInteger(COL_DECISION);
            Integer remember = values.getAsInteger(COL_REMEMBER);
            if (pkg != null && decision != null) {
                ContentValues cv = new ContentValues();
                cv.put("package_name", pkg);
                cv.put("decision", decision);
                cv.put("remember", remember != null ? remember : 0);
                cv.put("timestamp", System.currentTimeMillis());
                mDbHelper.getWritableDatabase()
                        .insertWithOnConflict("pending", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }

        if (match == URI_LOG_INSERT) {
            String pkg     = values.getAsString("package_name");
            String action  = values.getAsString("action");
            String content = values.getAsString("content");
            if (pkg != null && action != null) {
                ContentValues cv = new ContentValues();
                cv.put("package_name", pkg);
                cv.put("action", action);
                cv.put("content", content);
                Long ts = values.getAsLong("timestamp");
                cv.put("timestamp", ts != null ? ts : System.currentTimeMillis());
                mDbHelper.getWritableDatabase().insert("clipboard_log", null, cv);
            }
        }
        return null;
    }

    // ──────────────────────────── delete ────────────────────────────

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = sUriMatcher.match(uri);
        String pkg = uri.getLastPathSegment();

        if (match == URI_PERMISSION_PKG && pkg != null) {
            if (sCachedBlockList == null) loadBlockListFromFile();
            if (sCachedBlockList.remove(pkg)) {
                saveBlockListToFile(sCachedBlockList);
            }
            return 1;
        }

        if (match == URI_DELETE_ALL) {
            // 清空 blocklist 文件
            if (sCachedBlockList == null) loadBlockListFromFile();
            int count = sCachedBlockList.size();
            sCachedBlockList.clear();
            saveBlockListToFile(sCachedBlockList);
            return count;
        }

        if (match == URI_PENDING_PKG && pkg != null) {
            return mDbHelper.getWritableDatabase()
                    .delete("pending", "package_name = ?", new String[]{pkg});
        }

        if (match == URI_LOG_CLEAR) {
            return mDbHelper.getWritableDatabase().delete("clipboard_log", null, null);
        }
        return 0;
    }

    // ──────────────────────────── update ────────────────────────────

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        insert(uri, values);
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/permission";
    }

    // ──────────────────────────── 静态工具方法（App 端调用）────────────────────────────

    /**
     * 保存权限并发广播通知 Hook 侧刷新 ignoreSet
     */
    public static void savePermission(Context context, String packageName, int permission) {
        Log.d(TAG, "savePermission: " + packageName + " -> " + permission);
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString(CALL_KEY_PACKAGE, packageName);
            args.putInt(CALL_KEY_PERMISSION, permission);
            context.getContentResolver().call(uri, CALL_METHOD_SET, null, args);
            Log.d(TAG, "savePermission 成功");
            // 通知 Hook 侧刷新缓存
            sendPermissionChangedBroadcast(context);
        } catch (Throwable e) {
            Log.e(TAG, "保存权限失败: " + packageName + " -> " + e.getMessage());
        }
    }

    /**
     * 批量保存所有权限（不发广播，调用方需自行发送广播刷新）
     * @param permissions Map<packageName, permission>
     */
    public static void saveAllPermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        Log.d(TAG, "saveAllPermissions: " + permissions.size() + " 条");
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            // 转换为 ["pkg1","0","pkg2","1",...] 格式
            String[] flat = new String[permissions.size() * 2];
            int i = 0;
            for (Map.Entry<String, Integer> e : permissions.entrySet()) {
                flat[i++] = e.getKey();
                flat[i++] = String.valueOf(e.getValue());
            }
            args.putStringArray(CALL_KEY_ALL_DATA, flat);
            context.getContentResolver().call(uri, CALL_METHOD_SET_ALL, null, args);
            Log.i(TAG, "saveAllPermissions 成功: " + permissions.size() + " 条");
        } catch (Throwable e) {
            Log.e(TAG, "saveAllPermissions 失败: " + e.getMessage());
        }
    }

    /**
     * 发送权限变更广播，Hook 侧收到后会刷新 ignoreSet
     *
     * 关键：system_server 中注册的 BroadcastReceiver 只能通过隐式广播接收
     * 不能用 setPackage("android")，因为 system_server 不是 "android" 包
     * 发隐式广播 + Hook 侧注册 IntentFilter → system_server 可以收到
     *
     * 优化：Intent 中携带 blocklist 数据，system_server 无需再读文件/调用 ContentProvider
     */
    private static void sendPermissionChangedBroadcast(Context context) {
        try {
            Intent intent = new Intent(ACTION_PERMISSION_CHANGED);
            // ✅ 发隐式广播，不过滤目标包
            // Hook 侧在 system_server 中通过 registerReceiver 注册，监听此 Action
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

            // 携带 blocklist 数据，避免 system_server 再次调用 ContentProvider
            try {
                // sCachedBlockList 可能为 null（App 刚安装），先尝试加载
                ArrayList<String> blocklist = new ArrayList<>();
                if (sCachedBlockList != null && !sCachedBlockList.isEmpty()) {
                    blocklist.addAll(sCachedBlockList);
                } else {
                    // 兜底：直接读文件
                    File file = new File(BLOCKLIST_FILE);
                    if (file.exists()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                line = line.trim();
                                if (!line.isEmpty() && !line.startsWith("#")) {
                                    blocklist.add(line);
                                }
                            }
                        }
                    }
                }
                if (!blocklist.isEmpty()) {
                    intent.putStringArrayListExtra("blocklist", blocklist);
                    Log.d(TAG, "广播携带 blocklist: " + blocklist.size() + " 条");
                }
            } catch (Throwable e) {
                Log.w(TAG, "获取 blocklist 失败: " + e.getMessage());
            }

            context.sendBroadcast(intent);
            Log.d(TAG, "已发送权限变更广播: " + ACTION_PERMISSION_CHANGED);
        } catch (Throwable e) {
            Log.w(TAG, "发送广播失败（Hook 侧将在下次启动时刷新）: " + e.getMessage());
        }
    }

    /**
     * 静态方法：供 PermissionStorage 批量保存后通知 Hook 侧刷新
     */
    public static void sendPermissionChangedBroadcastStatic(Context context) {
        sendPermissionChangedBroadcast(context);
    }

    /**
     * 清空所有权限数据（用于重置）
     * App 端调用 saveChanges() 前先清空，确保只有本次勾选的应用被保存
     */
    public static void clearAllPermissions(Context context) {
        Log.w(TAG, "clearAllPermissions: 即将清空所有权限数据");
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/permission_reset");
            context.getContentResolver().delete(uri, null, null);
            Log.i(TAG, "clearAllPermissions: 已清空");
        } catch (Throwable e) {
            Log.e(TAG, "clearAllPermissions 失败: " + e.getMessage());
        }
    }

    /**
     * 整理文件（纯文本无需整理，直接返回）
     */
    public static void trimBlocklist(Context context) {
        // 纯文本文件每次全量写入，无残留，无需整理
        Log.d(TAG, "trimBlocklist: 纯文本文件无需整理");
    }

    /**
     * 查询单个权限（App 端使用）
     */
    public static int queryPermission(Context context, String packageName) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString(CALL_KEY_PACKAGE, packageName);
            Bundle result = context.getContentResolver().call(uri, CALL_METHOD_GET, null, args);
            if (result != null) {
                int perm = result.getInt(CALL_KEY_RESULT, -1);
                Log.d(TAG, "queryPermission(" + packageName + ") = " + perm);
                return perm;
            }
        } catch (Throwable e) {
            Log.e(TAG, "查询权限失败: " + packageName + " -> " + e.getMessage());
        }
        return -1;
    }

    /**
     * 全量获取所有权限（App 端使用，用于展示列表）
     */
    public static List<String[]> getAllPermissionsFromDb(Context context) {
        List<String[]> result = new ArrayList<>();
        Uri uri = Uri.parse("content://" + AUTHORITY + "/permission_all");
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String pkg  = c.getString(c.getColumnIndexOrThrow(COL_PACKAGE));
                    int    perm = c.getInt(c.getColumnIndexOrThrow(COL_PERMISSION));
                    result.add(new String[]{pkg, String.valueOf(perm)});
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "getAllPermissionsFromDb 失败: " + e.getMessage());
        }
        return result;
    }

    public static List<String[]> getAllPermissions(Context context) {
        return getAllPermissionsFromDb(context);
    }

    /**
     * 直接通过 ContentResolver.call() 全量拉取所有权限（Hook 侧专用）
     * 强制触发 ContentProvider 初始化 + 绕过 uid 安全检查
     *
     * 方案说明：
     * - ContentResolver.call() 会先确保 ContentProvider 进程启动并完成 onCreate()
     * - Binder.clearCallingIdentity() 让 Provider 以为自己有完整权限
     * - 不依赖 getLocalContentProvider()，避免进程间代理的时序问题
     *
     * 注意：ContentProvider 不可用时（进程未启动、user 未 unlock）会抛出异常，
     * 由调用方（PermissionCache.loadIgnoreSet）判断是否初始化成功。
     */
    public static java.util.Map<String, Integer> getAllPermissionsDirect(Context context) throws Exception {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        Uri uri = Uri.parse("content://" + AUTHORITY);
        Bundle args = new Bundle();

        // 关键：clearCallingIdentity 让 ContentResolver 认为我们有权调用
        long token = Binder.clearCallingIdentity();
        try {
            Bundle ret = context.getContentResolver().call(uri, CALL_METHOD_GET_ALL, null, args);
            if (ret == null) {
                // ContentProvider 返回 null = 不可用
                throw new Exception("ContentProvider 返回 null（进程未启动或 user 未 unlock）");
            }
            String[] flat = ret.getStringArray(CALL_KEY_ALL_DATA);
            if (flat != null && flat.length > 0) {
                for (int i = 0; i + 1 < flat.length; i += 2) {
                    try {
                        result.put(flat[i], Integer.parseInt(flat[i + 1]));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        Log.d(TAG, "getAllPermissionsDirect 返回 " + result.size() + " 条");
        return result;
    }

    public static void writePendingResult(Context context, String packageName,
                                          int decision, boolean remember) {
        Uri uri = Uri.parse("content://" + AUTHORITY + "/pending/" + packageName);
        ContentValues cv = new ContentValues();
        cv.put(COL_PACKAGE, packageName);
        cv.put(COL_DECISION, decision);
        cv.put(COL_REMEMBER, remember ? 1 : 0);
        cv.put("timestamp", System.currentTimeMillis());
        try {
            context.getContentResolver().insert(uri, cv);
        } catch (Throwable e) {
            Log.e(TAG, "写入pending结果失败: " + e.getMessage());
        }
    }

    public static void writeLog(Context context, String packageName, String action, String content) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/log");
            ContentValues cv = new ContentValues();
            cv.put("package_name", packageName);
            cv.put("action", action);
            cv.put("content", content);
            cv.put("timestamp", System.currentTimeMillis());
            context.getContentResolver().insert(uri, cv);
        } catch (Throwable e) {
            Log.e(TAG, "写入日志失败: " + e.getMessage());
        }
    }

    public static List<String[]> getLogs(Context context, int limit) {
        List<String[]> result = new ArrayList<>();
        Uri uri = Uri.parse("content://" + AUTHORITY + "/log_all");
        try (Cursor c = context.getContentResolver()
                .query(uri, null, null, null, "timestamp DESC LIMIT " + limit)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String pkg       = c.getString(c.getColumnIndexOrThrow("package_name"));
                    String action    = c.getString(c.getColumnIndexOrThrow("action"));
                    String content   = c.getString(c.getColumnIndexOrThrow("content"));
                    long   timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
                    result.add(new String[]{pkg, action, content != null ? content : "", String.valueOf(timestamp)});
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "获取日志失败: " + e.getMessage());
        }
        return result;
    }

    public static void clearLogs(Context context) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/log_clear");
            context.getContentResolver().delete(uri, null, null);
        } catch (Throwable e) {
            Log.e(TAG, "清空日志失败: " + e.getMessage());
        }
    }

    /**
     * 删除单个应用的权限记录
     * 从文本文件中移除该包的记录
     */
    public static void deletePermission(Context context, String packageName) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/permission/" + packageName);
            context.getContentResolver().delete(uri, null, null);
            Log.d(TAG, "deletePermission: " + packageName);
        } catch (Throwable e) {
            Log.e(TAG, "deletePermission 失败: " + packageName + " -> " + e.getMessage());
        }
    }
}
