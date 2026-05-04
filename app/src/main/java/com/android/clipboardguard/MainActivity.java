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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.FileInputStream;
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
    public static final int PAGE_PERMISSION_DETAIL = 4;

    public static final String PREF_NAME   = ClipboardGuardApp.PREF_NAME;
    public static final String KEY_THEME   = ClipboardGuardApp.KEY_THEME;
    public static final int    THEME_LIGHT  = ClipboardGuardApp.THEME_LIGHT;
    public static final int    THEME_DARK   = ClipboardGuardApp.THEME_DARK;
    public static final int    THEME_SYSTEM = ClipboardGuardApp.THEME_SYSTEM;

    private View mPageHome;
    private View mPageWrite;
    private View mPageRead;
    private View mPageSettings;
    private View mPagePermissionDetail;
    private FloatingActionButton mFab;
    private LinearLayout mBottomNav;
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
    private final Runnable mFabAutoHide = () -> {
        if (mFab != null && mFab.getVisibility() == View.VISIBLE) {
            mFab.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                    .setDuration(200)
                    .withEndAction(() -> mFab.setVisibility(View.GONE))
                    .start();
        }
    };

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyThemeNoView();
        super.onCreate(savedInstanceState);

        sInstanceRef = new WeakReference<>(this);
        setContentView(R.layout.activity_main);

        initThemeRadioButtons();
        applyTheme();

        View appBarView = findViewById(R.id.app_bar);
        ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0),
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarView);

        mPageHome             = findViewById(R.id.page_home);
        mPageWrite            = findViewById(R.id.page_write);
        mPageRead             = findViewById(R.id.page_read);
        mPageSettings         = findViewById(R.id.page_settings);
        mPagePermissionDetail = findViewById(R.id.page_permission_detail);
        mFab                  = findViewById(R.id.fab_save);
        mBottomNav            = findViewById(R.id.bottom_nav);
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

        initWritePage();
        initReadPage();
        initRuleFiles();
        initHomePage();
        setupBottomNav();
        setupSettingsPage();
        setupBackPressed();
        loadAppsAsync();

        showPage(sCurrentPage == PAGE_WRITE || sCurrentPage == PAGE_READ
                || sCurrentPage == PAGE_SETTINGS ? sCurrentPage : PAGE_HOME);

        PermissionProvider.sendFullConfigBroadcast(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        initHomePage();
        if (sCurrentPage == PAGE_PERMISSION_DETAIL) initPermissionDetailPage();
        loadAppsAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

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
                    item.isBlockedWrite ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE);
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
                    item.isBlockedRead ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE);
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

    private void showPage(int page) {
        sCurrentPage = page;

        if (page != PAGE_WRITE && page != PAGE_READ) {
            mHandler.removeCallbacks(mFabAutoHide);
            if (!mWritePendingChanges.isEmpty()) {
                mWritePendingChanges.clear();
                refreshWritePermissions();
                if (mWriteAdapter != null) mWriteAdapter.notifyDataSetChanged();
            }
            if (!mReadPendingChanges.isEmpty()) {
                mReadPendingChanges.clear();
                refreshReadPermissions();
                if (mReadAdapter != null) mReadAdapter.notifyDataSetChanged();
            }
        }

        mPageHome.setVisibility(View.GONE);
        mPageWrite.setVisibility(View.GONE);
        mPageRead.setVisibility(View.GONE);
        mPageSettings.setVisibility(View.GONE);
        mPagePermissionDetail.setVisibility(View.GONE);

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
            case PAGE_PERMISSION_DETAIL:
                mPagePermissionDetail.setVisibility(View.VISIBLE);
                mFab.setVisibility(View.GONE);
                initPermissionDetailPage();
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
            case PAGE_PERMISSION_DETAIL:
                toolbar.setTitle(R.string.settings_permission);
                toolbar.setNavigationIcon(R.drawable.ic_back);
                break;
        }
        toolbar.setNavigationOnClickListener(v -> {
            if (sCurrentPage == PAGE_PERMISSION_DETAIL) {
                showPage(PAGE_SETTINGS);
            }
        });
    }

    private void updateBottomNav(int page) {
        int sel   = ContextCompat.getColor(this, R.color.nav_selected);
        int unsel = ContextCompat.getColor(this, R.color.nav_unselected);
        tintNavItem(mNavHome,     page == PAGE_HOME,     sel, unsel);
        tintNavItem(mNavApps,     page == PAGE_WRITE,    sel, unsel);
        tintNavItem(mNavRead,     page == PAGE_READ,     sel, unsel);
        tintNavItem(mNavSettings, page == PAGE_SETTINGS, sel, unsel);

        mBottomNav.setVisibility(page == PAGE_PERMISSION_DETAIL ? View.GONE : View.VISIBLE);
    }

    private void tintNavItem(LinearLayout nav, boolean selected, int selColor, int unselColor) {
        int color = selected ? selColor : unselColor;
        ((ImageView) nav.getChildAt(0)).setColorFilter(color);
        ((TextView)  nav.getChildAt(1)).setTextColor(color);
    }

    private void setupBottomNav() {
        View.OnClickListener navClick = v -> {
            int id = v.getId();
            if      (id == R.id.nav_home)     showPage(PAGE_HOME);
            else if (id == R.id.nav_apps)     showPage(PAGE_WRITE);
            else if (id == R.id.nav_read)     showPage(PAGE_READ);
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

    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (sCurrentPage == PAGE_PERMISSION_DETAIL) {
                    showPage(PAGE_SETTINGS);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed(); // 不再使用已弃用的 super.onBackPressed()
                }
            }
        });
    }

    @SuppressLint("SetTextI18n")
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
            // 使用 getLongVersionCode() 代替已弃用的 versionCode
            mTvModuleVersion.setText("v" + pi.versionName + " (" + pi.getLongVersionCode() + ")");
        } catch (PackageManager.NameNotFoundException e) {
            mTvModuleVersion.setText("--");
        }

        mTvAndroidVersion.setText(Build.VERSION.RELEASE);
        mTvManufacturer.setText(Build.MANUFACTURER);
        mTvModel.setText(Build.MODEL);
    }

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

    private boolean isModuleActive() { return false; }
    private int getXposedApiVersion() { return -1; }

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

        View itemPermission = findViewById(R.id.item_permission);
        if (itemPermission != null) {
            itemPermission.setVisibility(View.GONE);
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

    private String getModuleVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "--";
        }
    }

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
            writePermMap.put(row[0], Integer.parseInt(row[1]));
        }

        List<String[]> savedReadPerms = PermissionProvider.getAllReadPermissions(this);
        android.util.ArrayMap<String, Integer> readPermMap = new android.util.ArrayMap<>();
        for (String[] row : savedReadPerms) {
            readPermMap.put(row[0], Integer.parseInt(row[1]));
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
            String appName = pm.getApplicationLabel(info).toString();
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            Integer writeSaved = writePermMap.get(info.packageName);
            AppItem writeItem = new AppItem();
            writeItem.packageName = info.packageName;
            writeItem.appName = appName;
            writeItem.isSystem = isSystem;
            writeItem.isCore = isCore;
            writeItem.isBlockedWrite = (writeSaved != null && writeSaved == PermissionStorage.PERMISSION_BLOCK);
            if (isCore) tmpWriteCore.add(writeItem);
            else if (isSystem) tmpWriteSystem.add(writeItem);
            else tmpWriteUser.add(writeItem);

            Integer readSaved = readPermMap.get(info.packageName);
            AppItem readItem = new AppItem();
            readItem.packageName = info.packageName;
            readItem.appName = appName;
            readItem.isSystem = isSystem;
            readItem.isCore = isCore;
            readItem.isBlockedRead = (readSaved != null && readSaved == PermissionStorage.PERMISSION_BLOCK);
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
            mWriteExpandableListView.expandGroup(GROUP_USER);

            if (mWriteSwipeRefresh != null) mWriteSwipeRefresh.setRefreshing(false);
            if (mReadSwipeRefresh != null) mReadSwipeRefresh.setRefreshing(false);
        });
    }

    private static void sortWriteApps(List<AppItem> list) {
        list.sort((a, b) -> {
            if (a.isBlockedWrite != b.isBlockedWrite) return a.isBlockedWrite ? -1 : 1;
            return a.appName.compareToIgnoreCase(b.appName);
        });
    }

    private static void sortReadApps(List<AppItem> list) {
        list.sort((a, b) -> {
            if (a.isBlockedRead != b.isBlockedRead) return a.isBlockedRead ? -1 : 1;
            return a.appName.compareToIgnoreCase(b.appName);
        });
    }

    private void refreshWritePermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllWritePermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) permMap.put(row[0], Integer.parseInt(row[1]));

        for (AppItem item : mWriteUserApps)   applyWritePermToItem(item, permMap);
        for (AppItem item : mWriteSystemApps) applyWritePermToItem(item, permMap);
        for (AppItem item : mWriteCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlockedWrite = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
        }
        applyWriteFilter();
    }

    private void applyWritePermToItem(AppItem item, android.util.ArrayMap<String, Integer> permMap) {
        Integer saved = permMap.get(item.packageName);
        item.isBlockedWrite = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
        Integer pending = mWritePendingChanges.get(item.packageName);
        if (pending != null) {
            item.isBlockedWrite = (pending == PermissionStorage.PERMISSION_BLOCK);
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
            if (mWriteAdapter != null) mWriteAdapter.notifyDataSetChanged();
            if (!mWriteCurrentQuery.isEmpty()) {
                mWriteExpandableListView.expandGroup(GROUP_USER);
                mWriteExpandableListView.expandGroup(GROUP_SYSTEM);
                mWriteExpandableListView.expandGroup(GROUP_CORE);
            }
        });
    }

    private boolean matchesWrite(AppItem item) {
        return item.appName.toLowerCase(Locale.ROOT).contains(mWriteCurrentQuery)
                || item.packageName.toLowerCase(Locale.ROOT).contains(mWriteCurrentQuery);
    }

    private AppItem getWriteItem(int group, int child) {
        if (group == GROUP_USER   && child < mWriteFilteredUser.size())   return mWriteFilteredUser.get(child);
        if (group == GROUP_SYSTEM && child < mWriteFilteredSystem.size()) return mWriteFilteredSystem.get(child);
        if (group == GROUP_CORE   && child < mWriteFilteredCore.size())   return mWriteFilteredCore.get(child);
        return null;
    }

    private void refreshReadPermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllReadPermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) permMap.put(row[0], Integer.parseInt(row[1]));

        for (AppItem item : mReadUserApps)   applyReadPermToItem(item, permMap);
        for (AppItem item : mReadSystemApps) applyReadPermToItem(item, permMap);
        for (AppItem item : mReadCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlockedRead = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
        }
        applyReadFilter();
    }

    private void applyReadPermToItem(AppItem item, android.util.ArrayMap<String, Integer> permMap) {
        Integer saved = permMap.get(item.packageName);
        item.isBlockedRead = (saved != null && saved == PermissionStorage.PERMISSION_BLOCK);
        Integer pending = mReadPendingChanges.get(item.packageName);
        if (pending != null) {
            item.isBlockedRead = (pending == PermissionStorage.PERMISSION_BLOCK);
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
        return item.appName.toLowerCase(Locale.ROOT).contains(mReadCurrentQuery)
                || item.packageName.toLowerCase(Locale.ROOT).contains(mReadCurrentQuery);
    }

    private AppItem getReadItem(int group, int child) {
        if (group == GROUP_USER   && child < mReadFilteredUser.size())   return mReadFilteredUser.get(child);
        if (group == GROUP_SYSTEM && child < mReadFilteredSystem.size()) return mReadFilteredSystem.get(child);
        if (group == GROUP_CORE   && child < mReadFilteredCore.size())   return mReadFilteredCore.get(child);
        return null;
    }

    private void setAllWriteApps(boolean blocked) {
        int perm = blocked ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE;
        for (AppItem i : mWriteUserApps)   { i.isBlockedWrite = blocked; mWritePendingChanges.put(i.packageName, perm); }
        for (AppItem i : mWriteSystemApps) { i.isBlockedWrite = blocked; mWritePendingChanges.put(i.packageName, perm); }
        applyWriteFilter();
    }

    private void toggleWriteGroupSelection(int groupPos, boolean select) {
        int perm = select ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE;
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
                    i.isBlockedWrite ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE);
        }
        for (AppItem i : mWriteSystemApps) {
            allWritePerms.put(i.packageName,
                    i.isBlockedWrite ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE);
        }

        PermissionProvider.saveAllWritePermissions(this, allWritePerms);
        PermissionProvider.sendBlocklistBroadcast(this);

        mWritePendingChanges.clear();

        int writeblocked = 0;
        for (AppItem i : mWriteUserApps)   if (i.isBlockedWrite) writeblocked++;
        for (AppItem i : mWriteSystemApps) if (i.isBlockedWrite) writeblocked++;

        Toast.makeText(this,
                writeblocked > 0 ? getString(R.string.save_success, writeblocked) : getString(R.string.save_no_block),
                Toast.LENGTH_SHORT).show();
    }

    private void setAllReadApps(boolean blocked) {
        int perm = blocked ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE;
        for (AppItem i : mReadUserApps)   { i.isBlockedRead = blocked; mReadPendingChanges.put(i.packageName, perm); }
        for (AppItem i : mReadSystemApps) { i.isBlockedRead = blocked; mReadPendingChanges.put(i.packageName, perm); }
        applyReadFilter();
    }

    private void toggleReadGroupSelection(int groupPos, boolean select) {
        int perm = select ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE;
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
                    i.isBlockedRead ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE);
        }
        for (AppItem i : mReadSystemApps) {
            allReadPerms.put(i.packageName,
                    i.isBlockedRead ? PermissionStorage.PERMISSION_BLOCK : PermissionStorage.PERMISSION_IGNORE);
        }

        PermissionProvider.saveAllReadPermissions(this, allReadPerms);
        PermissionProvider.sendBlocklistBroadcast(this);
        mReadPendingChanges.clear();

        int readblocked = 0;
        for (AppItem i : mReadUserApps)   if (i.isBlockedRead) readblocked++;
        for (AppItem i : mReadSystemApps) if (i.isBlockedRead) readblocked++;

        Toast.makeText(this,
                readblocked > 0 ? getString(R.string.save_success, readblocked) : getString(R.string.save_no_block),
                Toast.LENGTH_SHORT).show();
    }

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
                writeFile(file, "[]");
            } catch (Exception e) {
                XLog.e("ClipboardGuard", "initRuleFiles: failed to create " + fileName, e);
            }
        }
    }

    @SuppressWarnings("unused")
    private String readFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int bytesRead = fis.read(buffer, offset, buffer.length - offset);
                if (bytesRead == -1) break;
                offset += bytesRead;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void writeFile(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
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