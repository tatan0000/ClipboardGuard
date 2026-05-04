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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限数据 ContentProvider
 *
 * 存储层：纯文本文件（每行一个包名，只存需要拦截的包）
 * - write_blocklist.txt：写入拦截列表
 * - read_blocklist.txt：读取拦截列表
 *
 * 跨进程通道：ContentProvider call()
 * Hook 侧缓存：PermissionCache（启动时全量加载，变更时广播刷新）
 *
 * 日志方案：日志只输出到 XLog（LSPosed Manager），不保存到数据库。
 */
public class PermissionProvider extends ContentProvider {

    private static final String TAG = "ClipboardGuard.Provider";
    public static final String AUTHORITY = "com.android.clipboardguard.provider";

    public static final String ACTION_PERMISSION_CHANGED =
            "com.android.clipboardguard.PERMISSION_CHANGED";

    private static final int URI_PERMISSION_PKG = 1;
    private static final int URI_PERMISSION_ALL = 2;
    private static final int URI_PENDING_PKG    = 3;
    private static final int URI_QUERY_ALL      = 4;
    private static final int URI_DELETE_ALL     = 5;

    public static final String COL_PACKAGE    = "package_name";
    public static final String COL_PERMISSION = "permission";
    public static final String COL_DECISION   = "decision";
    public static final String COL_REMEMBER   = "remember";

    public static final String CALL_METHOD_GET         = "getPermission";
    public static final String CALL_METHOD_SET         = "setPermission";
    public static final String CALL_METHOD_GET_ALL     = "getAllPermissions";
    public static final String CALL_METHOD_SET_ALL     = "setAllPermissions";
    public static final String CALL_METHOD_GET_PENDING = "getPending";
    public static final String CALL_KEY_PACKAGE        = "pkg";
    public static final String CALL_KEY_PERMISSION     = "perm";
    public static final String CALL_KEY_RESULT         = "result";
    public static final String CALL_KEY_DECISION       = "decision";
    public static final String CALL_KEY_ALL_DATA       = "all_data";

    private static final String WRITE_BLOCKLIST_FILE = "/data/data/com.android.clipboardguard/files/write_blocklist.txt";
    private static final String READ_BLOCKLIST_FILE  = "/data/data/com.android.clipboardguard/files/read_blocklist.txt";

