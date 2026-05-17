package com.android.clipboardguard;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * 日志工具类
 * 设计：
 * - 在 system_server（Hook 侧）：由 WriteHook / ReadHook 通过 init() 传入已获取的
 *   XposedBridge.log() Method 引用，调用后日志输出到 logcat tag "Xposed"，
 *   可被 LSPosed Manager 模块日志页捕获。
 * - 在普通 App 进程：init() 不会被调用，sLogMethod 为 null，回退到 android.util.Log，
 *   日志以原始 tag 输出到 logcat（如 "ClipboardGuard"）。
 */
public class XLog {

    private static final String TAG = "XLog";

    // 由 Hook 侧进程初始化，App 进程此值为 null
    private static volatile Method sLogMethod = null;

    // ──────────────────────────── 初始化状态 ────────────────────────────

    /**
     * 在 system_server 进程中调用一次，传入 XposedBridge.log(String) 方法引用。
     * 应在 WriteHook.handleLoadPackage() 中、"android" 包名下调用。
     */
    public static void init(Method logMethod) {
        if (logMethod != null) {
            sLogMethod = logMethod;
            Log.i(TAG, "XposedBridge.log() 已初始化，日志将输出到 LSPosed 模块日志");
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

    private static void xposedLog(String msg) {
        Method m = sLogMethod;
        if (m != null) {
            try {
                m.invoke(null, msg);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "XposedBridge.log() 调用失败，转用 Log: " + t.getMessage());
            }
        }
        // fallback：用 XLog 自己的 tag 输出（不应在正常情况下到达）
        Log.w(TAG, "[Xposed-unavailable] " + msg);
    }

    // ──────────────────────────── 对外日志接口 ────────────────────────────

    public static void d(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog("[D][" + tag + "] " + msg);
        } else {
            Log.d(tag, msg);
        }
    }

    public static void i(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog("[I][" + tag + "] " + msg);
        } else {
            Log.i(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog("[W][" + tag + "] " + msg);
        } else {
            Log.w(tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        if (sLogMethod != null) {
            xposedLog("[E][" + tag + "] " + msg);
        } else {
            Log.e(tag, msg);
        }
    }

    public static void e(String tag, String msg, Throwable t) {
        if (sLogMethod != null) {
            xposedLog("[E][" + tag + "] " + msg);
            if (t != null) {
                xposedLog(Log.getStackTraceString(t));
            }
        } else {
            Log.e(tag, msg, t);
        }
    }

    public static void w(String tag, String msg, Throwable t) {
        if (sLogMethod != null) {
            xposedLog("[W][" + tag + "] " + msg);
            if (t != null) {
                xposedLog(Log.getStackTraceString(t));
            }
        } else {
            Log.w(tag, msg, t);
        }
    }
}
