package com.android.clipboardguard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/*
 * PermissionCache - 被动刷新内存缓存
 *
 * 改造说明：
 * - 移除定时静默刷新，仅依靠广播被动更新
 * - 移除静态 Context 引用，避免内存泄漏
 * - 未加载完成时查询返回 true（放行），避免误拦
 */
public class PermissionCache {

    // ──────────────────────────── 缓存状态 ────────────────────────────

    private static final String TAG = "ClipboardGuard.PermCache";

    private static final Set<String> sWriteBlockSet = new HashSet<>();
    private static boolean sWriteLoaded = false;

    private static final Set<String> sReadBlockSet = new HashSet<>();
    private static boolean sReadLoaded = false;
    private static volatile boolean sReadBlockedToastEnabled = true;
    private static volatile boolean sLsposedLogEnabled = true;

    private static BroadcastReceiver sRefreshReceiver;

    // ──────────────────────────── 本地缓存加载 ────────────────────────────

    public static synchronized void loadWriteBlockSet() {
        XLog.i(TAG, "loadWriteBlockSet 开始...");
        long start = System.currentTimeMillis();

        try {
            sWriteBlockSet.clear();
            Map<String, Integer> all = PermissionProvider.getAllWritePermissionsDirect(null);
            for (Map.Entry<String, Integer> e : all.entrySet()) {
                if (e.getValue() == PermissionDecision.PERMISSION_BLOCK) {
                    sWriteBlockSet.add(e.getKey());
                }
            }
            sWriteLoaded = true;

            long cost = System.currentTimeMillis() - start;
            XLog.i(TAG, "loadWriteBlockSet 完成！size=" + sWriteBlockSet.size() + "，耗时=" + cost + "ms");
        } catch (Throwable e) {
            XLog.e(TAG, "loadWriteBlockSet 失败: " + e.getMessage());
            sWriteLoaded = false;
        }
    }

    public static synchronized void loadReadBlockSet() {
        XLog.i(TAG, "loadReadBlockSet 开始...");
        long start = System.currentTimeMillis();

        try {
            sReadBlockSet.clear();
            Map<String, Integer> all = PermissionProvider.getAllReadPermissionsDirect(null);
            for (Map.Entry<String, Integer> e : all.entrySet()) {
                if (e.getValue() == PermissionDecision.PERMISSION_BLOCK) {
                    sReadBlockSet.add(e.getKey());
                }
            }
            sReadLoaded = true;

            long cost = System.currentTimeMillis() - start;
            XLog.i(TAG, "loadReadBlockSet 完成！size=" + sReadBlockSet.size() + "，耗时=" + cost + "ms");
        } catch (Throwable e) {
            XLog.e(TAG, "loadReadBlockSet 失败: " + e.getMessage());
            sReadLoaded = false;
        }
    }

    // ──────────────────────────── 广播数据更新 ────────────────────────────

    public static synchronized void updateFromWriteBlockList(ArrayList<String> blocklist) {
        if (blocklist == null) return;
        XLog.i(TAG, "updateFromWriteBlockList: 收到 " + blocklist.size() + " 条数据");
        sWriteBlockSet.clear();
        sWriteBlockSet.addAll(blocklist);
        sWriteLoaded = true;
    }

    public static synchronized void updateFromReadBlockList(ArrayList<String> blocklist) {
        if (blocklist == null) return;
        XLog.i(TAG, "updateFromReadBlockList: 收到 " + blocklist.size() + " 条数据");
        sReadBlockSet.clear();
        sReadBlockSet.addAll(blocklist);
        sReadLoaded = true;
    }

    // ──────────────────────────── Provider / 文件兜底加载 ────────────────────────────

    public static synchronized void refreshWriteBlockSet() {
        XLog.i(TAG, "被动刷新 writeBlockSet...");
        loadWriteBlockSet();
    }

    public static synchronized void refreshReadBlockSet() {
        XLog.i(TAG, "被动刷新 readBlockSet...");
        loadReadBlockSet();
    }

    public static synchronized boolean loadFullConfigFromProvider(Context context) {
        if (context == null) return false;
        long identity = Binder.clearCallingIdentity();
        try {
            // system_server 兜底读取模块私有文件；优先路径仍是 App 侧广播推送。
            String filesDir = context.createPackageContext(
                    "com.android.clipboardguard", Context.CONTEXT_IGNORE_SECURITY)
                    .getFilesDir().getPath();
            String prefsFile = new java.io.File(filesDir).getParent()
                    + "/shared_prefs/clipboardguard_prefs.xml";

            updateFromWriteBlockList(loadBlocklistFromFile(filesDir + "/write_blocklist.txt"));
            updateFromReadBlockList(loadBlocklistFromFile(filesDir + "/read_blocklist.txt"));

            loadRulesFromFileOrDefault(true);
            loadRulesFromFileOrDefault(false);

            sReadBlockedToastEnabled = readBooleanFromFile(prefsFile, "read_blocked_toast_enabled");
            sLsposedLogEnabled = readBooleanFromFile(prefsFile, "lsposed_log_enabled");
            XLog.i(TAG, "loadFullConfigFromProvider 完成，write=" + sWriteBlockSet.size()
                    + " read=" + sReadBlockSet.size()
                    + " toast=" + sReadBlockedToastEnabled
                    + " lsposedLog=" + sLsposedLogEnabled);
            return true;
        } catch (Throwable e) {
            XLog.w(TAG, "loadFullConfigFromProvider 失败: " + e.getMessage());
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static ArrayList<String> loadBlocklistFromFile(String filePath) {
        ArrayList<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String pkg = line.trim();
                if (!pkg.isEmpty()) {
                    result.add(pkg);
                }
            }
        } catch (Throwable e) {
            XLog.w(TAG, "loadBlocklistFromFile 失败: " + filePath + " -> " + e.getMessage());
        }
        return result;
    }

