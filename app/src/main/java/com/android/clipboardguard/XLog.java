package com.android.clipboardguard;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * 日志工具类
 * 设计：
 * - 在 system_server（Hook 侧）：由 ClipboardHook 通过 init() 传入 XposedModule 实例
 *   和 log(String) 方法引用，调用后日志输出到 logcat tag "Xposed"，
 *   可被 LSPosed Manager 模块日志页捕获。
 * - 在普通 App 进程：init() 不会被调用，sLogMethod 为 null，回退到 android.util.Log，
 *   日志以原始 tag 输出到 logcat（如 "ClipboardGuard"）。
 */
public class XLog {

    private static final String TAG = "XLog";

    // 由 Hook 侧进程初始化，App 进程此值为 null
    private static volatile Object sModuleInstance = null;
    private static volatile Method sLogMethod = null;

    // ──────────────────────────── 初始化状态 ────────────────────────────

    /**
     * 在 system_server 进程中调用一次，传入 XposedModule 实例和 log() 方法引用。
     * 应在 ClipboardHook.onPackageLoaded() 中、"android" 包名下调用。
     *
     * <p>libxposed API 102 的 log 签名：log(int priority, String tag, String msg, Throwable tr)
     *
     * @param module XposedModule 实例（即 ClipboardHook 自身）
     * @param logMethod XposedModule.log(int, String, String, Throwable) 方法引用
     */
    public static void init(Object module, Method logMethod) {
        if (module != null && logMethod != null) {
            sModuleInstance = module;
            sLogMethod = logMethod;
            Log.i(TAG, "XposedModule.log() 已初始化，日志将输出到 LSPosed 模块日志");
        }
    }

    // ──────────────────────────── 日志内容处理 ────────────────────────────

    /** 对剪贴板内容做日志脱敏：保留前半段非空白字符，后半段替换为星号。 */
    public static String maskClipboardContent(String content) {
        if (content == null || content.isEmpty()) return content;
        int visibleCount = Math.max(1, content.length() / 2);
        StringBuilder builder = new StringBuilder(content.length());
        int contentCount = 0;
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (contentCount < visibleCount || Character.isWhitespace(ch)) {
                builder.append(ch);
            } else {
                builder.append('*');
            }
            if (!Character.isWhitespace(ch)) {
                contentCount++;
            }
        }
        return builder.toString();
    }

    // ──────────────────────────── Xposed 日志桥接 ────────────────────────────

    /**
     * 调用 XposedModule.log(int priority, String tag, String msg, Throwable tr)
     * libxposed API 102 签名。
     */
    private static void xposedLog(int priority, String tag, String msg, Throwable tr) {
        Method m = sLogMethod;
        Object instance = sModuleInstance;
        if (m != null && instance != null) {
            try {
                m.invoke(instance, priority, tag, msg, tr);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "XposedModule.log() 调用失败，转用 Log: " + t.getMessage());
            }
        }
        // fallback：用 XLog 自己的 tag 输出（不应在正常情况下到达）
        Log.w(TAG, "[Xposed-unavailable] " + msg);
    }

    // ──────────────────────────── 对外日志接口 ────────────────────────────

    /** 输出 DEBUG 级别日志，Hook 侧走 XposedModule，App 侧走 Log.d */
    public static void d(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog(Log.DEBUG, tag, msg, null);
        } else {
            Log.d(tag, msg);
        }
    }

    /** 输出 INFO 级别日志，Hook 侧走 XposedModule，App 侧走 Log.i */
    public static void i(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog(Log.INFO, tag, msg, null);
        } else {
            Log.i(tag, msg);
        }
    }

    /** 输出 WARN 级别日志，Hook 侧走 XposedModule，App 侧走 Log.w */
    public static void w(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog(Log.WARN, tag, msg, null);
        } else {
            Log.w(tag, msg);
        }
    }

    /** 输出 ERROR 级别日志，Hook 侧走 XposedModule，App 侧走 Log.e */
    public static void e(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog(Log.ERROR, tag, msg, null);
        } else {
            Log.e(tag, msg);
        }
    }

    /** 输出 ERROR 级别日志（带异常堆栈），Hook 侧走 XposedModule，App 侧走 Log.e */
    public static void e(String tag, String msg, Throwable t) {
        if (sLogMethod != null) {
            xposedLog(Log.ERROR, tag, msg, t);
        } else {
            Log.e(tag, msg, t);
        }
    }

    /** 输出 WARN 级别日志（带异常堆栈），Hook 侧走 XposedModule，App 侧走 Log.w */
    public static void w(String tag, String msg, Throwable t) {
        if (sLogMethod != null) {
            xposedLog(Log.WARN, tag, msg, t);
        } else {
            Log.w(tag, msg, t);
        }
    }
}
