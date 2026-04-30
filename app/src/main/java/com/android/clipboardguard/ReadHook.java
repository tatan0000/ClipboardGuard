package com.android.clipboardguard;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ClipboardGuard - Xposed模块读取拦截 Hook。
 * 目前只负责挂上 getPrimaryClip，读取拦截逻辑后续再补齐。
 */
public class ReadHook implements IXposedHookLoadPackage {

    private static final String TAG = "ClipboardGuard-Read";
    private static final String MODULE_PKG = "com.android.clipboardguard";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (MODULE_PKG.equals(lpparam.packageName)) {
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            hookGetPrimaryClip(lpparam);
        }
    }

    /** Hook ClipboardService.getPrimaryClip 方法。 */
    private void hookGetPrimaryClip(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] candidates = {
                "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService$BinderService",
                "com.android.server.clipboard.ClipboardService",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
                for (java.lang.reflect.Method method : cls.getDeclaredMethods()) {
                    if ("getPrimaryClip".equals(method.getName())) {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new GetPrimaryClipHook());
                        Log.i(TAG, "ReadHook成功: " + className);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Log.e(TAG, "ReadHook失败：未找到 getPrimaryClip");
    }

    /**
     * 获取 system_server Context。
     * 后续真正补读取拦截时会用到，先保留。
     */
    private Context getSystemServerContext() {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            if (at != null) {
                return (Context) XposedHelpers.callMethod(at, "getApplication");
            }
        } catch (Throwable e) {
            Log.e(TAG, "getSystemServerContext 失败: " + e.getMessage());
        }
        return null;
    }

    private static class GetPrimaryClipHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            // TODO: 实现读取拦截逻辑
        }
    }
}
