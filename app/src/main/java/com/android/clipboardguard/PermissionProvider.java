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

/**
 * 权限数据 ContentProvider
 * 跨进程共享权限数据（permission表）和弹窗结果（pending表）
 */
public class PermissionProvider extends ContentProvider {

    private static final String TAG = "ClipboardGuard.Provider";
    public static final String AUTHORITY = "com.android.clipboardguard.provider";

    private static final int URI_PERMISSION_PKG = 1;
    private static final int URI_PERMISSION_ALL = 2;
    private static final int URI_PENDING_PKG   = 3;
    private static final int URI_QUERY_ALL     = 4;
    private static final int URI_DELETE_ALL    = 5;

    public static final String COL_PACKAGE = "package_name";
    public static final String COL_PERMISSION = "permission";
    public static final String COL_DECISION   = "decision";
    public static final String COL_REMEMBER   = "remember";

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(AUTHORITY, "permission/*", URI_PERMISSION_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission", URI_PERMISSION_ALL);
        sUriMatcher.addURI(AUTHORITY, "pending/*", URI_PENDING_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission_all", URI_QUERY_ALL);
        sUriMatcher.addURI(AUTHORITY, "permission_reset", URI_DELETE_ALL);
    }

    private static class DbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "clipboardguard.db";
        private static final int DB_VERSION = 1;

        DbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS permission (package_name TEXT PRIMARY KEY, permission INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE IF NOT EXISTS pending (package_name TEXT PRIMARY KEY, decision INTEGER NOT NULL, remember INTEGER NOT NULL DEFAULT 0, timestamp INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }

    private DbHelper mDbHelper;

    // ======== 静态方法 ========

    // call() 方法用到的 key
    public static final String CALL_METHOD_GET    = "getPermission";
    public static final String CALL_METHOD_SET    = "setPermission";
    public static final String CALL_METHOD_GET_PENDING = "getPending";
    public static final String CALL_KEY_PACKAGE   = "pkg";
    public static final String CALL_KEY_PERMISSION = "perm";
    public static final String CALL_KEY_RESULT    = "result";
    public static final String CALL_KEY_DECISION   = "decision";

    public static int queryPermission(Context context, String packageName) {
        // 优先用 call() 方式（不经过 Binder 包名校验）
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
        // 优先用 call() 方式
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY);
            Bundle args = new Bundle();
            args.putString(CALL_KEY_PACKAGE, packageName);
            args.putInt(CALL_KEY_PERMISSION, permission);
            context.getContentResolver().call(uri, CALL_METHOD_SET, null, args);
            return;
        } catch (Throwable e) {
            Log.e(TAG, "call()保存失败，降级insert: " + e.getMessage());
        }
        // 降级：insert
        Uri uri = Uri.parse("content://" + AUTHORITY + "/permission");
        ContentValues values = new ContentValues();
        values.put(COL_PACKAGE, packageName);
        values.put(COL_PERMISSION, permission);
        try {
            context.getContentResolver().insert(uri, values);
        } catch (Throwable e) {
            Log.e(TAG, "保存权限失败: " + e.getMessage());
        }
    }

