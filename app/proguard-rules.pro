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

# Release 保留 R8 代码/资源压缩，但不混淆类名，避免影响 LSPosed 激活状态识别。
-dontobfuscate

# Xposed/LSPosed 通过 assets/xposed_init 中的完整类名加载 Hook 入口，不能混淆或移除。
-keep class com.android.clipboardguard.WriteHook { *; }
-keep class com.android.clipboardguard.ReadHook { *; }

# Hook 入口继承/使用 Xposed API，compileOnly 依赖在运行时由框架提供。
-dontwarn de.robv.android.xposed.**
