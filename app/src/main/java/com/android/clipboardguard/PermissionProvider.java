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
import android.os.Process;
import androidx.annotation.NonNull;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 权限数据 ContentProvider
 *
 * 存储层：纯文本文件（每行一个包名，只存需要拦截的包）
 * - write_blocklist.txt：写入拦截列表
 * - read_blocklist.txt：读取拦截列表
 *
 * 跨进程通道：广播（App → system_server）
 * Hook 侧缓存：PermissionCache（开机加载 + 广播刷新）
 *
 * 日志方案：日志由 PermissionCache.isLsposedLogEnabled() 控制，
 * 输出到 XLog（LSPosed Manager 模块日志页）。
 */
public class PermissionProvider extends ContentProvider {

    // ──────────────────────────── Provider 常量 ────────────────────────────

    private static final String TAG = "ClipboardGuard.Provider";
    public static final String AUTHORITY = "com.android.clipboardguard.provider";
    public static final String PERMISSION_CONFIG_SYNC =
            "com.android.clipboardguard.permission.CONFIG_SYNC";

    public static final String ACTION_CONFIG_CHANGED =
            "com.android.clipboardguard.CONFIG_CHANGED";

    /** 广播附带此 Extra=true 时，接收端刷新模块状态 JSON。仅 App 打开时携带，规则编辑不携带。 */
    public static final String EXTRA_REQUEST_STATUS_UPDATE = "request_status_update";

    private static final int URI_PERMISSION_PKG = 1;
    private static final int URI_PERMISSION_ALL = 2;
    private static final int URI_PENDING_PKG    = 3;
    private static final int URI_QUERY_ALL      = 4;
    private static final int URI_DELETE_ALL     = 5;

    public static final String COL_PACKAGE    = "package_name";
    public static final String COL_DECISION   = "decision";
    public static final String COL_REMEMBER   = "remember";

    public static final String CALL_METHOD_GET         = "getPermission";
    public static final String CALL_METHOD_SET         = "setPermission";
    public static final String CALL_METHOD_GET_ALL     = "getAllPermissions";
    public static final String CALL_METHOD_SET_ALL     = "setAllPermissions";
    public static final String CALL_METHOD_GET_PENDING = "getPending";
    public static final String CALL_METHOD_GET_FULL_CONFIG = "getFullConfig";
    public static final String CALL_METHOD_GET_BLOCKED_PACKAGES = "getBlockedPackages";
    public static final String CALL_KEY_PACKAGE        = "pkg";
    public static final String CALL_KEY_RESULT         = "result";
    public static final String CALL_KEY_DECISION       = "decision";
    public static final String CALL_KEY_ALL_DATA       = "all_data";
    public static final String CALL_KEY_SCOPE          = "scope";
    public static final String CALL_KEY_BLOCKED_PACKAGES = "blocked_packages";
    public static final String CALL_KEY_WRITE_BLOCKLIST = "write_blocklist";
    public static final String CALL_KEY_READ_BLOCKLIST = "read_blocklist";
    public static final String CALL_KEY_WRITE_RULES_JSON = "write_rules_json";
    public static final String CALL_KEY_READ_RULES_JSON = "read_rules_json";
    public static final String CALL_KEY_WRITE_DEFAULT_RULES_JSON = "write_default_rules_json";
    public static final String CALL_KEY_READ_DEFAULT_RULES_JSON = "read_default_rules_json";
    public static final String CALL_KEY_READ_BLOCKED_TOAST_ENABLED = "read_blocked_toast_enabled";
    public static final String CALL_KEY_LSPOSED_LOG_ENABLED = "lsposed_log_enabled";

    private static final String PACKAGE_NAME = "com.android.clipboardguard";

    // ──────────────────────────── 规则文件读缓存 ────────────────────────────

    private static final Map<String, FileCache> sRulesFileCache = new HashMap<>(4);

    private static class FileCache {
        long lastModified;
        String content;
    }

    // ──────────────────────────── URI 匹配 ────────────────────────────

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(AUTHORITY, "permission/*",     URI_PERMISSION_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission",       URI_PERMISSION_ALL);
        sUriMatcher.addURI(AUTHORITY, "pending/*",        URI_PENDING_PKG);
        sUriMatcher.addURI(AUTHORITY, "permission_all",   URI_QUERY_ALL);
        sUriMatcher.addURI(AUTHORITY, "permission_reset", URI_DELETE_ALL);
    }

