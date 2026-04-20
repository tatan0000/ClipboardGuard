package com.android.clipboardguard;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private static final long DEBOUNCE_MS = 1_500;

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
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        Log.e(TAG, "Hook失败：未找到 setPrimaryClip");
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static class ClipboardServiceHook extends XC_MethodHook {

        /**
         * 在 before/after 之间传递：是否应该拦截（true=拦截，false=放行）
         * 只有 beforeHookedMethod 写，afterHookedMethod 读后清理。
         */
        private static final ThreadLocal<Boolean> sShouldBlock = new ThreadLocal<>();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            // 防止递归（afterHookedMethod 调内部方法时会再次触发）
            if (Boolean.TRUE.equals(sInAfterHook.get())) return;

            String pkgName = getCallingPackageName();
            if (pkgName == null || pkgName.isEmpty()) {
                Log.w(TAG, "无法获取调用者包名，放行");
                return;
            }
            if (isSystemCorePackage(pkgName)) return;

            Context ctx = getSystemServerContext();
            if (ctx == null) {
                Log.e(TAG, "获取Context失败，放行");
                return;
            }

            // 读取权限（先 clearCallingIdentity 避免 Binder uid 校验失败）
            int savedPerm;
            long identity = Binder.clearCallingIdentity();
            try {
                savedPerm = directQueryPermission(ctx, pkgName);
                if (savedPerm < 0) savedPerm = readPermissionFromFile(pkgName);
                if (savedPerm < 0) savedPerm = PermissionStorage.PERMISSION_IGNORE; // 默认放行
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            Log.i(TAG, "权限查询: " + pkgName + " -> " + savedPerm + " (0=拦截,1=放行)");

            String preview = extractPreview(param.args[0]);

            if (savedPerm == PermissionStorage.PERMISSION_IGNORE) {
                writeLog(ctx, pkgName, "放行", preview);
                return; // 直接放行，不设置 sShouldBlock
            }

            // ── 拦截模式：检查防抖 ──
            int decision;
            synchronized (sDebouncelock) {
                long now = System.currentTimeMillis();
                Long lastTime = sLastDecisionTime.get(pkgName);
                if (lastTime != null && now - lastTime < DEBOUNCE_MS) {
                    // 防抖期内沿用上次决策
                    Integer last = sLastUserDecision.get(pkgName);
                    decision = (last != null) ? last : PermissionStorage.PERMISSION_BLOCK;
                    Log.i(TAG, "防抖沿用上次选择: " + pkgName + " -> " + decision);
                } else {
                    decision = -1; // 需要弹窗
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

            if (decision == PermissionStorage.PERMISSION_IGNORE) {
                Log.i(TAG, "用户允许: " + pkgName);
                writeLog(ctx, pkgName, "放行", preview);
                // 不设置 sShouldBlock → afterHookedMethod 不处理
            } else {
                Log.i(TAG, "用户拒绝: " + pkgName + " - 拦截");
                writeLog(ctx, pkgName, "拦截", preview);
                // 阻断原方法执行
                param.setResult(null);
                // 通知 afterHookedMethod 执行真正的剪贴板清空
                sShouldBlock.set(true);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
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

        private String getCallingPackageName() {
            try {
                int uid = Binder.getCallingUid();
                if (uid <= 0) return null;
                Object at = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentActivityThread");
                if (at == null) return null;
                Context ctx = (Context) XposedHelpers.callMethod(at, "getApplication");
                if (ctx == null) return null;
                String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                return (pkgs != null && pkgs.length > 0) ? pkgs[0] : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Context getSystemServerContext() {
            try {
                Object at = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentActivityThread");
                if (at == null) return null;
                return (Context) XposedHelpers.callMethod(at, "getApplication");
            } catch (Throwable e) {
                Log.e(TAG, "获取Context失败: " + e.getMessage());
                return null;
            }
        }

        /**
         * 通过 ContentProvider.call() 读取权限（需外层已 clearCallingIdentity）
         */
        private int directQueryPermission(Context ctx, String pkgName) {
            try {
                android.net.Uri uri = android.net.Uri.parse("content://" + PermissionProvider.AUTHORITY);
                android.os.Bundle args = new android.os.Bundle();
                args.putString(PermissionProvider.CALL_KEY_PACKAGE, pkgName);
                android.os.Bundle result = ctx.getContentResolver()
                        .call(uri, PermissionProvider.CALL_METHOD_GET, null, args);
                if (result != null && result.containsKey(PermissionProvider.CALL_KEY_RESULT)) {
                    return result.getInt(PermissionProvider.CALL_KEY_RESULT, -1);
                }
            } catch (Throwable e) {
                Log.w(TAG, "ContentProvider读取失败: " + e.getMessage());
            }
            return -1;
        }

        /** 直接打开 SQLite 文件读取权限（最终 fallback） */
        private int readPermissionFromFile(String pkgName) {
            String dbPath = "/data/data/" + MODULE_PKG + "/databases/clipboardguard.db";
            android.database.sqlite.SQLiteDatabase db = null;
            try {
                db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                try (android.database.Cursor cursor = db.query(
                        "permission", null,
                        "package_name = ?", new String[]{pkgName},
                        null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        return cursor.getInt(cursor.getColumnIndexOrThrow("permission"));
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "直接DB读取失败: " + e.getMessage());
            } finally {
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
            return -1;
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

        /** 弹窗询问用户，阻塞直到结果返回或超时（默认 BLOCK） */
        private int askUser(Context ctx, String pkgName, String preview) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger result = new AtomicInteger(PermissionStorage.PERMISSION_BLOCK);

            // 先注册轮询监听，再启动 Activity
            new PermissionResultReceiver(pkgName, latch, result).register(ctx);

            // 构建启动 Intent
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(MODULE_PKG, PermissionDialogActivity.class.getName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME, pkgName);
            intent.putExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW, preview);

            boolean started = tryStartActivity(ctx, intent, pkgName);
            if (!started) {
                // 降级：广播
                sendDialogBroadcast(ctx, pkgName, preview);
            }

            try {
                latch.await(7, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int r = result.get();
            Log.i(TAG, "askUser结果: " + pkgName + " -> " + (r == PermissionStorage.PERMISSION_IGNORE ? "允许" : "拒绝"));
            return r;
        }

        private boolean tryStartActivity(Context ctx, Intent intent, String pkgName) {
            try {
                Object atm = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityTaskManager", null),
                        "getService");
                Method start = atm.getClass().getMethod("startActivity",
                        Intent.class, String.class, IBinder.class, String.class,
                        int.class, int.class, int.class, String.class, int.class);
                IBinder token = (IBinder) XposedHelpers.getObjectField(ctx, "mMainThread");
                int userId = android.os.Process.myUserHandle().hashCode();
                start.invoke(atm, intent, MODULE_PKG, token, null, -1, userId, 0, null, 0);
                Log.i(TAG, "ActivityTaskManager启动成功: " + pkgName);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "ActivityTaskManager失败: " + e.getMessage());
                return false;
            }
        }

        private void sendDialogBroadcast(Context ctx, String pkgName, String preview) {
            try {
                Intent bi = new Intent(DialogLaunchReceiver.ACTION_SHOW_DIALOG);
                bi.setPackage(MODULE_PKG);
                bi.putExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME, pkgName);
                bi.putExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW, preview);
                bi.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                ctx.sendBroadcast(bi);
                Log.i(TAG, "广播已发送: " + pkgName);
            } catch (Throwable e) {
                Log.e(TAG, "广播发送失败: " + e.getMessage());
            }
        }
    }

    // ──────────────────────────── 日志写入 ────────────────────────────

    private static void writeLog(Context ctx, String pkgName, String action, String content) {
        if (ctx == null) return;
        try {
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(PREF_CLIP_PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ENABLE_LOG, false)) return;
            PermissionProvider.writeLog(ctx, pkgName, action, content);
        } catch (Throwable e) {
            Log.e(TAG, "写入日志失败: " + e.getMessage());
        }
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
