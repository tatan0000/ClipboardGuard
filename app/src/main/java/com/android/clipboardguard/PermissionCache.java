package com.android.clipboardguard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PermissionCache - 被动刷新内存缓存
 *
 * 改造说明：
 * - 移除定时静默刷新，仅依靠广播和 FileObserver 被动更新
 * - 使用单线程守护线程池执行带文件时间检查的刷新
 * - 移除静态 Context 引用，避免内存泄漏
 * - 未加载完成时查询返回 true（放行），避免误拦
 * - 最低 API 30，使用新版 FileObserver 构造函数
 * - 增加规则文件 FileObserver，实现规则文件的被动同步
 */
public class PermissionCache {

    private static final String TAG = "ClipboardGuard.PermCache";

    // ── 缓存集合 ──
    private static final Set<String> sWriteBlockSet = new HashSet<>();
    private static boolean sWriteLoaded = false;

    private static final Set<String> sReadBlockSet = new HashSet<>();
    private static boolean sReadLoaded = false;

    // ── 单线程守护线程池，用于被动刷新 ──
    private static final ExecutorService sRefreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PermCache-Refresh");
        t.setDaemon(true);
        return t;
    });

    // ── 广播接收器 ──
    private static BroadcastReceiver sRefreshReceiver;

    // ── FileObserver（拦截列表） ──
    private static FileObserver sWriteFileObserver;
    private static FileObserver sReadFileObserver;

    // ── FileObserver（规则文件） ──
    private static FileObserver sWriteRulesFileObserver;
    private static FileObserver sReadRulesFileObserver;
    private static FileObserver sWriteDefaultRulesFileObserver;
    private static FileObserver sReadDefaultRulesFileObserver;

    // 硬编码配置文件路径（模块通用，Xposed 环境无更优方案）
    @SuppressLint("SdCardPath")
    private static final String CONFIG_DIR = "/data/data/com.android.clipboardguard/files/";
    private static final String WRITE_BLOCKLIST_FILE = "write_blocklist.txt";
    private static final String READ_BLOCKLIST_FILE  = "read_blocklist.txt";

    // 规则文件名
    private static final String WRITE_RULES_FILE   = "write_rules.json";
    private static final String READ_RULES_FILE    = "read_rules.json";
    private static final String WRITE_DEFAULT_RULES_FILE = "write_default_rules.json";
    private static final String READ_DEFAULT_RULES_FILE  = "read_default_rules.json";

    // ── 文件最后修改时间记录 ──
    private static volatile long sWriteFileLastModified = 0;
    private static volatile long sReadFileLastModified  = 0;

    // ──────────────────────────── 加载方法（无 Context 参数） ────────────────────────────

    public static synchronized void loadWriteBlockSet() {
        Log.i(TAG, "loadWriteBlockSet 开始...");
        long start = System.currentTimeMillis();

        try {
            sWriteBlockSet.clear();
            Map<String, Integer> all = PermissionProvider.getAllWritePermissionsDirect(null);
            for (Map.Entry<String, Integer> e : all.entrySet()) {
                if (e.getValue() == PermissionStorage.PERMISSION_BLOCK) {
                    sWriteBlockSet.add(e.getKey());
                }
            }
            sWriteLoaded = true;

            File file = new File(CONFIG_DIR + WRITE_BLOCKLIST_FILE);
            sWriteFileLastModified = file.exists() ? file.lastModified() : System.currentTimeMillis();

            long cost = System.currentTimeMillis() - start;
            Log.i(TAG, "loadWriteBlockSet 完成！size=" + sWriteBlockSet.size() + "，耗时=" + cost + "ms");
        } catch (Throwable e) {
            Log.e(TAG, "loadWriteBlockSet 失败: " + e.getMessage());
            sWriteLoaded = false;
        }
    }

    public static synchronized void loadReadBlockSet() {
        Log.i(TAG, "loadReadBlockSet 开始...");
        long start = System.currentTimeMillis();

        try {
            sReadBlockSet.clear();
            Map<String, Integer> all = PermissionProvider.getAllReadPermissionsDirect(null);
            for (Map.Entry<String, Integer> e : all.entrySet()) {
                if (e.getValue() == PermissionStorage.PERMISSION_BLOCK) {
                    sReadBlockSet.add(e.getKey());
                }
            }
            sReadLoaded = true;

            File file = new File(CONFIG_DIR + READ_BLOCKLIST_FILE);
            sReadFileLastModified = file.exists() ? file.lastModified() : System.currentTimeMillis();

            long cost = System.currentTimeMillis() - start;
            Log.i(TAG, "loadReadBlockSet 完成！size=" + sReadBlockSet.size() + "，耗时=" + cost + "ms");
        } catch (Throwable e) {
            Log.e(TAG, "loadReadBlockSet 失败: " + e.getMessage());
            sReadLoaded = false;
        }
    }

    // ──────────────────────────── 广播/文件事件触发更新 ────────────────────────────

    public static synchronized void updateFromWriteBlockList(ArrayList<String> blocklist) {
        if (blocklist == null) return;
        Log.i(TAG, "updateFromWriteBlockList: 收到 " + blocklist.size() + " 条数据");
        sWriteBlockSet.clear();
        sWriteBlockSet.addAll(blocklist);
        sWriteLoaded = true;
        updateWriteFileLastModified();
    }

    public static synchronized void updateFromReadBlockList(ArrayList<String> blocklist) {
        if (blocklist == null) return;
        Log.i(TAG, "updateFromReadBlockList: 收到 " + blocklist.size() + " 条数据");
        sReadBlockSet.clear();
        sReadBlockSet.addAll(blocklist);
        sReadLoaded = true;
        updateReadFileLastModified();
    }

    // ──────────────────────────── 被动刷新（文件时间检查） ────────────────────────────

    public static synchronized void refreshWriteBlockSet() {
        sRefreshExecutor.execute(() -> {
            File file = new File(CONFIG_DIR + WRITE_BLOCKLIST_FILE);
            long currentModified = file.exists() ? file.lastModified() : -1;
            if (currentModified == sWriteFileLastModified && sWriteFileLastModified != 0) {
                Log.d(TAG, "writeBlockSet 文件未变化，跳过刷新");
                return;
            }
            Log.i(TAG, "被动刷新 writeBlockSet...");
            loadWriteBlockSet();
        });
    }

    public static synchronized void refreshReadBlockSet() {
        sRefreshExecutor.execute(() -> {
            File file = new File(CONFIG_DIR + READ_BLOCKLIST_FILE);
            long currentModified = file.exists() ? file.lastModified() : -1;
            if (currentModified == sReadFileLastModified && sReadFileLastModified != 0) {
                Log.d(TAG, "readBlockSet 文件未变化，跳过刷新");
                return;
            }
            Log.i(TAG, "被动刷新 readBlockSet...");
            loadReadBlockSet();
        });
    }

    private static void updateWriteFileLastModified() {
        File file = new File(CONFIG_DIR + WRITE_BLOCKLIST_FILE);
        sWriteFileLastModified = file.exists() ? file.lastModified() : System.currentTimeMillis();
    }

    private static void updateReadFileLastModified() {
        File file = new File(CONFIG_DIR + READ_BLOCKLIST_FILE);
        sReadFileLastModified = file.exists() ? file.lastModified() : System.currentTimeMillis();
    }

    // ──────────────────────────── 广播接收器注册 ────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static synchronized void registerRefreshReceiver(Context context) {
        if (sRefreshReceiver != null) return;

        registerWriteFileObserver();
        registerReadFileObserver();
        registerRulesFileObservers(); // 首次尝试注册（可能因文件不存在而跳过）

        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    Log.d(TAG, "收到权限变更广播");

                    // 处理写入拦截列表
                    ArrayList<String> writeBlocklist = intent.getStringArrayListExtra("write_blocklist");
                    if (intent.hasExtra("write_blocklist")) {
                        updateFromWriteBlockList(writeBlocklist != null ? writeBlocklist : new ArrayList<>());
                    }

                    // 处理读取拦截列表
                    ArrayList<String> readBlocklist = intent.getStringArrayListExtra("read_blocklist");
                    if (intent.hasExtra("read_blocklist")) {
                        updateFromReadBlockList(readBlocklist != null ? readBlocklist : new ArrayList<>());
                    }

                    // 处理写入规则 JSON
                    String writeRulesJson = intent.getStringExtra("write_rules_json");
                    if (intent.hasExtra("write_rules_json") && writeRulesJson != null && !writeRulesJson.isEmpty()) {
                        ContentRulesManager.updateRulesFromJson(writeRulesJson, false);
                        // ★ 补注册：如果文件观察者未创建，现在创建
                        if (sWriteRulesFileObserver == null) registerWriteRulesObserver();
                        if (sWriteDefaultRulesFileObserver == null) registerWriteDefaultRulesObserver();
                    }

                    // 处理读取规则 JSON
                    String readRulesJson = intent.getStringExtra("read_rules_json");
                    if (intent.hasExtra("read_rules_json") && readRulesJson != null && !readRulesJson.isEmpty()) {
                        ContentRulesManager.updateReadRulesFromJson(readRulesJson);
                        // ★ 补注册
                        if (sReadRulesFileObserver == null) registerReadRulesObserver();
                        if (sReadDefaultRulesFileObserver == null) registerReadDefaultRulesObserver();
                    }

                    // 如果广播中没有有效数据，尝试基于文件刷新
                    if (!intent.hasExtra("write_blocklist") && !intent.hasExtra("read_blocklist")
                            && !intent.hasExtra("write_rules_json") && !intent.hasExtra("read_rules_json")) {
                        refreshWriteBlockSet();
                        refreshReadBlockSet();
                    }
                }
            };

            IntentFilter filter = new IntentFilter(PermissionProvider.ACTION_PERMISSION_CHANGED);
            long identity = Binder.clearCallingIdentity();
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                sRefreshReceiver = receiver;
                Log.i(TAG, "权限变更广播接收器注册成功");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (Throwable e) {
            Log.e(TAG, "注册权限变更广播接收器失败: " + e.getMessage());
        }
    }

    private static void registerRulesFileObservers() {
        registerWriteRulesObserver();
        registerReadRulesObserver();
        registerWriteDefaultRulesObserver();
        registerReadDefaultRulesObserver();
    }

    private static void registerWriteRulesObserver() {
        if (sWriteRulesFileObserver != null) {
            try { sWriteRulesFileObserver.stopWatching(); } catch (Throwable ignored) {}
        }
        File file = new File(CONFIG_DIR + WRITE_RULES_FILE);
        if (!file.exists()) {
            Log.d(TAG, "write_rules.json 暂未创建，跳过 FileObserver");
            return;
        }
        try {
            sWriteRulesFileObserver = new FileObserver(file, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    Log.i(TAG, "write_rules.json 变化，重新加载写入规则");
                    ContentRulesManager.loadRules();
                }
            };
            sWriteRulesFileObserver.startWatching();
            Log.i(TAG, "write_rules FileObserver 注册成功: " + file.getPath());
        } catch (Throwable e) {
            Log.e(TAG, "write_rules FileObserver 注册失败", e);
        }
    }

    private static void registerReadRulesObserver() {
        if (sReadRulesFileObserver != null) {
            try { sReadRulesFileObserver.stopWatching(); } catch (Throwable ignored) {}
        }
        File file = new File(CONFIG_DIR + READ_RULES_FILE);
        if (!file.exists()) {
            Log.d(TAG, "read_rules.json 暂未创建，跳过 FileObserver");
            return;
        }
        try {
            sReadRulesFileObserver = new FileObserver(file, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    Log.i(TAG, "read_rules.json 变化，重新加载读取规则");
                    ContentRulesManager.loadReadRules();
                }
            };
            sReadRulesFileObserver.startWatching();
            Log.i(TAG, "read_rules FileObserver 注册成功: " + file.getPath());
        } catch (Throwable e) {
            Log.e(TAG, "read_rules FileObserver 注册失败", e);
        }
    }

    private static void registerWriteDefaultRulesObserver() {
        if (sWriteDefaultRulesFileObserver != null) {
            try { sWriteDefaultRulesFileObserver.stopWatching(); } catch (Throwable ignored) {}
        }
        File file = new File(CONFIG_DIR + WRITE_DEFAULT_RULES_FILE);
        if (!file.exists()) {
            Log.d(TAG, "write_default_rules.json 暂未创建，跳过 FileObserver");
            return;
        }
        try {
            sWriteDefaultRulesFileObserver = new FileObserver(file, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    Log.i(TAG, "write_default_rules.json 变化，重新加载写入规则");
                    ContentRulesManager.loadRules();
                }
            };
            sWriteDefaultRulesFileObserver.startWatching();
            Log.i(TAG, "write_default_rules FileObserver 注册成功: " + file.getPath());
        } catch (Throwable e) {
            Log.e(TAG, "write_default_rules FileObserver 注册失败", e);
        }
    }

    private static void registerReadDefaultRulesObserver() {
        if (sReadDefaultRulesFileObserver != null) {
            try { sReadDefaultRulesFileObserver.stopWatching(); } catch (Throwable ignored) {}
        }
        File file = new File(CONFIG_DIR + READ_DEFAULT_RULES_FILE);
        if (!file.exists()) {
            Log.d(TAG, "read_default_rules.json 暂未创建，跳过 FileObserver");
            return;
        }
        try {
            sReadDefaultRulesFileObserver = new FileObserver(file, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    Log.i(TAG, "read_default_rules.json 变化，重新加载读取规则");
                    ContentRulesManager.loadReadRules();
                }
            };
            sReadDefaultRulesFileObserver.startWatching();
            Log.i(TAG, "read_default_rules FileObserver 注册成功: " + file.getPath());
        } catch (Throwable e) {
            Log.e(TAG, "read_default_rules FileObserver 注册失败", e);
        }
    }

    private static void registerWriteFileObserver() {
        if (sWriteFileObserver != null) {
            try { sWriteFileObserver.stopWatching(); } catch (Throwable ignored) {}
        }
        File file = new File(CONFIG_DIR + WRITE_BLOCKLIST_FILE);
        try {
            sWriteFileObserver = new FileObserver(file, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    Log.i(TAG, "FileObserver 检测到 write_blocklist 变化，刷新缓存...");
                    refreshWriteBlockSet();
                }
            };
            sWriteFileObserver.startWatching();
            Log.i(TAG, "write_blocklist FileObserver 注册成功: " + file.getPath());
        } catch (Throwable e) {
            Log.e(TAG, "write_blocklist FileObserver 注册失败: " + e.getMessage());
        }
    }

    private static void registerReadFileObserver() {
        if (sReadFileObserver != null) {
            try { sReadFileObserver.stopWatching(); } catch (Throwable ignored) {}
        }
        File file = new File(CONFIG_DIR + READ_BLOCKLIST_FILE);
        try {
            sReadFileObserver = new FileObserver(file, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    Log.i(TAG, "FileObserver 检测到 read_blocklist 变化，刷新缓存...");
                    refreshReadBlockSet();
                }
            };
            sReadFileObserver.startWatching();
            Log.i(TAG, "read_blocklist FileObserver 注册成功: " + file.getPath());
        } catch (Throwable e) {
            Log.e(TAG, "read_blocklist FileObserver 注册失败: " + e.getMessage());
        }
    }

    // ──────────────────────────── 查询接口 ────────────────────────────

    public static boolean isWriteIgnored(String packageName) {
        if (!sWriteLoaded) {
            Log.w(TAG, "写入缓存未加载，暂时放行: " + packageName);
            return true;
        }
        return !sWriteBlockSet.contains(packageName);
    }

    @SuppressWarnings("unused")
    public static boolean isReadIgnored(String packageName) {
        if (!sReadLoaded) {
            Log.w(TAG, "读取缓存未加载，暂时放行: " + packageName);
            return true;
        }
        return !sReadBlockSet.contains(packageName);
    }

    @SuppressWarnings("unused")
    public static boolean isWriteLoaded() { return sWriteLoaded; }

    @SuppressWarnings("unused")
    public static int getWriteBlockSetSize() { return sWriteBlockSet.size(); }

    @SuppressWarnings("unused")
    public static int getReadBlockSetSize() { return sReadBlockSet.size(); }

    @SuppressWarnings("unused")
    public static void removeFromBlockSet(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        sWriteBlockSet.remove(packageName);
        updateWriteFileLastModified();
        if (context != null) {
            PermissionProvider.saveWritePermission(context, packageName, PermissionStorage.PERMISSION_IGNORE);
        }
    }

    @SuppressWarnings("unused")
    public static synchronized void clearWriteBlockSet() {
        sWriteBlockSet.clear();
        sWriteLoaded = false;
    }

    @SuppressWarnings("unused")
    public static synchronized void clearReadBlockSet() {
        sReadBlockSet.clear();
        sReadLoaded = false;
    }
}