    public static java.util.List<String[]> getAllPermissions(Context context) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        Uri uri = Uri.parse("content://" + AUTHORITY + "/permission_all");
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String pkg = cursor.getString(cursor.getColumnIndexOrThrow(COL_PACKAGE));
                    int perm = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PERMISSION));
                    result.add(new String[]{pkg, String.valueOf(perm)});
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "获取所有权限失败: " + e.getMessage());
        }
        return result;
    }

    public static void writePendingResult(Context context, String packageName, int decision, boolean remember) {
        Uri uri = Uri.parse("content://" + AUTHORITY + "/pending/" + packageName);
        ContentValues values = new ContentValues();
        values.put(COL_PACKAGE, packageName);
        values.put(COL_DECISION, decision);
        values.put(COL_REMEMBER, remember ? 1 : 0);
        values.put("timestamp", System.currentTimeMillis());
        try {
            context.getContentResolver().insert(uri, values);
        } catch (Throwable e) {
            Log.e(TAG, "写入pending结果失败: " + e.getMessage());
        }
    }

    // ======== ContentProvider 实现 ========

    @Override
    public boolean onCreate() {
        mDbHelper = new DbHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        int match = sUriMatcher.match(uri);
        String packageName = uri.getLastPathSegment();

        if (match == URI_PERMISSION_PKG) {
            if (getContext() == null || packageName == null) return null;
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            try (Cursor cursor = db.query("permission", null, "package_name = ?", new String[]{packageName}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int permission = cursor.getInt(cursor.getColumnIndexOrThrow("permission"));
                    MatrixCursor result = new MatrixCursor(new String[]{COL_PACKAGE, COL_PERMISSION});
                    result.addRow(new Object[]{packageName, permission});
                    return result;
                }
            }
            MatrixCursor cursor = new MatrixCursor(new String[]{COL_PACKAGE, COL_PERMISSION});
            cursor.addRow(new Object[]{packageName, PermissionStorage.PERMISSION_ASK});
            return cursor;
        }

        if (match == URI_PENDING_PKG) {
            if (packageName == null) return null;
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query("pending", null, "package_name = ?", new String[]{packageName}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int decision = cursor.getInt(cursor.getColumnIndexOrThrow("decision"));
                int remember = cursor.getInt(cursor.getColumnIndexOrThrow("remember"));
                MatrixCursor result = new MatrixCursor(new String[]{COL_PACKAGE, COL_DECISION, COL_REMEMBER});
                result.addRow(new Object[]{packageName, decision, remember});
                cursor.close();
                return result;
            }
            return null;
        }

        if (match == URI_QUERY_ALL) {
            return mDbHelper.getReadableDatabase().query("permission", null, null, null, null, null, null);
        }

        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (getContext() == null || values == null) return null;
        int match = sUriMatcher.match(uri);

        if (match == URI_PERMISSION_ALL) {
            String packageName = values.getAsString(COL_PACKAGE);
            Integer permission = values.getAsInteger(COL_PERMISSION);
            if (packageName != null && permission != null) {
                SQLiteDatabase db = mDbHelper.getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("package_name", packageName);
                cv.put("permission", permission);
                db.insertWithOnConflict("permission", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }

        if (match == URI_PENDING_PKG) {
            String packageName = values.getAsString(COL_PACKAGE);
            Integer decision = values.getAsInteger(COL_DECISION);
            Integer remember = values.getAsInteger(COL_REMEMBER);
            if (packageName != null && decision != null) {
                SQLiteDatabase db = mDbHelper.getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("package_name", packageName);
                cv.put("decision", decision);
                cv.put("remember", remember != null ? remember : 0);
                cv.put("timestamp", System.currentTimeMillis());
                db.insertWithOnConflict("pending", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = sUriMatcher.match(uri);
        String packageName = uri.getLastPathSegment();

        if (match == URI_PERMISSION_PKG && getContext() != null && packageName != null) {
            PermissionStorage.resetPermission(getContext(), packageName);
            return 1;
        }

        if (match == URI_DELETE_ALL && getContext() != null) {
            return mDbHelper.getWritableDatabase().delete("permission", null, null);
        }

        if (match == URI_PENDING_PKG && packageName != null) {
            return mDbHelper.getWritableDatabase().delete("pending", "package_name = ?", new String[]{packageName});
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

    /**
     * call() 方法：支持跨进程无包名校验的读写
     * system_server 调用时 calling package 是 "android"，
     * Android 框架不会对 call() 做包名/uid 绑定校验。
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) extras = new Bundle();
        String packageName = extras.getString(CALL_KEY_PACKAGE);
        if (packageName == null) return null;

        // 用 clearCallingIdentity 避免任何额外的权限检查
        long identity = Binder.clearCallingIdentity();
        try {
            if (CALL_METHOD_GET.equals(method)) {
                SQLiteDatabase db = mDbHelper.getReadableDatabase();
                try (Cursor cursor = db.query("permission", null,
                        "package_name = ?", new String[]{packageName},
                        null, null, null)) {
                    Bundle result = new Bundle();
                    if (cursor != null && cursor.moveToFirst()) {
                        result.putInt(CALL_KEY_RESULT,
                                cursor.getInt(cursor.getColumnIndexOrThrow("permission")));
                    } else {
                        result.putInt(CALL_KEY_RESULT, -1); // 未设置
                    }
                    return result;
                }
            } else if (CALL_METHOD_GET_PENDING.equals(method)) {
                SQLiteDatabase db = mDbHelper.getReadableDatabase();
                try (Cursor cursor = db.query("pending", null,
                        "package_name = ?", new String[]{packageName},
                        null, null, null)) {
                    Bundle result = new Bundle();
                    if (cursor != null && cursor.moveToFirst()) {
                        result.putInt(CALL_KEY_DECISION,
                                cursor.getInt(cursor.getColumnIndexOrThrow("decision")));
                        result.putInt(CALL_KEY_RESULT, 1);
                    } else {
                        result.putInt(CALL_KEY_RESULT, -1); // 没有 pending
                    }
                    return result;
                }
            } else if (CALL_METHOD_SET.equals(method)) {
                int permission = extras.getInt(CALL_KEY_PERMISSION, 0);
                SQLiteDatabase db = mDbHelper.getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("package_name", packageName);
                cv.put("permission", permission);
                db.insertWithOnConflict("permission", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                Log.d(TAG, "call()写入成功: " + packageName + " -> " + permission);
                return new Bundle();
            }
        } catch (Throwable e) {
            Log.e(TAG, "call()处理失败: " + e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }
}