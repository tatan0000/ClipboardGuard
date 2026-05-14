package com.android.clipboardguard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ClipboardGuard - Xposed 读取拦截 Hook。
 *
 * 读取端和写入端一样运行在 system_server 中，不能直接读取模块私有目录文件。
 * 配置依赖 App 侧通过广播推送：读取拦截列表、读取正则规则、读取拒绝 Toast 开关。
 */
public class ReadHook implements IXposedHookLoadPackage {

    private static final String TAG = "ClipboardGuard.ReadHook";
    private static final String MODULE_PKG = "com.android.clipboardguard";
    private static final long READ_DIALOG_DEBOUNCE_MS = 3000;
    private static final long READ_TOAST_DEBOUNCE_MS = 3000;

    private static final Map<String, Long> sLastReadDecisionTime = new HashMap<>();
    private static final Map<String, Integer> sLastReadUserDecision = new HashMap<>();
    private static final Map<String, Boolean> sLastReadClearConsumed = new HashMap<>();
    private static final Map<String, Long> sLastReadToastTime = new HashMap<>();
    private static final Object sReadDebounceLock = new Object();

    /** 防止清空剪贴板等操作递归触发读取 Hook。 */
    private static final ThreadLocal<Boolean> sIsReadBlockingOperation = ThreadLocal.withInitial(() -> false);

    /** 标记广播接收器是否已注册成功。 */
    private static volatile boolean sReadReceiverRegistered = false;

    /** Hook 侧触发配置同步服务是否已发送，防止重复启动。 */
    private static final AtomicBoolean sReadHookTriggerSent = new AtomicBoolean(false);

