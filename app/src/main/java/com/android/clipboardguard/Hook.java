package com.android.clipboardguard;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ClipboardGuard - Xposed模块核心Hook类
 * 拦截 ClipboardService.setPrimaryClip 实现剪贴板权限控制
 * 只 Hook system_server 进程（android 包名），不 Hook App 进程
 *
 * 权限模型：
 *   PERMISSION_BLOCK  = 0：拦截，每次写剪贴板弹窗询问
 *   PERMISSION_IGNORE = 1：放行，直接通过
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "ClipboardGuard";
    private static final String MODULE_PKG = "com.android.clipboardguard";
    private static final long DEBOUNCE_MS = 1500;
    private static final long INIT_WAIT_MS = 5000; // 等待初始化超时

    // 防抖：记录上次用户做出决策的时间 和 决策结果
    private static final Map<String, Long>    sLastDecisionTime = new HashMap<>();
    private static final Map<String, Integer> sLastUserDecision = new HashMap<>();
    private static final Object sDebouncelock = new Object();

    // 防止 afterHookedMethod 清空剪贴板时递归触发 hook
    private static final ThreadLocal<Boolean> sInAfterHook = ThreadLocal.withInitial(() -> false);

    // 日志开关
    private static final String PREF_CLIP_PREFS = "clipboardguard_prefs";
    private static final String KEY_ENABLE_LOG   = "enable_log";

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (MODULE_PKG.equals(lpparam.packageName)) {
            hookSelfForActiveStatus(lpparam);
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            hookClipboardService(lpparam);
        }
    }

    /** Hook 本模块自身，让 isModuleActive() / getXposedApiVersion() 返回正确值 */
    private void hookSelfForActiveStatus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(MODULE_PKG + ".MainActivity",
                    lpparam.classLoader, "isModuleActive",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            p.setResult(true);
                        }
                    });
            XposedHelpers.findAndHookMethod(MODULE_PKG + ".MainActivity",
                    lpparam.classLoader, "getXposedApiVersion",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            p.setResult(XposedBridge.getXposedVersion());
                        }
                    });
            Log.i(TAG, "自身Hook成功");
        } catch (Throwable e) {
            Log.e(TAG, "自身Hook失败: " + e.getMessage());
        }
    }

    /** 依次尝试多个候选类名，找到 setPrimaryClip 就 Hook */
    private void hookClipboardService(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] candidates = {
            "com.android.server.clipboard.ClipboardService$ClipboardImpl",
            "com.android.server.clipboard.ClipboardService$BinderService",
            "com.android.server.clipboard.ClipboardService",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("setPrimaryClip".equals(m.getName())) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new ClipboardServiceHook());
                        Log.i(TAG, "Hook成功: " + className);

                        // Hook 成功后，延迟 10s 执行开机初始化
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            scheduleBootInit(0); // retryCount = 0
                        }, 10000);

                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        Log.e(TAG, "Hook失败：未找到 setPrimaryClip");
    }

    /**
     * 开机初始化调度器
     * 开机后 10s 执行，失败重试一次，再失败放弃
     */
    private static void scheduleBootInit(int retryCount) {
        if (PermissionCache.isLoaded()) {
            Log.i(TAG, "scheduleBootInit: 已初始化，跳过");
            return;
        }

        Log.i(TAG, "scheduleBootInit: 第 " + (retryCount + 1) + " 次尝试...");
        Context ctx = getSystemServerContextStatic();
        if (ctx != null) {
            PermissionCache.loadIgnoreSet(ctx);
            PermissionCache.registerRefreshReceiver(ctx);
            if (PermissionCache.isLoaded()) {
                Log.i(TAG, "开机初始化成功: blockSet.size=" + PermissionCache.getIgnoreSetSize());
                return;
            }
        }

        // 失败，重试一次
        if (retryCount < 1) {
            Log.w(TAG, "开机初始化失败，10s 后重试...");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                scheduleBootInit(retryCount + 1);
            }, 10000);
        } else {
            Log.e(TAG, "开机初始化失败，放弃");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static class ClipboardServiceHook extends XC_MethodHook {

        /**
         * 在 before/after 之间传递：是否应该拦截（true=拦截，false=放行）
         * 只有 beforeHookedMethod 写，afterHookedMethod 读后清理。
         */
        private static final ThreadLocal<Boolean> sShouldBlock = new ThreadLocal<>();

        /**
         * 防止嵌套调用：当 ClipboardService 内部触发广播回调时，
         * 回调中可能再次调用 setPrimaryClip，此时 RemoteCallbackList
         * 不允许嵌套 beginBroadcast()，会导致 system_server 崩溃。
         */
        private static final ThreadLocal<Boolean> sInClipboardOp = ThreadLocal.withInitial(() -> false);

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            // ── 防止嵌套调用导致 RemoteCallbackList 崩溃 ──
            if (Boolean.TRUE.equals(sInClipboardOp.get())) {
                return; // 嵌套调用，直接放行避免崩溃
            }
            sInClipboardOp.set(true);

            // 防止递归（afterHookedMethod 调内部方法时会再次触发）
            if (Boolean.TRUE.equals(sInAfterHook.get())) return;

            // ── 检查是否需要初始化（开机时可能还未初始化）──
            // 等待初始化完成，最多重试 3 次（每次 5s）
            boolean initialized = ensureInitialized();
            if (!initialized) {
                // 初始化失败 → 保守拦截但不弹窗（避免弹窗失败导致 ANR）
                Log.w(TAG, "PermissionCache 初始化失败，保守拦截（不弹窗）");
                return; // 直接返回，不阻断剪贴板（保守策略）
            }

            // ── 获取包名，带重试机制（开机时 ActivityThread 可能未就绪）──
            String pkgName = null;
            for (int retry = 0; retry < 5; retry++) {
                pkgName = getCallingPackageNameWithRetry();
                if (pkgName != null && !pkgName.isEmpty()) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            }

            // ── 获取 Context，带重试机制（弹窗必需）──
            Context ctx = null;
            for (int retry = 0; retry < 5; retry++) {
                ctx = getSystemServerContextWithRetry();
                if (ctx != null) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            }

            // ══════════════════════════════════════════════════════════════
            // 双重兜底：包名获取失败 或 Context 获取失败 → 保守拦截（不清空）
            // 这样做确保开机期间初始化未完成时，至少能拦截而非放行普通 App
            // ══════════════════════════════════════════════════════════════
            boolean packageUnknown = (pkgName == null || pkgName.isEmpty());
            if (packageUnknown) {
                // 包名获取失败 → 检查是否为核心包（可能返回 null 但不能确定）
                // 核心包放行，其他保守拦截但不弹窗（避免弹窗失败导致 ANR）
                Log.w(TAG, "无法获取调用者包名，保守拦截（不弹窗）");
                if (ctx != null) {
                    Object clipArg = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                    writeLog(ctx, "unknown", "拦截(包名获取失败)", extractPreview(clipArg));
                }
                return; // 直接返回，不阻断剪贴板（保守策略：只记录不清空）
            }

            if (isSystemCorePackage(pkgName)) return;
            if (ctx == null) {
                // Context 获取失败 → 保守拦截但不弹窗
                Log.e(TAG, "获取Context失败，保守拦截（不弹窗）: " + pkgName);
                return; // 直接返回，不阻断剪贴板
            }

            Object clipArg = (param.args != null && param.args.length > 0) ? param.args[0] : null;
            String preview = extractPreview(clipArg);

            // ── 步骤1：包名判断（O(1) HashSet 查表）──
            if (PermissionCache.isIgnored(pkgName)) {
                // 已设置为放行（包名在白名单）
                Log.i(TAG, "权限查询: " + pkgName + " -> 放行(已保存)");
                writeLog(ctx, pkgName, "放行", preview);
                return; // 直接放行
            }

            // 包名在拦截列表 → 先做广告规则正则匹配
            Log.i(TAG, "权限查询: " + pkgName + " -> 包名在拦截列表，检查内容...");

            // ── 步骤2：正则内容过滤（广告拦截）──
            // 规则为空 = 用户未配置规则 → 直接弹窗（兜底逻辑，让用户感知到该 App 在写剪贴板）
            // 规则非空 = 用户配置了规则 → 只有命中规则的内容才弹窗，正常内容直接放行
            String matchedRule = ContentRulesManager.matchesAdContent(preview);
            if (ContentRulesManager.isLoaded() && !ContentRulesManager.getRulesEmpty() && matchedRule == null) {
                // 规则已加载、规则非空、内容未命中 → 正常内容，直接放行
                Log.i(TAG, "内容未命中广告规则，放行: " + pkgName);
                writeLog(ctx, pkgName, "放行(内容过滤)", preview);
                return;
            }

            // 规则为空或内容命中 → 弹窗询问
            if (matchedRule != null) {
                Log.i(TAG, "内容命中广告规则 [" + matchedRule + "]，弹窗: " + pkgName);
            } else {
                Log.i(TAG, "正则规则未配置，直接弹窗: " + pkgName);
            }

            // ── 检查防抖（允许和拒绝都防抖，1.5s 内复用上次决策）──
            int decision;
            synchronized (sDebouncelock) {
                long now = System.currentTimeMillis();
                Long lastTime = sLastDecisionTime.get(pkgName);
                Integer last = sLastUserDecision.get(pkgName);
                if (lastTime != null && now - lastTime < DEBOUNCE_MS && last != null) {
                    // 防抖期内 → 沿用上次决策
                    decision = last;
                    Log.i(TAG, "防抖沿用上次选择(" + (decision == PermissionStorage.PERMISSION_IGNORE ? "允许" : "拒绝") + "): " + pkgName);
                } else {
                    // 防抖期外或无历史 → 需要弹窗
                    decision = -1;
                }
            }

            if (decision < 0) {
                // 弹窗询问
                decision = askUser(ctx, pkgName, preview);
                // 弹窗结束后才记录防抖（用真实决策）
                synchronized (sDebouncelock) {
                    sLastUserDecision.put(pkgName, decision);
                    sLastDecisionTime.put(pkgName, System.currentTimeMillis());
                }
            }

            handleDecision(ctx, decision, pkgName, preview);

            if (decision != PermissionStorage.PERMISSION_IGNORE) {
                // 阻断原方法执行
                param.setResult(null);
                // 通知 afterHookedMethod 执行真正的剪贴板清空
                sShouldBlock.set(true);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
                if (Boolean.TRUE.equals(sInAfterHook.get())) return;

                Boolean shouldBlock = sShouldBlock.get();
                sShouldBlock.remove();

                if (!Boolean.TRUE.equals(shouldBlock)) return;

                // 用户拒绝：通过内部方法将剪贴板置空
                sInAfterHook.set(true);
                try {
                    Object service = param.thisObject;
                    // 尝试调用 setPrimaryClipInternal(null, "android", 0)
                    Method internal = findMethod(service.getClass(), "setPrimaryClipInternal",
                            ClipData.class, String.class, int.class);
                    if (internal != null) {
                        internal.invoke(service, null, "clipboardguard_blocked", 0);
                        Log.i(TAG, "已通过内部方法清空剪贴板");
                    } else {
                        Log.w(TAG, "setPrimaryClipInternal 不存在，剪贴板已被前置阻断");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "清空剪贴板失败（可忽略，写入已被阻断）: " + e.getMessage());
                } finally {
                    sInAfterHook.set(false);
                }
            } finally {
                // 清理嵌套调用标记（必须，确保每个 before 都有对应的清理）
                sInClipboardOp.remove();
            }
        }

        // ──────────────────────────── 辅助方法 ────────────────────────────

        /** 在类及其父类中查找方法，找不到返回 null */
        private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
            while (cls != null) {
                try {
                    Method m = cls.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
                cls = cls.getSuperclass();
            }
            return null;
        }

        /**
         * 获取调用者包名（带重试机制）
         * 开机时 ActivityThread 可能未就绪，需要重试
         */
        private String getCallingPackageNameWithRetry() {
            try {
                int uid = Binder.getCallingUid();
                if (uid <= 0) return null;

                Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
                if (at == null) return null;

                Context ctx = (Context) XposedHelpers.callMethod(at, "getApplication");
                if (ctx == null) return null;

                String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) {
                    return pkgs[0];
                }
            } catch (Throwable e) {
                Log.d(TAG, "getCallingPackageNameWithRetry 失败: " + e.getMessage());
            }
            return null;
        }

        /**
         * 获取 system_server Context（带重试机制）
         * 开机时 ActivityThread.getApplication() 可能返回 null，需要重试
         */
        private Context getSystemServerContextWithRetry() {
            try {
                Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
                if (at == null) return null;
                return (Context) XposedHelpers.callMethod(at, "getApplication");
            } catch (Throwable e) {
                Log.d(TAG, "getSystemServerContextWithRetry 失败: " + e.getMessage());
                return null;
            }
        }

        private String extractPreview(Object arg) {
            if (arg == null) return "";
            try {
                ClipData data = (ClipData) arg;
                if (data.getItemCount() > 0) {
                    ClipData.Item item = data.getItemAt(0);
                    CharSequence text = item.getText();
                    if (text != null && text.length() > 0) {
                        String s = text.toString().trim();
                        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
                    }
                    String html = item.getHtmlText();
                    if (html != null && !html.isEmpty()) {
                        String s = html.replaceAll("<[^>]+>", "").trim();
                        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
                    }
                    if (item.getUri() != null) return "[图片/文件]";
                }
            } catch (Throwable ignored) {}
            return "(非文本内容)";
        }

        /** 弹窗询问用户，阻塞直到结果返回或超时（默认 BLOCK）
         *  使用 InlineDialogManager 在 system_server 内直接弹窗
         */
        private int askUser(Context ctx, String pkgName, String preview) {
            AtomicInteger result = new AtomicInteger(PermissionStorage.PERMISSION_BLOCK);

            try {
                // 获取 InlineDialogManager 单例（在 system_server 内直接弹窗）
                InlineDialogManager dialogManager = InlineDialogManager.getInstance(ctx);

                // 显示弹窗并等待结果
                boolean shown = dialogManager.showDialog(pkgName, preview, result);

                if (!shown) {
                    Log.w(TAG, "弹窗显示失败，默认拒绝: " + pkgName);
                    return PermissionStorage.PERMISSION_BLOCK;
                }
            } catch (Throwable e) {
                Log.e(TAG, "弹窗异常: " + e.getMessage() + "，默认拒绝: " + pkgName);
                return PermissionStorage.PERMISSION_BLOCK;
            }

            int r = result.get();
            Log.i(TAG, "askUser结果: " + pkgName + " -> " + (r == PermissionStorage.PERMISSION_IGNORE ? "允许" : "拒绝"));
            return r;
        }

        /**
         * 处理用户决策（仅记录日志，不修改黑白名单）
         * 黑白名单的修改只在 App 界面的勾选保存时进行
         */
        private void handleDecision(Context ctx, int decision, String pkgName, String preview) {
            if (decision == PermissionStorage.PERMISSION_IGNORE) {
                Log.i(TAG, "用户允许(临时): " + pkgName);
                writeLog(ctx, pkgName, "放行", preview);
            } else {
                Log.i(TAG, "用户拒绝(临时): " + pkgName + " - 拦截");
                writeLog(ctx, pkgName, "拦截", preview);
            }
        }
    }

    // ──────────────────────────── 日志写入 ────────────────────────────

    private static void writeLog(Context ctx, String pkgName, String action, String content) {
        if (ctx == null) return;
        // 避免无效包名导致日志写入失败
        if (pkgName == null || pkgName.isEmpty() || "android".equals(pkgName) || "unknown".equals(pkgName)) return;
        try {
            // 通过 ContentProvider 写入（权限检查和日志开关由 App 端处理）
            PermissionProvider.writeLog(ctx, pkgName, action, content);
        } catch (Throwable ignored) {
            // 静默忽略，日志写入失败不影响核心功能（后续会换日志方案）
        }
    }

    /** 获取 system_server 的 Context（静态版本，供外部调用）
     *  带重试机制，开机时 ActivityThread 可能未就绪
     */
    private static Context getSystemServerContextStatic() {
        // 最多重试 10 次，每次间隔 500ms，等待 ActivityThread 完全就绪
        for (int retry = 0; retry < 10; retry++) {
            try {
                Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
                if (at != null) {
                    Context ctx = (Context) XposedHelpers.callMethod(at, "getApplication");
                    if (ctx != null) {
                        if (retry > 0) {
                            Log.i(TAG, "getSystemServerContextStatic 重试 " + retry + " 次后成功");
                        }
                        return ctx;
                    }
                }
            } catch (Throwable e) {
                Log.d(TAG, "getSystemServerContextStatic 重试 " + retry + " 失败: " + e.getMessage());
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
        }
        Log.e(TAG, "getSystemServerContextStatic: 10 次重试后仍失败");
        return null;
    }

    /**
     * 按需初始化 PermissionCache（用户首次复制时触发）
     * 策略：
     * 1. 如果已加载 → 直接返回成功
     * 2. 如果未加载 → 立即尝试初始化
     * 3. 初始化成功 → 返回 true，弹窗
     * 4. 初始化失败 → 返回 false，保守拦截
     */
    private static boolean ensureInitialized() {
        if (PermissionCache.isLoaded()) {
            return true; // 已初始化
        }

        Log.w(TAG, "PermissionCache 未初始化，按需初始化...");
        Context ctx = getSystemServerContextStatic();
        if (ctx != null) {
            PermissionCache.loadIgnoreSet(ctx);
            PermissionCache.registerRefreshReceiver(ctx);
        }

        if (PermissionCache.isLoaded()) {
            Log.i(TAG, "按需初始化成功: blockSet.size=" + PermissionCache.getIgnoreSetSize());
            return true;
        }

        // 初始化失败，保守拦截
        Log.e(TAG, "初始化失败，保守拦截");
        return false;
    }

    // ──────────────────────────── 系统核心包白名单 ────────────────────────────

    private static boolean isSystemCorePackage(String pkgName) {
        if (pkgName == null) return true;
        if (sCorePackagesSet.contains(pkgName)) return true;
        for (String core : sCorePackagesSet) {
            if (pkgName.startsWith(core + ".")) return true;
        }
        return false;
    }

    private static final HashSet<String> sCorePackagesSet = new HashSet<>(Arrays.asList(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.mtp",
        "android.ext.shared",
        "com.android.pacprocessor",
        "com.android.server.telecom",
        "com.android.carrierconfig",
        "com.android.defcontainer",
        "com.android.mms.service",
        "com.validation",
        "com.android.calllogbackup",
        "com.android.carrierdefaultapp",
        "com.android.cellbroadcastreceiver",
        "com.android.egg",
        "com.android.onetimeinitializer",
        "com.android.packageinstaller",
        "com.android.proxyhandler",
        "android.ext.services",
        "com.android.statementservice",
        "com.android.inputdevices",
        "com.android.externalstorage",
        "com.example.android.livecubes",
        "com.android.emergency",
        "com.android.development_settings",
        "com.android.bips",
        "com.android.customlocale2",
        "com.android.companiondevicemanager",
        "com.genymotion.systempatcher",
        "com.android.wallpaperpicker",
        "com.android.wallpaperbackup",
        "com.android.wallpapercropper",
        "com.android.provision",
        "com.android.cts.priv.ctsshim",
        "com.android.certinstaller",
        "com.android.dreams.basic",
        "com.android.gesture.builder",
        "com.android.cts.ctsshim",
        "com.android.captiveportallogin",
        "com.android.bluetoothmidiservice",
        "com.android.bluetooth",
        "com.android.backupconfirm",
        "com.android.sharedstoragebackup",
        "com.android.smspush",
        "om.genymotion.genyd",
        "com.android.location.fused",
        "com.android.htmlviewer",
        "com.android.keychain",
        "com.android.wallpaper.livepicker",
        "com.android.nfc",
        "com.android.localtransport",
        "jp.co.omronsoft.openwnn",
        "com.android.bookmarkprovider",
        "com.android.providers.media",
        "com.android.providers.calendar",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.userdictionary",
        "com.android.providers.phone",
        "com.android.providers.blockednumber",
        "com.android.providers.contacts",
        "com.android.providers.media.module",
        "com.zui.incallui",
        "github.tornaco.xposedmoduletest",
        "de.robv.android.xposed.installer",
        "com.qualcomm.uimremoteclient",
        "com.qualcomm.qti.uceShimService",
        "vendor.qti.hardware.cacert.server",
        "com.qualcomm.qti.telephonyservice",
        "vendor.qti.iwlan",
        "com.qualcomm.uimremoteserver",
        "com.qti.qualcomm.datastatusnotification",
        "com.qualcomm.qti.callfeaturessetting",
        "com.miui.vsimcore",
        "com.qti.qualcomm.deviceinfo",
        "com.android.ons",
        "com.android.stk",
        "org.codeaurora.ims",
        "com.qualcomm.qti.dynamicddsservice",
        "com.qualcomm.qcrilmsgtunnel",
        "com.qti.dpmserviceapp",
        "com.qti.xdivert",
        "com.qualcomm.qti.cne",
        "com.qualcomm.qti.lpa",
        "com.qualcomm.qti.uim",
        "com.qualcomm.qti.uimGbaApp",
        "vendor.qti.imsrcs",
        "com.miui.securitycore",
        "com.mobiletools.systemhelper",
        "com.milink.service",
        "com.xiaomi.finddevice",
        "com.miui.contentcatcher",
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.xiaomi.mirror",
        "com.xiaomi.NetworkBoost",
        "com.miui.home",
        "com.xiaomi.bluetooth",
        "com.google.android.webview",
        "com.google.android.ext.services",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.qualcomm.qti.devicestatisticsservice",
        "com.android.se",
        "com.qti.phone",
        "com.qualcomm.qti.poweroffalarm",
        "com.qti.qcc",
        "com.qualcomm.timeservice"
    ));
}