    private static final String PACKAGE_NAME = "com.android.clipboardguard";

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(AUTHORITY, "permission/*",     URI_PERMISSION_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission",       URI_PERMISSION_ALL);
        sUriMatcher.addURI(AUTHORITY, "pending/*",        URI_PENDING_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission_all",   URI_QUERY_ALL);
        sUriMatcher.addURI(AUTHORITY, "permission_reset", URI_DELETE_ALL);
    }

    private static class LogDbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME    = "clipboardguard.db";
        private static final int    DB_VERSION = 3;

        LogDbHelper(Context context) {
            super(context, "/data/data/" + PACKAGE_NAME + "/databases/" + DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pending (" +
                    "package_name TEXT PRIMARY KEY, decision INTEGER NOT NULL, " +
                    "remember INTEGER NOT NULL DEFAULT 0, timestamp INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 3) {
                // v3: 移除 clipboard_log 表，只保留 pending
                db.execSQL("DROP TABLE IF EXISTS clipboard_log");
                db.execSQL("DROP TABLE IF EXISTS permission");
            }
            onCreate(db);
        }
    }

    private LogDbHelper mDbHelper;

    @Override
    public boolean onCreate() {
        mDbHelper = new LogDbHelper(getContext());
        XLog.i(TAG, "PermissionProvider 初始化完成");
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // call()
    // ═══════════════════════════════════════════════════════════════

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
                    result.putInt(CALL_KEY_RESULT, -1);
                    return result;
                }

                case CALL_METHOD_SET: {
                    return new Bundle();
                }

                case CALL_METHOD_SET_ALL: {
                    String[] allData = extras.getStringArray(CALL_KEY_ALL_DATA);
                    String blocklistType = extras.getString("type", "write");
                    if (allData != null) {
                        Map<String, Integer> perms = new HashMap<>();
                        for (int i = 0; i < allData.length - 1; i += 2) {
                            perms.put(allData[i], Integer.parseInt(allData[i + 1]));
                        }
                        if ("read".equals(blocklistType)) {
                            savePermissionsToFile(READ_BLOCKLIST_FILE, perms);
                        } else {
                            savePermissionsToFile(WRITE_BLOCKLIST_FILE, perms);
                        }
                        XLog.i(TAG, "批量保存 " + blocklistType + " 完成: " + perms.size() + " 条");
                    }
                    return new Bundle();
                }

                case CALL_METHOD_GET_ALL: {
                    String blocklistType = extras.getString("type", "write");
                    String filePath = "read".equals(blocklistType) ? READ_BLOCKLIST_FILE : WRITE_BLOCKLIST_FILE;
                    List<String> blocklist = loadBlocklistFromFile(filePath);
                    String[] flat = new String[blocklist.size() * 2];
                    int i = 0;
                    for (String pkg : blocklist) {
                        flat[i++] = pkg;
                        flat[i++] = String.valueOf(PermissionStorage.PERMISSION_BLOCK);
                    }
                    Bundle result = new Bundle();
                    result.putStringArray(CALL_KEY_ALL_DATA, flat);
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
            }
        } catch (Throwable e) {
            XLog.e(TAG, "call()失败: " + e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // query / insert / delete / update
    // ═══════════════════════════════════════════════════════════════

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        int match = sUriMatcher.match(uri);

        if (match == URI_PERMISSION_PKG || match == URI_QUERY_ALL) {
            return null;
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

        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values == null) return null;
        int match = sUriMatcher.match(uri);

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
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = sUriMatcher.match(uri);
        String pkg = uri.getLastPathSegment();

        if (match == URI_DELETE_ALL) {
            clearBlocklistFile(WRITE_BLOCKLIST_FILE);
            clearBlocklistFile(READ_BLOCKLIST_FILE);
            return 1;
        }

        if (match == URI_PENDING_PKG && pkg != null) {
            return mDbHelper.getWritableDatabase()
                    .delete("pending", "package_name = ?", new String[]{pkg});
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        insert(uri, values);
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/permission";
    }

    // ═══════════════════════════════════════════════════════════════
    // 文件读写：只存包名，每行一个
    // ═══════════════════════════════════════════════════════════════

    private static List<String> loadBlocklistFromFile(String filePath) {
        List<String> result = new ArrayList<>();
        try {
            File file = new File(filePath);
            if (!file.exists()) return result;

            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                result.add(line);
            }
            reader.close();
        } catch (IOException e) {
            XLog.e(TAG, "loadBlocklistFromFile 失败: " + filePath + " -> " + e.getMessage());
        }
        return result;
    }

    private static void savePermissionsToFile(String filePath, Map<String, Integer> permissions) {
        try {
            File file = new File(filePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            BufferedWriter writer = new BufferedWriter(new FileWriter(file));
            writer.write("# ClipboardGuard Blocklist");
            writer.newLine();
            writer.write("# 每行一个包名，表示需要拦截的应用");
            writer.newLine();
            for (Map.Entry<String, Integer> entry : permissions.entrySet()) {
                if (entry.getValue() == PermissionStorage.PERMISSION_BLOCK) {
                    writer.write(entry.getKey());
                    writer.newLine();
                }
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            XLog.e(TAG, "savePermissionsToFile 失败: " + filePath + " -> " + e.getMessage());
        }
    }

    private static void clearBlocklistFile(String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                new FileWriter(file).close();
            }
        } catch (IOException e) {
            XLog.e(TAG, "clearBlocklistFile 失败: " + filePath + " -> " + e.getMessage());
        }
    }

    public static void ensureBlocklistFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                writer.write("# ClipboardGuard Blocklist");
                writer.newLine();
                writer.write("# 每行一个包名，表示需要拦截的应用");
                writer.newLine();
                writer.flush();
                writer.close();
            } catch (IOException e) {
                XLog.e(TAG, "ensureBlocklistFile 失败: " + filePath + " -> " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 静态工具方法（App 端调用）
    // ═══════════════════════════════════════════════════════════════

    public static void savePermission(Context context, String packageName, int permission) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString(CALL_KEY_PACKAGE, packageName);
            args.putInt(CALL_KEY_PERMISSION, permission);
            context.getContentResolver().call(uri, CALL_METHOD_SET, null, args);
            sendBlocklistBroadcast(context);
        } catch (Throwable e) {
            XLog.e(TAG, "savePermission 失败: " + e.getMessage());
        }
    }