    /** Hook 加载时间，用于日志观察同步时机。 */
    private static volatile long sReadHookLoadTime = 0L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            if (MODULE_PKG.equals(lpparam.packageName)) {
                return;
            }
            if ("android".equals(lpparam.packageName)) {
                try {
                    XLog.init(XposedBridge.class.getMethod("log", String.class));
                } catch (NoSuchMethodException e) {
                    XLog.e(TAG, "获取 XposedBridge.log 方法失败: " + e.getMessage());
                }
                hookGetPrimaryClip(lpparam);
            }
        } catch (Throwable t) {
            XLog.e(TAG, "handleLoadPackage 异常: " + t.getMessage());
            XposedBridge.log(t);
        }
    }

    /** Hook ClipboardService.getPrimaryClip 方法。 */
    private void hookGetPrimaryClip(XC_LoadPackage.LoadPackageParam lpparam) {
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
                Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
                for (java.lang.reflect.Method method : cls.getDeclaredMethods()) {
                    if ("getPrimaryClip".equals(method.getName())) {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new GetPrimaryClipHook());
                        sReadHookLoadTime = System.currentTimeMillis();
                        XLog.i(TAG, "ReadHook 成功: " + className);

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                if (tryRegisterReadReceiver("延迟5s")) {
                                    XLog.i(TAG, "延迟5s注册成功，等待 App 广播推送读取配置");
                                }
                            } catch (Throwable t) {
                                XLog.w(TAG, "延迟5s初始化读取配置异常: " + t.getMessage());
                            }
                        }, 5000);

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                if (tryRegisterReadReceiver("延迟8s兜底")) {
                                    XLog.i(TAG, "延迟8s注册成功，等待 App 广播推送读取配置");
                                }
                            } catch (Throwable t) {
                                XLog.w(TAG, "延迟8s初始化读取配置异常: " + t.getMessage());
                            }
                        }, 8000);
                        return;
                    }
                }
            } catch (Throwable t) {
                XLog.w(TAG, "Read 候选类未找到: " + className + " - " + t.getMessage());
            }
        }
        XLog.e(TAG, "ReadHook 失败：未找到 getPrimaryClip");
    }

    /** 尝试注册配置刷新广播接收器。 */
    private static boolean tryRegisterReadReceiver(String tag) {
        if (sReadReceiverRegistered) return true;
        Context context = getReadSystemServerContextStatic();
        if (context == null) {
            XLog.w(TAG, "[" + tag + "] 获取 Context 失败，跳过注册");
            return false;
        }
        boolean ok = PermissionCache.registerRefreshReceiver(context);
        if (ok) {
            sReadReceiverRegistered = true;
            XLog.i(TAG, "[" + tag + "] 读取配置广播接收器注册成功");
            loadReadAllConfig(context, tag);
            startReadConfigSyncServiceOnce();
        } else {
            XLog.w(TAG, "[" + tag + "] 读取配置广播接收器注册失败，将稍后重试");
        }
        return ok;
    }

    /** 注册成功后启动配置同步服务，让 App 侧推送读取配置。 */
    private static void startReadConfigSyncServiceOnce() {
        if (!sReadHookTriggerSent.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Context context = getReadSystemServerContextStatic();
                if (context == null) {
                    XLog.w(TAG, "[Hook触发] 获取 Context 失败，无法启动 ConfigSyncService");
                    return;
                }
                Intent serviceIntent = new Intent();
                serviceIntent.setComponent(new ComponentName(MODULE_PKG, MODULE_PKG + ".ConfigSyncService"));
                serviceIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                XLog.i(TAG, "[Hook触发] 已启动 ConfigSyncService，同步读取配置，耗时 "
                        + (System.currentTimeMillis() - sReadHookLoadTime) + "ms");
            } catch (Throwable t) {
                XLog.e(TAG, "[Hook触发] 启动 ConfigSyncService 失败: " + t.getMessage());
            }
        }, 7000);
    }

    /** 一次性加载读取端需要的配置：写入/读取拦截列表 + 写入/读取内容规则。 */
    private static void loadReadAllConfig(Context context, String tag) {
        if (!PermissionCache.loadFullConfigFromProvider(context)) {
            PermissionCache.loadWriteBlockSet();
            PermissionCache.loadReadBlockSet();
            ContentRulesManager.loadWriteRules();
            ContentRulesManager.loadReadRules();
        }
        XLog.i(TAG, "[" + tag + "] 读取端配置加载完成，readBlockSet.size="
                + PermissionCache.getReadBlockSetSize());
    }

    /** 确保读取配置已经初始化；配置数据主要依赖 App 广播推送。 */
    private static boolean ensureReadConfigInitialized() {
        if (!sReadReceiverRegistered) {
            tryRegisterReadReceiver("兜底");
        }
        if (!PermissionCache.isReadLoaded()) {
            loadReadAllConfig(getReadSystemServerContextStatic(), "读取兜底");
        }
        return PermissionCache.isReadLoaded();
    }

    private static class GetPrimaryClipHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (Boolean.TRUE.equals(sIsReadBlockingOperation.get())) return;
            sIsReadBlockingOperation.set(true);
            try {
                if (!ensureReadConfigInitialized()) {
                    XLog.w(TAG, "读取配置未初始化，保守放行");
                    return;
                }

                String readPackageName = getCallingReadPackageName();
                if (readPackageName == null || readPackageName.isEmpty()) {
                    XLog.w(TAG, "无法获取读取调用者包名，保守放行");
                    return;
                }
                if (isReadSystemCorePackage(readPackageName)) return;
                if (PermissionCache.isReadIgnored(readPackageName)) return;

                Context systemContext = getReadSystemServerContext();
                if (systemContext == null) {
                    XLog.e(TAG, "获取 Context 失败，保守放行: " + readPackageName);
                    return;
                }

                ClipData readClipData = (param.getResult() instanceof ClipData) ? (ClipData) param.getResult() : null;
                String readContent = extractReadContent(readClipData);
                String readPreview = trimReadPreview(readContent);

                // 未开启读取正则或没有启用规则：勾选应用直接拒绝读取，可按开关显示 Toast。
                if (!ContentRulesManager.isReadEnabled() || !ContentRulesManager.hasEnabledReadRule()) {
                    rejectReadResult(param, systemContext, readPackageName, readPreview,
                            PermissionCache.isReadBlockedToastEnabled());
                    return;
                }

                // 已开启读取正则且有规则：只有命中规则时才弹窗询问。
                String matchedReadRule = ContentRulesManager.matchesReadContent(readContent);
                if (matchedReadRule == null) {
                    XLog.i(TAG, "读取内容未命中正则规则，放行: " + readPackageName);
                    return;
                }

                ReadDecisionResult readDecisionResult = getReadDecision(systemContext, readPackageName, readPreview, matchedReadRule);
                int readDecision = readDecisionResult.decision;
                if (readDecision == PermissionStorage.PERMISSION_IGNORE) {
                    writeReadLog(readPackageName, "允许读取", readPreview);
                    return;
                }
                if (readDecision == PermissionStorage.PERMISSION_CLEAR && readDecisionResult.shouldClearClipboard) {
                    clearClipboard(systemContext);
                    writeReadLog(readPackageName, "拒绝读取并清空剪贴板", readPreview);
                } else {
                    writeReadLog(readPackageName, "拒绝读取", readPreview);
                }
                param.setResult(null);
            } finally {
                sIsReadBlockingOperation.remove();
            }
        }

        private void rejectReadResult(MethodHookParam param, Context context, String readPackageName,
                String readPreview, boolean shouldShowReadToast) {
            param.setResult(null);
            writeReadLog(readPackageName, "拒绝读取", readPreview);
            if (shouldShowReadToast) {
                showReadBlockedToast(context, readPackageName);
            }
        }

        private ReadDecisionResult getReadDecision(Context context, String readPackageName,
                String readPreview, String matchedReadRule) {
            synchronized (sReadDebounceLock) {
                long now = System.currentTimeMillis();
                Long lastTime = sLastReadDecisionTime.get(readPackageName);
                Integer lastDecision = sLastReadUserDecision.get(readPackageName);
                if (lastTime != null && now - lastTime < READ_DIALOG_DEBOUNCE_MS && lastDecision != null) {
                    boolean shouldClearClipboard = false;
                    if (lastDecision == PermissionStorage.PERMISSION_CLEAR
                            && !Boolean.TRUE.equals(sLastReadClearConsumed.get(readPackageName))) {
                        shouldClearClipboard = true;
                        sLastReadClearConsumed.put(readPackageName, true);
                    }
                    return new ReadDecisionResult(lastDecision, shouldClearClipboard);
                }
            }

            int readDecision = askReadUser(context, readPackageName, readPreview, matchedReadRule);
            boolean shouldClearClipboard = readDecision == PermissionStorage.PERMISSION_CLEAR;
            synchronized (sReadDebounceLock) {
                sLastReadUserDecision.put(readPackageName, readDecision);
                sLastReadDecisionTime.put(readPackageName, System.currentTimeMillis());
                sLastReadClearConsumed.put(readPackageName, shouldClearClipboard);
            }
            return new ReadDecisionResult(readDecision, shouldClearClipboard);
        }

        private int askReadUser(Context context, String readPackageName, String readPreview, String matchedReadRule) {
            AtomicInteger readDecision = new AtomicInteger(PermissionStorage.PERMISSION_BLOCK);
            try {
                InlineDialogManager dialogManager = InlineDialogManager.getInstance(context);
                boolean shown = dialogManager.showReadDialogWithContent(
                        readPackageName, readPreview, matchedReadRule, readDecision);
                if (!shown) return PermissionStorage.PERMISSION_BLOCK;
            } catch (Throwable e) {
                XLog.e(TAG, "读取弹窗异常: " + e.getMessage());
                return PermissionStorage.PERMISSION_BLOCK;
            }
            return readDecision.get();
        }

        private String getCallingReadPackageName() {
            try {
                int uid = Binder.getCallingUid();
                if (uid <= 0) return null;
                Context context = getReadSystemServerContext();
                if (context == null) return null;
                String[] packages = context.getPackageManager().getPackagesForUid(uid);
                if (packages != null && packages.length > 0) return packages[0];
            } catch (Throwable ignored) {}
            return null;
        }

        private Context getReadSystemServerContext() {
            return getReadSystemServerContextStatic();
        }

        private String extractReadContent(ClipData readClipData) {
            if (readClipData == null) return "";
            try {
                if (readClipData.getItemCount() > 0) {
                    ClipData.Item item = readClipData.getItemAt(0);
                    CharSequence text = item.getText();
                    if (text != null && text.length() > 0) return text.toString().trim();
                    String html = item.getHtmlText();
                    if (html != null && !html.isEmpty()) return html.replaceAll("<[^>]+>", "").trim();
                    if (item.getUri() != null) return "[图片/文件]";
                }
            } catch (Throwable ignored) {}
            return "(非文本内容)";
        }

        private String trimReadPreview(String readText) {
            if (readText == null || readText.isEmpty()) return "";
            return readText.length() > 100 ? readText.substring(0, 100) + "…" : readText;
        }

        private void clearClipboard(Context context) {
            try {
                long identity = Binder.clearCallingIdentity();
                try {
                    ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboardManager != null) {
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } catch (Throwable e) {
                XLog.e(TAG, "清空剪贴板失败: " + e.getMessage());
            }
        }

        private void showReadBlockedToast(Context context, String readPackageName) {
            try {
                long now = System.currentTimeMillis();
                synchronized (sReadDebounceLock) {
                    Long lastToastTime = sLastReadToastTime.get(readPackageName);
                    if (lastToastTime != null && now - lastToastTime < READ_TOAST_DEBOUNCE_MS) return;
                    sLastReadToastTime.put(readPackageName, now);
                }
                String readAppName = getReadAppName(context, readPackageName);
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                        context,
                        "已拒绝 " + readAppName + " 读取剪贴板",
                        Toast.LENGTH_SHORT
                ).show());
            } catch (Throwable e) {
                XLog.w(TAG, "读取拒绝 Toast 显示失败: " + e.getMessage());
            }
        }

        private String getReadAppName(Context context, String readPackageName) {
            long identity = Binder.clearCallingIdentity();
            try {
                PackageManager packageManager = context.getPackageManager();
                ApplicationInfo appInfo = packageManager.getApplicationInfo(readPackageName, 0);
                CharSequence label = packageManager.getApplicationLabel(appInfo);
                if (label != null && label.length() > 0) return label.toString();
            } catch (Throwable ignored) {
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return readPackageName;
        }
    }

    private static class ReadDecisionResult {
        final int decision;
        final boolean shouldClearClipboard;

        ReadDecisionResult(int decision, boolean shouldClearClipboard) {
            this.decision = decision;
            this.shouldClearClipboard = shouldClearClipboard;
        }
    }

    private static Context getReadSystemServerContextStatic() {
        for (int retry = 0; retry < 3; retry++) {
            try {
                Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
                if (activityThread != null) {
                    Context context = (Context) XposedHelpers.callMethod(activityThread, "getApplication");
                    if (context != null) return context;
                }
            } catch (Throwable ignored) {}
            if (retry < 2) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    private static boolean isReadSystemCorePackage(String packageName) {
        if (packageName == null) return true;
        if (sReadCorePackagesSet.contains(packageName)) return true;
        for (String corePackage : sReadCorePackagesSet) {
            if (packageName.startsWith(corePackage + ".")) return true;
        }
        return false;
    }

    private static void writeReadLog(String readPackageName, String readAction, String readContent) {
        if (readPackageName == null || readPackageName.isEmpty()
                || "android".equals(readPackageName) || "unknown".equals(readPackageName)) {
            return;
        }
        if (!PermissionCache.isLsposedLogEnabled()) return;
        XLog.i(TAG, "[" + readPackageName + "] " + readAction + ": "
                + PrivacyLogUtils.maskClipboardContent(readContent));
    }

    private static final HashSet<String> sReadCorePackagesSet = new HashSet<>(Arrays.asList(
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
