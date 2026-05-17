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

# Xposed/LSPosed 通过 assets/xposed_init 中的完整类名加载 Hook 入口，不能混淆或移除。
-keep class com.android.clipboardguard.WriteHook { *; }
-keep class com.android.clipboardguard.ReadHook { *; }

# MainActivity 激活状态方法由 WriteHook 通过方法名 Hook，不能被 R8 删除或内联。
-keepclassmembers class com.android.clipboardguard.MainActivity {
    private boolean isModuleActive();
    private int getXposedApiVersion();
}

# 系统 Hook 侧通过显式 ComponentName 启动服务，类名不能被混淆。
-keepnames class com.android.clipboardguard.ConfigSyncService

# Android Manifest 组件由系统按类名启动，保留类名更稳。
-keepnames class com.android.clipboardguard.ClipboardGuardApp
-keepnames class com.android.clipboardguard.MainActivity
-keepnames class com.android.clipboardguard.WriteRulesDetailActivity
-keepnames class com.android.clipboardguard.ReadRulesDetailActivity
-keepnames class com.android.clipboardguard.PermissionProvider
-keepnames class com.android.clipboardguard.BootReceiver

# Hook 入口继承/使用 Xposed API，compileOnly 依赖在运行时由框架提供。
-dontwarn de.robv.android.xposed.**
