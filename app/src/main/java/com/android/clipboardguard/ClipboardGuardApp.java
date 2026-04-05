package com.android.clipboardguard;

import android.app.Application;
import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * 自定义 Application，用于初始化全局主题设置
 */
public class ClipboardGuardApp extends Application {

    public static final String PREF_NAME = "settings";
    public static final String KEY_THEME = "theme";
    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_SYSTEM = 2;

    @Override
    public void onCreate() {
        super.onCreate();

        // 在应用启动时从 SharedPreferences 读取主题设置
        // 并在 super.onCreate() 之前设置 AppCompatDelegate
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