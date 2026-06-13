package com.android.clipboardguard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.annotation.SuppressLint;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * ClipboardGuard - Xposed 写入 + 读取拦截 Hook
 *
 * 架构定位：system_server 系统服务级 LSPosed 模块
 * - UI App 只是控制台，核心逻辑在 system_server
 * - Hook 目标：ClipboardService（setPrimaryClip + getPrimaryClip + onTransact）
 * - 作用域：android（System Framework）
 *
 * 模块激活检测：
 * - system_server 通过 Binder onTransact(CBGUARD_STATUS) 返回 sModuleStatusJson
 * - App 通过 ServiceManager.getService("clipboard").transact() 直连查询
 *
 * 配置加载：
 * - 开机 Hook 从 /data/system/clipboardguard/ 读文件（带 3 次重试）
 * - App 改配置后发广播，system_server 落盘并刷新内存
 */
public class ClipboardHook extends XposedModule {

    private static final String TAG = "ClipboardGuard.Hook";
    private static final String MODULE_PKG = "com.android.clipboardguard";

    // ──────────────────────── Hook ID（热重载用）────────────────────────
    /** 写入 Hook ID，用于热重载时原子替换 */
    private static final String HOOK_ID_WRITE = "cb_write";
    /** 读取 Hook ID，用于热重载时原子替换 */
    private static final String HOOK_ID_READ = "cb_read";
    /** onTransact Hook ID，用于热重载时原子替换 */
    private static final String HOOK_ID_ONTRANSACT = "cb_ontransact";

    /** 模块实例引用，用于调用 XposedInterface 的实例方法（如 getApiVersion()） */
    private static volatile XposedModule sModuleInstance = null;

    // ──────────────────────── 写入端字段 ────────────────────────

    private static final long WRITE_DEBOUNCE_MS = 2000;
    static final long DIALOG_WAIT_TIMEOUT_MS = 5_000;

    private static final Map<String, Long> sLastWriteDecisionTime = new HashMap<>();
    private static final Map<String, Integer> sLastWriteUserDecision = new HashMap<>();
    private static final Object sWriteDebounceLock = new Object();
    private static final Map<String, CountDownLatch> sWriteDecisionLatches = new HashMap<>();
    private static final Map<String, AtomicInteger> sWriteDecisionResults = new HashMap<>();
    private static final Map<String, Integer> sWriteDecisionWaiters = new HashMap<>();
    private static final Object sWriteLatchLock = new Object();

    /** 防止同一 Binder 线程内递归触发写入 Hook。 */
    private static final ThreadLocal<Boolean> sIsBlockingOperation = ThreadLocal.withInitial(() -> false);

    /** 写入端广播接收器是否已注册（仅热更新）。 */
    private static volatile boolean sWriteReceiverRegistered = false;

    /**
     * 缓存 system_server Context（使用 ActivityThread.getSystemContext()）。
     * 参考 Thanox ThanoxHookImpl 的实现。
     *
     * <p>静态持有 Context 在 system_server 中是安全的：
     * system_server 是系统级进程，生命周期与系统一致，不存在 Activity 泄漏风险。
     */
    @SuppressWarnings("StaticFieldLeak")
    private static volatile Context sSystemServerContext;

    // ──────────────────────── 读取端字段 ────────────────────────

    private static final long READ_DIALOG_DEBOUNCE_MS = 3000;
    private static final long READ_TOAST_DEBOUNCE_MS = 3000;

    private static final Map<String, Long> sLastReadDecisionTime = new HashMap<>();
    private static final Map<String, Integer> sLastReadUserDecision = new HashMap<>();
    private static final Map<String, Boolean> sLastReadClearConsumed = new HashMap<>();
    private static final Map<String, Long> sLastReadToastTime = new HashMap<>();
    private static final Object sReadDebounceLock = new Object();

    /** 读取弹窗同步等待：CountDownLatch 阻塞 Binder 线程等用户决策。 */
    private static final Map<String, CountDownLatch> sReadDecisionLatches = new HashMap<>();
    private static final Map<String, AtomicInteger> sReadDecisionResults = new HashMap<>();
    private static final Map<String, Integer> sReadDecisionWaiters = new HashMap<>();
    private static final Object sReadLatchLock = new Object();

    /** 防止清空剪贴板等操作递归触发读取 Hook。 */
    private static final ThreadLocal<Boolean> sIsReadBlockingOperation = ThreadLocal.withInitial(() -> false);
    /** 防止清空剪贴板操作递归触发写入 Hook。 */
    private static final ThreadLocal<Boolean> sIsClearOperation = ThreadLocal.withInitial(() -> false);

    // ──────────────────────── 防抖缓存清理 ────────────────────────

    /** 防抖缓存条目过期阈值：超过 10 分钟未访问的条目将被移除 */
    private static final long DEBOUNCE_CLEANUP_THRESHOLD_MS = 10 * 60 * 1000; // 10 分钟
    /** 防抖缓存清理间隔：至多每分钟清理一次 */
    private static final long DEBOUNCE_CLEANUP_INTERVAL_MS = 60 * 1000; // 至多每分钟清理一次
    /** 上次清理防抖缓存的时间戳 */
    private static volatile long sLastDebounceCleanupTime = 0;

    /** 读取端广播接收器是否已注册（仅热更新）。 */
    private static volatile boolean sReadReceiverRegistered = false;
    /** 避免重复创建延迟注册线程。 */
    private static final AtomicBoolean sWriteRegisterScheduled = new AtomicBoolean(false);
    private static final AtomicBoolean sReadRegisterScheduled = new AtomicBoolean(false);

    // ──────────────────────── 开机配置加载重试 ────────────────────────

    /** 开机配置加载是否已尝试（含重试）。 */
    private static final AtomicBoolean sConfigBootAttempted = new AtomicBoolean(false);

    // ──────────────────────── Binder onTransact 状态查询 ────────────────────────

    /**
     * 自定义 Binder 事务码：状态查询。
     * "CBGD" = 0x43424744，挂在 ClipboardService$ClipboardImpl.onTransact 上，
     * 复用系统已有的 "clipboard" 服务名（SELinux 类型 clipboard_service），
     * 无需向 ServiceManager 注册新服务，绕开 default_android_service 拒绝。
     */
    static final int TRANSACTION_CBGUARD_STATUS = 0x43424744;

    /** 当前模块状态 JSON，reportHookStatus() 写入，onTransact hook 返回。 */
    private static volatile String sModuleStatusJson;
    /** 开机配置是否已成功加载。 */
    private static volatile boolean sConfigLoadSuccess = false;
    /** 开机配置加载是否已彻底放弃（不再重试，等 App 推送配置）。 */
    private static volatile boolean sConfigBootFailed = false;
    /** ensureWrite 惰性重试是否已尝试过一次（仅首次写入触发一次）。 */
    private static volatile boolean sEnsureWriteLoadAttempted = false;
    /** ensureRead 惰性重试是否已尝试过一次（仅首次读取触发一次）。
     *  与写入标志分离：避免写入触发兜底后读取被错误跳过（反之亦然）。 */
    private static volatile boolean sEnsureReadLoadAttempted = false;

    /**
     * 复用 Toast Handler，避免每次创建新 Handler。
     * 延迟初始化：LSPosed 在 system_server 早期加载类时，主线程 Looper 尚未就绪。
     * 仅在 App 进程（真正需要 Toast 时）才创建。
     */
    private static volatile Handler sToastHandler;

