package com.android.clipboardguard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.FileObserver;
import android.util.Log;

import de.robv.android.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PermissionCache - 内存 blockSet 缓存
 *
 * 改造说明（2026-04-29）：
 * - blockSet：只存勾选（拦截）的包名
 * - isIgnored()：blockSet 里没有该包 → 返回 true（放行，不弹窗）
 * - Hook 启动时全量拉取 → 内存查表 O(1)
 * - 主刷新机制：FileObserver 监听文件变化（实时、无需广播）
 * - 备用机制：30s 兜底刷新（防止 FileObserver 失效）
 * - 数据源：纯文本文件，直接 I/O 读取（不经 ContentProvider，避免 system_server 同步 Binder 调用）
 *
 * 查询：PermissionCache.isIgnored(pkg) → true=放行，false=拦截弹窗
 */
public class PermissionCache {

    private static final String TAG = "ClipboardGuard.PermCache";

    // ── 内存 blockSet：2026-04-28 改造
    // 勾选(拦截)的包名存这里
    // isIgnored() 返回 false = 需要拦截弹窗，true = 放行
    private static final Set<String> sBlockSet = new HashSet<>();
    private static boolean sLoaded = false;

    // ── 广播接收器（Hook 侧刷新用）──
    private static BroadcastReceiver sRefreshReceiver;

    // ── FileObserver（备用机制：监听 App 进程的 blocklist.txt 变化）──
    // 注意：system_server 无法直接读 App 私有目录文件，FileObserver 仅用于通知
    // 实际数据通过广播 Intent 传递，FileObserver 收到通知后触发 refresh
    private static FileObserver sFileObserver;
    private static final String CONFIG_DIR = "/data/data/com.android.clipboardguard/files/";
    private static final String BLOCKLIST_FILE = "blocklist.txt";

    // ── 单例 Context 引用（用于重新加载）──
    private static Context sContext;

    // ── 时间戳兜底：广播不可靠时，定期静默刷新 ──
    private static final long STALE_THRESHOLD_MS = 30_000; // 30s 无刷新则强制重载
    private static volatile long sLastRefreshTime = 0;
    private static final AtomicBoolean sRefreshing = new AtomicBoolean(false);
    private static final Object sTimeLock = new Object();

    // ──────────────────────────── 初始化 & 全量加载 ────────────────────────────

    /**
     * Hook 启动时调用：全量拉取所有权限到 blockSet
     *
     * 加载策略（system_server 无法直接读 App 私有目录文件）：
     * 1. 优先用 ContentProvider.getAllPermissionsDirect() 拉取（App 进程已启动时可用）
     * 2. 加载广告过滤规则（正则）
     * 3. 注册 FileObserver 监听文件变化（备用）
     * 4. 注册广播接收器，App 保存权限时会推送 blocklist 到这里
     *
     * 注意：system_server 发出同步 Binder 调用到 App 进程会产生 "Outgoing transactions
     * must be FLAG_ONEWAY" 警告，但不影响功能（只是规范问题，不导致崩溃）。
     * 正常使用时，App 进程已启动，ContentProvider 响应快。
     */
    public static synchronized void loadIgnoreSet(Context context) {
        if (context == null) {
            Log.e(TAG, "loadIgnoreSet: context 为空！");
            return;
        }
        sContext = context;

        Log.i(TAG, "loadIgnoreSet 开始...");
        long start = System.currentTimeMillis();

        try {
            // ── 通过 ContentProvider 拉取 blocklist ──
            // system_server → App ContentProvider（同步调用，会产生 Binder 警告但不影响功能）
            sBlockSet.clear();
            boolean loadSuccess = false;
            try {
                Map<String, Integer> all = PermissionProvider.getAllPermissionsDirect(context);
                for (Map.Entry<String, Integer> e : all.entrySet()) {
                    if (e.getValue() == PermissionStorage.PERMISSION_BLOCK) {
                        sBlockSet.add(e.getKey());
                    }
                }
                loadSuccess = true;
                Log.d(TAG, "ContentProvider 拉取 blockSet=" + sBlockSet.size() + " 条");
            } catch (Throwable e) {
                Log.e(TAG, "ContentProvider 拉取失败: " + e.getMessage() + "（等待 App 进程启动）");
                loadSuccess = false;
            }

            // 同步加载广告过滤规则（用户配置的正则）
            ContentRulesManager.loadRules();
            sLastRefreshTime = System.currentTimeMillis();
            long cost = System.currentTimeMillis() - start;
            Log.i(TAG, "loadBlockSet 完成！blockSet.size=" + sBlockSet.size() + "，耗时=" + cost + "ms");

            // 只有 ContentProvider 真正加载成功才标记 sLoaded = true
            // 否则视为未加载（需要等待开机广播或 App 启动后重新初始化）
            sLoaded = loadSuccess;

            // 注册 FileObserver（监听 App 进程数据文件变化通知，不依赖实际读取权限）
            registerFileObserver();

        } catch (Throwable e) {
            Log.e(TAG, "loadIgnoreSet 失败: " + e.getMessage());
            sLoaded = false; // 确保失败时不标记为已加载
        }
    }

