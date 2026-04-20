package com.android.clipboardguard;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.net.Uri;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // 静态实例（WeakReference 防内存泄漏）
    private static WeakReference<MainActivity> sInstanceRef;

    // 保存当前主题（recreate 后恢复，-1 = 未初始化）
    private static int sCurrentTheme = -1;
    // 保存当前页（recreate 后恢复，0 = PAGE_HOME）
    private static int sCurrentPage = 0;

    // 分组索引
    private static final int GROUP_USER   = 0;
    private static final int GROUP_SYSTEM = 1;
    private static final int GROUP_CORE   = 2;

    // 页面索引
    public static final int PAGE_HOME              = 0;
    public static final int PAGE_APPS              = 1;
    public static final int PAGE_LOG               = 2;
    public static final int PAGE_SETTINGS          = 3;
    public static final int PAGE_PERMISSION_DETAIL = 4;

    // 主题常量（引用 ClipboardGuardApp，避免多处重复定义）
    public static final String PREF_NAME   = ClipboardGuardApp.PREF_NAME;
    public static final String KEY_THEME   = ClipboardGuardApp.KEY_THEME;
    public static final int    THEME_LIGHT  = ClipboardGuardApp.THEME_LIGHT;
    public static final int    THEME_DARK   = ClipboardGuardApp.THEME_DARK;
    public static final int    THEME_SYSTEM = ClipboardGuardApp.THEME_SYSTEM;

    // 首次使用引导
    private static final String KEY_FIRST_LAUNCH = "first_launch";

    // ── Views ──
    private View mPageHome;
    private View mPageApps;
    private View mPageLog;
    private View mPageSettings;
    private View mPagePermissionDetail;
    private FloatingActionButton mFab;
    private LinearLayout mBottomNav;
    private LinearLayout mNavHome, mNavApps, mNavLog, mNavSettings;
    private ExpandableListView mExpandableListView;
    private EditText mEtSearch;
    private TextView mTvStatusTitle, mTvStatusDesc;
    private ImageView mIvStatusIcon;
    private TextView mTvXposedSdk, mTvModuleVersion;
    private TextView mTvAndroidVersion, mTvManufacturer, mTvModel;

    // ── 数据 ──
    private AppGroupAdapter mAdapter;
    private final List<AppItem> mUserApps   = new ArrayList<>();
    private final List<AppItem> mSystemApps = new ArrayList<>();
    private final List<AppItem> mCoreApps   = new ArrayList<>();
    private final List<AppItem> mFilteredUser   = new ArrayList<>();
    private final List<AppItem> mFilteredSystem = new ArrayList<>();
    private final List<AppItem> mFilteredCore   = new ArrayList<>();
    private String mCurrentQuery = "";
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private AlertDialog mGuideDialog;
    private View        mGuideView;

    /** 未保存的变更缓存（包名 → 新状态），FAB 保存时批量写入 */
    private final Map<String, Integer> mPendingChanges = new HashMap<>();

    // 日志
    private RecyclerView mRvLog;
    private LogAdapter   mLogAdapter;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final int REQUEST_CODE_FLOAT_WINDOW = 1001;

    // ──────────────────────────── 生命周期 ────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyThemeNoView();
        super.onCreate(savedInstanceState);

        sInstanceRef = new WeakReference<>(this);
        setContentView(R.layout.activity_main);

        initThemeRadioButtons();
        applyTheme();

        // 状态栏间距适配
        View appBarView = findViewById(R.id.app_bar);
        ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0),
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarView);

        // Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (sCurrentPage == PAGE_PERMISSION_DETAIL) showPage(PAGE_SETTINGS);
        });

        // 找 View
        mPageHome             = findViewById(R.id.page_home);
        mPageApps             = findViewById(R.id.page_apps);
        mPageLog              = findViewById(R.id.page_log);
        mPageSettings         = findViewById(R.id.page_settings);
        mPagePermissionDetail = findViewById(R.id.page_permission_detail);
        mFab                  = findViewById(R.id.fab_save);
        mBottomNav            = findViewById(R.id.bottom_nav);
        mNavHome              = findViewById(R.id.nav_home);
        mNavApps              = findViewById(R.id.nav_apps);
        mNavLog               = findViewById(R.id.nav_log);
        mNavSettings          = findViewById(R.id.nav_settings);
        mTvStatusTitle        = findViewById(R.id.tv_status_title);
        mTvStatusDesc         = findViewById(R.id.tv_status_desc);
        mIvStatusIcon         = findViewById(R.id.iv_status_icon);
        mTvXposedSdk          = findViewById(R.id.tv_xposed_sdk);
        mTvModuleVersion      = findViewById(R.id.tv_module_version);
        mTvAndroidVersion     = findViewById(R.id.tv_android_version);
        mTvManufacturer       = findViewById(R.id.tv_manufacturer);
        mTvModel              = findViewById(R.id.tv_model);
        mEtSearch             = findViewById(R.id.et_search);
        mExpandableListView   = findViewById(R.id.expandable_list);

        // 日志
        mRvLog = findViewById(R.id.rv_log);
        mLogAdapter = new LogAdapter();
        mRvLog.setLayoutManager(new LinearLayoutManager(this));
        mRvLog.setAdapter(mLogAdapter);

        // 全选/反选
        MaterialButton btnSelectAll   = findViewById(R.id.btn_select_all);
        MaterialButton btnDeselectAll = findViewById(R.id.btn_deselect_all);
        btnSelectAll.setOnClickListener(v -> { setAllAppsBlocked(true);  mAdapter.notifyDataSetChanged(); });
        btnDeselectAll.setOnClickListener(v -> { setAllAppsBlocked(false); mAdapter.notifyDataSetChanged(); });

        initHomePage();

        mAdapter = new AppGroupAdapter();
        mExpandableListView.setAdapter(mAdapter);

        // 列表点击切换勾选
        mExpandableListView.setOnChildClickListener((parent, v, groupPos, childPos, id) -> {
            AppItem item = getItem(groupPos, childPos);
            if (item == null || item.isCore) return false;
            item.isBlocked = !item.isBlocked;
            mPendingChanges.put(item.packageName,
                    item.isBlocked ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE);
            mAdapter.notifyDataSetChanged();
            return true;
        });

        // 搜索
        mEtSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mCurrentQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        // FAB 保存
        mFab.setOnClickListener(v -> saveChanges());

        // 底部导航
        View.OnClickListener navClick = v -> {
            int id = v.getId();
            if      (id == R.id.nav_home)     showPage(PAGE_HOME);
            else if (id == R.id.nav_apps)     showPage(PAGE_APPS);
            else if (id == R.id.nav_log)      showPage(PAGE_LOG);
            else if (id == R.id.nav_settings) showPage(PAGE_SETTINGS);
        };
        mNavHome.setOnClickListener(navClick);
        mNavApps.setOnClickListener(navClick);
        mNavLog.setOnClickListener(navClick);
        mNavSettings.setOnClickListener(navClick);

        // 恢复页面
        showPage(sCurrentPage == PAGE_APPS || sCurrentPage == PAGE_LOG
                || sCurrentPage == PAGE_SETTINGS ? sCurrentPage : PAGE_HOME);

        loadAppsAsync();

        // ── 设置页交互 ──
        setupSettingsPage();

        // 返回键：权限详情页回到设置页
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (sCurrentPage == PAGE_PERMISSION_DETAIL) {
                    showPage(PAGE_SETTINGS);
                } else {
                    setEnabled(false);
                    MainActivity.super.onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        initHomePage();
        if (sCurrentPage == PAGE_PERMISSION_DETAIL) initPermissionDetailPage();

        // 引导弹窗
        if (!getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_FIRST_LAUNCH, false)) {
            boolean hasPerm = Settings.canDrawOverlays(this);
            if (mGuideDialog != null && mGuideDialog.isShowing()) {
                refreshGuideDialog(hasPerm);
            } else {
                showPermissionGuideDialog();
            }
        }

        // 刷新应用权限（仅列表非空时）
        if (!mUserApps.isEmpty() || !mSystemApps.isEmpty()) {
            refreshPermissions();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ──────────────────────────── 设置页 ────────────────────────────

    private void setupSettingsPage() {
        setupThemeItem(R.id.item_theme_light,  THEME_LIGHT);
        setupThemeItem(R.id.item_theme_dark,   THEME_DARK);
        setupThemeItem(R.id.item_theme_system, THEME_SYSTEM);

        View itemPermission = findViewById(R.id.item_permission);
        if (itemPermission != null) {
            itemPermission.setOnClickListener(v -> showPage(PAGE_PERMISSION_DETAIL));
        }

        SwitchMaterial switchLog = findViewById(R.id.switch_enable_log);
        if (switchLog != null) {
            SharedPreferences prefs = getSharedPreferences("clipboardguard_prefs", MODE_PRIVATE);
            switchLog.setChecked(prefs.getBoolean("enable_log", false));
            switchLog.setOnCheckedChangeListener((btn, checked) ->
                    prefs.edit().putBoolean("enable_log", checked).apply());
        }

        View itemAbout = findViewById(R.id.item_about);
        if (itemAbout != null) {
            itemAbout.setOnClickListener(v ->
                    Toast.makeText(this, "模块版本: " + getModuleVersion(), Toast.LENGTH_SHORT).show());
        }
    }

    private void setupThemeItem(int viewId, int theme) {
        View item = findViewById(viewId);
        if (item != null) {
            item.setOnClickListener(v -> {
                switchTheme(theme);
                updateThemeRadioButtons(theme);
            });
        }
    }

    // ──────────────────────────── 页面切换 ────────────────────────────

    private void showPage(int page) {
        sCurrentPage = page;

        // 离开应用页时丢弃未保存变更
        if (page != PAGE_APPS && !mPendingChanges.isEmpty()) {
            mPendingChanges.clear();
            refreshPermissions();
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
        }

        mPageHome.setVisibility(View.GONE);
        mPageApps.setVisibility(View.GONE);
        mPageLog.setVisibility(View.GONE);
        mPageSettings.setVisibility(View.GONE);
        mPagePermissionDetail.setVisibility(View.GONE);

        switch (page) {
            case PAGE_HOME:
                mPageHome.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                break;
            case PAGE_APPS:
                mPageApps.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.VISIBLE);
                mExpandableListView.expandGroup(GROUP_USER);
                break;
            case PAGE_LOG:
                mPageLog.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                loadLogs();
                break;
            case PAGE_SETTINGS:
                mPageSettings.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                break;
            case PAGE_PERMISSION_DETAIL:
                mPagePermissionDetail.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                initPermissionDetailPage();
                break;
        }

        // 标题栏
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        switch (page) {
            case PAGE_HOME:       toolbar.setTitle(R.string.app_name);          toolbar.setNavigationIcon(null);       break;
            case PAGE_APPS:       toolbar.setTitle(R.string.nav_apps);          toolbar.setNavigationIcon(null);       break;
            case PAGE_LOG:        toolbar.setTitle(R.string.nav_log);           toolbar.setNavigationIcon(null);       break;
            case PAGE_SETTINGS:   toolbar.setTitle(R.string.nav_settings);      toolbar.setNavigationIcon(null);       break;
            case PAGE_PERMISSION_DETAIL: toolbar.setTitle(R.string.settings_permission); toolbar.setNavigationIcon(R.drawable.ic_back); break;
        }

        // 底部导航高亮
        int sel   = ContextCompat.getColor(this, R.color.nav_selected);
        int unsel = ContextCompat.getColor(this, R.color.nav_unselected);
        tintNavItem(mNavHome,     page == PAGE_HOME,     sel, unsel);
        tintNavItem(mNavApps,     page == PAGE_APPS,     sel, unsel);
        tintNavItem(mNavLog,      page == PAGE_LOG,      sel, unsel);
        tintNavItem(mNavSettings, page == PAGE_SETTINGS, sel, unsel);

        mBottomNav.setVisibility(page == PAGE_PERMISSION_DETAIL ? View.GONE : View.VISIBLE);
    }

    private void tintNavItem(LinearLayout nav, boolean selected, int selColor, int unselColor) {
        int color = selected ? selColor : unselColor;
        ((ImageView) nav.getChildAt(0)).setColorFilter(color);
        ((TextView)  nav.getChildAt(1)).setTextColor(color);
    }

    // ──────────────────────────── 首页 ────────────────────────────

    private void initHomePage() {
        boolean isActive = isModuleActive();
        if (isActive) {
            mTvStatusTitle.setText(R.string.status_active);
            mTvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_active));
            mTvStatusDesc.setText(R.string.status_active_desc);
            mIvStatusIcon.setImageResource(R.drawable.ic_shield_on);
        } else {
            mTvStatusTitle.setText(R.string.status_not_active);
            mTvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_inactive));
            mTvStatusDesc.setText(R.string.status_not_active_desc);
            mIvStatusIcon.setImageResource(R.drawable.ic_shield_off);
        }

        int xApi = getXposedApiVersion();
        mTvXposedSdk.setText(xApi > 0 ? String.valueOf(xApi) : "未检测到");

        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            mTvModuleVersion.setText("v" + pi.versionName + " (" + pi.versionCode + ")");
        } catch (PackageManager.NameNotFoundException e) {
            mTvModuleVersion.setText("--");
        }

        mTvAndroidVersion.setText(Build.VERSION.RELEASE);
        mTvManufacturer.setText(Build.MANUFACTURER);
        mTvModel.setText(Build.MODEL);
    }

    /** 权限详情页 */
    private void initPermissionDetailPage() {
        boolean hasPerm = Settings.canDrawOverlays(this);

        TextView tvStatus = findViewById(R.id.tv_float_status);
        if (tvStatus != null) {
            if (hasPerm) {
                tvStatus.setText(R.string.guide_float_granted);
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active));
            } else {
                tvStatus.setText(R.string.guide_float_not_granted);
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive));
            }
        }

        MaterialButton btnFloat = findViewById(R.id.btn_float_settings);
        if (btnFloat != null) {
            btnFloat.setVisibility(View.VISIBLE);
            btnFloat.setText(hasPerm ? R.string.guide_btn_open_float : R.string.guide_btn_go_settings);
            btnFloat.setOnClickListener(v -> startActivity(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
        }
    }

    /** Hook 注入时返回 true */
    private boolean isModuleActive() { return false; }

    /** Hook 注入时返回真实版本 */
    private int getXposedApiVersion() { return -1; }

    // ──────────────────────────── 主题 ────────────────────────────

    private void applyThemeNoView() {
        if (sCurrentTheme < 0) {
            sCurrentTheme = getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY_THEME, THEME_SYSTEM);
        }
        applyNightMode(sCurrentTheme);
    }

    private void applyTheme() {
        int theme = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM);
        boolean isDark = (theme == THEME_DARK)
                || (theme == THEME_SYSTEM && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(isDark ? Color.BLACK : Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            w.getDecorView().setSystemUiVisibility(isDark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    public static void switchTheme(int theme) {
        if (theme < THEME_LIGHT || theme > THEME_SYSTEM) return;
        sCurrentTheme = theme;
        applyNightMode(theme);
        try {
            MainActivity inst = sInstanceRef != null ? sInstanceRef.get() : null;
            if (inst != null) {
                inst.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .edit().putInt(KEY_THEME, theme).commit();
            }
        } catch (Exception ignored) {}
    }

    private static void applyNightMode(int theme) {
        switch (theme) {
            case THEME_LIGHT:  AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);          break;
            case THEME_DARK:   AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);         break;
            default:           AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM); break;
        }
    }

    private void updateThemeRadioButtons(int theme) {
        RadioButton rb0 = findViewById(R.id.rb_theme_light);
        RadioButton rb1 = findViewById(R.id.rb_theme_dark);
        RadioButton rb2 = findViewById(R.id.rb_theme_system);
        if (rb0 == null || rb1 == null || rb2 == null) return;
        rb0.setChecked(theme == THEME_LIGHT);
        rb1.setChecked(theme == THEME_DARK);
        rb2.setChecked(theme == THEME_SYSTEM);
    }

    private void initThemeRadioButtons() {
        updateThemeRadioButtons(sCurrentTheme >= 0 ? sCurrentTheme
                : getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM));
    }

    private String getModuleVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "--";
        }
    }

    // ──────────────────────────── 首次使用引导弹窗 ────────────────────────────

    private void showPermissionGuideDialog() {
        if (mGuideDialog != null && mGuideDialog.isShowing()) return;

        mGuideView = getLayoutInflater().inflate(R.layout.dialog_first_launch_guide, null);

        TextView tvTitle  = mGuideView.findViewById(R.id.tv_float_perm_title);
        TextView tvDesc   = mGuideView.findViewById(R.id.tv_float_perm_desc);
        TextView tvStatus = mGuideView.findViewById(R.id.tv_float_perm_status);
        TextView btnGo    = mGuideView.findViewById(R.id.btn_go_settings);

        boolean hasPerm = Settings.canDrawOverlays(this);
        tvTitle.setText(R.string.guide_float_perm);
        tvDesc.setText(R.string.guide_float_perm_desc);

        updateGuideFloatStatus(tvStatus, btnGo, hasPerm);

        TextView tvAutoTitle = mGuideView.findViewById(R.id.tv_autostart_title);
        TextView tvAutoDesc  = mGuideView.findViewById(R.id.tv_autostart_desc);
        TextView tvAutoHint  = mGuideView.findViewById(R.id.tv_autostart_hint);
        tvAutoTitle.setText(R.string.guide_autostart_perm);
        tvAutoDesc.setText(R.string.guide_autostart_perm_desc);
        tvAutoHint.setText(R.string.guide_autostart_hint);
        tvAutoHint.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));

        mGuideDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.guide_title)
                .setView(mGuideView)
                .setPositiveButton(hasPerm ? R.string.guide_btn_done : R.string.guide_btn_later,
                        (d, which) -> {
                            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                                    .edit().putBoolean(KEY_FIRST_LAUNCH, true).apply();
                            mGuideDialog = null;
                        })
                .setCancelable(false)
                .show();
    }

    /** 刷新引导弹窗内的权限状态（回到 App 时调用） */
    private void refreshGuideDialog(boolean hasPerm) {
        if (mGuideDialog == null || !mGuideDialog.isShowing()) return;
        try {
            View dv = mGuideDialog.getWindow().getDecorView();
            TextView tvStatus = dv.findViewById(R.id.tv_float_perm_status);
            TextView btnGo    = dv.findViewById(R.id.btn_go_settings);
            updateGuideFloatStatus(tvStatus, btnGo, hasPerm);

            // 同步底部按钮文字
            android.widget.Button pos = dv.findViewById(android.R.id.button1);
            if (pos != null) {
                pos.setText(hasPerm ? R.string.guide_btn_done : R.string.guide_btn_later);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 统一更新悬浮窗状态文字和「去设置」按钮 */
    private void updateGuideFloatStatus(TextView tvStatus, TextView btnGo, boolean hasPerm) {
        if (tvStatus != null) {
            tvStatus.setText(hasPerm ? R.string.guide_float_perm_granted : R.string.guide_float_perm_not_granted);
            tvStatus.setTextColor(ContextCompat.getColor(this,
                    hasPerm ? R.color.status_active : R.color.status_inactive));
        }
        if (btnGo != null) {
            if (hasPerm) {
                btnGo.setVisibility(View.GONE);
            } else {
                btnGo.setVisibility(View.VISIBLE);
                btnGo.setOnClickListener(v -> {
                    try {
                        startActivityForResult(new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())),
                                REQUEST_CODE_FLOAT_WINDOW);
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }

    // ──────────────────────────── 应用列表 ────────────────────────────

    private void loadAppsAsync() {
        sExecutor.execute(() -> {
            loadAllApps();
            runOnUiThread(() -> {
                applyFilter();
                mExpandableListView.expandGroup(GROUP_USER);
            });
        });
    }

    private static HashSet<String> sCorePackages;

    private void initCorePackages() {
        if (sCorePackages != null) return;
        sCorePackages = new HashSet<>();
        Collections.addAll(sCorePackages, getResources().getStringArray(R.array.global_whitelist_packages));
    }

    private boolean isCoreSystemPackage(String pkgName) {
        initCorePackages();
        if (sCorePackages.contains(pkgName)) return true;
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

        List<String[]> savedPerms = PermissionProvider.getAllPermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) {
            permMap.put(row[0], Integer.parseInt(row[1]));
        }

        final String self = getPackageName();
        for (ApplicationInfo info : apps) {
            if (self.equals(info.packageName)) continue;

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
            Integer saved = permMap.get(item.packageName);
            item.isBlocked = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);

            if (isCore)            mCoreApps.add(item);
            else if (item.isSystem) mSystemApps.add(item);
            else                    mUserApps.add(item);
        }

        sortApps(mUserApps);
        sortApps(mSystemApps);
        Collections.sort(mCoreApps, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
    }

    private static void sortApps(List<AppItem> list) {
        Collections.sort(list, (a, b) -> {
            if (a.isBlocked != b.isBlocked) return a.isBlocked ? -1 : 1;
            return a.appName.compareToIgnoreCase(b.appName);
        });
    }

    private void refreshPermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllPermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) permMap.put(row[0], Integer.parseInt(row[1]));

        for (AppItem item : mUserApps)   applyPermToItem(item, permMap);
        for (AppItem item : mSystemApps) applyPermToItem(item, permMap);
        for (AppItem item : mCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlocked = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
        }
        applyFilter();
    }

    private void applyPermToItem(AppItem item, android.util.ArrayMap<String, Integer> permMap) {
        Integer saved = permMap.get(item.packageName);
        item.isBlocked = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
        if (mPendingChanges.containsKey(item.packageName)) {
            item.isBlocked = (mPendingChanges.get(item.packageName) == PermissionStorage.PERMISSION_BLOCK);
        }
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
            for (AppItem i : mUserApps)   if (matches(i)) mFilteredUser.add(i);
            for (AppItem i : mSystemApps) if (matches(i)) mFilteredSystem.add(i);
            for (AppItem i : mCoreApps)   if (matches(i)) mFilteredCore.add(i);
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

    private boolean matches(AppItem item) {
        return item.appName.toLowerCase(Locale.ROOT).contains(mCurrentQuery)
                || item.packageName.toLowerCase(Locale.ROOT).contains(mCurrentQuery);
    }

    private AppItem getItem(int group, int child) {
        if (group == GROUP_USER   && child < mFilteredUser.size())   return mFilteredUser.get(child);
        if (group == GROUP_SYSTEM && child < mFilteredSystem.size()) return mFilteredSystem.get(child);
        if (group == GROUP_CORE   && child < mFilteredCore.size())   return mFilteredCore.get(child);
        return null;
    }

    // ──────────────────────────── 全选/反选 ────────────────────────────

    private void setAllAppsBlocked(boolean blocked) {
        int perm = blocked ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE;
        for (AppItem i : mUserApps)   { i.isBlocked = blocked; mPendingChanges.put(i.packageName, perm); }
        for (AppItem i : mSystemApps) { i.isBlocked = blocked; mPendingChanges.put(i.packageName, perm); }
        applyFilter();
    }

    // ──────────────────────────── 保存 ────────────────────────────

    private void saveChanges() {
        if (mPendingChanges.isEmpty()) {
            Toast.makeText(this, "没有更改需要保存", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Map.Entry<String, Integer> e : mPendingChanges.entrySet()) {
            PermissionStorage.setPermission(this, e.getKey(), e.getValue());
        }
        mPendingChanges.clear();

        int blocked = 0;
        for (AppItem i : mUserApps)   if (i.isBlocked) blocked++;
        for (AppItem i : mSystemApps) if (i.isBlocked) blocked++;

        Toast.makeText(this,
                blocked > 0 ? getString(R.string.save_success, blocked) : getString(R.string.save_no_block),
                Toast.LENGTH_SHORT).show();
    }

    // ──────────────────────────── 日志 ────────────────────────────

    private void loadLogs() {
        new Thread(() -> {
            List<String[]> raw = PermissionProvider.getLogs(this, 100);
            List<LogEntry> logs = new ArrayList<>(raw.size());
            for (String[] row : raw) {
                if (row.length >= 4) logs.add(new LogEntry(row[0], row[1], row[2], Long.parseLong(row[3])));
            }
            runOnUiThread(() -> {
                mLogAdapter.setLogs(logs);
                View tvEmpty = mPageLog.findViewById(R.id.tv_empty);
                if (tvEmpty != null) tvEmpty.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    // ──────────────────────────── 数据模型 ────────────────────────────

    static class AppItem {
        String   packageName;
        String   appName;
        Drawable icon;
        boolean  isSystem;
        boolean  isCore;
        boolean  isBlocked;
    }

    // ──────────────────────────── Adapter ────────────────────────────

    class AppGroupAdapter extends BaseExpandableListAdapter {

        @Override public int  getGroupCount()                        { return 3; }
        @Override public int  getChildrenCount(int g)                {
            if (g == GROUP_USER)   return mFilteredUser.size();
            if (g == GROUP_SYSTEM) return mFilteredSystem.size();
            return mFilteredCore.size();
        }
        @Override public Object  getGroup(int g)                     { return g; }
        @Override public Object  getChild(int g, int c)              { return getItem(g, c); }
        @Override public long    getGroupId(int g)                   { return g; }
        @Override public long    getChildId(int g, int c)            { return c; }
        @Override public boolean hasStableIds()                      { return false; }
        @Override public boolean isChildSelectable(int g, int c)     { return g != GROUP_CORE; }

        @Override
        public View getGroupView(int g, boolean expanded, View convert, ViewGroup parent) {
            GroupViewHolder h;
            if (convert == null) {
                convert = getLayoutInflater().inflate(R.layout.item_group_header, parent, false);
                h = new GroupViewHolder(convert);
                convert.setTag(h);
            } else {
                h = (GroupViewHolder) convert.getTag();
            }
            boolean isUser = (g == GROUP_USER);
            boolean isCore = (g == GROUP_CORE);
            List<AppItem> list = isCore ? mFilteredCore : isUser ? mFilteredUser : mFilteredSystem;
            int blocked = 0;
            for (AppItem i : list) if (i.isBlocked) blocked++;

            h.tvArrow.setText(expanded ? "▲" : "▼");
            if (isCore) {
                h.tvTitle.setText(getString(R.string.group_core_apps) + "  " + list.size() + " 个（不可更改）🔒");
            } else {
                h.tvTitle.setText((isUser ? getString(R.string.group_user_apps)
                                          : getString(R.string.group_system_apps))
                        + "  " + list.size() + " 个"
                        + (blocked > 0 ? "（拦截 " + blocked + "）" : ""));
            }
            return convert;
        }

        @Override
        public View getChildView(int g, int c, boolean last, View convert, ViewGroup parent) {
            ChildViewHolder h;
            if (convert == null) {
                convert = getLayoutInflater().inflate(R.layout.item_app_permission, parent, false);
                h = new ChildViewHolder(convert);
                convert.setTag(h);
            } else {
                h = (ChildViewHolder) convert.getTag();
            }
            AppItem item = getItem(g, c);
            if (item == null) return convert;

            h.ivIcon.setImageDrawable(item.icon);
            h.tvName.setText(item.appName);
            h.cbBlock.setChecked(item.isBlocked);
            h.cbBlock.setEnabled(!item.isCore);
            h.tvName.setEnabled(!item.isCore);
            h.tvPkg.setEnabled(!item.isCore);
            h.tvPkg.setText(item.isCore ? item.packageName + "  🔒" : item.packageName);
            return convert;
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
