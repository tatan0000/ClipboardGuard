package com.android.clipboardguard;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import com.google.android.material.button.MaterialButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.appbar.MaterialToolbar;
import com.android.clipboardguard.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面
 * 
 * 首页（Home）：模块激活状态、版本信息
 * 应用页（Apps）：全部已安装应用，勾选 = 拦截，FAB 保存
 *
 * 权限逻辑：
 *   勾选(checked)  = BLOCK  = 每次写剪贴板弹窗询问
 *   未勾选         = IGNORE = 放行，不拦截
 */
public class MainActivity extends AppCompatActivity {

    // 单例
    private static MainActivity sInstance;
    // 保存当前选择的主题值（用于 recreate 后恢复，-1 = 未初始化）
    private static int sCurrentTheme = -1;
    // 保存当前页面索引（用于 recreate 后恢复，初始化为 0 = PAGE_HOME）
    private static int sCurrentPage = 0;

    // 分组索引
    private static final int GROUP_USER   = 0;
    private static final int GROUP_SYSTEM = 1;
    private static final int GROUP_CORE   = 2;

    // 页面索引
    public static final int PAGE_HOME     = 0;
    public static final int PAGE_APPS    = 1;
    public static final int PAGE_LOG     = 2;
    public static final int PAGE_SETTINGS = 3;

    // 主题选项
    public static final String PREF_NAME = "settings";
    public static final String KEY_THEME  = "theme";
    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK   = 1;
    public static final int THEME_SYSTEM = 2;

    // ── Views ──
    private View mPageHome;
    private View mPageApps;
    private View mPageLog;
    private View mPageSettings;
    private FloatingActionButton mFab;
    // 底部导航
    private LinearLayout mBottomNav;
    private LinearLayout mNavHome;
    private LinearLayout mNavApps;
    private LinearLayout mNavLog;
    private LinearLayout mNavSettings;
    private ExpandableListView mExpandableListView;
    private EditText mEtSearch;
    private TextView mTvStatusTitle;
    private TextView mTvStatusDesc;
    private ImageView mIvStatusIcon;
    private TextView mTvXposedSdk;
    private TextView mTvModuleVersion;
    // 系统信息
    private TextView mTvAndroidVersion;
    private TextView mTvManufacturer;
    private TextView mTvModel;

    // ── 数据 ──
    private AppGroupAdapter mAdapter;
    private final List<AppItem> mUserApps   = new ArrayList<>();
    private final List<AppItem> mSystemApps = new ArrayList<>();
    private final List<AppItem> mCoreApps   = new ArrayList<>();
    private final List<AppItem> mFilteredUser   = new ArrayList<>();
    private final List<AppItem> mFilteredSystem = new ArrayList<>();
    private final List<AppItem> mFilteredCore    = new ArrayList<>();
    private String mCurrentQuery = "";

    // 用于记录「本次操作里修改过的项」（包名→新状态），FAB 保存时批量写入
    private final Map<String, Integer> mPendingChanges = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 在 super.onCreate() 前应用主题样式（使用官方推荐的方式）
        applyThemeNoView();

        super.onCreate(savedInstanceState);

        // 单例
        sInstance = this;

        setContentView(R.layout.activity_main);

        // 初始化主题显示
        initThemeRadioButtons();

        // 应用状态栏颜色（必须在 setContentView 后调用）
        applyTheme();

