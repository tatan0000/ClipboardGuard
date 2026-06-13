# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================
# Xposed 模块专用规则
# ============================================================

# 1. Xposed 模块入口类（必须完整保留）
# LSPosed 通过 META-INF/xposed/java_init.list 中的完整类名加载
-keep class com.android.clipboardguard.ClipboardHook { *; }
-keep class com.android.clipboardguard.ClipboardHook$* { *; }

# 2. Xposed API 类（compileOnly 依赖，运行时由框架提供）
#    官方推荐：只保留 XposedModule 子类的构造函数，其余由框架提供
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 3. 保留所有 Hook 回调方法（intercept 方法必须存在）
#    API 102 签名：intercept(XposedInterface.Chain)
-keepclassmembers class * implements io.github.libxposed.api.XposedInterface$Hooker {
    public *** intercept(io.github.libxposed.api.XposedInterface$Chain);
}

# 4. 反射调用的系统类（ClipboardHook 中使用）
-keep class android.app.ActivityThread { *; }
-keep class android.app.ActivityThread$* { *; }
-keep class android.os.ServiceManager { *; }
-keep class com.android.server.clipboard.ClipboardService { *; }
-keep class com.android.server.clipboard.ClipboardService$* { *; }

# 5. 保留反射调用的方法名
-keepclassmembers class android.app.ActivityThread {
    public static *** currentActivityThread();
    public android.app.ContextImpl getSystemContext();
    public android.app.Application getApplication();
}
-keepclassmembers class android.os.ServiceManager {
    public static *** getService(java.lang.String);
}
-keepclassmembers class com.android.server.clipboard.ClipboardService$ClipboardImpl {
    public *** onTransact(int, android.os.Parcel, android.os.Parcel, int);
}

# 6. 日志类（XLog 使用反射调用 XposedModule.log）
-keep class com.android.clipboardguard.XLog { *; }
-keepclassmembers class com.android.clipboardguard.XLog {
    public static *** init(...);
    public static *** log(...);
    public static *** w(...);
    public static *** e(...);
}

# 7. Android Manifest 组件（系统按类名启动）
-keepnames class com.android.clipboardguard.ClipboardGuardApp
-keepnames class com.android.clipboardguard.MainActivity
-keepnames class com.android.clipboardguard.WriteRulesDetailActivity
-keepnames class com.android.clipboardguard.ReadRulesDetailActivity
-keepnames class com.android.clipboardguard.PermissionProvider

# 8. 配置管理类（可能被反射或序列化）
-keep class com.android.clipboardguard.ConfigManager { *; }
-keep class com.android.clipboardguard.ConfigManager$* { *; }
-keep class com.android.clipboardguard.PermissionCache { *; }
-keep class com.android.clipboardguard.PermissionCache$* { *; }

# 9. ContentProvider 和 Service（系统组件）
-keep class com.android.clipboardguard.PermissionProvider { *; }
-keep class com.android.clipboardguard.ConfigBridgeService { *; }

# 10. 防止内联优化破坏 Hook
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# 11. 保留行号信息（调试用）
-keepattributes SourceFile,LineNumberTable

# 12. 保留注解（Xposed 可能需要）
-keepattributes *Annotation*
