package com.android.clipboardguard;

import android.content.ClipData;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
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
 * ClipboardGuard - Xposed 写入拦截 Hook
 *
 * 初始化策略（稳定版）：
 * 1. Hook 成功后，立即加载一次本地文件（可能为空）。
 * 2. 延迟 8 秒注册广播接收器 + 再次加载文件（覆盖 App 开机广播）。
 * 3. 首次复制时执行 ensureInitialized()：
 *    - 如果广播尚未注册，尝试注册。
 *    - 如果缓存尚未加载，从文件加载。
 * 4. 之后完全依赖广播和 FileObserver 被动更新。
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
        if (MODULE_PKG.equals(lpparam.packageName)) {
            if (!"android".equals(lpparam.processName)) {
                hookSelfForActiveStatus(lpparam);
            }
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            hookClipboardService(lpparam);
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
            Log.i(TAG, "自身Hook成功");
        } catch (Throwable e) {
            Log.e(TAG, "自身Hook失败: " + e.getMessage());
        }
    }

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

                        // 立即加载一次本地文件（可能为空）
                        PermissionCache.loadWriteBlockSet();
                        PermissionCache.loadReadBlockSet();
                        ContentRulesManager.loadRules();
                        ContentRulesManager.loadReadRules();

                        // 延迟 8 秒再注册广播（等待 AMS 就绪）并重新加载文件
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!sReceiverRegistered) {
                                Context ctx = getSystemServerContextStatic();
                                if (ctx != null) {
                                    try {
                                        PermissionCache.registerRefreshReceiver(ctx);
                                        sReceiverRegistered = true;
                                        Log.i(TAG, "延迟注册广播接收器成功");
                                    } catch (Throwable e) {
                                        Log.e(TAG, "延迟注册广播失败: " + e.getMessage());
                                    }
                                }
                            }
                            // 无论广播是否注册成功，再次尝试从文件加载（BootReceiver 已运行）
                            if (!PermissionCache.isWriteLoaded()) {
                                PermissionCache.loadWriteBlockSet();
                                PermissionCache.loadReadBlockSet();
                                ContentRulesManager.loadRules();
                                ContentRulesManager.loadReadRules();
                                Log.i(TAG, "延迟加载完成，writeBlockSet.size=" + PermissionCache.getWriteBlockSetSize());
                            }
                        }, 8000);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        Log.e(TAG, "Hook失败：未找到 setPrimaryClip");
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
                    Log.w(TAG, "PermissionCache 未初始化，保守放行");
                    return;
                }

                String pkgName = getCallingPackageNameWithRetry();
                if (pkgName == null || pkgName.isEmpty()) {
                    Log.w(TAG, "无法获取调用者包名，保守放行");
                    return;
                }

                if (isSystemCorePackage(pkgName)) return;

                Context ctx = getSystemServerContextWithRetry();
                if (ctx == null) {
                    Log.e(TAG, "获取Context失败，保守放行: " + pkgName);
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
                Log.i(TAG, "内容命中规则 [" + matchedRule + "]，弹窗");
                return true;
            }
            Log.i(TAG, "内容未命中正则规则，放行");
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
                Log.e(TAG, "弹窗异常: " + e.getMessage());
                return PermissionStorage.PERMISSION_BLOCK;
            }
            return result.get();
        }

        private void handleDecision(Context ctx, int decision, String pkgName, String preview) {
            writeLog(ctx, pkgName, decision == PermissionStorage.PERMISSION_IGNORE ? "放行" : "拦截", preview);
        }
    }

    private static void writeLog(Context ctx, String pkgName, String action, String content) {
        if (ctx == null || pkgName == null || pkgName.isEmpty() || "android".equals(pkgName) || "unknown".equals(pkgName))
            return;
        try {
            PermissionProvider.writeLog(ctx, pkgName, action, content);
        } catch (Throwable ignored) {}
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
     * 确保缓存和广播已就绪。
     * 首次复制时调用，执行一次性的兜底注册与加载。
     */
    private static boolean ensureInitialized() {
        // 若广播尚未注册，尝试注册
        if (!sReceiverRegistered) {
            Context ctx = getSystemServerContextStatic();
            if (ctx != null) {
                try {
                    PermissionCache.registerRefreshReceiver(ctx);
                    sReceiverRegistered = true;
                    Log.i(TAG, "兜底注册广播接收器成功");
                } catch (Throwable e) {
                    Log.e(TAG, "兜底注册广播失败: " + e.getMessage());
                }
            }
        }

        // 如果缓存从未加载过，从文件加载
        if (!PermissionCache.isWriteLoaded()) {
            Log.w(TAG, "缓存未加载，尝试从文件加载配置...");
            PermissionCache.loadWriteBlockSet();
            PermissionCache.loadReadBlockSet();
            ContentRulesManager.loadRules();
            ContentRulesManager.loadReadRules();
        }
        return true;
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