    /**
     * 从广播 Intent 中更新 blocklist（App 保存权限时推送过来）
     * App 端通过 PermissionProvider.sendPermissionChangedBroadcastWithData() 发送
     * @param blocklist 包含 BLOCK 权限的包名列表
     */
    public static synchronized void updateFromBlockList(java.util.List<String> blocklist) {
        if (blocklist == null) {
            Log.w(TAG, "updateFromBlockList: 数据为空，跳过");
            return;
        }
        Log.i(TAG, "updateFromBlockList: 收到 " + blocklist.size() + " 条 blocklist");

        // 加载广告过滤规则（正则）
        if (!ContentRulesManager.isLoaded()) {
            ContentRulesManager.loadRules();
        }

        sBlockSet.clear();
        sBlockSet.addAll(blocklist);
        sLastRefreshTime = System.currentTimeMillis();
        sLoaded = true;

        Log.i(TAG, "updateFromBlockList 完成！blockSet.size=" + sBlockSet.size());
    }

    /**
     * 刷新 ignoreSet（收到广播时调用）
     */
    public static synchronized void refreshIgnoreSet() {
        if (sContext == null) {
            Log.w(TAG, "refreshIgnoreSet: sContext 为空，跳过刷新");
            return;
        }
        Log.i(TAG, "refreshIgnoreSet 开始...");
        loadIgnoreSet(sContext);
    }

    /**
     * 检查是否需要静默刷新（30s 兜底机制）
     * 由 isIgnored() 触发，广播失败时的兜底方案
     */
    private static void checkAndSilentRefresh() {
        long now = System.currentTimeMillis();
        if (now - sLastRefreshTime < STALE_THRESHOLD_MS) return;
        if (!sRefreshing.compareAndSet(false, true)) return; // 已有刷新在进行中，跳过

        Log.i(TAG, "静默刷新: ignoreSet 已超过 " + STALE_THRESHOLD_MS / 1000 + "s 未更新，开始后台重载...");
        new Thread(() -> {
            try {
                if (sContext != null) {
                    // 同步刷新（重新加载 ignoreSet）
                    synchronized (sTimeLock) {
                        loadIgnoreSet(sContext);
                    }
                    Log.i(TAG, "静默刷新完成");
                }
            } catch (Throwable e) {
                Log.e(TAG, "静默刷新失败: " + e.getMessage());
            } finally {
                sRefreshing.set(false);
            }
        }, "PermCache-SilentRefresh").start();
    }