    /** 写入 Hook 安装成功标志。 */
    private static volatile boolean sWriteHookInstalled = false;

    /** 读取 Hook 安装成功标志。 */
    private static volatile boolean sReadHookInstalled = false;

    // ══════════════════════════════════════════════════════
    //  Xposed 入口（libxposed API 102 生命周期）
    // ══════════════════════════════════════════════════════

    /**
     * 模块加载时调用（所有进程）。
     * 保存模块实例引用，初始化日志。
     */
    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        sModuleInstance = this;
        // 初始化 XLog，使用 API 102 的 log(int, String, String, Throwable) 签名
        try {
            XLog.init(this, XposedModule.class.getMethod(
                    "log", int.class, String.class, String.class, Throwable.class));
        } catch (NoSuchMethodException e) {
            android.util.Log.e(TAG, "获取 XposedModule.log 方法失败: " + e.getMessage());
        }
    }

    /**
     * system_server 启动时调用（替代旧的 onPackageLoaded + "android" 包名检查）。
     * 安装所有 Hook 并加载配置。
     */
    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            ClassLoader classLoader = param.getClassLoader();
            hookWriteClipboard(classLoader);
            hookReadClipboard(classLoader);
            hookOnTransact(classLoader);
            // 两个 Hook 都装完才上报激活，避免首页被半成功状态误导。
            reportHookStatus();
            loadAllConfigDirect();
        } catch (Throwable t) {
            XLog.e(TAG, "onSystemServerStarting 异常: " + t.getMessage());
            log(android.util.Log.ERROR, TAG, "onSystemServerStarting 异常", t);
        }
    }

    // ══════════════════════════════════════════════════════
    //  热重载（Hot Reload）- libxposed API 102
    // ══════════════════════════════════════════════════════

    /**
     * 旧模块即将卸载前调用。释放资源，清理静态状态。
     * 返回 true 允许卸载。
     */
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        XLog.i(TAG, "[HotReload] 旧模块即将卸载，释放资源");
        resetStaticState();
        return true;
    }

    /**
     * 新模块加载完成后调用。重新初始化并安装 Hook。
     * 使用 replaceHook 原子替换旧 Hook，避免中间状态。
     */
    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        XLog.i(TAG, "[HotReload] 新模块加载，重新初始化");
        sModuleInstance = this;
        // 重新初始化 XLog
        try {
            XLog.init(this, XposedModule.class.getMethod(
                    "log", int.class, String.class, String.class, Throwable.class));
        } catch (NoSuchMethodException e) {
            android.util.Log.e(TAG, "[HotReload] 获取 XposedModule.log 方法失败: " + e.getMessage());
        }

        // 获取 ClassLoader：优先从旧 Hook 句柄获取，回退到当前类加载器
        ClassLoader classLoader = null;
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            try {
                classLoader = handle.getExecutable().getDeclaringClass().getClassLoader();
                if (classLoader != null) break;
            } catch (Throwable ignored) {
            }
        }
        if (classLoader == null) {
            classLoader = this.getClass().getClassLoader();
        }

        // 构建新 Hook 映射（ID → Hooker）
        java.util.Map<String, XposedInterface.Hooker> newHooks = buildNewHooks();

        // 1. 尝试原子替换已有 Hook
        java.util.Set<String> replacedIds = new java.util.HashSet<>();
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            String id = handle.getId();
            XposedInterface.Hooker newHooker = (id != null) ? newHooks.get(id) : null;
            if (newHooker != null) {
                try {
                    handle.replaceHook(newHooker);
                    replacedIds.add(id);
                    // 更新 Hook 安装标志
                    if (HOOK_ID_WRITE.equals(id)) sWriteHookInstalled = true;
                    else if (HOOK_ID_READ.equals(id)) sReadHookInstalled = true;
                    XLog.i(TAG, "[HotReload] 原子替换 Hook: " + id);
                } catch (Throwable e) {
                    XLog.w(TAG, "[HotReload] 替换 Hook 失败: " + id + " - " + e.getMessage());
                    handle.unhook();
                }
            } else {
                handle.unhook();
            }
        }

        // 2. 安装未替换的新 Hook
        for (java.util.Map.Entry<String, XposedInterface.Hooker> entry : newHooks.entrySet()) {
            if (replacedIds.contains(entry.getKey())) continue;
            try {
                installHookById(classLoader, entry.getKey(), entry.getValue());
                XLog.i(TAG, "[HotReload] 新安装 Hook: " + entry.getKey());
            } catch (Throwable e) {
                XLog.e(TAG, "[HotReload] 安装 Hook 失败: " + entry.getKey() + " - " + e.getMessage());
            }
        }

        // 3. 重新加载配置并上报状态
        reportHookStatus();
        loadAllConfigDirect();
        XLog.i(TAG, "[HotReload] 热重载完成");
    }

    /** 重置所有静态状态（热重载时调用） */
    private void resetStaticState() {
        // 配置加载标志
        sConfigLoadSuccess = false;
        sConfigBootFailed = false;
        sConfigBootAttempted.set(false);
        sEnsureWriteLoadAttempted = false;
        sEnsureReadLoadAttempted = false;

        // Hook 安装标志
        sWriteHookInstalled = false;
        sReadHookInstalled = false;

        // 广播接收器标志
        sWriteReceiverRegistered = false;
        sReadReceiverRegistered = false;
        sWriteRegisterScheduled.set(false);
        sReadRegisterScheduled.set(false);

        // 防抖缓存
        synchronized (sWriteDebounceLock) {
            sLastWriteDecisionTime.clear();
            sLastWriteUserDecision.clear();
        }
        synchronized (sReadDebounceLock) {
            sLastReadDecisionTime.clear();
            sLastReadUserDecision.clear();
            sLastReadClearConsumed.clear();
            sLastReadToastTime.clear();
        }

        // Latch 缓存
        synchronized (sWriteLatchLock) {
            sWriteDecisionLatches.clear();
            sWriteDecisionResults.clear();
            sWriteDecisionWaiters.clear();
        }
        synchronized (sReadLatchLock) {
            sReadDecisionLatches.clear();
            sReadDecisionResults.clear();
            sReadDecisionWaiters.clear();
        }

        // Context 缓存
        sSystemServerContext = null;
        sToastHandler = null;

        XLog.i(TAG, "[HotReload] 静态状态已重置");
    }

    /** 构建新 Hook 映射（ID → Hooker） */
    private java.util.Map<String, XposedInterface.Hooker> buildNewHooks() {
        java.util.Map<String, XposedInterface.Hooker> map = new java.util.HashMap<>();
        map.put(HOOK_ID_WRITE, new SetPrimaryClipHook());
        map.put(HOOK_ID_READ, new GetPrimaryClipHook());
        map.put(HOOK_ID_ONTRANSACT, new OnTransactHook());
        return map;
    }

    /** 根据 ID 安装对应的 Hook */
    private void installHookById(ClassLoader classLoader, String id, XposedInterface.Hooker hooker) {
        switch (id) {
            case HOOK_ID_WRITE:
                installWriteHookById(classLoader, hooker);
                break;
            case HOOK_ID_READ:
                installReadHookById(classLoader, hooker);
                break;
            case HOOK_ID_ONTRANSACT:
                installOnTransactHookById(classLoader, hooker);
                break;
            default:
                XLog.w(TAG, "[HotReload] 未知 Hook ID: " + id);
        }
    }

    /** 按 ID 安装写入 Hook */
    private void installWriteHookById(ClassLoader classLoader, XposedInterface.Hooker hooker) {
        String[] candidates = {
                "com.android.server.clipboard.ClipboardManagerService",
                "com.android.server.clipboard.ClipboardManagerService$Impl",
                "com.android.server.clipboard.ClipboardManagerService$BinderService",
                "com.android.server.clipboard.ClipboardManagerService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService",
                "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService$BinderService",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("setPrimaryClip".equals(m.getName())) {
                        m.setAccessible(true);
                        if (getApiVersion() >= 102) {
                            hook(m).setId(HOOK_ID_WRITE).intercept(hooker);
                        } else {
                            hook(m).intercept(hooker);
                        }
                        sWriteHookInstalled = true;
                        XLog.i(TAG, "[HotReload] 写入 Hook 安装成功: " + className);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        XLog.e(TAG, "[HotReload] 写入 Hook 安装失败：未找到 setPrimaryClip");
    }

    /** 按 ID 安装读取 Hook */
    private void installReadHookById(ClassLoader classLoader, XposedInterface.Hooker hooker) {
        String[] candidates = {
                "com.android.server.clipboard.ClipboardManagerService",
                "com.android.server.clipboard.ClipboardManagerService$Impl",
                "com.android.server.clipboard.ClipboardManagerService$BinderService",
                "com.android.server.clipboard.ClipboardManagerService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService",
                "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService$BinderService",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("getPrimaryClip".equals(m.getName())) {
                        m.setAccessible(true);
                        if (getApiVersion() >= 102) {
                            hook(m).setId(HOOK_ID_READ).intercept(hooker);
                        } else {
                            hook(m).intercept(hooker);
                        }
                        sReadHookInstalled = true;
                        XLog.i(TAG, "[HotReload] 读取 Hook 安装成功: " + className);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        XLog.e(TAG, "[HotReload] 读取 Hook 安装失败：未找到 getPrimaryClip");
    }

    /** 按 ID 安装 onTransact Hook */
    @SuppressLint("PrivateApi")
    private void installOnTransactHookById(ClassLoader classLoader, XposedInterface.Hooker hooker) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                    false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if ("onTransact".equals(m.getName())) {
                    m.setAccessible(true);
                    if (getApiVersion() >= 102) {
                        hook(m).setId(HOOK_ID_ONTRANSACT).intercept(hooker);
                    } else {
                        hook(m).intercept(hooker);
                    }
                    XLog.i(TAG, "[HotReload] onTransact Hook 安装成功");
                    return;
                }
            }
        } catch (Throwable e) {
            XLog.w(TAG, "[HotReload] onTransact Hook 安装失败: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════
    //  Hook 注册
    // ══════════════════════════════════════════════════════

    /** Hook ClipboardService.setPrimaryClip（写入拦截）。 */
    private void hookWriteClipboard(ClassLoader classLoader) {
        String[] candidates = {
                "com.android.server.clipboard.ClipboardManagerService",
                "com.android.server.clipboard.ClipboardManagerService$Impl",
                "com.android.server.clipboard.ClipboardManagerService$BinderService",
                "com.android.server.clipboard.ClipboardManagerService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService",
                "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService$BinderService",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("setPrimaryClip".equals(m.getName())) {
                        m.setAccessible(true);
                        if (getApiVersion() >= 102) {
                            hook(m).setId(HOOK_ID_WRITE).intercept(new SetPrimaryClipHook());
                        } else {
                            hook(m).intercept(new SetPrimaryClipHook());
                        }
                        XLog.i(TAG, "写入 Hook 成功: " + className);
                        sWriteHookInstalled = true;

                        // 参考 Thanox ThanoxHookImpl.waitForSystemReady：
                        // system_server 进程中不使用 Handler(Looper.getMainLooper()).postDelayed，
                        // 而是启动后台线程用 sleep 等待系统就绪后再注册广播接收器。
                        if (sWriteRegisterScheduled.compareAndSet(false, true)) {
                            new Thread(() -> {
                            try {
                                Thread.sleep(5000);
                                if (tryRegisterWriteReceiver("写入延迟5s")) {
                                    XLog.i(TAG, "写入延迟5s注册成功");
                                } else {
                                    Thread.sleep(10000);
                                    if (tryRegisterWriteReceiver("写入延迟15s")) {
                                        XLog.i(TAG, "写入延迟15s注册成功");
                                    }
                                }
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            } catch (Throwable t) {
                                XLog.e(TAG, "写入延迟注册异常: " + t.getMessage());
                            } finally {
                                sWriteRegisterScheduled.set(false);
                            }
                        }, "ClipboardGuard-WriteRegister").start();
                        }
                        return;
                    }
                }
            } catch (Throwable t) {
                XLog.w(TAG, "写入候选类未找到: " + className + " - " + t.getMessage());
            }
        }
        XLog.e(TAG, "写入 Hook 失败：未找到 setPrimaryClip");
    }

    /** Hook ClipboardService.getPrimaryClip（读取拦截）。 */
    private void hookReadClipboard(ClassLoader classLoader) {
        String[] candidates = {
                "com.android.server.clipboard.ClipboardManagerService",
                "com.android.server.clipboard.ClipboardManagerService$Impl",
                "com.android.server.clipboard.ClipboardManagerService$BinderService",
                "com.android.server.clipboard.ClipboardManagerService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService",
                "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService$BinderService",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("getPrimaryClip".equals(m.getName())) {
                        m.setAccessible(true);
                        if (getApiVersion() >= 102) {
                            hook(m).setId(HOOK_ID_READ).intercept(new GetPrimaryClipHook());
                        } else {
                            hook(m).intercept(new GetPrimaryClipHook());
                        }
                        XLog.i(TAG, "读取 Hook 成功: " + className);
                        sReadHookInstalled = true;

                        // 同上：后台线程 sleep 等待，避免在 system_server 中使用 Handler
                        if (sReadRegisterScheduled.compareAndSet(false, true)) {
                            new Thread(() -> {
                            try {
                                Thread.sleep(5000);
                                if (tryRegisterReadReceiver("读取延迟5s")) {
                                    XLog.i(TAG, "读取延迟5s注册成功");
                                } else {
                                    Thread.sleep(10000);
                                    if (tryRegisterReadReceiver("读取延迟15s")) {
                                        XLog.i(TAG, "读取延迟15s注册成功");
                                    }
                                }
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            } catch (Throwable t) {
                                XLog.w(TAG, "读取延迟注册异常: " + t.getMessage());
                            } finally {
                                sReadRegisterScheduled.set(false);
                            }
                        }, "ClipboardGuard-ReadRegister").start();
                        }
                        return;
                    }
                }
            } catch (Throwable t) {
                XLog.w(TAG, "读取候选类未找到: " + className + " - " + t.getMessage());
            }
        }
        XLog.e(TAG, "读取 Hook 失败：未找到 getPrimaryClip");
    }

    /**
     * Hook ClipboardService$ClipboardImpl.onTransact，拦截自定义事务码
     * TRANSACTION_CBGUARD_STATUS 返回模块激活状态 JSON。
     *
     * 设计动机：ServiceManager.addService() 自定义服务名被 SELinux
     * default_android_service 类型拒绝。此处复用系统已注册的 "clipboard" 服务
     * （SELinux 类型 clipboard_service），在现有 Binder 通道上增加一个事务码，
     * 零额外注册、零 SELinux 依赖。
     *
     * App 侧通过 ServiceManager.getService("clipboard") + transact(CBGUARD_STATUS)
     * 直连查询，与 Thanox 的 "tv_input" 劫持策略一致。
     */
    @SuppressLint("PrivateApi")
    private void hookOnTransact(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                    false, classLoader);
            // 查找 onTransact 方法
            for (Method m : cls.getDeclaredMethods()) {
                if ("onTransact".equals(m.getName())) {
                    m.setAccessible(true);
                    if (getApiVersion() >= 102) {
                        hook(m).setId(HOOK_ID_ONTRANSACT).intercept(new OnTransactHook());
                    } else {
                        hook(m).intercept(new OnTransactHook());
                    }
                    XLog.i(TAG, "onTransact Hook 成功: ClipboardService$ClipboardImpl");
                    return;
                }
            }
            XLog.w(TAG, "onTransact 方法未找到");
        } catch (Throwable t) {
            XLog.w(TAG, "onTransact Hook 失败: " + t.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════
    //  模块激活状态
    // ══════════════════════════════════════════════════════

    /** 上报 Hook 状态：写入和读取都安装成功才算模块真正激活。
     *  状态通过 Binder onTransact 直连返回，无文件兜底。 */
    public static void reportHookStatus() {
        // 自动检测 Xposed API 版本
        int xposedApi = 0;
        XposedModule module = sModuleInstance;
        if (module != null) {
            try {
                xposedApi = module.getApiVersion();
            } catch (Throwable t) {
                XLog.w(TAG, "获取 API 版本失败: " + t.getMessage());
            }
        }
        boolean active = sWriteHookInstalled && sReadHookInstalled;
        String detail = buildHookStatusDetail();
        XLog.i(TAG, "[Alive] Hook 状态: " + detail
                + " active=" + active + " (Xposed API " + xposedApi + ")");

        if (active) {
            String json = ConfigManager.saveModuleStatus("All", detail, xposedApi,
                    android.os.Process.myPid());
            if (json != null) {
                sModuleStatusJson = json;
            }
        }
    }

    /** 构建 Hook 状态详情字符串 */
    private static String buildHookStatusDetail() {
        if (sWriteHookInstalled && sReadHookInstalled) return "Write + Read";
        if (sWriteHookInstalled) return "Write only";
        if (sReadHookInstalled) return "Read only";
        return "None";
    }

    // ══════════════════════════════════════════════════════
    //  开机配置加载（带重试）
    // ══════════════════════════════════════════════════════

    /**
     * 开机配置加载（带重试）。
     *
     * 流程：同步尝试 → 失败等 5s → 重试 → 失败等 7s → 重试 → 最终失败停止，等 App 推送配置。
     */
    private static void loadAllConfigDirect() {
        if (sConfigLoadSuccess) return;
        if (sConfigBootFailed) return;

        // ── 开机路径：同步尝试 ──
        if (ConfigManager.loadFromDataSystem()) {
            sConfigLoadSuccess = true;
            XLog.i(TAG, "配置从 " + ConfigManager.CONFIG_DIR + " 加载成功");
            return;
        }

        // ── 调度后台重试（仅一次）──
        if (!sConfigBootAttempted.compareAndSet(false, true)) return;

        XLog.w(TAG, "配置加载失败，5s 后重试...");
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
            if (sConfigLoadSuccess) return;
            if (ConfigManager.loadFromDataSystem()) {
                sConfigLoadSuccess = true;
                XLog.i(TAG, "配置加载成功（第 2 次尝试）");
                return;
            }

            XLog.w(TAG, "配置加载仍失败，7s 后重试...");
            try { Thread.sleep(7000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
            if (sConfigLoadSuccess) return;
            if (ConfigManager.loadFromDataSystem()) {
                sConfigLoadSuccess = true;
                XLog.i(TAG, "配置加载成功（第 3 次尝试）");
                return;
            }

            sConfigBootFailed = true;
            XLog.w(TAG, "配置加载最终失败，停止重试。App 启动后推送配置将做兜底");
        }, "ClipboardGuard-BootConfig").start();
    }

    /** 注册写入端广播接收器（仅用于热更新，不是初始化必要条件）。 */
    private static boolean tryRegisterWriteReceiver(String tag) {
        if (sWriteReceiverRegistered) return true;
        Context ctx = getSystemServerContext();
        if (ctx == null) {
            XLog.i(TAG, "[" + tag + "] 获取 Context 失败（可能尚未就绪）");
            return false;
        }
        boolean ok = PermissionCache.registerRefreshReceiver(ctx);
        if (ok) {
            sWriteReceiverRegistered = true;
            XLog.i(TAG, "[" + tag + "] 写入广播接收器注册成功（热更新通道）");
        } else {
            XLog.i(TAG, "[" + tag + "] 写入广播接收器注册失败（可能尚未就绪）");
        }
        return ok;
    }

    /** 注册读取端广播接收器（仅用于热更新）。 */
    private static boolean tryRegisterReadReceiver(String tag) {
        if (sReadReceiverRegistered) return true;
        Context ctx = getSystemServerContext();
        if (ctx == null) {
            XLog.i(TAG, "[" + tag + "] 获取 Context 失败（可能尚未就绪）");
            return false;
        }
        boolean ok = PermissionCache.registerRefreshReceiver(ctx);
        if (ok) {
            sReadReceiverRegistered = true;
            XLog.i(TAG, "[" + tag + "] 读取广播接收器注册成功（热更新通道）");
        } else {
            XLog.i(TAG, "[" + tag + "] 读取广播接收器注册失败（可能尚未就绪）");
        }
        return ok;
    }

    /** 确保写入端配置已初始化（首次写入触发一次兜底重试）。 */
    private static boolean ensureWriteInitialized() {
        if (PermissionCache.isWriteLoaded()) return true;
        if (sConfigBootFailed || sEnsureWriteLoadAttempted) return false;
        sEnsureWriteLoadAttempted = true;
        loadAllConfigDirect();
        return PermissionCache.isWriteLoaded();
    }

    /** 确保读取端配置已初始化（首次读取触发一次兜底重试）。
     *  使用独立的 sEnsureReadLoadAttempted，不依赖写入端。 */
    private static boolean ensureReadInitialized() {
        if (PermissionCache.isReadLoaded() && ContentRulesManager.isReadLoaded()) return true;
        if (sConfigBootFailed || sEnsureReadLoadAttempted) return false;
        sEnsureReadLoadAttempted = true;
        loadAllConfigDirect();
        return PermissionCache.isReadLoaded() && ContentRulesManager.isReadLoaded();
    }

    // ══════════════════════════════════════════════════════
    //  写入拦截块（SetPrimaryClipHook）
    // ══════════════════════════════════════════════════════

    /** 写入拦截 Hook：拦截 ClipboardService.setPrimaryClip 调用 */
    private static class SetPrimaryClipHook implements XposedInterface.Hooker {

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            if (Boolean.TRUE.equals(sIsBlockingOperation.get())) return chain.proceed();
            sIsBlockingOperation.set(true);
            try {
                if (Boolean.TRUE.equals(sIsClearOperation.get())) return chain.proceed();
                cleanupExpiredDebounceEntries();

                boolean initialized = ensureWriteInitialized();
                if (!initialized) {
                    XLog.w(TAG, "写入配置未初始化，保守放行");
                    return chain.proceed();
                }

                String pkgName = getCallingWritePackageName(chain);
                if (pkgName == null || pkgName.isEmpty()) {
                    XLog.w(TAG, "无法获取写入调用者包名，保守放行");
                    return chain.proceed();
                }
                if (isCorePackage(pkgName)) return chain.proceed();

                Context ctx = getSystemServerContext();
                if (ctx == null) {
                    XLog.e(TAG, "写入端获取 Context 失败，保守放行: " + pkgName);
                    return chain.proceed();
                }

                List<Object> args = chain.getArgs();
                Object clipArg = !args.isEmpty() ? args.get(0) : null;
                // 完整内容用于规则匹配，预览用于弹窗/日志
                String fullContent = extractClipFullContent(clipArg);
                String preview = fullContent.length() > 100 ? fullContent.substring(0, 100) + "…" : fullContent;

                if (PermissionCache.isWriteIgnored(pkgName)) {
                    writeLog(pkgName, "放行", preview);
                    return chain.proceed();
                }
                if (!shouldShowWritePopup(pkgName, fullContent)) {
                    writeLog(pkgName, "放行(内容过滤)", preview);
                    return chain.proceed();
                }

                int decision;
                synchronized (sWriteDebounceLock) {
                    long now = System.currentTimeMillis();
                    Long lastTime = sLastWriteDecisionTime.get(pkgName);
                    Integer last = sLastWriteUserDecision.get(pkgName);
                    if (lastTime != null && now - lastTime < WRITE_DEBOUNCE_MS && last != null) {
                        decision = last;
                    } else {
                        decision = -1;
                    }
                }
                if (decision < 0) {
                    decision = askWriteUser(ctx, pkgName, preview);
                }

                writeLog(pkgName, decision == PermissionDecision.PERMISSION_IGNORE ? "放行" : "拦截", preview);
                if (decision != PermissionDecision.PERMISSION_IGNORE) {
                    // 拦截：不调用原始方法，直接返回 null
                    return null;
                }
                return chain.proceed();
            } finally {
                sIsBlockingOperation.remove();
            }
        }

        /**
         * 判断是否应该显示写入拦截弹窗（基于规则匹配）。
         * @param pkgName 调用者包名
         * @param fullContent 完整剪贴板内容（用于规则匹配，非截断预览）
         */
        private boolean shouldShowWritePopup(String pkgName, String fullContent) {
            // 规则完全未启用 → 弹窗询问（基础行为）
            if (!ContentRulesManager.isWriteEnabled() || !ContentRulesManager.isWriteLoaded()) {
                XLog.i(TAG, "写入规则未启用，弹窗询问: " + pkgName);
                return true;
            }
            // 规则已启用，但当前包不在任何规则的适用域内 → 放行
            if (!ContentRulesManager.hasEnabledWriteRuleForPackage(pkgName)) {
                XLog.i(TAG, "写入规则已启用但当前包不在适用域，放行: " + pkgName);
                return false;
            }
            // 规则已启用且当前包在适用域内 → 匹配命中才弹窗，未命中放行
            String matchedRule = ContentRulesManager.matchesWriteContent(pkgName, fullContent);
            if (matchedRule != null) {
                XLog.i(TAG, "写入内容命中规则 [" + matchedRule + "]，弹窗");
                return true;
            }
            XLog.i(TAG, "写入内容未命中规则，放行");
            return false;
        }

        /** 获取写入操作调用者的包名 */
        private String getCallingWritePackageName(XposedInterface.Chain chain) {
            try {
                // 优先从 ClipData 参数中提取 callingPackage
                // ClipboardService.setPrimaryClip(ClipData, String, String, int, int)
                // 第二个参数通常是 callingPackage
                List<Object> args = chain.getArgs();
                if (args.size() > 1 && args.get(1) instanceof String callingPkg) {
                    if (!callingPkg.isEmpty()) {
                        return callingPkg;
                    }
                }
                // 回退到 getPackagesForUid
                int uid = Binder.getCallingUid();
                if (uid <= 0) return null;
                Context ctx = getSystemServerContext();
                if (ctx == null) return null;
                String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) return pkgs[0];
            } catch (Throwable e) {
                XLog.w(TAG, "获取写入调用包名失败: " + e.getMessage());
            }
            return null;
        }

        /** 显示写入拦截弹窗并同步等待用户决策（CountDownLatch 阻塞） */
        private int askWriteUser(Context ctx, String pkgName, String preview) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger resultHolder = new AtomicInteger(PermissionDecision.PERMISSION_BLOCK);
            boolean owner = true;
            synchronized (sWriteLatchLock) {
                CountDownLatch existingLatch = sWriteDecisionLatches.get(pkgName);
                AtomicInteger existingResult = sWriteDecisionResults.get(pkgName);
                if (existingLatch != null && existingResult != null) {
                    latch = existingLatch;
                    resultHolder = existingResult;
                    owner = false;
                    sWriteDecisionWaiters.merge(pkgName, 1, Integer::sum);
                    XLog.i(TAG, "写入弹窗已有等待请求，沿用当前弹窗结果: " + pkgName);
                } else {
                    sWriteDecisionLatches.put(pkgName, latch);
                    sWriteDecisionResults.put(pkgName, resultHolder);
                    sWriteDecisionWaiters.put(pkgName, 1);
                }
            }

            if (owner) {
                try {
                    InlineDialogManager dm = InlineDialogManager.getInstance(ctx);
                    dm.showWriteDialogAsync(pkgName, preview);
                } catch (Throwable e) {
                    XLog.e(TAG, "写入弹窗异常: " + e.getMessage());
                    synchronized (sWriteLatchLock) {
                        sWriteDecisionLatches.remove(pkgName);
                        sWriteDecisionResults.remove(pkgName);
                        sWriteDecisionWaiters.remove(pkgName);
                    }
                    return PermissionDecision.PERMISSION_IGNORE;
                }
            }

            boolean responded = false;
            try {
                responded = latch.await(DIALOG_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int decision = resultHolder.get();
            if (!responded) {
                XLog.i(TAG, "写入弹窗超时（5s），自动拒绝: " + pkgName);
                decision = PermissionDecision.PERMISSION_BLOCK;
            }

            synchronized (sWriteLatchLock) {
                Integer curW = sWriteDecisionWaiters.get(pkgName);
                int waiters = (curW != null ? curW : 1) - 1;
                if (waiters <= 0) {
                    sWriteDecisionLatches.remove(pkgName);
                    sWriteDecisionResults.remove(pkgName);
                    sWriteDecisionWaiters.remove(pkgName);
                } else {
                    sWriteDecisionWaiters.put(pkgName, waiters);
                }
            }
            synchronized (sWriteDebounceLock) {
                sLastWriteUserDecision.put(pkgName, decision);
                sLastWriteDecisionTime.put(pkgName, System.currentTimeMillis());
            }
            return decision;
        }
    }

    // ══════════════════════════════════════════════════════
    //  读取拦截块（GetPrimaryClipHook）
    // ══════════════════════════════════════════════════════

    /** 读取拦截 Hook：拦截 ClipboardService.getPrimaryClip 调用 */
    private static class GetPrimaryClipHook implements XposedInterface.Hooker {

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            // 先调用原始方法获取结果
            Object result = chain.proceed();
            
            if (Boolean.TRUE.equals(sIsReadBlockingOperation.get())) return result;
            sIsReadBlockingOperation.set(true);
            try {
                cleanupExpiredDebounceEntries();
                if (!ensureReadInitialized()) {
                    XLog.w(TAG, "读取配置未初始化，保守放行");
                    return result;
                }

                String pkgName = getCallingReadPackageName(chain);
                if (pkgName == null || pkgName.isEmpty()) {
                    XLog.w(TAG, "无法获取读取调用者包名，保守放行");
                    return result;
                }
                if (isCorePackage(pkgName)) return result;
                if (PermissionCache.isReadIgnored(pkgName)) return result;

                Context ctx = getSystemServerContext();
                if (ctx == null) {
                    XLog.e(TAG, "读取端获取 Context 失败，保守放行: " + pkgName);
                    return result;
                }

                ClipData clipData = (result instanceof ClipData) ? (ClipData) result : null;
                String content = extractClipContent(clipData);
                String preview = trimPreview(content);

                // 规则完全未启用 → 直接拦截（基础行为）
                if (!ContentRulesManager.isReadEnabled()) {
                    XLog.i(TAG, "读取规则未启用，直接拦截: " + pkgName);
                    rejectRead(ctx, pkgName, preview,
                            PermissionCache.isReadBlockedToastEnabled());
                    return null;
                }
                // 规则已启用，但当前包不在任何规则的适用域内 → 放行
                if (!ContentRulesManager.hasEnabledReadRuleForPackage(pkgName)) {
                    XLog.i(TAG, "读取规则已启用但当前包不在适用域，放行: " + pkgName);
                    return result;
                }

                // 规则已启用 → 匹配命中弹窗询问，未命中放行
                String matchedRule = ContentRulesManager.matchesReadContent(pkgName, content);
                if (matchedRule == null) {
                    XLog.i(TAG, "读取内容未命中规则，放行: " + pkgName);
                    return result;
                }
                XLog.i(TAG, "读取内容命中规则 [" + matchedRule + "]，弹窗");

                ReadDecisionResult decisionResult = getReadDecision(ctx, pkgName, preview, matchedRule);
                int decision = decisionResult.decision;
                if (decision == PermissionDecision.PERMISSION_IGNORE) {
                    writeReadLog(pkgName, "允许读取", preview);
                    return result;
                }
                if (decision == PermissionDecision.PERMISSION_CLEAR && decisionResult.shouldClearClipboard) {
                    clearClipboard(ctx);
                    writeReadLog(pkgName, "拒绝读取并清空剪贴板", preview);
                } else {
                    writeReadLog(pkgName, "拒绝读取", preview);
                }
                return null;
            } finally {
                sIsReadBlockingOperation.remove();
            }
        }

        /** 拒绝读取操作，可选显示 Toast 提示 */
        private void rejectRead(Context ctx, String pkgName,
                String preview, boolean showToast) {
            writeReadLog(pkgName, "拒绝读取", preview);
            if (showToast) showReadBlockedToast(ctx, pkgName);
        }

        /** 获取读取决策（先查防抖缓存，无缓存则弹窗等待用户选择） */
        private ReadDecisionResult getReadDecision(Context ctx, String pkgName,
                String preview, String matchedRule) {
            // 1. 检查防抖缓存
            synchronized (sReadDebounceLock) {
                long now = System.currentTimeMillis();
                Long lastTime = sLastReadDecisionTime.get(pkgName);
                Integer lastDecision = sLastReadUserDecision.get(pkgName);
                if (lastTime != null && now - lastTime < READ_DIALOG_DEBOUNCE_MS && lastDecision != null) {
                    boolean shouldClear = false;
                    if (lastDecision == PermissionDecision.PERMISSION_CLEAR
                            && !Boolean.TRUE.equals(sLastReadClearConsumed.get(pkgName))) {
                        shouldClear = true;
                        sLastReadClearConsumed.put(pkgName, true);
                    }
                    return new ReadDecisionResult(lastDecision, shouldClear);
                }
            }

            // 2. 无缓存 → 显示弹窗并同步等待用户决策（阻塞 Binder 线程，最多 5s）
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger resultHolder = new AtomicInteger(PermissionDecision.PERMISSION_BLOCK);
            boolean owner = true;
            synchronized (sReadLatchLock) {
                CountDownLatch existingLatch = sReadDecisionLatches.get(pkgName);
                AtomicInteger existingResult = sReadDecisionResults.get(pkgName);
                if (existingLatch != null && existingResult != null) {
                    latch = existingLatch;
                    resultHolder = existingResult;
                    owner = false;
                    sReadDecisionWaiters.merge(pkgName, 1, Integer::sum);
                    XLog.i(TAG, "读取弹窗已有等待请求，沿用当前弹窗结果: " + pkgName);
                } else {
                    sReadDecisionLatches.put(pkgName, latch);
                    sReadDecisionResults.put(pkgName, resultHolder);
                    sReadDecisionWaiters.put(pkgName, 1);
                }
            }

            if (owner) {
                try {
                    InlineDialogManager dm = InlineDialogManager.getInstance(ctx);
                    dm.showReadDialogAsync(pkgName, preview, matchedRule);
                } catch (Throwable e) {
                    XLog.e(TAG, "读取弹窗异常: " + e.getMessage());
                    synchronized (sReadLatchLock) {
                        sReadDecisionLatches.remove(pkgName);
                        sReadDecisionResults.remove(pkgName);
                        sReadDecisionWaiters.remove(pkgName);
                    }
                    return new ReadDecisionResult(PermissionDecision.PERMISSION_IGNORE, false);
                }
            }

            // 等待用户决策或超时（5s）
            boolean responded = false;
            try {
                responded = latch.await(DIALOG_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int decision = resultHolder.get();
            if (!responded) {
                XLog.i(TAG, "读取弹窗超时（5s），自动拒绝: " + pkgName);
                decision = PermissionDecision.PERMISSION_BLOCK;
            }

            // 清理 latch 引用
            synchronized (sReadLatchLock) {
                Integer curR = sReadDecisionWaiters.get(pkgName);
                int waiters = (curR != null ? curR : 1) - 1;
                if (waiters <= 0) {
                    sReadDecisionLatches.remove(pkgName);
                    sReadDecisionResults.remove(pkgName);
                    sReadDecisionWaiters.remove(pkgName);
                } else {
                    sReadDecisionWaiters.put(pkgName, waiters);
                }
            }

            // 3. 写入防抖缓存
            boolean shouldClear = decision == PermissionDecision.PERMISSION_CLEAR;
            synchronized (sReadDebounceLock) {
                sLastReadUserDecision.put(pkgName, decision);
                sLastReadDecisionTime.put(pkgName, System.currentTimeMillis());
                sLastReadClearConsumed.put(pkgName, shouldClear);
            }

            return new ReadDecisionResult(decision, shouldClear);
        }

        /** 获取读取操作调用者的包名 */
        private String getCallingReadPackageName(XposedInterface.Chain chain) {
            try {
                // 优先从方法参数中提取 callingPackage
                // ClipboardService.getPrimaryClip(String, String, int, int)
                // 第一个参数通常是 callingPackage
                List<Object> args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof String callingPkg) {
                    if (!callingPkg.isEmpty()) {
                        return callingPkg;
                    }
                }
                // 回退到 getPackagesForUid
                int uid = Binder.getCallingUid();
                if (uid <= 0) return null;
                Context ctx = getSystemServerContext();
                if (ctx == null) return null;
                String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) return pkgs[0];
            } catch (Throwable e) {
                XLog.w(TAG, "获取读取调用包名失败: " + e.getMessage());
            }
            return null;
        }

        /** 从 ClipData 提取文本内容 */
        private String extractClipContent(ClipData clipData) {
            if (clipData == null) return "";
            try {
                if (clipData.getItemCount() > 0) {
                    ClipData.Item item = clipData.getItemAt(0);
                    CharSequence text = item.getText();
                    if (text != null && !text.toString().isEmpty()) return text.toString().trim();
                    String html = item.getHtmlText();
                    if (html != null && !html.isEmpty()) return html.replaceAll("<[^>]+>", "").trim();
                    if (item.getUri() != null) return "[图片/文件]";
                }
            } catch (Throwable e) {
                XLog.w(TAG, "提取读取内容失败: " + e.getMessage());
            }
            return "(非文本内容)";
        }

        /** 截取预览文本（最多 100 字符） */
        private String trimPreview(String text) {
            if (text == null || text.isEmpty()) return "";
            return text.length() > 100 ? text.substring(0, 100) + "…" : text;
        }

        /** 清空剪贴板内容 */
        private void clearClipboard(Context ctx) {
            sIsClearOperation.set(true);
            try {
                long id = Binder.clearCallingIdentity();
                try {
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("", ""));
                } finally {
                    Binder.restoreCallingIdentity(id);
                }
            } catch (Throwable e) {
                XLog.e(TAG, "清空剪贴板失败: " + e.getMessage());
            } finally {
                sIsClearOperation.remove();
            }
        }

        /** 显示读取拦截 Toast 提示（带防抖） */
        private void showReadBlockedToast(Context ctx, String pkgName) {
            try {
                long now = System.currentTimeMillis();
                synchronized (sReadDebounceLock) {
                    Long last = sLastReadToastTime.get(pkgName);
                    if (last != null && now - last < READ_TOAST_DEBOUNCE_MS) return;
                    sLastReadToastTime.put(pkgName, now);
                }
                String appName = getAppName(ctx, pkgName);
                getToastHandler().post(() ->
                        Toast.makeText(ctx, "已拒绝 " + appName + " 读取剪贴板", Toast.LENGTH_SHORT).show());
            } catch (Throwable e) {
                XLog.w(TAG, "读取拒绝 Toast 显示失败: " + e.getMessage());
            }
        }

        /** 获取应用显示名称 */
        private String getAppName(Context ctx, String pkgName) {
            long id = Binder.clearCallingIdentity();
            try {
                PackageManager pm = ctx.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(pkgName, 0);
                CharSequence label = pm.getApplicationLabel(info);
                if (!label.toString().isEmpty()) return label.toString();
            } catch (Throwable ignored) {
            } finally {
                Binder.restoreCallingIdentity(id);
            }
            return pkgName;
        }
    }

    // ══════════════════════════════════════════════════════
    //  读取决策结果
    // ══════════════════════════════════════════════════════

    /** 读取决策结果：包含决策值和是否需要清空剪贴板 */
    @SuppressWarnings("ClassCanBeRecord") // minSdk 30，record 需要 API 33+
    private static class ReadDecisionResult {
        final int decision;
        final boolean shouldClearClipboard;
        
        ReadDecisionResult(int decision, boolean shouldClearClipboard) {
            this.decision = decision;
            this.shouldClearClipboard = shouldClearClipboard;
        }
    }

    // ══════════════════════════════════════════════════════
    //  公共辅助方法
    // ══════════════════════════════════════════════════════

    /**
     * 懒加载 Toast Handler。
     * 在 App 进程主线程首次需要时创建，避免 system_server <clinit> 时 Looper 为 null 的崩溃。
     */
    private static Handler getToastHandler() {
        if (sToastHandler == null) {
            synchronized (ClipboardHook.class) {
                if (sToastHandler == null) {
                    Looper looper = Looper.getMainLooper();
                    if (looper == null) {
                        throw new IllegalStateException("Toast Handler 初始化失败：主线程 Looper 未就绪");
                    }
                    sToastHandler = new Handler(looper);
                }
            }
        }
        return sToastHandler;
    }

    /**
     * 获取 system_server 的 Context。
     *
     * <p>参考 Thanox {@code ThanoxHookImpl.installHooks}：
     * system_server 进程中应使用 {@code ActivityThread.getSystemContext()} 而非
     * {@code getApplication()}，前者是系统级 Context，后者在 system_server 中
     * 可能返回 null 或非系统 Context，导致 ContentProvider 查询失败。
     *
     * <p>不使用永久失败标记：早期调用时系统尚未就绪，重试是合理的。
     */
    @SuppressLint("PrivateApi")
    private static Context getSystemServerContext() {
        if (sSystemServerContext != null) return sSystemServerContext;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentThread = atClass.getMethod("currentActivityThread");
            Object at = currentThread.invoke(null);
            if (at != null) {
                // 优先用 getSystemContext()（Thanox 方式），回退 getApplication()
                Context ctx = null;
                try {
                    Method getSystemContext = atClass.getMethod("getSystemContext");
                    ctx = (Context) getSystemContext.invoke(at);
                } catch (Throwable e) {
                    XLog.w(TAG, "[Context] getSystemContext 调用失败: " + e.getMessage());
                }
                if (ctx == null) {
                    try {
                        Method getApplication = atClass.getMethod("getApplication");
                        ctx = (Context) getApplication.invoke(at);
                    } catch (Throwable e) {
                        XLog.w(TAG, "[Context] getApplication 调用失败: " + e.getMessage());
                    }
                }
                if (ctx != null) {
                    sSystemServerContext = ctx;
                    XLog.i(TAG, "[Context] 获取 system_server Context 成功: " + ctx.getClass().getSimpleName());
                    return ctx;
                }
            }
        } catch (Throwable e) {
            XLog.w(TAG, "[Context] getSystemServerContext 失败: " + e.getMessage());
        }
        return null;
    }

    /** 从 ClipData 提取完整文本内容（用于规则匹配） */
    private static String extractClipFullContent(Object arg) {
        if (arg == null) return "";
        try {
            ClipData data = (ClipData) arg;
            if (data.getItemCount() > 0) {
                ClipData.Item item = data.getItemAt(0);
                CharSequence text = item.getText();
                if (text != null && !text.toString().isEmpty()) {
                    return text.toString().trim();
                }
                String html = item.getHtmlText();
                if (html != null && !html.isEmpty()) {
                    return html.replaceAll("<[^>]+>", "").trim();
                }
            }
        } catch (Throwable e) {
            XLog.w(TAG, "提取写入内容失败: " + e.getMessage());
        }
        return "";
    }


    /** 输出写入操作日志（带脱敏） */
    private static void writeLog(String pkgName, String action, String content) {
        if (pkgName == null || pkgName.isEmpty() || "android".equals(pkgName) || "unknown".equals(pkgName))
            return;
        if (!PermissionCache.isLsposedLogEnabled()) return;
        XLog.i(TAG, "[Write][" + pkgName + "] " + action + ": " + XLog.maskClipboardContent(content));
    }

    /** 输出读取操作日志（带脱敏） */
    private static void writeReadLog(String pkgName, String action, String content) {
        if (pkgName == null || pkgName.isEmpty() || "android".equals(pkgName) || "unknown".equals(pkgName))
            return;
        if (!PermissionCache.isLsposedLogEnabled()) return;
        XLog.i(TAG, "[Read][" + pkgName + "] " + action + ": " + XLog.maskClipboardContent(content));
    }

    /** 检查是否为核心系统包（白名单，永不拦截） */
    private static boolean isCorePackage(String pkgName) {
        if (pkgName == null) return true;
        HashSet<String> corePackages = getCorePackages();
        if (corePackages.contains(pkgName)) return true;
        for (String core : corePackages) {
            if (pkgName.startsWith(core + ".")) return true;
        }
        return false;
    }

    private static final Object sCorePackagesLock = new Object();
    private static volatile HashSet<String> sCorePackages;

    /** 获取核心包白名单（带缓存，失败时也缓存基础白名单避免重复锁竞争） */
    private static HashSet<String> getCorePackages() {
        HashSet<String> cached = sCorePackages;
        if (cached != null) return cached;
        synchronized (sCorePackagesLock) {
            if (sCorePackages != null) return sCorePackages;
            HashSet<String> packages = new HashSet<>();
            packages.add("android");
            try {
                Context ctx = getModuleContext();
                if (ctx != null) {
                    String[] configured = ctx.getResources()
                            .getStringArray(R.array.global_whitelist_packages);
                    for (String pkg : configured) {
                        if (pkg != null && !pkg.trim().isEmpty()) {
                            packages.add(pkg.trim());
                        }
                    }
                }
            } catch (Throwable e) {
                XLog.w(TAG, "读取核心白名单资源失败: " + e.getMessage());
            }
            // 无论资源是否加载成功都缓存，避免高频调用时重复锁竞争
            sCorePackages = packages;
            return packages;
        }
    }

    /** 获取模块资源 Context（用于读取白名单配置） */
    private static Context getModuleContext() {
        Context systemContext = getSystemServerContext();
        if (systemContext == null) return null;
        long identity = Binder.clearCallingIdentity();
        try {
            return systemContext.createPackageContext(MODULE_PKG,
                    Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable e) {
            XLog.w(TAG, "创建模块资源 Context 失败: " + e.getMessage());
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // ──────────────────── 弹窗结果回写（InlineDialogManager → 防抖缓存） ────────────────────

    /**
     * 写入弹窗用户决策回写防抖缓存。
     * InlineDialogManager 在用户点击按钮后异步调用，下一次同一应用的剪贴板操作将在防抖窗口内使用此决策。
     */
    static void cacheWriteDecision(String pkgName, int decision) {
        if (pkgName == null) return;
        synchronized (sWriteDebounceLock) {
            sLastWriteUserDecision.put(pkgName, decision);
            sLastWriteDecisionTime.put(pkgName, System.currentTimeMillis());
        }
    }

    /** 写入弹窗同步通知：用户选择后唤醒当前 setPrimaryClip 调用。 */
    static void notifyWriteDecision(String pkgName, int decision) {
        if (pkgName == null) return;
        synchronized (sWriteLatchLock) {
            AtomicInteger holder = sWriteDecisionResults.get(pkgName);
            CountDownLatch latch = sWriteDecisionLatches.get(pkgName);
            if (holder != null) {
                holder.set(decision);
            }
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    /**
     * 读取弹窗用户决策回写防抖缓存。
     */
    static void cacheReadDecision(String pkgName, int decision) {
        if (pkgName == null) return;
        synchronized (sReadDebounceLock) {
            sLastReadUserDecision.put(pkgName, decision);
            sLastReadDecisionTime.put(pkgName, System.currentTimeMillis());
            sLastReadClearConsumed.put(pkgName, decision != PermissionDecision.PERMISSION_CLEAR);
        }
    }

    /**
     * 读取弹窗同步通知：用户做出选择后，CountDownLatch.countDown() 解除 Binder 线程阻塞。
     * InlineDialogManager.onResult() 在 read 操作用户点击时调用。
     */
    static void notifyReadDecision(String pkgName, int decision) {
        if (pkgName == null) return;
        synchronized (sReadLatchLock) {
            AtomicInteger holder = sReadDecisionResults.get(pkgName);
            CountDownLatch latch = sReadDecisionLatches.get(pkgName);
            if (holder != null) {
                holder.set(decision);
            }
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  onTransact Hook（用于模块激活状态查询）
    // ══════════════════════════════════════════════════════

    /** onTransact Hook：拦截自定义事务码返回模块激活状态 JSON */
    private static class OnTransactHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            if (!args.isEmpty()) {
                int code = (int) args.get(0);
                if (code == TRANSACTION_CBGUARD_STATUS) {
                    String json = sModuleStatusJson;
                    android.os.Parcel reply = (android.os.Parcel) args.get(2);
                    reply.writeNoException();
                    reply.writeString(json != null ? json : "{}");
                    return true; // 拦截，不调原 onTransact
                }
            }
            return chain.proceed();
        }
    }

    // ══════════════════════════════════════════════════════
    //  防抖缓存过期清理
    // ══════════════════════════════════════════════════════

    /**
     * 清理过期的防抖缓存条目，防止 system_server 长期运行后内存泄漏。
     * 超过 10 分钟未访问的条目将被移除。
     *
     * 由每次写入/读取拦截时惰性触发，无需定时器。
     */
    private static void cleanupExpiredDebounceEntries() {
        long now = System.currentTimeMillis();
        if (now - sLastDebounceCleanupTime < DEBOUNCE_CLEANUP_INTERVAL_MS) return;
        sLastDebounceCleanupTime = now;

        int removed = 0;
        // 写入端
        synchronized (sWriteDebounceLock) {
            removed += cleanupMapByAge(sLastWriteDecisionTime, sLastWriteUserDecision, now);
        }
        // 读取端
        synchronized (sReadDebounceLock) {
            removed += cleanupMapByAge(sLastReadDecisionTime, sLastReadUserDecision, now);
            removed += cleanupMapByAge(sLastReadDecisionTime, sLastReadClearConsumed, now);
            removed += cleanupMapByAge(sLastReadToastTime, null, now);
        }
        if (removed > 0) {
            XLog.d(TAG, "防抖缓存清理: 移除 " + removed + " 条过期条目");
        }
    }

    /** 清理时间 Map 中超过阈值的条目，并同步清理关联 Map */
    private static <V> int cleanupMapByAge(Map<String, Long> timeMap,
            Map<String, V> associatedMap, long now) {
        int removed = 0;
        java.util.Iterator<Map.Entry<String, Long>> it = timeMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > DEBOUNCE_CLEANUP_THRESHOLD_MS) {
                it.remove();
                if (associatedMap != null) {
                    associatedMap.remove(entry.getKey());
                }
                removed++;
            }
        }
        return removed;
    }
}