    // ──────────────────────────── Pending 结果数据库 ────────────────────────────

    private static class LogDbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME    = "clipboardguard.db";
        private static final int    DB_VERSION = 3;

        LogDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
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

    // ──────────────────────────── Provider 生命周期 ────────────────────────────

    /** ContentProvider 初始化，创建数据库 Helper */
    @Override
    public boolean onCreate() {
        mDbHelper = new LogDbHelper(getContext());
        XLog.i(TAG, "PermissionProvider 初始化完成");
        return true;
    }

    /**
     * 模块激活标志。由 MainActivity Binder IPC 轮询确认后设置。
     * 未激活时所有广播（全量/名单/规则/开关）静默跳过，不向 system_server 发任何东西。
     */
    public static volatile boolean sModuleActive = false;

    /**
     * App 保存配置后通知 system_server：发广播携带完整配置。
     * system_server 收到后写入 /data/system/clipboardguard/ 并刷新内存。
     */
    public static void requestConfigSync(Context context) {
        if (!sModuleActive) return;
        broadcastFullConfigSnapshot(context);
    }

    // ═══════════════════════════════════════════════════════════════
    // call()
    // ═══════════════════════════════════════════════════════════════

    /** 处理跨进程调用，根据 method 路由到不同操作 */
    @Override
    public Bundle call(@NonNull String method, String arg, Bundle extras) {
        if (extras == null) extras = new Bundle();
        int callingUid = Binder.getCallingUid();
        boolean trustedCaller = isTrustedCaller(callingUid);
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
                    if (!trustedCaller) {
                        XLog.w(TAG, "拒绝未授权调用: " + method + " uid=" + callingUid);
                        return new Bundle();
                    }
                    String[] allData = extras.getStringArray(CALL_KEY_ALL_DATA);
                    String blocklistType = extras.getString("type", "write");
                    Context ctx = getContext();
                    if (ctx == null) return new Bundle();
                    // 保存前记录旧黑名单，用于检测被移除的包（联动清理规则适用域）
                    List<String> oldWriteBlocklist = loadBlocklistFromFile(getWriteBlocklistPath(ctx));
                    List<String> oldReadBlocklist = loadBlocklistFromFile(getReadBlocklistPath(ctx));
                    if (allData != null) {
                        Map<String, Integer> perms = new HashMap<>();
                        for (int i = 0; i < allData.length - 1; i += 2) {
                            String pkg = allData[i];
                            String perm = allData[i + 1];
                            if (pkg == null || pkg.isEmpty() || perm == null) continue;
                            try {
                                perms.put(pkg, Integer.parseInt(perm));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if ("read".equals(blocklistType)) {
                            savePermissionsToFile(getReadBlocklistPath(ctx), perms);
                        } else {
                            savePermissionsToFile(getWriteBlocklistPath(ctx), perms);
                        }
                        broadcastBlocklistsOnly(ctx);
                        // 联动清理：仅在对应类型的规则中移除被取消勾选的包
                        cleanupAndSyncRules(ctx, blocklistType, oldWriteBlocklist, oldReadBlocklist);
                        XLog.i(TAG, "批量保存 " + blocklistType + " 完成: " + perms.size() + " 条");
                    }
                    return new Bundle();
                }

                case CALL_METHOD_GET_ALL: {
                    if (!trustedCaller) {
                        XLog.w(TAG, "拒绝未授权调用: " + method + " uid=" + callingUid);
                        return new Bundle();
                    }
                    Context ctx = getContext();
                    if (ctx == null) return new Bundle();
                    String blocklistType = extras.getString("type", "write");
                    String filePath = "read".equals(blocklistType)
                            ? getReadBlocklistPath(ctx)
                            : getWriteBlocklistPath(ctx);
                    List<String> blocklist = loadBlocklistFromFile(filePath);
                    String[] flat = new String[blocklist.size() * 2];
                    int i = 0;
                    for (String pkg : blocklist) {
                        flat[i++] = pkg;
                        flat[i++] = String.valueOf(PermissionDecision.PERMISSION_BLOCK);
                    }
                    Bundle result = new Bundle();
                    result.putStringArray(CALL_KEY_ALL_DATA, flat);
                    return result;
                }

                case CALL_METHOD_GET_PENDING: {
                    if (!trustedCaller) {
                        XLog.w(TAG, "拒绝未授权调用: " + method + " uid=" + callingUid);
                        return new Bundle();
                    }
                    String pkg = extras.getString(CALL_KEY_PACKAGE);
                    if (pkg == null) return null;
                    Bundle result = new Bundle();
                    try (Cursor c = mDbHelper.getReadableDatabase().query(
                            "pending", null,
                            "package_name = ?", new String[]{pkg},
                            null, null, null)) {
                        if (c.moveToFirst()) {
                            result.putInt(CALL_KEY_DECISION, c.getInt(c.getColumnIndexOrThrow("decision")));
                            result.putInt(CALL_KEY_RESULT, 1);
                        } else {
                            result.putInt(CALL_KEY_RESULT, -1);
                        }
                    }
                    return result;
                }

                case CALL_METHOD_GET_FULL_CONFIG: {
                    if (!trustedCaller) {
                        XLog.w(TAG, "拒绝未授权调用: " + method + " uid=" + callingUid);
                        return new Bundle();
                    }
                    Context context = getContext();
                    if (context == null) return new Bundle();
                    ensureConfigFiles(context);
                    Bundle result = new Bundle();
                    result.putStringArrayList(CALL_KEY_WRITE_BLOCKLIST,
                            new ArrayList<>(loadBlocklistFromFile(getWriteBlocklistPath(context))));
                    result.putStringArrayList(CALL_KEY_READ_BLOCKLIST,
                            new ArrayList<>(loadBlocklistFromFile(getReadBlocklistPath(context))));
                    String writeRulesJson = readAppRulesFile(context, "write_rules.json");
                    if (writeRulesJson != null) result.putString(CALL_KEY_WRITE_RULES_JSON, writeRulesJson);
                    String writeDefaultJson = readAppRulesFile(context, "write_default_rules.json");
                    if (writeDefaultJson != null) {
                        result.putString(CALL_KEY_WRITE_DEFAULT_RULES_JSON, writeDefaultJson);
                    }
                    String readRulesJson = readAppRulesFile(context, "read_rules.json");
                    if (readRulesJson != null) result.putString(CALL_KEY_READ_RULES_JSON, readRulesJson);
                    String readDefaultJson = readAppRulesFile(context, "read_default_rules.json");
                    if (readDefaultJson != null) {
                        result.putString(CALL_KEY_READ_DEFAULT_RULES_JSON, readDefaultJson);
                    }
                    result.putBoolean(CALL_KEY_READ_BLOCKED_TOAST_ENABLED, isReadBlockedToastEnabled(context));
                    result.putBoolean(CALL_KEY_LSPOSED_LOG_ENABLED, isLsposedLogEnabled(context));
                    return result;
                }

                case CALL_METHOD_GET_BLOCKED_PACKAGES: {
                    if (!trustedCaller) {
                        XLog.w(TAG, "拒绝未授权调用: " + method + " uid=" + callingUid);
                        return new Bundle();
                    }
                    Context context = getContext();
                    if (context == null) return new Bundle();
                    String scope = extras.getString(CALL_KEY_SCOPE, "write");
                    ArrayList<String> blockedPackages = new ArrayList<>(
                            "read".equals(scope)
                                    ? getBlockedReadPackagesDirect(context)
                                    : getBlockedWritePackagesDirect(context));
                    Bundle result = new Bundle();
                    result.putStringArrayList(CALL_KEY_BLOCKED_PACKAGES, blockedPackages);
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

    /** 查询操作，主要用于获取 pending 决策记录 */
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!isTrustedCaller(Binder.getCallingUid())) {
            XLog.w(TAG, "拒绝未授权 query: " + uri);
            return null;
        }
        int match = sUriMatcher.match(uri);

        if (match == URI_PERMISSION_PKG || match == URI_QUERY_ALL) {
            return null;
        }

        if (match == URI_PENDING_PKG) {
            String pkg = uri.getLastPathSegment();
            if (pkg == null) return null;
            try (Cursor c = mDbHelper.getReadableDatabase().query("pending", null,
                    "package_name = ?", new String[]{pkg}, null, null, null)) {
                if (c.moveToFirst()) {
                    MatrixCursor result = new MatrixCursor(new String[]{COL_PACKAGE, COL_DECISION, COL_REMEMBER});
                    result.addRow(new Object[]{
                            pkg,
                            c.getInt(c.getColumnIndexOrThrow(COL_DECISION)),
                            c.getInt(c.getColumnIndexOrThrow(COL_REMEMBER))
                    });
                    return result;
                }
            }
            return null;
        }

        return null;
    }

    /** 插入操作，用于保存 pending 决策记录 */
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        if (!isTrustedCaller(Binder.getCallingUid())) {
            XLog.w(TAG, "拒绝未授权 insert: " + uri);
            return null;
        }
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

    /** 删除操作，用于清除 pending 记录或重置拦截名单 */
    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        if (!isTrustedCaller(Binder.getCallingUid())) {
            XLog.w(TAG, "拒绝未授权 delete: " + uri);
            return 0;
        }
        int match = sUriMatcher.match(uri);
        String pkg = uri.getLastPathSegment();

        if (match == URI_DELETE_ALL) {
            Context context = getContext();
            if (context == null) return 0;
            clearBlocklistFile(getWriteBlocklistPath(context));
            clearBlocklistFile(getReadBlocklistPath(context));
            return 1;
        }

        if (match == URI_PENDING_PKG && pkg != null) {
            return mDbHelper.getWritableDatabase()
                    .delete("pending", "package_name = ?", new String[]{pkg});
        }
        return 0;
    }

