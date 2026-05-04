package com.android.clipboardguard;

import android.content.ClipData;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;

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
 * ClipboardGuard - Xposed 写入拦截 Hook
 *
 * 初始化策略（v5 - 2026-05-05）：
 * - 开机立即注册广播接收器必定失败（AMS 未就绪），不再尝试。
 * - 改为两级延迟注册：5s 第一次尝试，8s 第二次兜底。
 * - 配置依赖 App 侧推送：BootReceiver 自启动（12s/15s）、打开 App、保存配置时。
 * - 首次复制时 ensureInitialized() 确保广播接收器已注册。
 */
public class WriteHook implements IXposedHookLoadPackage {

    private static final String TAG = "ClipboardGuard";
    private static final String MODULE_PKG = "com.android.clipboardguard";
    private static final long DEBOUNCE_MS = 1500;

    private static final Map<String, Long>    sLastDecisionTime = new HashMap<>();
    private static final Map<String, Integer> sLastUserDecision = new HashMap<>();
    private static final Object sDebounceLock = new Object();

    private static final ThreadLocal<Boolean> sInAfterHook = ThreadLocal.withInitial(() -> false);
    private static volatile boolean sIsBlockingOperation = false;

    /** 标记广播接收器是否已注册成功 */
    private static volatile boolean sReceiverRegistered = false;

