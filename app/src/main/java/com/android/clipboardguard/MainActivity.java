package com.android.clipboardguard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static WeakReference<MainActivity> sInstanceRef;

    private static int sCurrentTheme = -1;
    private static int sCurrentPage = 0;

    private static final int GROUP_USER   = 0;
    private static final int GROUP_SYSTEM = 1;
    private static final int GROUP_CORE   = 2;

    public static final int PAGE_HOME              = 0;
    public static final int PAGE_WRITE             = 1;
    public static final int PAGE_READ              = 2;
    public static final int PAGE_SETTINGS          = 3;

    public static final String PREF_NAME   = ClipboardGuardApp.PREF_NAME;
    public static final String KEY_THEME   = ClipboardGuardApp.KEY_THEME;
    public static final int    THEME_LIGHT  = ClipboardGuardApp.THEME_LIGHT;
    public static final int    THEME_DARK   = ClipboardGuardApp.THEME_DARK;
    public static final int    THEME_SYSTEM = ClipboardGuardApp.THEME_SYSTEM;

    private View mPageHome;
    private View mPageWrite;
    private View mPageRead;
    private View mPageSettings;
    private FloatingActionButton mFab;
    private LinearLayout mNavHome, mNavApps, mNavRead, mNavSettings;
    private ExpandableListView mWriteExpandableListView;
    private ExpandableListView mReadExpandableListView;
    private SwipeRefreshLayout mWriteSwipeRefresh;
    private SwipeRefreshLayout mReadSwipeRefresh;
    private TextView mTvStatusTitle, mTvStatusDesc;
    private ImageView mIvStatusIcon;
    private TextView mTvXposedSdk, mTvModuleVersion;
    private TextView mTvAndroidVersion, mTvManufacturer, mTvModel;

    private AppGroupAdapter mWriteAdapter;
    private final List<AppItem> mWriteUserApps   = new ArrayList<>();
    private final List<AppItem> mWriteSystemApps = new ArrayList<>();
    private final List<AppItem> mWriteCoreApps   = new ArrayList<>();
    private final List<AppItem> mWriteFilteredUser   = new ArrayList<>();
    private final List<AppItem> mWriteFilteredSystem = new ArrayList<>();
    private final List<AppItem> mWriteFilteredCore   = new ArrayList<>();
    private String mWriteCurrentQuery = "";
    private final Map<String, Integer> mWritePendingChanges = new HashMap<>();

    private AppGroupAdapter mReadAdapter;
    private final List<AppItem> mReadUserApps   = new ArrayList<>();
    private final List<AppItem> mReadSystemApps = new ArrayList<>();
    private final List<AppItem> mReadCoreApps   = new ArrayList<>();
    private final List<AppItem> mReadFilteredUser   = new ArrayList<>();
    private final List<AppItem> mReadFilteredSystem = new ArrayList<>();
    private final List<AppItem> mReadFilteredCore   = new ArrayList<>();
    private String mReadCurrentQuery = "";
    private final Map<String, Integer> mReadPendingChanges = new HashMap<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ClipboardGuard-Worker");
        t.setDaemon(true);
        return t;
    });

    private final LruCache<String, Drawable> mIconCache = new LruCache<>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            return value.getIntrinsicWidth() * value.getIntrinsicHeight() * 4;
        }
    };

    private static final long FAB_AUTO_HIDE_DELAY = 4000L;
    private static final long BOTTOM_NAV_DOUBLE_CLICK_MS = 350L;
    private long mLastWriteNavClickTime = 0L;
    private long mLastReadNavClickTime = 0L;
    private final Runnable mFabAutoHide = () -> {
        if (mFab != null && mFab.getVisibility() == View.VISIBLE) {
            mFab.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                    .setDuration(200)
                    .withEndAction(() -> mFab.setVisibility(View.GONE))
                    .start();
        }
    };

    // ──────────────────── FAB 显示控制 ────────────────────────────

    private void resetFabAutoHide() {
        mHandler.removeCallbacks(mFabAutoHide);
        if (mFab.getVisibility() != View.VISIBLE && (sCurrentPage == PAGE_WRITE || sCurrentPage == PAGE_READ)) {
            mFab.setVisibility(View.VISIBLE);
            mFab.setAlpha(1f);
            mFab.setScaleX(1f);
            mFab.setScaleY(1f);
        }
        mHandler.postDelayed(mFabAutoHide, FAB_AUTO_HIDE_DELAY);
    }

    // ──────────────────── 生命周期与基础初始化 ────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyThemeNoView();
        super.onCreate(savedInstanceState);

        sInstanceRef = new WeakReference<>(this);
        setContentView(R.layout.activity_main);

        initThemeRadioButtons();
        applyTheme();

        applyAppBarInsets();
        bindMainViews();

        initPagesAndData();

        showPage(sCurrentPage == PAGE_WRITE || sCurrentPage == PAGE_READ
                || sCurrentPage == PAGE_SETTINGS ? sCurrentPage : PAGE_HOME);

        PermissionProvider.sendFullConfigBroadcast(this);
    }

    private void applyAppBarInsets() {
        View appBarView = findViewById(R.id.app_bar);
        ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0),
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarView);
    }

    private void bindMainViews() {
        mPageHome             = findViewById(R.id.page_home);
        mPageWrite            = findViewById(R.id.page_write);
        mPageRead             = findViewById(R.id.page_read);
        mPageSettings         = findViewById(R.id.page_settings);
        mFab                  = findViewById(R.id.fab_save);
        mNavHome              = findViewById(R.id.nav_home);
        mNavApps              = findViewById(R.id.nav_apps);
        mNavRead              = findViewById(R.id.nav_read);
        mNavSettings          = findViewById(R.id.nav_settings);
        mTvStatusTitle        = findViewById(R.id.tv_status_title);
        mTvStatusDesc         = findViewById(R.id.tv_status_desc);
        mIvStatusIcon         = findViewById(R.id.iv_status_icon);
        mTvXposedSdk          = findViewById(R.id.tv_xposed_sdk);
        mTvModuleVersion      = findViewById(R.id.tv_module_version);
        mTvAndroidVersion     = findViewById(R.id.tv_android_version);
        mTvManufacturer       = findViewById(R.id.tv_manufacturer);
        mTvModel              = findViewById(R.id.tv_model);
    }

    private void initPagesAndData() {
        initWritePage();
        initReadPage();
        initRuleFiles();
        initHomePage();
        setupBottomNav();
        setupSettingsPage();
        loadAppsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        initHomePage();
        loadAppsAsync();
    }

    @Override
    protected void onPause() {
        discardPendingChangesForPage(sCurrentPage);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    // ──────────────────── 读写页面初始化 ────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void initWritePage() {
        mWriteExpandableListView = findViewById(R.id.expandable_list_write);
        mWriteSwipeRefresh = findViewById(R.id.swipe_refresh_write);

        mWriteAdapter = new AppGroupAdapter(false);
        mWriteExpandableListView.setAdapter(mWriteAdapter);

        mWriteSwipeRefresh.setOnRefreshListener(this::loadAppsAsync);

        // 触摸时显示 FAB，同时调用 performClick 避免警告

        mWriteExpandableListView.setOnTouchListener((v, event) -> {
            v.performClick();
            resetFabAutoHide();
            return false;
        });
        mWriteExpandableListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE) resetFabAutoHide();
            }
            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {}
        });

        mWriteExpandableListView.setOnChildClickListener((parent, v, groupPos, childPos, id) -> {
            AppItem item = getWriteItem(groupPos, childPos);
            if (item == null || item.isCore) return false;
            item.isBlockedWrite = !item.isBlockedWrite;
            mWritePendingChanges.put(item.packageName,
                    item.isBlockedWrite ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE);
            mWriteAdapter.notifyDataSetChanged();
            resetFabAutoHide();
            return true;
        });

        EditText mWriteEtSearch = findViewById(R.id.et_search_write);
        mWriteEtSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mWriteCurrentQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                applyWriteFilter();
                resetFabAutoHide();
            }
        });

        TextView btnSelectAll = findViewById(R.id.btn_select_all_write);
        TextView btnDeselectAll = findViewById(R.id.btn_deselect_all_write);
        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> {
                setAllWriteApps(true);
                mWriteAdapter.notifyDataSetChanged();
                resetFabAutoHide();
            });
        }
        if (btnDeselectAll != null) {
            btnDeselectAll.setOnClickListener(v -> {
                setAllWriteApps(false);
                mWriteAdapter.notifyDataSetChanged();
                resetFabAutoHide();
            });
        }

        View cardWriteRules = findViewById(R.id.card_write_rules);
        if (cardWriteRules != null) {
            cardWriteRules.setOnClickListener(v ->
                    startActivity(new Intent(this, WriteRulesDetailActivity.class)));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initReadPage() {
        mReadExpandableListView = findViewById(R.id.expandable_list_read);
        mReadSwipeRefresh = findViewById(R.id.swipe_refresh_read);

        mReadAdapter = new AppGroupAdapter(true);
        mReadExpandableListView.setAdapter(mReadAdapter);

        mReadSwipeRefresh.setOnRefreshListener(this::loadAppsAsync);

        mReadExpandableListView.setOnTouchListener((v, event) -> {
            v.performClick();
            resetFabAutoHide();
            return false;
        });
        mReadExpandableListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE) resetFabAutoHide();
            }
            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {}
        });

        mReadExpandableListView.setOnChildClickListener((parent, v, groupPos, childPos, id) -> {
            AppItem item = getReadItem(groupPos, childPos);
            if (item == null || item.isCore) return false;
            item.isBlockedRead = !item.isBlockedRead;
            mReadPendingChanges.put(item.packageName,
                    item.isBlockedRead ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE);
            mReadAdapter.notifyDataSetChanged();
            resetFabAutoHide();
            return true;
        });

        mReadExpandableListView.setOnGroupClickListener((parent, v, groupPos, id) -> true);

        EditText mReadEtSearch = findViewById(R.id.et_search_read);
        mReadEtSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mReadCurrentQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                applyReadFilter();
                resetFabAutoHide();
            }
        });

        TextView btnSelectAllRead = findViewById(R.id.btn_select_all_read);
        TextView btnDeselectAllRead = findViewById(R.id.btn_deselect_all_read);
        if (btnSelectAllRead != null) {
            btnSelectAllRead.setOnClickListener(v -> {
                setAllReadApps(true);
                mReadAdapter.notifyDataSetChanged();
                resetFabAutoHide();
            });
        }
        if (btnDeselectAllRead != null) {
            btnDeselectAllRead.setOnClickListener(v -> {
                setAllReadApps(false);
                mReadAdapter.notifyDataSetChanged();
                resetFabAutoHide();
            });
        }

        View cardReadRules = findViewById(R.id.card_read_rules);
        if (cardReadRules != null) {
            cardReadRules.setOnClickListener(v ->
                    startActivity(new Intent(this, ReadRulesDetailActivity.class)));
        }
    }

    // ──────────────────── 页面切换与底部导航 ────────────────────────────

    private void showPage(int page) {
        int previousPage = sCurrentPage;
        sCurrentPage = page;

        if (previousPage != page) discardPendingChangesForPage(previousPage);

        mPageHome.setVisibility(View.GONE);
        mPageWrite.setVisibility(View.GONE);
        mPageRead.setVisibility(View.GONE);
        mPageSettings.setVisibility(View.GONE);
        switch (page) {
            case PAGE_HOME:
                mPageHome.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                break;
            case PAGE_WRITE:
                mPageWrite.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                mWriteExpandableListView.expandGroup(GROUP_USER);
                break;
            case PAGE_READ:
                mPageRead.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                mReadExpandableListView.expandGroup(GROUP_USER);
                break;
            case PAGE_SETTINGS:
                mPageSettings.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                break;
        }

        updateToolbar(page);
        updateBottomNav(page);
    }

    private void updateToolbar(int page) {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        switch (page) {
            case PAGE_HOME:       toolbar.setTitle(R.string.app_name);         toolbar.setNavigationIcon(null); break;
            case PAGE_WRITE:      toolbar.setTitle(R.string.title_write_block); toolbar.setNavigationIcon(null); break;
            case PAGE_READ:       toolbar.setTitle(R.string.title_read_block);  toolbar.setNavigationIcon(null); break;
            case PAGE_SETTINGS:   toolbar.setTitle(R.string.nav_settings);      toolbar.setNavigationIcon(null); break;
        }
    }

    private void updateBottomNav(int page) {
        int sel   = ContextCompat.getColor(this, R.color.nav_selected);
        int unsel = ContextCompat.getColor(this, R.color.nav_unselected);
        tintNavItem(mNavHome,     page == PAGE_HOME,     sel, unsel);
        tintNavItem(mNavApps,     page == PAGE_WRITE,    sel, unsel);
        tintNavItem(mNavRead,     page == PAGE_READ,     sel, unsel);
        tintNavItem(mNavSettings, page == PAGE_SETTINGS, sel, unsel);

    }

    private void tintNavItem(LinearLayout nav, boolean selected, int selColor, int unselColor) {
        int color = selected ? selColor : unselColor;
        ((ImageView) nav.getChildAt(0)).setColorFilter(color);
        ((TextView)  nav.getChildAt(1)).setTextColor(color);
    }

    private void discardPendingChangesForPage(int page) {
        if (page != PAGE_WRITE && page != PAGE_READ) return;
        mHandler.removeCallbacks(mFabAutoHide);

        if (page == PAGE_WRITE && !mWritePendingChanges.isEmpty()) {
            mWritePendingChanges.clear();
            refreshWritePermissions();
            if (mWriteAdapter != null) mWriteAdapter.notifyDataSetChanged();
        } else if (page == PAGE_READ && !mReadPendingChanges.isEmpty()) {
            mReadPendingChanges.clear();
            refreshReadPermissions();
            if (mReadAdapter != null) mReadAdapter.notifyDataSetChanged();
        }
    }

    private void setupBottomNav() {
        View.OnClickListener navClick = v -> {
            int id = v.getId();
            if      (id == R.id.nav_home)     showPage(PAGE_HOME);
            else if (id == R.id.nav_apps)     handleWriteNavClick();
            else if (id == R.id.nav_read)     handleReadNavClick();
            else if (id == R.id.nav_settings) showPage(PAGE_SETTINGS);
        };
        mNavHome.setOnClickListener(navClick);
        mNavApps.setOnClickListener(navClick);
        mNavRead.setOnClickListener(navClick);
        mNavSettings.setOnClickListener(navClick);

        mFab.setOnClickListener(v -> {
            if (sCurrentPage == PAGE_WRITE) {
                saveWriteChanges();
            } else if (sCurrentPage == PAGE_READ) {
                saveReadChanges();
            }
            resetFabAutoHide();
        });
    }

    private void handleWriteNavClick() {
        long now = SystemClock.elapsedRealtime();
        if (sCurrentPage == PAGE_WRITE) {
            if (now - mLastWriteNavClickTime <= BOTTOM_NAV_DOUBLE_CLICK_MS) {
                scrollWriteListToTop();
            }
        } else {
            showPage(PAGE_WRITE);
        }
        mLastWriteNavClickTime = now;
    }

    private void handleReadNavClick() {
        long now = SystemClock.elapsedRealtime();
        if (sCurrentPage == PAGE_READ) {
            if (now - mLastReadNavClickTime <= BOTTOM_NAV_DOUBLE_CLICK_MS) {
                scrollReadListToTop();
            }
        } else {
            showPage(PAGE_READ);
        }
        mLastReadNavClickTime = now;
    }

    private void scrollWriteListToTop() {
        if (mWriteExpandableListView != null) {
            mWriteExpandableListView.setSelection(0);
        }
    }

    private void scrollReadListToTop() {
        if (mReadExpandableListView != null) {
            mReadExpandableListView.setSelection(0);
        }
    }

    // ──────────────────── 首页状态信息 ────────────────────────────

    @SuppressLint("SetTextI18n")
    private void initHomePage() {
        updateModuleStatusCard(isModuleActive());

        int xApi = getXposedApiVersion();
        mTvXposedSdk.setText(xApi > 0 ? String.valueOf(xApi) : "未检测到");
        updateDeviceInfo();
    }

    private void updateModuleStatusCard(boolean isActive) {
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
    }

    @SuppressLint("SetTextI18n")
    private void updateDeviceInfo() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            // 使用 getLongVersionCode() 代替已弃用的 versionCode
            mTvModuleVersion.setText("v" + pi.versionName + " (" + pi.getLongVersionCode() + ")");
        } catch (PackageManager.NameNotFoundException e) {
            mTvModuleVersion.setText("--");
        }

        mTvAndroidVersion.setText(Build.VERSION.RELEASE);
        mTvManufacturer.setText(Build.MANUFACTURER);
        mTvModel.setText(Build.MODEL);
    }

    private boolean isModuleActive() { return false; }
    private int getXposedApiVersion() { return -1; }

    // ──────────────────── 主题与设置页 ────────────────────────────

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
        WindowInsetsController controller = w.getDecorView().getWindowInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    isDark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            );
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
                        .edit().putInt(KEY_THEME, theme).apply();
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

    @SuppressLint("SetTextI18n")
    private void setupSettingsPage() {
        setupThemeItem(R.id.item_theme_light,  THEME_LIGHT);
        setupThemeItem(R.id.item_theme_dark,   THEME_DARK);
        setupThemeItem(R.id.item_theme_system, THEME_SYSTEM);

        SharedPreferences prefs = getSharedPreferences("clipboardguard_prefs", MODE_PRIVATE);

        SwitchMaterial switchReadBlockedToast = findViewById(R.id.switch_read_blocked_toast_enabled);
        if (switchReadBlockedToast != null) {
            switchReadBlockedToast.setChecked(prefs.getBoolean("read_blocked_toast_enabled", true));
            switchReadBlockedToast.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("read_blocked_toast_enabled", isChecked).apply();
                PermissionProvider.sendReadWriteBlocklistBroadcast(this);
            });
        }

        SwitchMaterial switchLsposedLog = findViewById(R.id.switch_lsposed_log_enabled);
        if (switchLsposedLog != null) {
            switchLsposedLog.setChecked(prefs.getBoolean("lsposed_log_enabled", true));
            switchLsposedLog.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("lsposed_log_enabled", isChecked).apply();
                PermissionProvider.sendReadWriteBlocklistBroadcast(this);
            });
        }

        View itemAbout = findViewById(R.id.item_about);
        if (itemAbout != null) {
            itemAbout.setOnClickListener(v ->
                    startActivity(new Intent(this, AboutModuleActivity.class)));
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

    // ──────────────────── 应用列表加载与分类 ────────────────────────────

    private void loadAppsAsync() {
        sExecutor.execute(this::loadAllApps);
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
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        List<String[]> savedWritePerms = PermissionProvider.getAllWritePermissions(this);
        android.util.ArrayMap<String, Integer> writePermMap = new android.util.ArrayMap<>();
        for (String[] row : savedWritePerms) {
            putPermissionRow(writePermMap, row);
        }

        List<String[]> savedReadPerms = PermissionProvider.getAllReadPermissions(this);
        android.util.ArrayMap<String, Integer> readPermMap = new android.util.ArrayMap<>();
        for (String[] row : savedReadPerms) {
            putPermissionRow(readPermMap, row);
        }

        List<AppItem> tmpWriteUser = new ArrayList<>();
        List<AppItem> tmpWriteSystem = new ArrayList<>();
        List<AppItem> tmpWriteCore = new ArrayList<>();
        List<AppItem> tmpReadUser = new ArrayList<>();
        List<AppItem> tmpReadSystem = new ArrayList<>();
        List<AppItem> tmpReadCore = new ArrayList<>();

        final String self = getPackageName();
        for (ApplicationInfo info : apps) {
            if (self.equals(info.packageName)) continue;

            boolean isCore = isCoreSystemPackage(info.packageName);
            CharSequence label = pm.getApplicationLabel(info);
            String appName = label.toString();
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            Integer writeSaved = writePermMap.get(info.packageName);
            AppItem writeItem = new AppItem();
            writeItem.packageName = info.packageName;
            writeItem.appName = appName;
            writeItem.isSystem = isSystem;
            writeItem.isCore = isCore;
            writeItem.isBlockedWrite = (writeSaved != null && writeSaved == PermissionDecision.PERMISSION_BLOCK);
            if (isCore) tmpWriteCore.add(writeItem);
            else if (isSystem) tmpWriteSystem.add(writeItem);
            else tmpWriteUser.add(writeItem);

            Integer readSaved = readPermMap.get(info.packageName);
            AppItem readItem = new AppItem();
            readItem.packageName = info.packageName;
            readItem.appName = appName;
            readItem.isSystem = isSystem;
            readItem.isCore = isCore;
            readItem.isBlockedRead = (readSaved != null && readSaved == PermissionDecision.PERMISSION_BLOCK);
            if (isCore) tmpReadCore.add(readItem);
            else if (isSystem) tmpReadSystem.add(readItem);
            else tmpReadUser.add(readItem);
        }

        sortWriteApps(tmpWriteUser);
        sortWriteApps(tmpWriteSystem);
        sortWriteApps(tmpWriteCore);
        sortReadApps(tmpReadUser);
        sortReadApps(tmpReadSystem);
        sortReadApps(tmpReadCore);

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            mWriteUserApps.clear();   mWriteUserApps.addAll(tmpWriteUser);
            mWriteSystemApps.clear(); mWriteSystemApps.addAll(tmpWriteSystem);
            mWriteCoreApps.clear();   mWriteCoreApps.addAll(tmpWriteCore);
            mReadUserApps.clear();    mReadUserApps.addAll(tmpReadUser);
            mReadSystemApps.clear();  mReadSystemApps.addAll(tmpReadSystem);
            mReadCoreApps.clear();    mReadCoreApps.addAll(tmpReadCore);

            refreshWritePermissions();
            refreshReadPermissions();
            applyWriteFilter();
            applyReadFilter();
            if (mWriteExpandableListView != null) mWriteExpandableListView.expandGroup(GROUP_USER);

            if (mWriteSwipeRefresh != null) mWriteSwipeRefresh.setRefreshing(false);
            if (mReadSwipeRefresh != null) mReadSwipeRefresh.setRefreshing(false);
        });
    }

    private static void sortWriteApps(List<AppItem> list) {
        list.sort((a, b) -> {
            if (a.isBlockedWrite != b.isBlockedWrite) return a.isBlockedWrite ? -1 : 1;
            return safeText(a.appName).compareToIgnoreCase(safeText(b.appName));
        });
    }

    private static void sortReadApps(List<AppItem> list) {
        list.sort((a, b) -> {
            if (a.isBlockedRead != b.isBlockedRead) return a.isBlockedRead ? -1 : 1;
            return safeText(a.appName).compareToIgnoreCase(safeText(b.appName));
        });
    }

    private void sortWriteAppLists() {
        sortWriteApps(mWriteUserApps);
        sortWriteApps(mWriteSystemApps);
        sortWriteApps(mWriteCoreApps);
    }

    private void sortReadAppLists() {
        sortReadApps(mReadUserApps);
        sortReadApps(mReadSystemApps);
        sortReadApps(mReadCoreApps);
    }

    // ──────────────────── 写入权限列表 ────────────────────────────

    private void refreshWritePermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllWritePermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) putPermissionRow(permMap, row);

        for (AppItem item : mWriteUserApps)   applyWritePermToItem(item, permMap);
        for (AppItem item : mWriteSystemApps) applyWritePermToItem(item, permMap);
        for (AppItem item : mWriteCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlockedWrite = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        }
        sortWriteAppLists();
        applyWriteFilter();
    }

    private void applyWritePermToItem(AppItem item, android.util.ArrayMap<String, Integer> permMap) {
        Integer saved = permMap.get(item.packageName);
        item.isBlockedWrite = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        Integer pending = mWritePendingChanges.get(item.packageName);
        if (pending != null) {
            item.isBlockedWrite = (pending == PermissionDecision.PERMISSION_BLOCK);
        }
    }

    private void applyWriteFilter() {
        mWriteFilteredUser.clear();
        mWriteFilteredSystem.clear();
        mWriteFilteredCore.clear();

        if (mWriteCurrentQuery.isEmpty()) {
            mWriteFilteredUser.addAll(mWriteUserApps);
            mWriteFilteredSystem.addAll(mWriteSystemApps);
            mWriteFilteredCore.addAll(mWriteCoreApps);
        } else {
            for (AppItem i : mWriteUserApps)   if (matchesWrite(i)) mWriteFilteredUser.add(i);
            for (AppItem i : mWriteSystemApps) if (matchesWrite(i)) mWriteFilteredSystem.add(i);
            for (AppItem i : mWriteCoreApps)   if (matchesWrite(i)) mWriteFilteredCore.add(i);
        }

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (mWriteAdapter != null) mWriteAdapter.notifyDataSetChanged();
            if (!mWriteCurrentQuery.isEmpty() && mWriteExpandableListView != null) {
                mWriteExpandableListView.expandGroup(GROUP_USER);
                mWriteExpandableListView.expandGroup(GROUP_SYSTEM);
                mWriteExpandableListView.expandGroup(GROUP_CORE);
            }
        });
    }

    private boolean matchesWrite(AppItem item) {
        return safeText(item.appName).toLowerCase(Locale.ROOT).contains(mWriteCurrentQuery)
                || safeText(item.packageName).toLowerCase(Locale.ROOT).contains(mWriteCurrentQuery);
    }

    private AppItem getWriteItem(int group, int child) {
        if (group == GROUP_USER   && child < mWriteFilteredUser.size())   return mWriteFilteredUser.get(child);
        if (group == GROUP_SYSTEM && child < mWriteFilteredSystem.size()) return mWriteFilteredSystem.get(child);
        if (group == GROUP_CORE   && child < mWriteFilteredCore.size())   return mWriteFilteredCore.get(child);
        return null;
    }

    // ──────────────────── 读取权限列表 ────────────────────────────

    private void refreshReadPermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllReadPermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) putPermissionRow(permMap, row);

        for (AppItem item : mReadUserApps)   applyReadPermToItem(item, permMap);
        for (AppItem item : mReadSystemApps) applyReadPermToItem(item, permMap);
        for (AppItem item : mReadCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlockedRead = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        }
        sortReadAppLists();
        applyReadFilter();
    }

    private void applyReadPermToItem(AppItem item, android.util.ArrayMap<String, Integer> permMap) {
        Integer saved = permMap.get(item.packageName);
        item.isBlockedRead = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        Integer pending = mReadPendingChanges.get(item.packageName);
        if (pending != null) {
            item.isBlockedRead = (pending == PermissionDecision.PERMISSION_BLOCK);
        }
    }

    private void applyReadFilter() {
        mReadFilteredUser.clear();
        mReadFilteredSystem.clear();
        mReadFilteredCore.clear();

        if (mReadCurrentQuery.isEmpty()) {
            mReadFilteredUser.addAll(mReadUserApps);
            mReadFilteredSystem.addAll(mReadSystemApps);
            mReadFilteredCore.addAll(mReadCoreApps);
        } else {
            for (AppItem i : mReadUserApps)   if (matchesRead(i)) mReadFilteredUser.add(i);
            for (AppItem i : mReadSystemApps) if (matchesRead(i)) mReadFilteredSystem.add(i);
            for (AppItem i : mReadCoreApps)   if (matchesRead(i)) mReadFilteredCore.add(i);
        }

        if (mReadAdapter != null) mReadAdapter.notifyDataSetChanged();
        if (!mReadCurrentQuery.isEmpty() && mReadExpandableListView != null) {
            mReadExpandableListView.expandGroup(GROUP_USER);
            mReadExpandableListView.expandGroup(GROUP_SYSTEM);
            mReadExpandableListView.expandGroup(GROUP_CORE);
        }
    }

    private boolean matchesRead(AppItem item) {
        return safeText(item.appName).toLowerCase(Locale.ROOT).contains(mReadCurrentQuery)
                || safeText(item.packageName).toLowerCase(Locale.ROOT).contains(mReadCurrentQuery);
    }

    private static void putPermissionRow(android.util.ArrayMap<String, Integer> target, String[] row) {
        if (row == null || row.length < 2 || row[0] == null || row[0].isEmpty()) return;
        try {
            target.put(row[0], Integer.parseInt(row[1]));
        } catch (NumberFormatException ignored) {
            // 忽略异常行，避免单条脏配置导致应用列表刷新失败。
        }
    }

    private static String safeText(String text) {
        return text != null ? text : "";
    }

    private AppItem getReadItem(int group, int child) {
        if (group == GROUP_USER   && child < mReadFilteredUser.size())   return mReadFilteredUser.get(child);
        if (group == GROUP_SYSTEM && child < mReadFilteredSystem.size()) return mReadFilteredSystem.get(child);
        if (group == GROUP_CORE   && child < mReadFilteredCore.size())   return mReadFilteredCore.get(child);
        return null;
    }

    // ──────────────────── 写入权限保存 ────────────────────────────

    private void setAllWriteApps(boolean blocked) {
        int perm = blocked ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE;
        for (AppItem i : mWriteUserApps)   { i.isBlockedWrite = blocked; mWritePendingChanges.put(i.packageName, perm); }
        for (AppItem i : mWriteSystemApps) { i.isBlockedWrite = blocked; mWritePendingChanges.put(i.packageName, perm); }
        applyWriteFilter();
    }

    private void toggleWriteGroupSelection(int groupPos, boolean select) {
        int perm = select ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE;
        List<AppItem> list;
        if (groupPos == GROUP_USER) {
            list = mWriteFilteredUser;
        } else if (groupPos == GROUP_SYSTEM) {
            list = mWriteFilteredSystem;
        } else {
            return;
        }

        for (AppItem item : list) {
            if (!item.isCore) {
                item.isBlockedWrite = select;
                mWritePendingChanges.put(item.packageName, perm);
            }
        }
        mWriteAdapter.notifyDataSetChanged();
    }

    private void saveWriteChanges() {
        if (mWritePendingChanges.isEmpty()) {
            Toast.makeText(this, "没有更改需要保存", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Integer> allWritePerms = new HashMap<>();
        for (AppItem i : mWriteUserApps) {
            allWritePerms.put(i.packageName,
                    i.isBlockedWrite ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE);
        }
        for (AppItem i : mWriteSystemApps) {
            allWritePerms.put(i.packageName,
                    i.isBlockedWrite ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE);
        }

        PermissionProvider.saveAllWritePermissions(this, allWritePerms);
        PermissionProvider.sendReadWriteBlocklistBroadcast(this);

        mWritePendingChanges.clear();
        sortWriteAppLists();
        applyWriteFilter();

        int writeblocked = 0;
        for (AppItem i : mWriteUserApps)   if (i.isBlockedWrite) writeblocked++;
        for (AppItem i : mWriteSystemApps) if (i.isBlockedWrite) writeblocked++;

        Toast.makeText(this,
                writeblocked > 0 ? getString(R.string.save_success, writeblocked) : getString(R.string.save_no_block),
                Toast.LENGTH_SHORT).show();
    }

    // ──────────────────── 读取权限保存 ────────────────────────────

    private void setAllReadApps(boolean blocked) {
        int perm = blocked ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE;
        for (AppItem i : mReadUserApps)   { i.isBlockedRead = blocked; mReadPendingChanges.put(i.packageName, perm); }
        for (AppItem i : mReadSystemApps) { i.isBlockedRead = blocked; mReadPendingChanges.put(i.packageName, perm); }
        applyReadFilter();
    }

    private void toggleReadGroupSelection(int groupPos, boolean select) {
        int perm = select ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE;
        List<AppItem> list;
        if (groupPos == GROUP_USER) {
            list = mReadFilteredUser;
        } else if (groupPos == GROUP_SYSTEM) {
            list = mReadFilteredSystem;
        } else {
            return;
        }

        for (AppItem item : list) {
            if (!item.isCore) {
                item.isBlockedRead = select;
                mReadPendingChanges.put(item.packageName, perm);
            }
        }
        if (mReadAdapter != null) mReadAdapter.notifyDataSetChanged();
    }

    private void saveReadChanges() {
        if (mReadPendingChanges.isEmpty()) {
            Toast.makeText(this, "没有更改需要保存", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Integer> allReadPerms = new HashMap<>();
        for (AppItem i : mReadUserApps) {
            allReadPerms.put(i.packageName,
                    i.isBlockedRead ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE);
        }
        for (AppItem i : mReadSystemApps) {
            allReadPerms.put(i.packageName,
                    i.isBlockedRead ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE);
        }

        PermissionProvider.saveAllReadPermissions(this, allReadPerms);
        PermissionProvider.sendReadWriteBlocklistBroadcast(this);
        mReadPendingChanges.clear();
        sortReadAppLists();
        applyReadFilter();

        int readblocked = 0;
        for (AppItem i : mReadUserApps)   if (i.isBlockedRead) readblocked++;
        for (AppItem i : mReadSystemApps) if (i.isBlockedRead) readblocked++;

        Toast.makeText(this,
                readblocked > 0 ? getString(R.string.save_success, readblocked) : getString(R.string.save_no_block),
                Toast.LENGTH_SHORT).show();
    }

    // ──────────────────── 规则文件初始化 ────────────────────────────

    @SuppressLint("SdCardPath")
    private void initRuleFiles() {
        // 使用 Context.getFilesDir().getPath() 代替硬编码 /data/
        String filesDir = getFilesDir().getPath();
        PermissionProvider.ensureBlocklistFile(filesDir + "/write_blocklist.txt");
        PermissionProvider.ensureBlocklistFile(filesDir + "/read_blocklist.txt");

        ensureEmptyJsonFile("write_rules.json");
        ensureEmptyJsonFile("read_rules.json");
        ensureEmptyJsonFile("write_default_rules.json");
        ensureEmptyJsonFile("read_default_rules.json");
    }

    private void ensureEmptyJsonFile(String fileName) {
        File file = new File(getFilesDir(), fileName);
        if (!file.exists()) {
            try {
                writeEmptyJsonFile(file);
            } catch (Exception e) {
                XLog.e("ClipboardGuard", "initRuleFiles: failed to create " + fileName, e);
            }
        }
    }

    private void writeEmptyJsonFile(File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("[]".getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    // ──────────────────── 数据模型 ────────────────────────────

    static class AppItem {
        String  packageName;
        String  appName;
        boolean isSystem;
        boolean isCore;
        boolean isBlockedWrite;
        boolean isBlockedRead;
    }

    static class GroupViewHolder {
        TextView tvTitle, tvArrow;
        CheckBox cbSelectAll;
        GroupViewHolder(View v) {
            tvTitle = v.findViewById(R.id.tv_group_title);
            tvArrow = v.findViewById(R.id.tv_arrow);
            cbSelectAll = v.findViewById(R.id.cb_select_all);
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

    // ──────────────────── Adapter ────────────────────────────

    class AppGroupAdapter extends BaseExpandableListAdapter {

        private final boolean mIsReadPage;

        AppGroupAdapter(boolean isReadPage) {
            mIsReadPage = isReadPage;
        }

        @Override public int  getGroupCount()                        { return 3; }
        @Override public int  getChildrenCount(int g)                {
            if (mIsReadPage) {
                if (g == GROUP_USER)   return mReadFilteredUser.size();
                if (g == GROUP_SYSTEM) return mReadFilteredSystem.size();
                return mReadFilteredCore.size();
            } else {
                if (g == GROUP_USER)   return mWriteFilteredUser.size();
                if (g == GROUP_SYSTEM) return mWriteFilteredSystem.size();
                return mWriteFilteredCore.size();
            }
        }
        @Override public Object  getGroup(int g)                     { return g; }
        @Override public Object  getChild(int g, int c)              {
            if (mIsReadPage) return getReadItem(g, c);
            else             return getWriteItem(g, c);
        }
        @Override public long    getGroupId(int g)                   { return g; }
        @Override public long    getChildId(int g, int c)            { return c; }
        @Override public boolean hasStableIds()                      { return false; }
        @Override public boolean isChildSelectable(int g, int c)     { return g != GROUP_CORE; }
        @SuppressLint("SetTextI18n")
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

            List<AppItem> list;
            if (mIsReadPage) {
                list = isCore ? mReadFilteredCore : isUser ? mReadFilteredUser : mReadFilteredSystem;
            } else {
                list = isCore ? mWriteFilteredCore : isUser ? mWriteFilteredUser : mWriteFilteredSystem;
            }

            int blocked = 0;
            for (AppItem i : list) {
                if (mIsReadPage ? i.isBlockedRead : i.isBlockedWrite) blocked++;
            }

            h.tvArrow.setText(expanded ? "▲" : "▼");

            final int groupPos = g;
            ExpandableListView elv = mIsReadPage ? mReadExpandableListView : mWriteExpandableListView;
            convert.setOnClickListener(v -> {
                if (elv.isGroupExpanded(groupPos)) {
                    elv.collapseGroup(groupPos);
                } else {
                    elv.expandGroup(groupPos);
                }
                resetFabAutoHide();
            });

            if (isCore) {
                h.tvTitle.setText(getString(R.string.group_core_apps) + "  " + list.size() + " 个（不可更改）🔒");
                h.cbSelectAll.setVisibility(View.GONE);
            } else {
                h.tvTitle.setText((isUser ? getString(R.string.group_user_apps)
                        : getString(R.string.group_system_apps))
                        + "  " + list.size() + " 个"
                        + (blocked > 0 ? "（拦截 " + blocked + "）" : ""));
                h.cbSelectAll.setVisibility(View.VISIBLE);

                boolean allBlocked = blocked == list.size();
                boolean noneBlocked = blocked == 0;

                if (!list.isEmpty() && allBlocked) {
                    h.cbSelectAll.setChecked(true);
                    h.cbSelectAll.setSelected(false);
                } else if (!list.isEmpty() && noneBlocked) {
                    h.cbSelectAll.setChecked(false);
                    h.cbSelectAll.setSelected(false);
                } else {
                    h.cbSelectAll.setChecked(false);
                    h.cbSelectAll.setSelected(true);
                }

                h.cbSelectAll.setOnClickListener(v -> v.post(() -> {
                    boolean currentChecked = h.cbSelectAll.isChecked();
                    if (mIsReadPage) {
                        toggleReadGroupSelection(groupPos, currentChecked);
                    } else {
                        toggleWriteGroupSelection(groupPos, currentChecked);
                    }
                    resetFabAutoHide();
                }));
                h.cbSelectAll.setFocusable(false);
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
            AppItem item = mIsReadPage ? getReadItem(g, c) : getWriteItem(g, c);
            if (item == null) return convert;

            Drawable icon = mIconCache.get(item.packageName);
            if (icon == null) {
                try {
                    icon = getPackageManager().getApplicationIcon(item.packageName);
                    mIconCache.put(item.packageName, icon);
                } catch (PackageManager.NameNotFoundException e) {
                    icon = ContextCompat.getDrawable(MainActivity.this, R.drawable.ic_app_default);
                }
            }
            h.ivIcon.setImageDrawable(icon);

            h.tvName.setText(item.appName);
            boolean isBlocked = mIsReadPage ? item.isBlockedRead : item.isBlockedWrite;
            h.cbBlock.setChecked(isBlocked);
            h.cbBlock.setVisibility(item.isCore ? View.GONE : View.VISIBLE);
            h.tvName.setEnabled(!item.isCore);
            h.tvPkg.setEnabled(!item.isCore);
            h.tvPkg.setText(item.isCore ? item.packageName + "  🔒" : item.packageName);
            return convert;
        }
    }
}