    /** 更新操作，委托给 insert 实现 */
    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (!isTrustedCaller(Binder.getCallingUid())) {
            XLog.w(TAG, "拒绝未授权 update: " + uri);
            return 0;
        }
        insert(uri, values);
        return 1;
    }

    /** 返回 MIME 类型 */
    @Override
    public String getType(@NonNull Uri uri) {
        return "vnd.android.cursor.item/permission";
    }

    // ═══════════════════════════════════════════════════════════════
    // 文件读写：只存包名，每行一个
    // ═══════════════════════════════════════════════════════════════

    /** 从文件加载拦截名单（每行一个包名） */
    private static List<String> loadBlocklistFromFile(String filePath) {
        List<String> result = new ArrayList<>();
        File file = new File(filePath);
        if (!file.exists()) return result;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                result.add(line);
            }
        } catch (IOException e) {
            XLog.e(TAG, "loadBlocklistFromFile 失败: " + filePath + " -> " + e.getMessage());
        }
        return result;
    }

    /** 将权限 Map 保存到文件，只写入 BLOCK 状态的包名 */
    private static void savePermissionsToFile(String filePath, Map<String, Integer> permissions) {
        try {
            File file = new File(filePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                ensureDirectory(parent);
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writeBlocklistHeader(writer);
                for (Map.Entry<String, Integer> entry : permissions.entrySet()) {
                    if (entry.getValue() == PermissionDecision.PERMISSION_BLOCK) {
                        writer.write(entry.getKey());
                        writer.newLine();
                    }
                }
                writer.flush();
            }
        } catch (IOException e) {
            XLog.e(TAG, "savePermissionsToFile 失败: " + filePath + " -> " + e.getMessage());
        }
    }

    /** 清空拦截名单文件内容 */
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

    /** 确保拦截名单文件存在，不存在则创建 */
    public static void ensureBlocklistFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    ensureDirectory(parent);
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                    writeBlocklistHeader(writer);
                    writer.flush();
                }
            } catch (IOException e) {
                XLog.e(TAG, "ensureBlocklistFile 失败: " + filePath + " -> " + e.getMessage());
            }
        }
    }

    /** 写入拦截名单文件头部注释 */
    private static void writeBlocklistHeader(BufferedWriter writer) throws IOException {
        writer.write("# ClipboardGuard Blocklist");
        writer.newLine();
        writer.write("# 每行一个包名，表示需要拦截的应用");
        writer.newLine();
    }

    /** 获取写入拦截名单文件路径 */
    private static String getWriteBlocklistPath(Context context) {
        return getBlocklistPath(context, "write_blocklist.txt");
    }

    /** 获取读取拦截名单文件路径 */
    private static String getReadBlocklistPath(Context context) {
        return getBlocklistPath(context, "read_blocklist.txt");
    }

    private static String getBlocklistPathCompat(Context context, String fileName) {
        if (context != null) {
            return new File(context.getFilesDir(), fileName).getPath();
        }
        return new File(new File(getPackageDataDir(), "files"), fileName).getPath();
    }

    private static String getBlocklistPath(Context context, String fileName) {
        // App 进程（untrusted_app）无法写入 /data/system/clipboardguard/（SELinux EACCES）
        // 始终使用 App 私有目录，system_server 通过广播读取数据
        return getBlocklistPathCompat(context, fileName);
    }

    private static String getPackageDataDir() {
        String androidData = System.getenv("ANDROID_DATA");
        File dataRoot = new File(androidData != null ? androidData : File.separator + "data");
        return new File(new File(dataRoot, "user/0"), PACKAGE_NAME).getPath();
    }

    /** 确保所有配置文件存在 */
    private static void ensureConfigFiles(Context context) {
        if (context == null) return;
        ensureBlocklistFile(getWriteBlocklistPath(context));
        ensureBlocklistFile(getReadBlocklistPath(context));
        ensureJsonFile(context, "write_rules.json");
        ensureJsonFile(context, "read_rules.json");
        ensureJsonFile(context, "write_default_rules.json");
        ensureJsonFile(context, "read_default_rules.json");
    }

    /** 确保 JSON 规则文件存在，不存在则创建空规则文件 */
    private static void ensureJsonFile(Context context, String fileName) {
        File file = new File(context.getFilesDir(), fileName);
        if (file.exists()) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                ensureDirectory(parent);
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write("{\"enabled\":false,\"content_rules\":[]}");
                writer.flush();
            }
        } catch (IOException e) {
            XLog.e(TAG, "ensureJsonFile 失败: " + fileName + " -> " + e.getMessage());
        }
    }

    // ═══════════════════════════════════ App 端保存接口 ═══════════════════════════════

    /** App 端保存写入拦截名单 */
    public static void saveAllWritePermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null) return;
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

    /** App 端保存读取拦截名单 */
    public static void saveAllReadPermissions(Context context, Map<String, Integer> permissions) {
        if (permissions == null) return;
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

    /** 将权限 Map 扁平化为字符串数组（key/value 交替） */
    private static String[] flattenPermissions(Map<String, Integer> permissions) {
        String[] flat = new String[permissions.size() * 2];
        int i = 0;
        for (Map.Entry<String, Integer> e : permissions.entrySet()) {
            flat[i++] = e.getKey();
            flat[i++] = String.valueOf(e.getValue());
        }
        return flat;
    }

    // ═══════════════════════════════════ 配置广播 ═══════════════════════════════

    /** 同步读取拦截 Toast 开关设置 */
    public static void requestReadToastSettingSync(Context context) {
        broadcastFlagsOnly(context);
    }

    /** 同步 LSPosed 日志开关设置 */
    public static void requestLsposedLogSettingSync(Context context) {
        broadcastFlagsOnly(context);
    }

    /**
     * 创建配置变更广播 Intent。
     *
     * 注意：接收端在 system_server (package="android")，不能调用
     * setPackage("com.android.clipboardguard")，否则广播永远不会送达。
     * 安全由接收端注册时声明的 PERMISSION_CONFIG_SYNC 签名权限保证。
     */
    /** 创建配置变更广播 Intent */
    private static Intent createConfigChangedIntent() {
        Intent intent = new Intent(ACTION_CONFIG_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        return intent;
    }

    /** 写入文本文件 */
    private static void writeTextFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
            writer.flush();
        }
    }

    /** 读取文本文件内容 */
    private static String readTextFile(File file) {
        if (file == null || !file.exists()) return null;
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

    public static void broadcastFullConfigSnapshot(Context context) {
        if (context == null || !sModuleActive) return;
        try {
            Intent intent = createConfigChangedIntent();
            // 标记为 App 打开触发的全量同步，接收端需刷新模块状态 JSON
            intent.putExtra(EXTRA_REQUEST_STATUS_UPDATE, true);

            List<String> writeBlocklist = loadBlocklistFromFile(getWriteBlocklistPath(context));
            intent.putStringArrayListExtra(CALL_KEY_WRITE_BLOCKLIST, new ArrayList<>(writeBlocklist));

            List<String> readBlocklist = loadBlocklistFromFile(getReadBlocklistPath(context));
            intent.putStringArrayListExtra(CALL_KEY_READ_BLOCKLIST, new ArrayList<>(readBlocklist));

            // 始终发送规则，即使为空，确保 system_server 能清掉旧规则
            String emptyRules = "{\"enabled\":false,\"content_rules\":[]}";
            String writeRulesJson = readAppRulesFile(context, "write_rules.json");
            intent.putExtra(CALL_KEY_WRITE_RULES_JSON,
                    (writeRulesJson != null && !writeRulesJson.isEmpty()) ? writeRulesJson : emptyRules);

            String writeDefaultJson = readAppRulesFile(context, "write_default_rules.json");
            intent.putExtra(CALL_KEY_WRITE_DEFAULT_RULES_JSON,
                    (writeDefaultJson != null && !writeDefaultJson.isEmpty()) ? writeDefaultJson : emptyRules);

            String readRulesJson = readAppRulesFile(context, "read_rules.json");
            intent.putExtra(CALL_KEY_READ_RULES_JSON,
                    (readRulesJson != null && !readRulesJson.isEmpty()) ? readRulesJson : emptyRules);

            String readDefaultJson = readAppRulesFile(context, "read_default_rules.json");
            intent.putExtra(CALL_KEY_READ_DEFAULT_RULES_JSON,
                    (readDefaultJson != null && !readDefaultJson.isEmpty()) ? readDefaultJson : emptyRules);

            intent.putExtra(CALL_KEY_READ_BLOCKED_TOAST_ENABLED, isReadBlockedToastEnabled(context));
            intent.putExtra(CALL_KEY_LSPOSED_LOG_ENABLED, isLsposedLogEnabled(context));

            context.sendBroadcast(intent, PERMISSION_CONFIG_SYNC);
            XLog.d(TAG, "已发送全量配置变更广播 → system_server 落盘");
        } catch (Throwable e) {
            XLog.w(TAG, "发送配置广播失败: " + e.getMessage());
        }
    }

    /** 仅同步写入/读取拦截名单，不发规则和开关状态。 */
    public static void broadcastBlocklistsOnly(Context context) {
        if (context == null || !sModuleActive) return;
        try {
            Intent intent = createConfigChangedIntent();

            List<String> writeBlocklist = loadBlocklistFromFile(getWriteBlocklistPath(context));
            intent.putStringArrayListExtra(CALL_KEY_WRITE_BLOCKLIST, new ArrayList<>(writeBlocklist));

            List<String> readBlocklist = loadBlocklistFromFile(getReadBlocklistPath(context));
            intent.putStringArrayListExtra(CALL_KEY_READ_BLOCKLIST, new ArrayList<>(readBlocklist));

            context.sendBroadcast(intent, PERMISSION_CONFIG_SYNC);
            XLog.d(TAG, "已发送拦截名单广播: 写入" + writeBlocklist.size()
                    + "条, 读取" + readBlocklist.size() + "条");
        } catch (Throwable e) {
            XLog.w(TAG, "发送拦截名单广播失败: " + e.getMessage());
        }
    }

    /** 按类型同步规则：写入只发写入，读取只发读取。不发拦截名单和开关状态。 */
    public static void broadcastRulesOnly(Context context, String type) {
        if (context == null || !sModuleActive) return;
        try {
            Intent intent = createConfigChangedIntent();
            String emptyRules = "{\"enabled\":false,\"content_rules\":[]}";

            if ("write".equals(type)) {
                String writeRulesJson = readAppRulesFile(context, "write_rules.json");
                intent.putExtra(CALL_KEY_WRITE_RULES_JSON,
                        (writeRulesJson != null && !writeRulesJson.isEmpty()) ? writeRulesJson : emptyRules);
                String writeDefaultJson = readAppRulesFile(context, "write_default_rules.json");
                intent.putExtra(CALL_KEY_WRITE_DEFAULT_RULES_JSON,
                        (writeDefaultJson != null && !writeDefaultJson.isEmpty()) ? writeDefaultJson : emptyRules);
            } else {
                String readRulesJson = readAppRulesFile(context, "read_rules.json");
                intent.putExtra(CALL_KEY_READ_RULES_JSON,
                        (readRulesJson != null && !readRulesJson.isEmpty()) ? readRulesJson : emptyRules);
                String readDefaultJson = readAppRulesFile(context, "read_default_rules.json");
                intent.putExtra(CALL_KEY_READ_DEFAULT_RULES_JSON,
                        (readDefaultJson != null && !readDefaultJson.isEmpty()) ? readDefaultJson : emptyRules);
            }

            context.sendBroadcast(intent, PERMISSION_CONFIG_SYNC);
            XLog.d(TAG, "已发送规则变更广播 (type=" + type + ")");
        } catch (Throwable e) {
            XLog.w(TAG, "发送规则广播失败: " + e.getMessage());
        }
    }

    /** 仅同步设置页两个开关状态，不发名单和规则。 */
    public static void broadcastFlagsOnly(Context context) {
        if (context == null || !sModuleActive) return;
        try {
            Intent intent = createConfigChangedIntent();

            intent.putExtra(CALL_KEY_READ_BLOCKED_TOAST_ENABLED, isReadBlockedToastEnabled(context));
            intent.putExtra(CALL_KEY_LSPOSED_LOG_ENABLED, isLsposedLogEnabled(context));

            context.sendBroadcast(intent, PERMISSION_CONFIG_SYNC);
            XLog.d(TAG, "已发送开关状态广播: toast=" + isReadBlockedToastEnabled(context)
                    + ", log=" + isLsposedLogEnabled(context));
        } catch (Throwable e) {
            XLog.w(TAG, "发送开关广播失败: " + e.getMessage());
        }
    }

    // ────────────────────────── 联动清理规则适用域（App 侧） ──────────────────────────

    /**
     * 黑名单取消勾选后，对比新旧黑名单，联动清理规则适用域。
     * 由 CALL_METHOD_SET_ALL 在保存黑名单后调用（主页面保存触发，规则编辑页不可能同时打开）。
     *
     * 数据流：App 私有目录清理 → 广播到 system_server 落盘 → system_server 重载内存
     */
    private static void cleanupAndSyncRules(Context context, String changedType,
                                            List<String> oldWriteBlocklist,
                                            List<String> oldReadBlocklist) {
        if (context == null) return;

        // 仅检测实际变更的拦截列表类型
        Set<String> removedPackages = new HashSet<>();
        if ("write".equals(changedType)) {
            List<String> newBlocklist = loadBlocklistFromFile(getWriteBlocklistPath(context));
            for (String pkg : oldWriteBlocklist) {
                if (!newBlocklist.contains(pkg)) removedPackages.add(pkg);
            }
        } else {
            List<String> newBlocklist = loadBlocklistFromFile(getReadBlocklistPath(context));
            for (String pkg : oldReadBlocklist) {
                if (!newBlocklist.contains(pkg)) removedPackages.add(pkg);
            }
        }

        if (removedPackages.isEmpty()) return;

        XLog.i(TAG, "黑名单移除 " + removedPackages + "，联动清理规则适用域 (type=" + changedType + ")");

        // 1) 先清理 App 私有目录下对应类型的规则 JSON
        cleanupAppRulePackages(context, changedType, removedPackages);

        // 2) 广播清理后的规则到 system_server → system_server 落盘 + applyMergedRulesFromDisk 重载内存
        broadcastRulesOnly(context, changedType);
    }

    /** 仅清理对应类型的规则文件适用域：写入拦截列表变更只清理写入规则，反之亦然。 */
    private static void cleanupAppRulePackages(Context context, String type, Set<String> packages) {
        if (packages == null || packages.isEmpty()) return;
        if ("write".equals(type)) {
            cleanupJsonFile(context, "write_rules.json", packages);
            cleanupJsonFile(context, "write_default_rules.json", packages);
        } else {
            cleanupJsonFile(context, "read_rules.json", packages);
            cleanupJsonFile(context, "read_default_rules.json", packages);
        }
    }

    /** 清理规则文件中的指定包名 */
    private static void cleanupJsonFile(Context context, String fileName, Set<String> packages) {
        File file = new File(context.getFilesDir(), fileName);
        if (!file.exists()) return;
        try {
            String content = readTextFile(file);
            if (content == null || content.isEmpty()) return;
            // 文件格式: {"enabled":..., "content_rules": [{...}, ...]}
            JSONObject root = new JSONObject(content);
            JSONArray rules = root.optJSONArray("content_rules");
            if (rules == null || rules.length() == 0) return;
            boolean changed = false;
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                if (!rule.has("applicable_packages")) continue;
                JSONArray oldPkgs = rule.getJSONArray("applicable_packages");
                JSONArray newPkgs = new JSONArray();
                for (int j = 0; j < oldPkgs.length(); j++) {
                    String pkg = oldPkgs.getString(j);
                    if (!packages.contains(pkg)) {
                        newPkgs.put(pkg);
                    } else {
                        changed = true;
                    }
                }
                if (changed) {
                    rule.put("applicable_packages", newPkgs);
                }
            }
            if (changed) {
                writeTextFile(file, root.toString(2));
                XLog.i(TAG, "已清理 App 侧 " + fileName + " 适用域: " + packages);
            }
        } catch (Exception e) {
            XLog.w(TAG, "清理 App 侧规则文件失败 " + fileName + ": " + e.getMessage());
        }
    }

    /** 检查调用者是否为可信来源（本应用、system_server 或同 UID） */
    private boolean isTrustedCaller(int callingUid) {
        Context context = getContext();
        if (context == null) return false;
        if (callingUid == Process.myUid()) return true;
        if (callingUid == Process.SYSTEM_UID) return true;
        String[] packages = context.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (PACKAGE_NAME.equals(pkg) || "android".equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合并自定义规则和默认规则（只包含启用的默认规则）
     * Hook 侧收到广播后直接使用，规则数 = 自定义规则 + 启用的默认规则
     */
    /** 检查读取拦截 Toast 是否启用 */
    public static boolean isReadBlockedToastEnabled(Context context) {
        if (context == null) return true;
        return context.getSharedPreferences("clipboardguard_prefs", Context.MODE_PRIVATE)
                .getBoolean("read_blocked_toast_enabled", true);
    }

    /** 检查 LSPosed 日志输出是否启用 */
    public static boolean isLsposedLogEnabled(Context context) {
        if (context == null) return true;
        return context.getSharedPreferences("clipboardguard_prefs", Context.MODE_PRIVATE)
                .getBoolean("lsposed_log_enabled", true);
    }

    /** 确保目录存在 */
    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("创建目录失败: " + directory.getPath());
        }
    }

    // ═══════════════════════════════════ 规则文件读取 ═══════════════════════════════

    /** 读取 App 规则文件（带缓存） */
    private static String readAppRulesFile(Context context, String fileName) {
        if (context == null) return null;
        File file = new File(context.getFilesDir(), fileName);
        long lastModified = file.lastModified();

        synchronized (sRulesFileCache) {
            FileCache cache = sRulesFileCache.get(fileName);
            if (cache != null && cache.lastModified == lastModified) {
                return cache.content;
            }
        }

        String content = readFileContent(file);
        synchronized (sRulesFileCache) {
            FileCache cache = new FileCache();
            cache.lastModified = lastModified;
            cache.content = content;
            sRulesFileCache.put(fileName, cache);
        }
        return content;
    }

    /** 读取文件内容 */
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

    // ═══════════════════════════════════ App 端读取接口 ═══════════════════════════════

    /** 获取所有写入拦截权限列表 */
    public static List<String[]> getAllWritePermissions(Context context) {
        return loadBlocklistAsPermissionList(getWriteBlocklistPath(context));
    }

    /** 获取所有读取拦截权限列表 */
    public static List<String[]> getAllReadPermissions(Context context) {
        return loadBlocklistAsPermissionList(getReadBlocklistPath(context));
    }

    /** 将拦截名单加载为权限列表格式 */
    private static List<String[]> loadBlocklistAsPermissionList(String filePath) {
        List<String[]> result = new ArrayList<>();
        List<String> blocklist = loadBlocklistFromFile(filePath);
        for (String pkg : blocklist) {
            result.add(new String[]{pkg, String.valueOf(PermissionDecision.PERMISSION_BLOCK)});
        }
        return result;
    }

    /** 直接获取写入拦截包名列表 */
    public static List<String> getBlockedWritePackagesDirect(Context context) {
        return loadBlocklistAsPackageList(getWriteBlocklistPath(context));
    }

    /** 直接获取读取拦截包名列表 */
    public static List<String> getBlockedReadPackagesDirect(Context context) {
        return loadBlocklistAsPackageList(getReadBlocklistPath(context));
    }

    /** 将拦截名单加载为包名列表 */
    private static List<String> loadBlocklistAsPackageList(String filePath) {
        return new ArrayList<>(loadBlocklistFromFile(filePath));
    }

}