    @Override
    @SuppressWarnings("RedundantThrows")
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            if (MODULE_PKG.equals(lpparam.packageName)) {
                if (!"android".equals(lpparam.processName)) {
                    hookSelfForActiveStatus(lpparam);
                }
                return;
            }
            if ("android".equals(lpparam.packageName)) {
                // 初始化 XLog：传入 XposedBridge.log(String) 方法引用
                try {
                    XLog.init(XposedBridge.class.getMethod("log", String.class));
                } catch (NoSuchMethodException e) {
                    XLog.e(TAG, "获取 XposedBridge.log 方法失败: " + e.getMessage());
                }
                hookClipboardService(lpparam);
            }
        } catch (Throwable t) {
            XLog.e(TAG, "handleLoadPackage 异常: " + t.getMessage());
            XposedBridge.log(t);
        }
    }

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
            XLog.i(TAG, "WriteHook自身Hook成功");
        } catch (Throwable e) {
            XLog.e(TAG, "WriteHook自身Hook失败: " + e.getMessage());
        }
    }

    private void hookClipboardService(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] candidates = {
                // Android 14+ (API 34+): Google 重命名为 ClipboardManagerService
                "com.android.server.clipboard.ClipboardManagerService",
                "com.android.server.clipboard.ClipboardManagerService$Impl",
                "com.android.server.clipboard.ClipboardManagerService$BinderService",
                "com.android.server.clipboard.ClipboardManagerService$ClipboardImpl",
                // Android 13 及以下 (API 33-): 原始 ClipboardService
                "com.android.server.clipboard.ClipboardService",
                "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService$BinderService",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("setPrimaryClip".equals(m.getName())) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new ClipboardServiceHook());
                        XLog.i(TAG, "WriteHook成功: " + className);

                        // ★ 初始化代码必须用 try-catch 包裹！
                        // 如果这里抛出未捕获的异常，会导致 handleLoadPackage 整体失败，
                        // LSPosed 会标记模块为异常状态并禁用 Hook
                        try {
                            // 开机立即注册必定失败（AMS 未就绪），不再尝试
                            // 改为两级延迟注册：5s → 8s
                            final Handler initHandler = new Handler(Looper.getMainLooper());

                            // 第一级：延迟 5s 注册
                            initHandler.postDelayed(() -> {
                                try {
                                    if (tryRegisterReceiver("延迟5s")) {
                                        XLog.i(TAG, "延迟5s注册成功，等待 App 广播推送配置");
                                    }
                                } catch (Throwable t) {
                                    XLog.e(TAG, "延迟5s初始化异常: " + t.getMessage());
                                }
                            }, 5000);

                            // 第二级：延迟 8s 注册（兜底，5s 失败时补上）
                            initHandler.postDelayed(() -> {
                                try {
                                    if (tryRegisterReceiver("延迟8s")) {
                                        XLog.i(TAG, "延迟8s注册成功，等待 App 广播推送配置");
                                    }
                                } catch (Throwable t) {
                                    XLog.e(TAG, "延迟8s初始化异常: " + t.getMessage());
                                }
                            }, 8000);
                        } catch (Throwable t) {
                            XLog.e(TAG, "Hook后初始化异常（不影响Hook本身）: " + t.getMessage());
                        }
                        return;
                    }
                }
            } catch (Throwable t) {
                XLog.w(TAG, "候选类未找到: " + className + " - " + t.getMessage());
            }
        }
        XLog.e(TAG, "WriteHook失败：未找到 setPrimaryClip");
    }

    /**
     * 尝试注册广播接收器。
     * @param tag 日志标签
     * @return true 注册成功或已注册过，false 注册失败
     */
    private static boolean tryRegisterReceiver(String tag) {
        if (sReceiverRegistered) return true;
        Context ctx = getSystemServerContextStatic();
        if (ctx == null) {
            XLog.w(TAG, "[" + tag + "] 获取 Context 失败，跳过注册");
            return false;
        }
        boolean ok = PermissionCache.registerRefreshReceiver(ctx);
        if (ok) {
            sReceiverRegistered = true;
            XLog.i(TAG, "[" + tag + "] 广播接收器注册成功");
        } else {
            XLog.w(TAG, "[" + tag + "] 广播接收器注册失败，将稍后重试");
        }
        return ok;
    }

    /** 一次性加载全部配置（写入/读取拦截列表 + 内容规则） */
    private static void loadAllConfig(String tag) {
        PermissionCache.loadWriteBlockSet();
        PermissionCache.loadReadBlockSet();
        ContentRulesManager.loadRules();
        ContentRulesManager.loadReadRules();
        XLog.i(TAG, "[" + tag + "] 配置加载完成，writeBlockSet.size=" + PermissionCache.getWriteBlockSetSize());
    }

    private static class ClipboardServiceHook extends XC_MethodHook {

        private static final ThreadLocal<Boolean> sShouldBlock = new ThreadLocal<>();

        @Override
        @SuppressWarnings("RedundantThrows")
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (sIsBlockingOperation) return;
            sIsBlockingOperation = true;

            try {
                if (Boolean.TRUE.equals(sInAfterHook.get())) return;

                boolean initialized = ensureInitialized();
                if (!initialized) {
                    XLog.w(TAG, "PermissionCache 未初始化，保守放行");
                    return;
                }

                String pkgName = getCallingPackageNameWithRetry();
                if (pkgName == null || pkgName.isEmpty()) {
                    XLog.w(TAG, "无法获取调用者包名，保守放行");
                    return;
                }

                if (isSystemCorePackage(pkgName)) return;

                Context ctx = getSystemServerContextWithRetry();
                if (ctx == null) {
                    XLog.e(TAG, "获取Context失败，保守放行: " + pkgName);
                    return;
                }

                Object clipArg = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                String preview = extractPreview(clipArg);

                if (PermissionCache.isWriteIgnored(pkgName)) {
                    writeLog(ctx, pkgName, "放行", preview);
                    return;
                }

                if (!shouldShowPopup(preview)) {
                    writeLog(ctx, pkgName, "放行(内容过滤)", preview);
                    return;
                }

                int decision;
                synchronized (sDebounceLock) {
                    long now = System.currentTimeMillis();
                    Long lastTime = sLastDecisionTime.get(pkgName);
                    Integer last = sLastUserDecision.get(pkgName);
                    if (lastTime != null && now - lastTime < DEBOUNCE_MS && last != null) {
                        decision = last;
                    } else {
                        decision = -1;
                    }
                }

                if (decision < 0) {
                    decision = askUser(ctx, pkgName, preview);
                    synchronized (sDebounceLock) {
                        sLastUserDecision.put(pkgName, decision);
                        sLastDecisionTime.put(pkgName, System.currentTimeMillis());
                    }
                }

                handleDecision(ctx, decision, pkgName, preview);

                if (decision != PermissionStorage.PERMISSION_IGNORE) {
                    param.setResult(null);
                    sShouldBlock.set(false);
                }
            } finally {
                sIsBlockingOperation = false;
            }
        }

        @Override
        @SuppressWarnings("RedundantThrows")
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            sShouldBlock.remove();
            sInAfterHook.remove();
        }

        private boolean shouldShowPopup(String preview) {
            if (!ContentRulesManager.isEnabled()) return true;
            if (!ContentRulesManager.isLoaded()) return true;
            if (!ContentRulesManager.hasEnabledRule()) return true;

            String matchedRule = ContentRulesManager.matchesAdContent(preview);
            if (matchedRule != null) {
                XLog.i(TAG, "内容命中规则 [" + matchedRule + "]，弹窗");
                return true;
            }
            XLog.i(TAG, "内容未命中正则规则，放行");
            return false;
        }

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
                if (pkgs != null && pkgs.length > 0) return pkgs[0];
            } catch (Throwable ignored) {}
            return null;
        }

        private Context getSystemServerContextWithRetry() {
            try {
                Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
                if (at == null) return null;
                return (Context) XposedHelpers.callMethod(at, "getApplication");
            } catch (Throwable ignored) {
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

        private int askUser(Context ctx, String pkgName, String preview) {
            AtomicInteger result = new AtomicInteger(PermissionStorage.PERMISSION_BLOCK);
            try {
                InlineDialogManager dialogManager = InlineDialogManager.getInstance(ctx);
                boolean shown = dialogManager.showDialog(pkgName, preview, result);
                if (!shown) return PermissionStorage.PERMISSION_BLOCK;
            } catch (Throwable e) {
                XLog.e(TAG, "弹窗异常: " + e.getMessage());
                return PermissionStorage.PERMISSION_BLOCK;
            }
            return result.get();
        }

        private void handleDecision(Context ctx, int decision, String pkgName, String preview) {
            writeLog(ctx, pkgName, decision == PermissionStorage.PERMISSION_IGNORE ? "放行" : "拦截", preview);
        }
    }

    private static void writeLog(Context ctx, String pkgName, String action, String content) {
        // 只输出到 LSPosed 日志，不保存到数据库
        if (pkgName == null || pkgName.isEmpty() || "android".equals(pkgName) || "unknown".equals(pkgName))
            return;
        XLog.i(TAG, "[" + pkgName + "] " + action + ": " + content);
    }

    private static Context getSystemServerContextStatic() {
        for (int retry = 0; retry < 3; retry++) {
            try {
                Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
                if (at != null) {
                    Context ctx = (Context) XposedHelpers.callMethod(at, "getApplication");
                    if (ctx != null) return ctx;
                }
            } catch (Throwable ignored) {}
            if (retry < 2) {
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    /**
     * 确保广播接收器已注册。
     * Hook 侧（system_server）无法直接读取 App 私有目录文件，
     * 配置只能靠 App 通过广播推送（BootReceiver / 打开App / 保存配置时）。
     */
    private static boolean ensureInitialized() {
        if (!sReceiverRegistered) {
            tryRegisterReceiver("兜底");
        }
        return PermissionCache.isWriteLoaded();
    }

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