    private static void loadRulesFromFileOrDefault(boolean writeRules) {
        if (writeRules) {
            ContentRulesManager.loadWriteRules();
        } else {
            ContentRulesManager.loadReadRules();
        }
    }

    private static boolean readBooleanFromFile(String filePath, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            String prefix = key + "=";
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(prefix)) {
                    return Boolean.parseBoolean(line.substring(prefix.length()).trim());
                }
            }
        } catch (Throwable e) {
            XLog.w(TAG, "readBooleanFromFile 失败: " + filePath + " -> " + e.getMessage());
        }
        return true;
    }

    // ──────────────────────────── 广播接收器注册 ────────────────────────────

    /**
     * 注册权限变更广播接收器。
     *
     * @param context system_server Context
     * @return true 注册成功，false 注册失败（系统尚未就绪等）
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static synchronized boolean registerRefreshReceiver(Context context) {
        if (sRefreshReceiver != null) return true;

        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    XLog.d(TAG, "收到权限变更广播");

                    // 写入/读取拦截列表：即使为空也要更新，表示用户清空了勾选项。
                    ArrayList<String> writeBlocklist = intent.getStringArrayListExtra("write_blocklist");
                    if (intent.hasExtra("write_blocklist")) {
                        updateFromWriteBlockList(writeBlocklist != null ? writeBlocklist : new ArrayList<>());
                    }

                    ArrayList<String> readBlocklist = intent.getStringArrayListExtra("read_blocklist");
                    if (intent.hasExtra("read_blocklist")) {
                        updateFromReadBlockList(readBlocklist != null ? readBlocklist : new ArrayList<>());
                    }

                    // 规则 JSON：只在携带有效内容时更新。
                    String writeRulesJson = intent.getStringExtra("write_rules_json");
                    if (intent.hasExtra("write_rules_json") && writeRulesJson != null && !writeRulesJson.isEmpty()) {
                        ContentRulesManager.updateWriteRulesFromJson(writeRulesJson);
                    }

                    String readRulesJson = intent.getStringExtra("read_rules_json");
                    if (intent.hasExtra("read_rules_json") && readRulesJson != null && !readRulesJson.isEmpty()) {
                        ContentRulesManager.updateReadRulesFromJson(readRulesJson);
                    }

                    if (intent.hasExtra("read_blocked_toast_enabled")) {
                        sReadBlockedToastEnabled = intent.getBooleanExtra("read_blocked_toast_enabled", true);
                        XLog.i(TAG, "updateReadBlockedToastEnabled: " + sReadBlockedToastEnabled);
                    }

                    if (intent.hasExtra("lsposed_log_enabled")) {
                        sLsposedLogEnabled = intent.getBooleanExtra("lsposed_log_enabled", true);
                        XLog.i(TAG, "updateLsposedLogEnabled: " + sLsposedLogEnabled);
                    }

                    // 如果广播中没有有效数据，尝试基于 ContentProvider 刷新
                    if (!intent.hasExtra("write_blocklist") && !intent.hasExtra("read_blocklist")
                            && !intent.hasExtra("write_rules_json") && !intent.hasExtra("read_rules_json")
                            && !intent.hasExtra("read_blocked_toast_enabled")
                            && !intent.hasExtra("lsposed_log_enabled")) {
                        refreshWriteBlockSet();
                        refreshReadBlockSet();
                    }
                }
            };

            IntentFilter filter = new IntentFilter(PermissionProvider.ACTION_PERMISSION_CHANGED);
            long identity = Binder.clearCallingIdentity();
            try {
                // 广播发送方是 App 进程，接收方在 system_server，必须允许跨进程接收。
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                sRefreshReceiver = receiver;
                XLog.i(TAG, "权限变更广播接收器注册成功");
                return true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (NullPointerException npe) {
            // 系统尚未就绪（IActivityManager 代理为 null），早期启动时正常现象
            XLog.w(TAG, "广播接收器注册失败：系统尚未就绪");
            return false;
        } catch (Throwable e) {
            XLog.e(TAG, "注册权限变更广播接收器失败: " + e.getMessage());
            return false;
        }
    }

    // ──────────────────────────── 查询接口 ────────────────────────────

    public static boolean isWriteIgnored(String packageName) {
        if (!sWriteLoaded) {
            XLog.w(TAG, "写入缓存未加载，暂时放行: " + packageName);
            return true;
        }
        return !sWriteBlockSet.contains(packageName);
    }

    public static boolean isReadIgnored(String packageName) {
        if (!sReadLoaded) {
            XLog.w(TAG, "读取缓存未加载，暂时放行: " + packageName);
            return true;
        }
        return !sReadBlockSet.contains(packageName);
    }

    public static boolean isWriteLoaded() { return sWriteLoaded; }

    public static boolean isReadLoaded() { return sReadLoaded; }

    public static boolean isReadBlockedToastEnabled() { return sReadBlockedToastEnabled; }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isLsposedLogEnabled() { return sLsposedLogEnabled; }

    public static int getWriteBlockSetSize() { return sWriteBlockSet.size(); }

    public static int getReadBlockSetSize() { return sReadBlockSet.size(); }
}