    /**
     * 注册广播接收器（在 Hook 初始化时调用）
     * 监听 App 侧保存权限后发出的 ACTION_PERMISSION_CHANGED 广播（数据推送）
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static synchronized void registerRefreshReceiver(Context context) {
        // 检查是否已注册
        if (sRefreshReceiver != null) {
            Log.d(TAG, "刷新接收器已注册，跳过");
            return;
        }

        // ── 注册 FileObserver（备用：监听 App 进程数据文件变化通知）──
        registerFileObserver();

        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    Log.i(TAG, "收到权限变更广播，刷新 ignoreSet...");
                    // 优先从 Intent 中提取 blocklist 数据（App 保存时推送过来）
                    java.util.ArrayList<String> blocklist =
                            intent.getStringArrayListExtra("blocklist");
                    if (blocklist != null && !blocklist.isEmpty()) {
                        Log.i(TAG, "广播携带 blocklist: " + blocklist.size() + " 条");
                        updateFromBlockList(blocklist);
                    } else {
                        // 兜底：通过 ContentProvider 拉取
                        Log.i(TAG, "广播无 blocklist 数据，通过 ContentProvider 拉取...");
                        refreshIgnoreSet();
                    }
                }
            };

            IntentFilter filter = new IntentFilter(
                    PermissionProvider.ACTION_PERMISSION_CHANGED);
            long identity = Binder.clearCallingIdentity();
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                sRefreshReceiver = receiver; // 保存引用
                Log.i(TAG, "权限变更广播接收器注册成功");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (Throwable e) {
            Log.e(TAG, "注册权限变更广播接收器失败: " + e.getMessage());
        }
    }

    /**
     * 注册 FileObserver 监听配置文件变化（主刷新机制）
     * 相比广播更可靠：文件一变就刷新，不依赖进程间通信
     */
    private static void registerFileObserver() {
        if (sFileObserver != null) {
            // 已注册过，先停止
            try {
                sFileObserver.stopWatching();
            } catch (Throwable ignored) {}
        }

        String path = CONFIG_DIR + BLOCKLIST_FILE;
        try {
            sFileObserver = new FileObserver(path, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                @Override
                public void onEvent(int event, String path) {
                    Log.i(TAG, "FileObserver 检测到配置文件变化，刷新缓存...");
                    refreshIgnoreSet();
                }
            };
            sFileObserver.startWatching();
            Log.i(TAG, "FileObserver 注册成功: " + path);
        } catch (Throwable e) {
            Log.e(TAG, "FileObserver 注册失败: " + e.getMessage());
        }
    }

    // ──────────────────────────── 查询接口 ────────────────────────────

    /**
     * 检查包名是否在放行名单中
     * @return true = 放行(IGNORE)，false = 需要拦截(BLOCK)
     */
    public static boolean isIgnored(String packageName) {
        if (!sLoaded) {
            Log.w(TAG, "isIgnored: blockSet 尚未加载！package=" + packageName);
            return false; // 保守策略：未加载完视为需要拦截
        }

        // 兜底检查：30s 未刷新则静默后台重载（解决跨进程广播不可靠问题）
        checkAndSilentRefresh();

        // blockSet 里有 → 拦截（不忽略），返回 false
        // blockSet 里没有（不在文件里 = 未勾选 = 默认拦截），返回 true = 放行
        boolean ignored = !sBlockSet.contains(packageName);
        Log.d(TAG, "isIgnored(" + packageName + ") = " + ignored + " (blockSet.size=" + sBlockSet.size() + ")");
        return ignored;
    }


    /**
     * 获取当前 ignoreSet 大小（调试用）
     */
    public static int getIgnoreSetSize() {
        return sBlockSet.size();
    }

    /**
     * 是否已加载完成
     */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * 从黑名单移除包名（用户点击"允许"时调用）
     * 同时更新内存缓存和持久化存储
     */
    public static synchronized void removeFromBlockSet(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        sBlockSet.remove(packageName);  // 从黑名单移除 = 放行
        sLastRefreshTime = System.currentTimeMillis();
        Log.i(TAG, "removeFromBlockSet: " + packageName + " 已放行，blockSet.size=" + sBlockSet.size());
        // 持久化：标记为 PERMISSION_IGNORE（不在 blockSet 中）
        if (context != null) {
            PermissionProvider.savePermission(context, packageName, PermissionStorage.PERMISSION_IGNORE);
        }
    }

    /**
     * 清空黑名单（谨慎使用）
     */
    public static synchronized void clear() {
        sBlockSet.clear();
        sLoaded = false;
        Log.i(TAG, "blockSet 已清空");
    }
}