    public static void saveAllWritePermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString("type", "write");
            String[] flat = flattenPermissions(permissions);
            args.putStringArray(CALL_KEY_ALL_DATA, flat);
            context.getContentResolver().call(uri, CALL_METHOD_SET_ALL, null, args);
        } catch (Throwable e) {
            XLog.e(TAG, "saveAllWritePermissions 失败: " + e.getMessage());
        }
    }

    public static void saveAllReadPermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString("type", "read");
            String[] flat = flattenPermissions(permissions);
            args.putStringArray(CALL_KEY_ALL_DATA, flat);
            context.getContentResolver().call(uri, CALL_METHOD_SET_ALL, null, args);
        } catch (Throwable e) {
            XLog.e(TAG, "saveAllReadPermissions 失败: " + e.getMessage());
        }
    }

    private static String[] flattenPermissions(Map<String, Integer> permissions) {
        String[] flat = new String[permissions.size() * 2];
        int i = 0;
        for (Map.Entry<String, Integer> e : permissions.entrySet()) {
            flat[i++] = e.getKey();
            flat[i++] = String.valueOf(e.getValue());
        }
        return flat;
    }

    public static void clearAllPermissions(Context context) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/permission_reset");
            context.getContentResolver().delete(uri, null, null);
        } catch (Throwable e) {
            XLog.e(TAG, "clearAllPermissions 失败: " + e.getMessage());
        }
    }

    /** @deprecated 使用 sendBlocklistBroadcast 或 sendFullConfigBroadcast 代替 */
    @Deprecated
    public static void sendPermissionChangedBroadcastStatic(Context context) {
        sendBlocklistBroadcast(context);
    }

    // ═══════════════════════════════════ 新增：只广播 blocklist ═══════════════════════════════

    /**
     * 仅发送写入/读取的 blocklist 广播，不携带规则。
     * 用于包名拦截变更后通知 Hook 侧。
     */
    public static void sendBlocklistBroadcast(Context context) {
        try {
            Intent intent = new Intent(ACTION_PERMISSION_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

            // ★ 重要：无论列表是否为空，都必须携带 key
            // 这样 Hook 侧才能区分"没有数据"和"数据被清空"两种情况
            List<String> writeBlocklist = loadBlocklistFromFile(WRITE_BLOCKLIST_FILE);
            intent.putStringArrayListExtra("write_blocklist", new ArrayList<>(writeBlocklist));

            List<String> readBlocklist = loadBlocklistFromFile(READ_BLOCKLIST_FILE);
            intent.putStringArrayListExtra("read_blocklist", new ArrayList<>(readBlocklist));

            context.sendBroadcast(intent);
            XLog.d(TAG, "已发送 blocklist 广播，写入=" + writeBlocklist.size() + " 读取=" + readBlocklist.size());
        } catch (Throwable e) {
            XLog.w(TAG, "发送 blocklist 广播失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════

    public static void sendFullConfigBroadcast(Context context) {
        try {
            Intent intent = new Intent(ACTION_PERMISSION_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

            // ★ 写入 blocklist：无论是否为空都携带 key
            List<String> writeBlocklist = loadBlocklistFromFile(WRITE_BLOCKLIST_FILE);
            intent.putStringArrayListExtra("write_blocklist", new ArrayList<>(writeBlocklist));

            // ★ 读取 blocklist：无论是否为空都携带 key
            List<String> readBlocklist = loadBlocklistFromFile(READ_BLOCKLIST_FILE);
            intent.putStringArrayListExtra("read_blocklist", new ArrayList<>(readBlocklist));

            // 写入规则 JSON（合并默认规则中启用的规则）
            String writeRulesJson = buildMergedRulesJson(context, "write_rules.json", "write_default_rules.json");
            if (writeRulesJson != null && !writeRulesJson.isEmpty()) {
                intent.putExtra("write_rules_json", writeRulesJson);
            }

            // 读取规则 JSON（合并默认规则中启用的规则）
            String readRulesJson = buildMergedRulesJson(context, "read_rules.json", "read_default_rules.json");
            if (readRulesJson != null && !readRulesJson.isEmpty()) {
                intent.putExtra("read_rules_json", readRulesJson);
            }

            context.sendBroadcast(intent);
            XLog.d(TAG, "已发送完整配置广播");
        } catch (Throwable e) {
            XLog.w(TAG, "发送完整配置广播失败: " + e.getMessage());
        }
    }

    /**
     * 合并自定义规则和默认规则（只包含启用的默认规则）
     * Hook 侧收到广播后直接使用，规则数 = 自定义规则 + 启用的默认规则
     */
    private static String buildMergedRulesJson(Context context, String rulesFileName, String defaultRulesFileName) {
        try {
            JSONObject mergedRoot = new JSONObject();
            JSONArray mergedArr = new JSONArray();

            // 读取自定义规则文件，获取 enabled 开关状态
            File rulesFile = new File(context.getFilesDir(), rulesFileName);
            if (rulesFile.exists()) {
                String content = readFileContent(rulesFile);
                if (content != null && !content.isEmpty()) {
                    try {
                        JSONObject root = new JSONObject(content);
                        mergedRoot.put("enabled", root.optBoolean("enabled", false));
                        JSONArray arr = root.optJSONArray("content_rules");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                mergedArr.put(arr.getJSONObject(i));
                            }
                        }
                    } catch (Exception e) {
                        mergedRoot.put("enabled", false);
                    }
                }
            } else {
                mergedRoot.put("enabled", false);
            }

            // 读取默认规则文件，只添加启用的默认规则
            File defaultFile = new File(context.getFilesDir(), defaultRulesFileName);
            if (defaultFile.exists()) {
                String content = readFileContent(defaultFile);
                if (content != null && !content.isEmpty()) {
                    try {
                        JSONArray arr = new JSONArray(content);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject rule = arr.getJSONObject(i);
                            if (rule.optBoolean("enabled", false)) {
                                mergedArr.put(rule);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            mergedRoot.put("content_rules", mergedArr);
            return mergedRoot.toString();
        } catch (Exception e) {
            XLog.e(TAG, "buildMergedRulesJson failed: " + rulesFileName, e);
            return null;
        }
    }

    @Deprecated
    private static void sendPermissionChangedBroadcast(Context context) {
        sendBlocklistBroadcast(context);
    }

    private static String readFileContent(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从文件加载写入权限列表
     */
    public static List<String[]> getAllWritePermissions(Context context) {
        return loadBlocklistAsPermissionList(WRITE_BLOCKLIST_FILE);
    }

    /**
     * 从文件加载读取权限列表
     */
    public static List<String[]> getAllReadPermissions(Context context) {
        return loadBlocklistAsPermissionList(READ_BLOCKLIST_FILE);
    }

    private static List<String[]> loadBlocklistAsPermissionList(String filePath) {
        List<String[]> result = new ArrayList<>();
        List<String> blocklist = loadBlocklistFromFile(filePath);
        for (String pkg : blocklist) {
            result.add(new String[]{pkg, String.valueOf(PermissionStorage.PERMISSION_BLOCK)});
        }
        return result;
    }

    public static Map<String, Integer> getAllWritePermissionsDirect(Context context) {
        return loadBlocklistAsPermissionMap(WRITE_BLOCKLIST_FILE);
    }

    public static Map<String, Integer> getAllReadPermissionsDirect(Context context) {
        return loadBlocklistAsPermissionMap(READ_BLOCKLIST_FILE);
    }

    private static Map<String, Integer> loadBlocklistAsPermissionMap(String filePath) {
        Map<String, Integer> result = new HashMap<>();
        List<String> blocklist = loadBlocklistFromFile(filePath);
        for (String pkg : blocklist) {
            result.put(pkg, PermissionStorage.PERMISSION_BLOCK);
        }
        return result;
    }

    public static void saveWritePermission(Context context, String packageName, int permission) {
        Map<String, Integer> current = getAllWritePermissionsDirect(context);
        if (permission == PermissionStorage.PERMISSION_BLOCK) {
            current.put(packageName, PermissionStorage.PERMISSION_BLOCK);
        } else {
            current.remove(packageName);
        }
        saveAllWritePermissions(context, current);
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
            XLog.e(TAG, "写入pending结果失败: " + e.getMessage());
        }
    }
}
