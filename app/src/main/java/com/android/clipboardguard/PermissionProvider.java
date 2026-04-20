package com.android.clipboardguard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限数据 ContentProvider
 * 跨进程共享权限数据（permission表）和弹窗结果（pending表）及日志（clipboard_log表）
 */
public class PermissionProvider extends ContentProvider {

    private static final String TAG = "ClipboardGuard.Provider";
    public static final  String AUTHORITY = "com.android.clipboardguard.provider";

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
    public static final String CALL_METHOD_GET_PENDING = "getPending";
    public static final String CALL_KEY_PACKAGE       = "pkg";
    public static final String CALL_KEY_PERMISSION    = "perm";
    public static final String CALL_KEY_RESULT        = "result";
    public static final String CALL_KEY_DECISION      = "decision";

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

    // ──────────────────────────── 数据库 ────────────────────────────

    private static class DbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME    = "clipboardguard.db";
        private static final int    DB_VERSION = 1;

        DbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS permission (" +
                    "package_name TEXT PRIMARY KEY, permission INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE IF NOT EXISTS pending (" +
                    "package_name TEXT PRIMARY KEY, decision INTEGER NOT NULL, " +
                    "remember INTEGER NOT NULL DEFAULT 0, timestamp INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS clipboard_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT NOT NULL, " +
                    "action TEXT NOT NULL, content TEXT, timestamp INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 留空：未来迁移在此处理
        }
    }

    private DbHelper mDbHelper;

    // ──────────────────────────── ContentProvider 生命周期 ────────────────────────────

    @Override
    public boolean onCreate() {
        mDbHelper = new DbHelper(getContext());
        return true;
    }

    // ──────────────────────────── query ────────────────────────────

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        int match = sUriMatcher.match(uri);

        if (match == URI_PERMISSION_PKG) {
            String pkg = uri.getLastPathSegment();
            if (pkg == null) return null;
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            try (Cursor c = db.query("permission", null,
                    "package_name = ?", new String[]{pkg}, null, null, null)) {
                MatrixCursor result = new MatrixCursor(new String[]{COL_PACKAGE, COL_PERMISSION});
                if (c != null && c.moveToFirst()) {
                    result.addRow(new Object[]{pkg, c.getInt(c.getColumnIndexOrThrow(COL_PERMISSION))});
                } else {
                    result.addRow(new Object[]{pkg, PermissionStorage.PERMISSION_ASK});
                }
                return result;
            }
        }

        if (match == URI_PENDING_PKG) {
            String pkg = uri.getLastPathSegment();
            if (pkg == null) return null;
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            // 返回原始 Cursor，调用方负责关闭
            Cursor c = db.query("pending", null,
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

        if (match == URI_QUERY_ALL) {
            return mDbHelper.getReadableDatabase()
                    .query("permission", null, null, null, null, null, null);
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
                ContentValues cv = new ContentValues();
                cv.put("package_name", pkg);
                cv.put("permission", perm);
                mDbHelper.getWritableDatabase()
                        .insertWithOnConflict("permission", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
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
            // 直接删除数据库记录，不通过 PermissionStorage（避免循环调用）
            ContentValues cv = new ContentValues();
            cv.put("package_name", pkg);
            cv.put("permission", PermissionStorage.PERMISSION_ASK);
            mDbHelper.getWritableDatabase()
                    .insertWithOnConflict("permission", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            PermissionStorage.invalidateCache(pkg);
            return 1;
        }

        if (match == URI_DELETE_ALL) {
            int rows = mDbHelper.getWritableDatabase().delete("permission", null, null);
            PermissionStorage.invalidateAllCache();
            return rows;
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
        // permission 表统一用 CONFLICT_REPLACE 的 insert 处理
        insert(uri, values);
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/permission";
    }

    // ──────────────────────────── call() ────────────────────────────

    /**
     * call() 方式读写：绕过 Binder 包名/uid 校验。
     * system_server 通过 clearCallingIdentity() 后以 uid=1000 调用，可正常通过。
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) extras = new Bundle();
        String pkg = extras.getString(CALL_KEY_PACKAGE);
        if (pkg == null) return null;

        long identity = Binder.clearCallingIdentity();
        try {
            if (CALL_METHOD_GET.equals(method)) {
                Bundle result = new Bundle();
                try (Cursor c = mDbHelper.getReadableDatabase().query(
                        "permission", null,
                        "package_name = ?", new String[]{pkg},
                        null, null, null)) {
                    result.putInt(CALL_KEY_RESULT,
                            (c != null && c.moveToFirst())
                                    ? c.getInt(c.getColumnIndexOrThrow("permission"))
                                    : -1);
                }
                return result;

            } else if (CALL_METHOD_GET_PENDING.equals(method)) {
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

            } else if (CALL_METHOD_SET.equals(method)) {
                int permission = extras.getInt(CALL_KEY_PERMISSION, 0);
                ContentValues cv = new ContentValues();
                cv.put("package_name", pkg);
                cv.put("permission", permission);
                mDbHelper.getWritableDatabase()
                        .insertWithOnConflict("permission", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                Log.d(TAG, "call()写入: " + pkg + " -> " + permission);
                return new Bundle();
            }
        } catch (Throwable e) {
            Log.e(TAG, "call()处理失败: " + e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    // ──────────────────────────── 静态工具方法 ────────────────────────────

    public static int queryPermission(Context context, String packageName) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString(CALL_KEY_PACKAGE, packageName);
            Bundle result = context.getContentResolver().call(uri, CALL_METHOD_GET, null, args);
            if (result != null && result.containsKey(CALL_KEY_RESULT)) {
                return result.getInt(CALL_KEY_RESULT, -1);
            }
        } catch (Throwable e) {
            Log.e(TAG, "查询权限失败: " + e.getMessage());
        }
        return -1;
    }

    public static void savePermission(Context context, String packageName, int permission) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString(CALL_KEY_PACKAGE, packageName);
            args.putInt(CALL_KEY_PERMISSION, permission);
            context.getContentResolver().call(uri, CALL_METHOD_SET, null, args);
        } catch (Throwable e) {
            Log.e(TAG, "保存权限失败: " + e.getMessage());
            // 降级 insert
            try {
                Uri uri = Uri.parse("content://" + AUTHORITY + "/permission");
                ContentValues cv = new ContentValues();
                cv.put(COL_PACKAGE, packageName);
                cv.put(COL_PERMISSION, permission);
                context.getContentResolver().insert(uri, cv);
            } catch (Throwable e2) {
                Log.e(TAG, "降级保存权限也失败: " + e2.getMessage());
            }
        }
    }

    public static List<String[]> getAllPermissions(Context context) {
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
            Log.e(TAG, "获取所有权限失败: " + e.getMessage());
        }
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
}
