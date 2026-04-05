package com.android.clipboardguard;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
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
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "ClipboardGuard";
    private static final String MODULE_PKG = "com.android.clipboardguard";
    private static final long DEBOUNCE_MS = 1_500;  // 防抖1.5秒
    private static final int DEFAULT_PERMISSION = PermissionStorage.PERMISSION_IGNORE;
    private static final Map<String, Long> sLastPopupTime = new HashMap<>();
    private static final Map<String, Long> sLastDecisionTime = new HashMap<>();  // 用户选择完成时间
    private static final Map<String, Integer> sLastUserDecision = new HashMap<>();
    private static final Object sLock = new Object();
    // 标记：防止 afterHookedMethod 中清空剪贴板时递归触发 hook
    private static final ThreadLocal<Boolean> sInAfterHook = ThreadLocal.withInitial(() -> false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkgName = lpparam.packageName;

        // Hook 本模块自身，用于激活状态检测
        if (MODULE_PKG.equals(pkgName)) {
            hookSelfForActiveStatus(lpparam);
            return;
        }

        // 只 Hook system_server 进程
        if (!"android".equals(pkgName)) return;

        hookClipboardService(lpparam);
    }

    /**
     * Hook 本模块自身，让 MainActivity.isModuleActive() 返回 true
     */
    private void hookSelfForActiveStatus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    MODULE_PKG + ".MainActivity",
                    lpparam.classLoader,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    MODULE_PKG + ".MainActivity",
                    lpparam.classLoader,
                    "getXposedApiVersion",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(XposedBridge.getXposedVersion());
                        }
                    });
            Log.i(TAG, "自身Hook成功：状态检测已激活");
        } catch (Throwable e) {
            Log.e(TAG, "自身Hook失败: " + e.getMessage());
        }
    }

    private void hookClipboardService(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] candidates = {
            "com.android.server.clipboard.ClipboardService$ClipboardImpl",
            "com.android.server.clipboard.ClipboardService$BinderService",
        };

        for (String className : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("setPrimaryClip")) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new ClipboardServiceHook());
                        Log.i(TAG, "Hook成功: " + className);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // 降级：直接Hook外部类
        try {
            Class<?> cls = XposedHelpers.findClass("com.android.server.clipboard.ClipboardService", lpparam.classLoader);
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("setPrimaryClip")) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new ClipboardServiceHook());
                    Log.i(TAG, "Hook成功: ClipboardService");
                    return;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Hook失败: " + e.getMessage());
        }
    }

    /**
     * ClipboardService Hook 回调
     */
    private static class ClipboardServiceHook extends XC_MethodHook {

        // 用于在 before 和 after 之间传递决策结果
        private static final ThreadLocal<Integer> sThreadDecision = ThreadLocal.withInitial(() -> -1);
        // 标记：是否已经处理过（防止重复处理）
        private static final ThreadLocal<Boolean> sThreadHandled = ThreadLocal.withInitial(() -> false);

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            // 防止 afterHookedMethod 中清空剪贴板时递归触发
            if (sInAfterHook.get()) {
                return;
            }

            // 已经处理过，直接跳过（防止递归）
            if (sThreadHandled.get()) {
                sThreadHandled.set(false);
                return;
            }

            // 获取调用者包名
            String pkgName = getCallingPackageName();
            if (pkgName == null || pkgName.isEmpty()) {
                Log.w(TAG, "无法获取调用者包名，放行");
                return;
            }

            // 跳过系统组件
            if (pkgName.startsWith("android") || "system".equals(pkgName)
                    || pkgName.startsWith("com.android.systemui")) {
                return;
            }

            Log.i(TAG, "检测到剪贴板操作: " + pkgName);

            // 从 ActivityThread 获取 system_server 的 Context
            Context systemContext = getSystemServerContext();
            if (systemContext == null) {
                Log.e(TAG, "获取system_server Context失败，放行");
                return;
            }

            // 读取权限：优先通过 IContentProvider 直接调用（绕开 Binder 包名校验）
            // 必须先 clearCallingIdentity：Hook 在 system_server Binder 线程执行，
            // Binder.getCallingUid() 此时返回的是远端调用者(如 Chrome uid=10131)，
            // 不清除的话 ContentResolver.call() 会以 uid=10131 发出，导致包名/uid 不匹配
            long identity = Binder.clearCallingIdentity();
            int savedPerm;
            try {
                savedPerm = directQueryPermission(systemContext, pkgName);
                if (savedPerm < 0) {
                    // fallback: 尝试直接读取 SQLite 文件
                    savedPerm = readPermissionFromFile(pkgName);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            // 全部失败，使用默认值
            if (savedPerm < 0) {
                savedPerm = DEFAULT_PERMISSION;
            }
            Log.i(TAG, "权限查询: " + pkgName + " -> " + savedPerm + " (0=拦截,1=放行)");

            // IGNORE = 放行
            if (savedPerm == PermissionStorage.PERMISSION_IGNORE) {
                Log.i(TAG, "放行: " + pkgName);
                return;
            }

            // 防抖：用户选择完成后3秒内对同一应用不弹窗
            // 防抖期间保持上一次用户的选择
            boolean shouldPopup;
            int decision;
            boolean isDebounce = false;
            synchronized (sLock) {
                long now = System.currentTimeMillis();
                Long lastDecision = sLastDecisionTime.get(pkgName);
                if (lastDecision != null && now - lastDecision < DEBOUNCE_MS) {
                    isDebounce = true;
                    // 防抖期间：应用上次的用户选择
                    Log.i(TAG, "防抖期间保持上次选择: " + pkgName);
                    Integer lastUserDecision = sLastUserDecision.get(pkgName);
                    decision = (lastUserDecision != null) ? lastUserDecision : PermissionStorage.PERMISSION_BLOCK;
                    shouldPopup = false;
                } else {
                    shouldPopup = true;
                    decision = PermissionStorage.PERMISSION_BLOCK; // 默认值，等待弹窗返回
                }
                // 提前保存决策，避免防抖期间决策未保存
                if (shouldPopup) {
                    sLastUserDecision.put(pkgName, decision);
                }
            }

            // 提取内容预览
            String preview = extractPreview(param.args[0]);

            if (shouldPopup) {
                // 弹窗询问用户
                Log.i(TAG, "请求权限: " + pkgName);
                decision = askUser(systemContext, pkgName, preview);
                // 保存用户决定用于防抖期间
                long now = System.currentTimeMillis();
                synchronized (sLock) {
                    sLastUserDecision.put(pkgName, decision);
                    sLastDecisionTime.put(pkgName, now);  // 记录用户选择完成时间
                }
            }

            // 保存决策结果，在 afterHookedMethod 中处理
            sThreadDecision.set(decision);
            // 标记：在 beforeHookedMethod 中标记为已处理
            sThreadHandled.set(true);

            if (decision == PermissionStorage.PERMISSION_IGNORE) {
                // 允许：继续执行原始方法（不设置任何返回值，void方法）
                Log.i(TAG, "用户允许: " + pkgName);
            } else {
                // 拒绝：阻止原始方法执行，然后通过反射调用内部方法实现真正的拦截
                Log.i(TAG, "用户拒绝: " + pkgName + " - 拦截");

                // 保存原始 ClipData（用于可能需要恢复）
                final Object originalClip = param.args[0];

                // 阻止原始方法执行
                param.setResult(null);

                // 在 afterHookedMethod 中调用内部方法恢复/清空剪贴板
                // 这里只是标记，具体清理在 afterHookedMethod 中做
                sThreadHandled.set(true);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            // 防止递归触发
            if (sInAfterHook.get()) {
                return;
            }

            // 检查是否需要处理拦截（用户拒绝的情况）
            int decision = sThreadDecision.get();
            if (decision == PermissionStorage.PERMISSION_BLOCK) {
                // 用户拒绝：通过反射调用内部方法真正清空剪贴板
                // 这样可以绕过我们的 hook，真正实现拦截
                try {
                    // 设置标记，防止递归
                    sInAfterHook.set(true);

                    // 获取 this 对象（ClipboardService 实例）
                    Object clipboardService = param.thisObject;
                    // 调用 setPrimaryClipInternal 方法
                    Method internalMethod = clipboardService.getClass().getDeclaredMethod(
                            "setPrimaryClipInternal",
                            android.content.ClipData.class,
                            String.class,
                            int.class);
                    internalMethod.setAccessible(true);

                    // 传入 null 真正清空剪贴板
                    internalMethod.invoke(clipboardService, null, "clipboardguard_blocked", 0);
                    Log.i(TAG, "已通过内部方法拦截剪贴板写入");
                } catch (NoSuchMethodException e) {
                    // 内部方法不存在是正常的（不同 Android 版本差异）
                    // 降级方案：使用 ClipboardManager 强制清空
                    try {
                        Context systemContext = getSystemServerContext();
                        if (systemContext != null) {
                            Object clipboardManager = systemContext.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboardManager != null) {
                                Method clearMethod = clipboardManager.getClass().getMethod("setPrimaryClip", android.content.ClipData.class);
                                clearMethod.invoke(clipboardManager, (Object) null);
                                Log.i(TAG, "通过系统服务清空剪贴板（已拦截）");
                            }
                        }
                    } catch (Exception ex) {
                        // 降级失败不影响拦截效果（剪贴板已被置空）
                        Log.w(TAG, "降级清空（可忽略）: " + ex.getMessage());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "调用内部方法失败: " + e.getMessage());
                } finally {
                    sInAfterHook.set(false);
                }
            }

            // 清理线程本地变量
            sThreadDecision.remove();
            sThreadHandled.remove();
        }

        private String getCallingPackageName() {
            try {
                int callingUid = Binder.getCallingUid();
                if (callingUid <= 0) return null;

                Object activityThread = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentActivityThread");
                if (activityThread == null) return null;

                Object app = XposedHelpers.callMethod(activityThread, "getApplication");
                if (app == null) return null;

                Context context = (Context) app;
                String[] pkgs = context.getPackageManager().getPackagesForUid(callingUid);
                if (pkgs != null && pkgs.length > 0) {
                    return pkgs[0];
                }
            } catch (Throwable ignored) {}
            return null;
        }



        /**
         * 核心方案：通过 Binder.clearCallingIdentity() + ContentResolver.call() 读取权限。
         *
         * 根本原因：Hook 在 system_server Binder 线程执行，此时 Binder.getCallingUid()
         * 返回的是远端调用者的 uid（如 Chrome uid=10131），而 ContentResolver.call()
         * 会用当前 Binder 调用者的 uid 去和 callingPackage 做绑定校验，导致报错：
         *   "Given calling package android does not match caller's uid 10131"
         *
         * clearCallingIdentity() 会把 Binder 调用者 uid 重置为本进程 uid=1000（android），
         * 与 callingPackage "android" 完全匹配，校验通过。
         */
        private int directQueryPermission(Context systemContext, String pkgName) {
            try {
                android.net.Uri uri = android.net.Uri.parse(
                        "content://" + PermissionProvider.AUTHORITY);
                android.os.Bundle args = new android.os.Bundle();
                args.putString(PermissionProvider.CALL_KEY_PACKAGE, pkgName);
                // 此处 callingIdentity 已由外层 clearCallingIdentity() 处理，
                // 直接用 systemContext.getContentResolver().call() 即可正常通过校验
                android.os.Bundle result = systemContext.getContentResolver()
                        .call(uri, PermissionProvider.CALL_METHOD_GET, null, args);
                if (result != null && result.containsKey(PermissionProvider.CALL_KEY_RESULT)) {
                    int perm = result.getInt(PermissionProvider.CALL_KEY_RESULT, -1);
                    Log.d(TAG, "ContentProvider读取: " + pkgName + " = " + perm);
                    return perm;
                }
            } catch (Throwable e) {
                Log.w(TAG, "directQueryPermission失败: " + e.getMessage());
            }
            return -1;
        }

        /**
         * 直接读取 SQLite 数据库文件（最终 fallback，不依赖任何 IPC）
         * system_server (uid=1000) 在 Android 10+ 的 SELinux 策略中通常允许读取
         * /data/data/<pkg>/databases/ 下的 db 文件（通过 shell 域）
         */
        private int readPermissionFromFile(String pkgName) {
            String dbPath = "/data/data/" + MODULE_PKG + "/databases/clipboardguard.db";
            android.database.sqlite.SQLiteDatabase db = null;
            try {
                db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbPath, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                try (android.database.Cursor cursor = db.query(
                        "permission", null,
                        "package_name = ?", new String[]{pkgName},
                        null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int perm = cursor.getInt(cursor.getColumnIndexOrThrow("permission"));
                        Log.d(TAG, "直接DB读取: " + pkgName + " = " + perm);
                        return perm;
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "直接DB读取失败: " + e.getMessage());
            } finally {
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
            return -1;
        }

        /**
         * 查询 pending 表获取最新的弹窗结果（通过 ContentProvider 跨进程读取）
         */
        private int queryPendingResult(Context systemContext, String pkgName) {
            try {
                // 使用 ContentResolver.query 跨进程查询 pending 表
                android.content.ContentResolver resolver = systemContext.getContentResolver();
                android.net.Uri uri = android.net.Uri.parse("content://" + PermissionProvider.AUTHORITY + "/pending/" + pkgName);
                // 直接 query，selection 已在 URI 中
                android.database.Cursor cursor = resolver.query(uri, null, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int decisionIdx = cursor.getColumnIndex("decision");
                            if (decisionIdx >= 0) {
                                int decision = cursor.getInt(decisionIdx);
                                Log.d(TAG, "读取pending结果: " + pkgName + " = " + decision);
                                // 读取后删除 pending 记录
                                resolver.delete(uri, null, null);
                                cursor.close();
                                return decision;
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "查询pending结果失败: " + e.getMessage());
            }
            return -1;
        }

        /**
         * 获取 system_server 进程的 Context
         */
        private Context getSystemServerContext() {
            try {
                Object activityThread = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentActivityThread");
                if (activityThread == null) return null;

                Object app = XposedHelpers.callMethod(activityThread, "getApplication");
                return (Context) app;
            } catch (Throwable e) {
                Log.e(TAG, "获取Context失败: " + e.getMessage());
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
                    text = item.getHtmlText();
                    if (text != null && text.length() > 0) {
                        String s = text.toString().trim().replaceAll("<[^>]+>", "");
                        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
                    }
                    if (item.getUri() != null) return "[图片/文件]";
                }
            } catch (Throwable ignored) {}
            return "(非文本内容)";
        }

        private int askUser(Context context, String pkgName, String preview) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger result = new AtomicInteger(PermissionStorage.PERMISSION_BLOCK);

            PermissionResultReceiver receiver = new PermissionResultReceiver(pkgName, latch, result);
            receiver.register(context);

            // 构建启动参数
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(MODULE_PKG, PermissionDialogActivity.class.getName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME, pkgName);
            intent.putExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW, preview);

            try {
                // 获取 ActivityTaskManager（高版本）或 ActivityManager（低版本）
                Object activityTaskManager = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityTaskManager", null),
                        "getService");

                // 调用 startActivity
                Method startActivity = activityTaskManager.getClass().getMethod(
                        "startActivity", Intent.class, String.class, IBinder.class, String.class,
                        int.class, int.class, int.class, String.class, int.class);

                // 获取 system_server 的 token（作为调用者）
                IBinder windowToken = (IBinder) XposedHelpers.getObjectField(context, "mMainThread");

                int userId = android.os.Process.myUserHandle().hashCode();

                startActivity.invoke(activityTaskManager,
                        intent, "com.android.clipboardguard", windowToken, null,
                        -1, userId, 0, null, 0);

                Log.i(TAG, "Activity启动成功: " + pkgName);
            } catch (Exception e) {
                // 降级方案：使用广播
                try {
                    Log.w(TAG, "ActivityTaskManager失败，降级广播: " + e.getMessage());
                    Intent broadcastIntent = new Intent("com.android.clipboardguard.ACTION_SHOW_DIALOG");
                    broadcastIntent.setPackage(MODULE_PKG);
                    broadcastIntent.putExtra(PermissionDialogActivity.EXTRA_PACKAGE_NAME, pkgName);
                    broadcastIntent.putExtra(PermissionDialogActivity.EXTRA_CONTENT_PREVIEW, preview);
                    broadcastIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    context.sendBroadcast(broadcastIntent);
                    Log.i(TAG, "广播已发送: " + pkgName);
                } catch (Throwable e2) {
                    Log.e(TAG, "启动弹窗失败: " + e2.getMessage());
                    return PermissionStorage.PERMISSION_BLOCK;
                }
            }

            try {
                // 弹窗超时4秒 + 3秒通信余量 = 7秒
                // 防抖1.5秒内不再弹窗
                latch.await(7, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int r = result.get();
            Log.i(TAG, "askUser结果: " + pkgName + " -> " + (r == PermissionStorage.PERMISSION_IGNORE ? "允许" : "拒绝"));
            return r;
        }
    }
}