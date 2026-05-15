package com.android.clipboardguard;

import android.app.Application;
import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

/*
 * 自定义 Application，用于初始化全局主题设置
 *
 * 注意：
 * - 权限存储由 PermissionProvider 管理（纯文本文件，App 端自动初始化）
 * - PermissionCache 初始化在 Hook.java（system_server 侧）完成
 * - App 端不需要关心 blockSet，那是 Hook 侧专用的
 */
public class ClipboardGuardApp extends Application {

    private static final String TAG = "ClipboardGuardApp";

    public static final String PREF_NAME = "settings";
    public static final String KEY_THEME = "theme";
    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_SYSTEM = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        // 权限存储由 PermissionProvider 处理，App 端不需要手动初始化
        // Hook 侧的 PermissionCache.loadIgnoreSet() 在 system_server 启动时完成
        applyTheme();
    }

    /**
     * 应用主题设置
     */
    private void applyTheme() {
        int savedTheme = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, THEME_SYSTEM);

        switch (savedTheme) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}