        // 状态栏高度适配（Toolbar）
        View toolbarView = findViewById(R.id.toolbar);
        MaterialToolbar toolbar = (MaterialToolbar) toolbarView;
        final int padL = toolbar.getPaddingLeft();
        final int padR = toolbar.getPaddingRight();
        final int padB = toolbar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(toolbarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(padL, statusH, padR, padB);
            return insets;
        });
        ViewCompat.requestApplyInsets(toolbarView);

        // 找 View
        mPageHome     = findViewById(R.id.page_home);
        mPageApps    = findViewById(R.id.page_apps);
        mPageLog    = findViewById(R.id.page_log);
        mPageSettings = findViewById(R.id.page_settings);
        mFab       = findViewById(R.id.fab_save);
        mBottomNav = findViewById(R.id.bottom_nav);
        mNavHome   = findViewById(R.id.nav_home);
        mNavApps   = findViewById(R.id.nav_apps);
        mNavLog    = findViewById(R.id.nav_log);
        mNavSettings = findViewById(R.id.nav_settings);

        mTvStatusTitle    = findViewById(R.id.tv_status_title);
        mTvStatusDesc     = findViewById(R.id.tv_status_desc);
        mIvStatusIcon     = findViewById(R.id.iv_status_icon);
        mTvXposedSdk      = findViewById(R.id.tv_xposed_sdk);
        mTvModuleVersion  = findViewById(R.id.tv_module_version);
        // 系统信息
        mTvAndroidVersion    = findViewById(R.id.tv_android_version);
        mTvManufacturer     = findViewById(R.id.tv_manufacturer);
        mTvModel            = findViewById(R.id.tv_model);


        mEtSearch          = findViewById(R.id.et_search);
        mExpandableListView = findViewById(R.id.expandable_list);

        // 全选/反选按钮
        MaterialButton btnSelectAll = findViewById(R.id.btn_select_all);
        MaterialButton btnDeselectAll = findViewById(R.id.btn_deselect_all);
        btnSelectAll.setOnClickListener(v -> {
            setAllAppsBlocked(true);
            mAdapter.notifyDataSetChanged();
        });
        btnDeselectAll.setOnClickListener(v -> {
            setAllAppsBlocked(false);
            mAdapter.notifyDataSetChanged();
        });

        // 初始化状态页信息
        initHomePage();

        // 应用列表 Adapter
        mAdapter = new AppGroupAdapter();
        mExpandableListView.setAdapter(mAdapter);

        // 点击列表条目 → 切换勾选并记录待保存变更
        mExpandableListView.setOnChildClickListener((parent, v, groupPos, childPos, id) -> {
            AppItem item = getItem(groupPos, childPos);
            if (item == null) return false;

            // 系统核心包不可更改
            if (item.isCore) return false;

            // 切换勾选状态（勾选 = 拦截）
            item.isBlocked = !item.isBlocked;
            // 记录到待保存 Map（每次点击都更新，点多次只算最后状态）
            mPendingChanges.put(item.packageName,
                    item.isBlocked ? PermissionStorage.PERMISSION_BLOCK
                                   : PermissionStorage.PERMISSION_IGNORE);
            mAdapter.notifyDataSetChanged();
            return true;
        });

        // 搜索过滤
        mEtSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                mCurrentQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        // FAB 保存
        mFab.setOnClickListener(v -> saveChanges());

        // 底部导航（4页：首页、应用、日志、设置）
        View.OnClickListener navClickListener = v -> {
            int id = v.getId();
            if (id == R.id.nav_home) {
                showPage(PAGE_HOME);
            } else if (id == R.id.nav_apps) {
                showPage(PAGE_APPS);
            } else if (id == R.id.nav_log) {
                showPage(PAGE_LOG);
            } else if (id == R.id.nav_settings) {
                showPage(PAGE_SETTINGS);
            }
        };
        mNavHome.setOnClickListener(navClickListener);
        mNavApps.setOnClickListener(navClickListener);
        mNavLog.setOnClickListener(navClickListener);
        mNavSettings.setOnClickListener(navClickListener);

        // 根据保存的页面索引恢复
        switch (sCurrentPage) {
            case PAGE_APPS:
                showPage(PAGE_APPS);
                break;
            case PAGE_LOG:
                showPage(PAGE_LOG);
                break;
            case PAGE_SETTINGS:
                showPage(PAGE_SETTINGS);
                break;
            default:
                showPage(PAGE_HOME);
                break;
        }

        // 加载应用列表（后台）
        loadAppsAsync();

        // ──────────────────────────── 设置页交互 ────────────────────────────
        // 主题选择 - 亮色
        View itemThemeLight = findViewById(R.id.item_theme_light);
        if (itemThemeLight != null) {
            itemThemeLight.setOnClickListener(v -> {
                switchTheme(THEME_LIGHT);
                updateThemeRadioButtons(THEME_LIGHT);
                // switchTheme 已调用 AppCompatDelegate，会自动重建 Activity
            });
        }

        // 主题选择 - 暗色
        View itemThemeDark = findViewById(R.id.item_theme_dark);
        if (itemThemeDark != null) {
            itemThemeDark.setOnClickListener(v -> {
                switchTheme(THEME_DARK);
                updateThemeRadioButtons(THEME_DARK);
                // switchTheme 已调用 AppCompatDelegate，会自动重建 Activity
            });
        }

        // 主题选择 - 跟随系统
        View itemThemeSystem = findViewById(R.id.item_theme_system);
        if (itemThemeSystem != null) {
            itemThemeSystem.setOnClickListener(v -> {
                switchTheme(THEME_SYSTEM);
                updateThemeRadioButtons(THEME_SYSTEM);
                // switchTheme 已调用 AppCompatDelegate，会自动重建 Activity
            });
        }

        // 关于模块点击
        View itemAbout = findViewById(R.id.item_about);
        if (itemAbout != null) {
            itemAbout.setOnClickListener(v -> {
                // TODO: 跳转到关于页或弹窗
                Toast.makeText(this, "模块版本: " + getModuleVersion(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 如果已加载过，刷新权限（防止外部改动）
        if (!mUserApps.isEmpty() || !mSystemApps.isEmpty() || !mCoreApps.isEmpty()) {
            refreshPermissions();
        }
    }

    // ──────────────────────────── 页面切换 ────────────────────────────

    private void showPage(int page) {
        // 保存当前页（用于 recreate 后恢复）
        sCurrentPage = page;

        // 离开应用页时，丢弃未保存的更改
        if (!mPendingChanges.isEmpty()) {
            mPendingChanges.clear();
            // 恢复所有应用到已保存的状态
            refreshPermissions();
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
        }

        // 先隐藏全部
        mPageHome.setVisibility(View.GONE);
        mPageApps.setVisibility(View.GONE);
        mPageLog.setVisibility(View.GONE);
        mPageSettings.setVisibility(View.GONE);

        // 显示选中页
        switch (page) {
            case PAGE_HOME:
                mPageHome.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                break;
            case PAGE_APPS:
                mPageApps.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.VISIBLE);
                // 展开用户应用
                mExpandableListView.expandGroup(GROUP_USER);
                break;
            case PAGE_LOG:
                mPageLog.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                break;
            case PAGE_SETTINGS:
                mPageSettings.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                break;
        }

        // 标题栏
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        switch (page) {
            case PAGE_HOME:
                toolbar.setTitle(R.string.app_name);
                break;
            case PAGE_APPS:
                toolbar.setTitle(R.string.nav_apps);
                break;
            case PAGE_LOG:
                toolbar.setTitle(R.string.nav_log);
                break;
            case PAGE_SETTINGS:
                toolbar.setTitle(R.string.nav_settings);
                break;
        }

        // 更新底部导航图标和文字颜色
        int selectedColor = getColor(R.color.nav_selected);
        int unselectedColor = getColor(R.color.nav_unselected);
        // 图标
        ((ImageView) mNavHome.getChildAt(0)).setColorFilter(page == PAGE_HOME ? selectedColor : unselectedColor);
        ((ImageView) mNavApps.getChildAt(0)).setColorFilter(page == PAGE_APPS ? selectedColor : unselectedColor);
        ((ImageView) mNavLog.getChildAt(0)).setColorFilter(page == PAGE_LOG ? selectedColor : unselectedColor);
        ((ImageView) mNavSettings.getChildAt(0)).setColorFilter(page == PAGE_SETTINGS ? selectedColor : unselectedColor);
        // 文字
        ((TextView) mNavHome.getChildAt(1)).setTextColor(page == PAGE_HOME ? selectedColor : unselectedColor);
        ((TextView) mNavApps.getChildAt(1)).setTextColor(page == PAGE_APPS ? selectedColor : unselectedColor);
        ((TextView) mNavLog.getChildAt(1)).setTextColor(page == PAGE_LOG ? selectedColor : unselectedColor);
        ((TextView) mNavSettings.getChildAt(1)).setTextColor(page == PAGE_SETTINGS ? selectedColor : unselectedColor);
    }

    // ──────────────────────────── 首页初始化 ────────────────────────────

    private void initHomePage() {
        // 模块是否激活：检测 Hook 是否注入了特殊字段（Xposed 会替换这里）
        boolean isActive = isModuleActive();

        if (isActive) {
            mTvStatusTitle.setText(R.string.status_active);
            mTvStatusTitle.setTextColor(getColor(R.color.status_active));
            mTvStatusDesc.setText(R.string.status_active_desc);
            mIvStatusIcon.setImageResource(R.drawable.ic_shield_on);
        } else {
            mTvStatusTitle.setText(R.string.status_not_active);
            mTvStatusTitle.setTextColor(getColor(R.color.status_inactive));
            mTvStatusDesc.setText(R.string.status_not_active_desc);
            mIvStatusIcon.setImageResource(R.drawable.ic_shield_off);
        }

        // Xposed API 版本（通过 Hook 注入到静态字段，未激活时为 -1）
        int xposedApi = getXposedApiVersion();
        mTvXposedSdk.setText(xposedApi > 0 ? String.valueOf(xposedApi) : "未检测到");

        // 模块版本
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            mTvModuleVersion.setText("v" + pi.versionName
                    + " (" + pi.versionCode + ")");
        } catch (PackageManager.NameNotFoundException e) {
            mTvModuleVersion.setText("--");
        }

        // ── 系统信息（主线程快速设置） ──
        // Android 版本、设备信息（主线程即可，很轻量）
        mTvAndroidVersion.setText(Build.VERSION.RELEASE);
        mTvManufacturer.setText(Build.MANUFACTURER);
        mTvModel.setText(Build.MODEL);
    }

    /**
     * 检测模块是否激活：
     * Hook.java 的 handleLoadPackage 会在激活时把 sModuleActive 设为 true，
     * 但 Hook 运行在 system_server 进程，这里只能用约定字段判断。
     * XposedSmsCode 的做法：在 Hook 里把静态字段改成 true，
     * 我们在这里用同样约定（默认 false，模块 Hook 自身后置为 true）。
     */
    private boolean isModuleActive() {
        // 默认 false；Xposed 框架会 Hook 这个方法，在激活时返回 true
        return false;
    }

    /** 获取 Xposed API 版本（未激活时 -1）*/
    private int getXposedApiVersion() {
        return -1; // Hook 激活后会替换此方法
    }

    // ──────────────────────────── 主题设置 ────────────────────────────

    /** 在 super.onCreate() 前应用主题（仅设置 AppCompatDelegate） */
    private void applyThemeNoView() {
        int theme;

        // 优先使用静态变量（switchTheme 已同步更新），避免异步写入 SharedPreferences 未完成
        if (sCurrentTheme >= 0) {
            theme = sCurrentTheme;
        } else {
            // 静态变量未初始化，从 SharedPreferences 读取
            Context appCtx = getApplicationContext();
            if (appCtx == null) appCtx = this;
            theme = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY_THEME, THEME_SYSTEM);
            // 同时更新静态变量，避免后续重复读取
            sCurrentTheme = theme;
        }

        // 设置 AppCompatDelegate（必须在 super.onCreate() 前调用）
        switch (theme) {
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

    /** 应用主题设置（包括状态栏颜色） */
    private void applyTheme() {
        // 直接从 SharedPreferences 读取
        int theme = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM);

        // 状态栏颜色
        int statusBarColor = getColor(R.color.color_primary);
        int nightMode = AppCompatDelegate.getDefaultNightMode();
        
        // 根据主题模式设置状态栏
        if (theme == THEME_LIGHT) {
            // 亮色模式：白色状态栏 + 黑色文字
            statusBarColor = Color.WHITE;
        } else if (theme == THEME_DARK) {
            // 深色模式：黑色状态栏 + 白色文字
            statusBarColor = Color.BLACK;
        } else {
            // 跟随系统
            int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                statusBarColor = Color.BLACK;
            } else {
                statusBarColor = Color.WHITE;
            }
        }
        
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(statusBarColor);
        
        // 根据主题设置状态栏文字颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean isLightStatusBar = (theme == THEME_LIGHT) || 
                (theme == THEME_SYSTEM && (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES);
            if (isLightStatusBar) {
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                window.getDecorView().setSystemUiVisibility(0);
            }
        }
    }

    /** 切换主题 - 保存设置并立即应用（官方推荐方式） */
    public static void switchTheme(int theme) {
        if (theme < THEME_LIGHT || theme > THEME_SYSTEM) return;

        // 立即更新静态变量，避免 Activity 重建时从 SharedPreferences 读取失败
        sCurrentTheme = theme;

        // 立即设置 AppCompatDelegate（官方推荐方式，会自动重建Activity）
        switch (theme) {
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

        // 保存到 SharedPreferences（使用同步写入确保立即生效）
        try {
            Context ctx = sInstance;
            if (ctx != null) {
                Context appCtx = ctx.getApplicationContext();
                if (appCtx == null) appCtx = ctx;
                SharedPreferences sp = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                sp.edit().putInt(KEY_THEME, theme).commit(); // 使用 commit() 同步写入
            }
        } catch (Exception e) {
            // 忽略错误，避免崩溃
        }
    }

    /** 更新主题RadioButton状态 */
    private void updateThemeRadioButtons(int theme) {
        RadioButton rbLight = findViewById(R.id.rb_theme_light);
        RadioButton rbDark = findViewById(R.id.rb_theme_dark);
        RadioButton rbSystem = findViewById(R.id.rb_theme_system);
        if (rbLight == null || rbDark == null || rbSystem == null) return;

        rbLight.setChecked(theme == THEME_LIGHT);
        rbDark.setChecked(theme == THEME_DARK);
        rbSystem.setChecked(theme == THEME_SYSTEM);
    }

    /** 初始化主题RadioButton状态 */
    private void initThemeRadioButtons() {
        int theme;
        if (sCurrentTheme >= 0) {
            theme = sCurrentTheme;
        } else {
            theme = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM);
        }
        updateThemeRadioButtons(theme);
    }

    /** 获取主题选项文本 */
    private String getThemeText(int theme) {
        switch (theme) {
            case THEME_LIGHT:
                return getString(R.string.theme_light);
            case THEME_DARK:
                return getString(R.string.theme_dark);
            case THEME_SYSTEM:
            default:
                return getString(R.string.theme_follow_system);
        }
    }

    /** 获取模块版本号 */
    private String getModuleVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "--";
        }
    }

    // ──────────────────────────── 应用列表加载 ────────────────────────────

    @SuppressWarnings("deprecation")
    private void loadAppsAsync() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                loadAllApps();
                return null;
            }

            @Override
            protected void onPostExecute(Void unused) {
                applyFilter();
                mExpandableListView.expandGroup(GROUP_USER);
            }
        }.execute();
    }

    /** 从资源读取系统核心包白名单（来自 Thanox global_white_list.xml），用于 UI 过滤 */
    private static HashSet<String> sCorePackages;

    /** 初始化系统核心包白名单（只在主线程调用一次） */
    private void initCorePackages() {
        if (sCorePackages != null) return;
        sCorePackages = new HashSet<>();
        String[] arr = getResources().getStringArray(R.array.global_whitelist_packages);
        Collections.addAll(sCorePackages, arr);
    }

    /** 判断是否是应该从列表中排除的系统核心包（精确匹配或子包匹配） */
    private boolean isCoreSystemPackage(String pkgName) {
        initCorePackages();
        if (sCorePackages.contains(pkgName)) return true;
        // 支持子包前缀匹配（如 com.android.systemui.*）
        for (String core : sCorePackages) {
            if (pkgName.startsWith(core + ".")) return true;
        }
        return false;
    }

    private void loadAllApps() {
        mUserApps.clear();
        mSystemApps.clear();
        mCoreApps.clear();

        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        // 拉取已保存的权限配置
        List<String[]> savedPerms = PermissionProvider.getAllPermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) {
            permMap.put(row[0], Integer.parseInt(row[1]));
        }

        final String modulePkg = "com.android.clipboardguard";

        for (ApplicationInfo info : apps) {
            if (modulePkg.equals(info.packageName)) continue;

            boolean isCore = isCoreSystemPackage(info.packageName);

            AppItem item = new AppItem();
            item.packageName = info.packageName;
            item.appName     = pm.getApplicationLabel(info).toString();
            item.isSystem    = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            item.isCore      = isCore;
            try {
                item.icon = pm.getApplicationIcon(info);
            } catch (Throwable e) {
                item.icon = getDrawable(R.drawable.ic_app_default);
            }

            // 数据库中有记录且为 BLOCK(0) → 勾选（拦截）
            // 未记录或 IGNORE(1) → 不勾选（放行）
            Integer saved = permMap.get(item.packageName);
            item.isBlocked = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);

            if (isCore) {
                // 系统核心包单独一组，置灰不可改
                mCoreApps.add(item);
            } else if (item.isSystem) {
                mSystemApps.add(item);
            } else {
                mUserApps.add(item);
            }
        }

        Collections.sort(mUserApps,   (a, b) -> {
            // 拦截的排前面（isBlocked=true 排在前面）
            if (a.isBlocked != b.isBlocked) {
                return a.isBlocked ? -1 : 1;
            }
            return a.appName.compareToIgnoreCase(b.appName);
        });
        Collections.sort(mSystemApps, (a, b) -> {
            // 拦截的排前面（isBlocked=true 排在前面）
            if (a.isBlocked != b.isBlocked) {
                return a.isBlocked ? -1 : 1;
            }
            return a.appName.compareToIgnoreCase(b.appName);
        });
        Collections.sort(mCoreApps, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
    }

    /** 刷新权限状态（不重新枚举包，仅更新状态字段），同时把 pendingChanges 合并进来 */
    private void refreshPermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllPermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) {
            permMap.put(row[0], Integer.parseInt(row[1]));
        }

        for (AppItem item : mUserApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlocked = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
            // 再叠加 pending（未保存的本次操作）
            if (mPendingChanges.containsKey(item.packageName)) {
                item.isBlocked = (mPendingChanges.get(item.packageName)
                        == PermissionStorage.PERMISSION_BLOCK);
            }
        }
        for (AppItem item : mSystemApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlocked = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
            if (mPendingChanges.containsKey(item.packageName)) {
                item.isBlocked = (mPendingChanges.get(item.packageName)
                        == PermissionStorage.PERMISSION_BLOCK);
            }
        }
        // 核心包不参与 pendingChanges，但同步权限状态
        for (AppItem item : mCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlocked = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
        }
        applyFilter();
    }

    private void applyFilter() {
        mFilteredUser.clear();
        mFilteredSystem.clear();
        mFilteredCore.clear();

        if (mCurrentQuery.isEmpty()) {
            mFilteredUser.addAll(mUserApps);
            mFilteredSystem.addAll(mSystemApps);
            mFilteredCore.addAll(mCoreApps);
        } else {
            for (AppItem item : mUserApps) {
                if (matches(item, mCurrentQuery)) mFilteredUser.add(item);
            }
            for (AppItem item : mSystemApps) {
                if (matches(item, mCurrentQuery)) mFilteredSystem.add(item);
            }
            for (AppItem item : mCoreApps) {
                if (matches(item, mCurrentQuery)) mFilteredCore.add(item);
            }
        }

        runOnUiThread(() -> {
            mAdapter.notifyDataSetChanged();
            if (!mCurrentQuery.isEmpty()) {
                mExpandableListView.expandGroup(GROUP_USER);
                mExpandableListView.expandGroup(GROUP_SYSTEM);
                mExpandableListView.expandGroup(GROUP_CORE);
            }
        });
    }

    private boolean matches(AppItem item, String query) {
        return item.appName.toLowerCase(Locale.ROOT).contains(query)
                || item.packageName.toLowerCase(Locale.ROOT).contains(query);
    }

    private AppItem getItem(int groupPos, int childPos) {
        if (groupPos == GROUP_USER   && childPos < mFilteredUser.size())    return mFilteredUser.get(childPos);
        if (groupPos == GROUP_SYSTEM && childPos < mFilteredSystem.size()) return mFilteredSystem.get(childPos);
        if (groupPos == GROUP_CORE   && childPos < mFilteredCore.size())    return mFilteredCore.get(childPos);
        return null;
    }

    // ──────────────────────────── 全选/全不选 ────────────────────────────

    /** 将所有（全量，非仅过滤后）应用设为拦截或放行，并记录到 pending（核心包除外） */
    private void setAllAppsBlocked(boolean blocked) {
        int perm = blocked ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE;
        for (AppItem item : mUserApps) {
            item.isBlocked = blocked;
            mPendingChanges.put(item.packageName, perm);
        }
        for (AppItem item : mSystemApps) {
            item.isBlocked = blocked;
            mPendingChanges.put(item.packageName, perm);
        }
        // 核心包不参与全选/全不选操作
        applyFilter();
    }

    // ──────────────────────────── 保存 ────────────────────────────

    /** FAB 点击：将所有 pending 变更批量写入（ContentProvider + SharedPreferences + 系统文件） */
    private void saveChanges() {
        if (mPendingChanges.isEmpty()) {
            Toast.makeText(this, "没有更改需要保存", Toast.LENGTH_SHORT).show();
            return;
        }

        for (Map.Entry<String, Integer> entry : mPendingChanges.entrySet()) {
            PermissionStorage.setPermission(this, entry.getKey(), entry.getValue());
        }
        mPendingChanges.clear();

        // 统计保存后所有拦截应用的总数
        int blockedCount = 0;
        for (AppItem item : mUserApps) {
            if (item.isBlocked) blockedCount++;
        }
        for (AppItem item : mSystemApps) {
            if (item.isBlocked) blockedCount++;
        }
        // 核心包不计入保存计数（核心包不参与 pendingChanges）

        String msg = blockedCount > 0
                ? getString(R.string.save_success, blockedCount)
                : getString(R.string.save_no_block);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ──────────────────────────── 数据模型 ────────────────────────────

    static class AppItem {
        String   packageName;
        String   appName;
        Drawable icon;
        boolean  isSystem;
        boolean  isCore;    // 是否为系统核心包（置灰，不可更改）
        boolean  isBlocked; // true=拦截(勾选), false=放行(不勾选)
    }

    // ──────────────────────────── Adapter ────────────────────────────

    class AppGroupAdapter extends BaseExpandableListAdapter {

        @Override public int getGroupCount() { return 3; }

        @Override
        public int getChildrenCount(int groupPos) {
            if (groupPos == GROUP_USER)   return mFilteredUser.size();
            if (groupPos == GROUP_SYSTEM) return mFilteredSystem.size();
            return mFilteredCore.size();
        }

        @Override public Object getGroup(int gp)              { return gp; }
        @Override public Object getChild(int gp, int cp)      { return getItem(gp, cp); }
        @Override public long   getGroupId(int gp)            { return gp; }
        @Override public long   getChildId(int gp, int cp)    { return cp; }
        @Override public boolean hasStableIds()               { return false; }
        @Override public boolean isChildSelectable(int gp, int cp) {
            // 核心包不可选中/不可点击
            if (gp == GROUP_CORE) return false;
            return true;
        }

        @Override
        public View getGroupView(int groupPos, boolean isExpanded, View convertView, ViewGroup parent) {
            GroupViewHolder holder;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_group_header, parent, false);
                holder = new GroupViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (GroupViewHolder) convertView.getTag();
            }

            boolean isUser = (groupPos == GROUP_USER);
            boolean isCore = (groupPos == GROUP_CORE);
            int count = isCore ? mFilteredCore.size()
                    : isUser ? mFilteredUser.size() : mFilteredSystem.size();
            int blockedCount = 0;
            List<AppItem> list = isCore ? mFilteredCore
                    : isUser ? mFilteredUser : mFilteredSystem;
            for (AppItem item : list) {
                if (item.isBlocked) blockedCount++;
            }

            holder.tvArrow.setText(isExpanded ? "▲" : "▼");
            if (isCore) {
                holder.tvTitle.setText(getString(R.string.group_core_apps) + "  " + count + " 个（不可更改）🔒");
            } else {
                holder.tvTitle.setText((isUser ? getString(R.string.group_user_apps)
                                               : getString(R.string.group_system_apps))
                        + "  " + count + " 个"
                        + (blockedCount > 0 ? "（拦截 " + blockedCount + "）" : ""));
            }
            return convertView;
        }

        @Override
        public View getChildView(int groupPos, int childPos, boolean isLastChild,
                                 View convertView, ViewGroup parent) {
            ChildViewHolder holder;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_app_permission, parent, false);
                holder = new ChildViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ChildViewHolder) convertView.getTag();
            }

            AppItem item = getItem(groupPos, childPos);
            if (item == null) return convertView;

            holder.ivIcon.setImageDrawable(item.icon);
            holder.tvName.setText(item.appName);
            holder.tvPkg.setText(item.packageName);

            // 勾选 = 拦截；核心包置灰不可改
            holder.cbBlock.setChecked(item.isBlocked);
            holder.cbBlock.setEnabled(!item.isCore);
            holder.tvName.setEnabled(!item.isCore);
            holder.tvPkg.setEnabled(!item.isCore);
            if (item.isCore) {
                holder.tvPkg.setText(item.packageName + "  🔒");
            } else {
                holder.tvPkg.setText(item.packageName);
            }

            return convertView;
        }
    }

    static class GroupViewHolder {
        TextView tvTitle, tvArrow;
        GroupViewHolder(View v) {
            tvTitle = v.findViewById(R.id.tv_group_title);
            tvArrow = v.findViewById(R.id.tv_arrow);
        }
    }

    static class ChildViewHolder {
        ImageView ivIcon;
        TextView  tvName, tvPkg;
        CheckBox  cbBlock;
        ChildViewHolder(View v) {
            ivIcon  = v.findViewById(R.id.iv_icon);
            tvName  = v.findViewById(R.id.tv_app_name);
            tvPkg   = v.findViewById(R.id.tv_package_name);
            cbBlock = v.findViewById(R.id.cb_block);
        }
    }
}
