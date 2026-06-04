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
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.DocumentsContract;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 主界面 Activity，包含 4 个页面：首页 / 写入拦截 / 读取拦截 / 设置。
 *
 * 页面切换通过 View 可见性控制（非 Fragment），底部导航栏切换页面。
 *
 * 核心职责：
 * - 首页：显示模块激活状态（通过 Binder IPC 轮询 system_server）
 * - 写入拦截页：管理写入拦截名单（ExpandableListView 分组：用户/系统/核心应用）
 * - 读取拦截页：管理读取拦截名单（同上结构）
 * - 设置页：主题切换、开关设置、备份恢复、关于页面入口
 *
 * 配置同步：保存后通过 PermissionProvider 广播到 system_server 侧刷新内存。
 * 备份恢复：支持 ZIP 格式的配置导入导出。
 */
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

    private static final String[] BACKUP_FILE_NAMES = {
            "write_blocklist.txt",
            "read_blocklist.txt",
            "write_rules.json",
            "read_rules.json",
            "write_default_rules.json",
            "read_default_rules.json"
    };

    /** Binder IPC 轮询重试次数与间隔。 */
    private static final int IPC_RETRY_MAX = 24;
    private static final long IPC_RETRY_INTERVAL_MS = 5000L;

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
    private ActivityResultLauncher<Intent> mBackupFolderLauncher;
    private ActivityResultLauncher<Intent> mRestoreFileLauncher;

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
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ClipboardGuard-Worker");
        t.setDaemon(true);
        return t;
    });

    private final LruCache<String, Drawable> mIconCache = new LruCache<>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            int width = Math.max(1, value.getIntrinsicWidth());
            int height = Math.max(1, value.getIntrinsicHeight());
            return width * height * 4;
        }
    };

    private static final long FAB_AUTO_HIDE_DELAY = 4000L;
    private static final long BOTTOM_NAV_DOUBLE_CLICK_MS = 350L;
    private static final long DETAIL_ACTIVITY_CLICK_DEBOUNCE_MS = 600L;
    private long mLastWriteNavClickTime = 0L;
    private long mLastReadNavClickTime = 0L;
    private long mLastDetailActivityClickTime = 0L;
    private final AtomicBoolean mIsLoadingApps = new AtomicBoolean(false);
    private final AtomicBoolean mIsRefreshingPermissions = new AtomicBoolean(false);
    private final AtomicBoolean mLoadAppsQueued = new AtomicBoolean(false);
    private final AtomicBoolean mRefreshPermissionsQueued = new AtomicBoolean(false);
    private final AtomicInteger mLoadAppsGeneration = new AtomicInteger(0);
    private final AtomicInteger mRefreshPermissionsGeneration = new AtomicInteger(0);
    private volatile boolean mHasLoadedApps = false;
    private volatile boolean mInitialConfigSyncDone = false;
    private final Runnable mFabAutoHide = () -> {
        if (mFab != null && mFab.getVisibility() == View.VISIBLE) {
            mFab.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                    .setDuration(200)
                    .withEndAction(() -> mFab.setVisibility(View.GONE))
                    .start();
        }
    };

    // ──────────────────── FAB 显示控制 ────────────────────────────

    /** 重置 FAB 自动隐藏计时器 */
    private void resetFabAutoHide() {
        if (mFab == null) return;
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
    /** Activity 创建：初始化视图、主题、页面、数据加载 */
    protected void onCreate(Bundle savedInstanceState) {
        applyThemeNoView();
        super.onCreate(savedInstanceState);

        sInstanceRef = new WeakReference<>(this);
        initBackupRestoreLaunchers();
        setContentView(R.layout.activity_main);

        initThemeRadioButtons();
        applyTheme();

        applyAppBarInsets();
        bindMainViews();

        // 版本号：主线程直接读（自家包名，PMS 缓存命中，不阻塞）
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            String verText = "v" + pi.versionName + " (" + pi.getLongVersionCode() + ")";
            if (mTvModuleVersion != null) {
                mTvModuleVersion.setText(verText);
            }
        } catch (PackageManager.NameNotFoundException e) {
            if (mTvModuleVersion != null) {
                mTvModuleVersion.setText("--");
            }
        }

        // 先初始化规则文件，再检测模块激活状态
        // 避免 checkModuleActive() 触发配置同步时规则文件尚未创建
        initPagesAndData();

        checkModuleActive();

        showPage(sCurrentPage == PAGE_WRITE || sCurrentPage == PAGE_READ
                || sCurrentPage == PAGE_SETTINGS ? sCurrentPage : PAGE_HOME);
    }

    /** 给顶部应用栏补上状态栏高度，避免标题与系统状态栏重叠 */
    private void applyAppBarInsets() {
        View appBarView = findViewById(R.id.app_bar);
        if (appBarView == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0),
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarView);
    }

    /** 绑定主界面所有 View 控件 */
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

    private boolean mWritePageInited = false;
    private boolean mReadPageInited  = false;

    /** 初始化页面和数据加载 */
    private void initPagesAndData() {
        initHomePage();
        setupBottomNav();
        setupSettingsPage();
        // 文件 I/O 放到后台，但使用 CountDownLatch 确保完成后再继续
        // 避免 checkModuleActive() 触发配置同步时规则文件尚未创建
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(6);
        String filesDir = getFilesDir().getPath();
        sExecutor.execute(() -> { PermissionProvider.ensureBlocklistFile(filesDir + "/write_blocklist.txt"); latch.countDown(); });
        sExecutor.execute(() -> { PermissionProvider.ensureBlocklistFile(filesDir + "/read_blocklist.txt"); latch.countDown(); });
        sExecutor.execute(() -> { ensureEmptyJsonFile("write_rules.json"); latch.countDown(); });
        sExecutor.execute(() -> { ensureEmptyJsonFile("read_rules.json"); latch.countDown(); });
        sExecutor.execute(() -> { ensureEmptyJsonFile("write_default_rules.json"); latch.countDown(); });
        sExecutor.execute(() -> { ensureEmptyJsonFile("read_default_rules.json"); latch.countDown(); });
        // 等待所有文件初始化完成（最多 3 秒）
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            XLog.w("ClipboardGuard", "等待规则文件初始化被中断");
        }
        loadAppsAsync();
    }

    /** 初始化备份恢复的 ActivityResultLauncher */
    private void initBackupRestoreLaunchers() {
        mBackupFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != RESULT_OK || data == null || data.getData() == null) {
                        Toast.makeText(this, R.string.backup_cancelled, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    backupConfigToFolder(data.getData());
                });

        mRestoreFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != RESULT_OK || data == null || data.getData() == null) {
                        Toast.makeText(this, R.string.restore_cancelled, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    restoreConfigFromFile(data.getData());
                });
    }

    @Override
    /** Activity 恢复：刷新权限状态、同步配置 */
    protected void onResume() {
        super.onResume();
        if (mHasLoadedApps) {
            refreshPermissionsAsync();
        }
        // 配置同步已移至 checkModuleActive() 中，仅首次激活时执行一次
    }

    @Override
    /** Activity 暂停：丢弃未提交的更改（用户需点 FAB 保存） */
    protected void onPause() {
        discardPendingChangesForPage(sCurrentPage);
        super.onPause();
    }

    @Override
    /** Activity 销毁：清理资源 */
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        mIconCache.evictAll();
        if (mWriteSwipeRefresh != null) {
            mWriteSwipeRefresh.setRefreshing(false);
            mWriteSwipeRefresh = null;
        }
        if (mReadSwipeRefresh != null) {
            mReadSwipeRefresh.setRefreshing(false);
            mReadSwipeRefresh = null;
        }
        mWriteExpandableListView = null;
        mReadExpandableListView = null;
        mWriteAdapter = null;
        mReadAdapter = null;
        mFab = null;
        MainActivity instance = sInstanceRef != null ? sInstanceRef.get() : null;
        if (instance == this) {
            sInstanceRef.clear();
            sInstanceRef = null;
        }
    }

    // ──────────────────── 读写页面初始化 ────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    /** 初始化写入拦截页面（ExpandableListView、搜索、FAB） */
    private void initWritePage() {
        mWriteExpandableListView = findViewById(R.id.expandable_list_write);
        mWriteSwipeRefresh = findViewById(R.id.swipe_refresh_write);
        if (mWriteExpandableListView == null || mWriteSwipeRefresh == null) return;

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
        if (mWriteEtSearch != null) {
            mWriteEtSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    mWriteCurrentQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                    applyWriteFilter();
                    resetFabAutoHide();
                }
            });
        }

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
            cardWriteRules.setOnClickListener(v -> openDetailActivity(WriteRulesDetailActivity.class));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    /** 初始化读取拦截页面（ExpandableListView、搜索、FAB） */
    private void initReadPage() {
        mReadExpandableListView = findViewById(R.id.expandable_list_read);
        mReadSwipeRefresh = findViewById(R.id.swipe_refresh_read);
        if (mReadExpandableListView == null || mReadSwipeRefresh == null) return;

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
        if (mReadEtSearch != null) {
            mReadEtSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    mReadCurrentQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                    applyReadFilter();
                    resetFabAutoHide();
                }
            });
        }

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
            cardReadRules.setOnClickListener(v -> openDetailActivity(ReadRulesDetailActivity.class));
        }
    }

    // ──────────────────── 页面切换与底部导航 ────────────────────────────

    /** 切换显示指定页面 */
    private void showPage(int page) {
        if (mPageHome == null || mPageWrite == null || mPageRead == null || mPageSettings == null) return;
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
                if (!mWritePageInited) { initWritePage(); mWritePageInited = true; }
                mPageWrite.setVisibility(View.VISIBLE);
                if (mFab != null) mFab.setVisibility(View.GONE);
                if (mWriteExpandableListView != null) mWriteExpandableListView.expandGroup(GROUP_USER);
                break;
            case PAGE_READ:
                if (!mReadPageInited) { initReadPage(); mReadPageInited = true; }
                mPageRead.setVisibility(View.VISIBLE);
                if (mFab != null) mFab.setVisibility(View.GONE);
                if (mReadExpandableListView != null) mReadExpandableListView.expandGroup(GROUP_USER);
                break;
            case PAGE_SETTINGS:
                mPageSettings.setVisibility(View.VISIBLE);
                if (mFab != null) mFab.setVisibility(View.GONE);
                break;
        }

        updateToolbar(page);
        updateBottomNav(page);
    }

    /** 更新顶部工具栏标题 */
    private void updateToolbar(int page) {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) return;
        switch (page) {
            case PAGE_HOME:       toolbar.setTitle(R.string.app_name);         toolbar.setNavigationIcon(null); break;
            case PAGE_WRITE:      toolbar.setTitle(R.string.title_write_block); toolbar.setNavigationIcon(null); break;
            case PAGE_READ:       toolbar.setTitle(R.string.title_read_block);  toolbar.setNavigationIcon(null); break;
            case PAGE_SETTINGS:   toolbar.setTitle(R.string.nav_settings);      toolbar.setNavigationIcon(null); break;
        }
    }

    /** 更新底部导航栏选中状态 */
    private void updateBottomNav(int page) {
        if (mNavHome == null || mNavApps == null || mNavRead == null || mNavSettings == null) return;
        int sel   = ContextCompat.getColor(this, R.color.nav_selected);
        int unsel = ContextCompat.getColor(this, R.color.nav_unselected);
        tintNavItem(mNavHome,     page == PAGE_HOME,     sel, unsel);
        tintNavItem(mNavApps,     page == PAGE_WRITE,    sel, unsel);
        tintNavItem(mNavRead,     page == PAGE_READ,     sel, unsel);
        tintNavItem(mNavSettings, page == PAGE_SETTINGS, sel, unsel);

    }

    /** 设置导航项颜色（选中/未选中） */
    private void tintNavItem(LinearLayout nav, boolean selected, int selColor, int unselColor) {
        if (nav == null || nav.getChildCount() < 2) return;
        if (!(nav.getChildAt(0) instanceof ImageView) || !(nav.getChildAt(1) instanceof TextView)) return;
        int color = selected ? selColor : unselColor;
        ((ImageView) nav.getChildAt(0)).setColorFilter(color);
        ((TextView)  nav.getChildAt(1)).setTextColor(color);
    }

    /** 丢弃指定页面的未保存更改 */
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

    /** 设置底部导航栏点击事件 */
    private void setupBottomNav() {
        View.OnClickListener navClick = v -> {
            int id = v.getId();
            if      (id == R.id.nav_home)     showPage(PAGE_HOME);
            else if (id == R.id.nav_apps)     handleWriteNavClick();
            else if (id == R.id.nav_read)     handleReadNavClick();
            else if (id == R.id.nav_settings) showPage(PAGE_SETTINGS);
        };
        if (mNavHome != null) mNavHome.setOnClickListener(navClick);
        if (mNavApps != null) mNavApps.setOnClickListener(navClick);
        if (mNavRead != null) mNavRead.setOnClickListener(navClick);
        if (mNavSettings != null) mNavSettings.setOnClickListener(navClick);

        if (mFab != null) {
            mFab.setOnClickListener(v -> {
                if (sCurrentPage == PAGE_WRITE) {
                    saveWriteChanges();
                } else if (sCurrentPage == PAGE_READ) {
                    saveReadChanges();
                }
                resetFabAutoHide();
            });
        }
    }

    /** 处理写入导航点击（双击回到顶部） */
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

    /** 处理读取导航点击（双击回到顶部） */
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

    /** 滚动写入列表到顶部 */
    private void scrollWriteListToTop() {
        if (mWriteExpandableListView != null) {
            mWriteExpandableListView.setSelection(0);
        }
    }

    /** 滚动读取列表到顶部 */
    private void scrollReadListToTop() {
        if (mReadExpandableListView != null) {
            mReadExpandableListView.setSelection(0);
        }
    }

    // ──────────────────── 首页状态信息 ────────────────────────────

    /**
     * 自定义 Binder 事务码：状态查询。
     * 与 ClipboardHook.TRANSACTION_CBGUARD_STATUS 一致（"CBGD" = 0x43424744）。
     * App 通过 clipboard 服务的 onTransact(CBGUARD_STATUS) 直连 system_server 查询状态。
     */
    private static final int TRANSACTION_CBGUARD_STATUS = 0x43424744;

    /**
     * 通过系统 clipboard 服务的自定义事务码查询模块状态。
     * 原理：system_server 在 ClipboardImpl.onTransact 上 Hook 了 TRANSACTION_CBGUARD_STATUS，
     * 直接返回 sModuleStatusJson。无需注册新服务，复用已有 clipboard_service SELinux 类型。
     * @return 状态 JSON，Hook 未就绪、Binder 异常时返回 null
     */
    private static String getStatusViaBinder() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "clipboard");
            if (binder == null) return null;

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                binder.transact(TRANSACTION_CBGUARD_STATUS, data, reply, 0);
                reply.readException();
                String json = reply.readString();
                return (json != null && !json.isEmpty()) ? json : null;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            // Binder 未就绪（system_server 尚未 Hook）或 ServiceManager 不可用，静默重试
            return null;
        }
    }

    private static String getXposedApiText(String statusJson) {
        if (statusJson == null || statusJson.isEmpty()) {
            return "";
        }
        try {
            int api = new JSONObject(statusJson).optInt("xposed_api", 0);
            return api > 0 ? String.valueOf(api) : "";
        } catch (Exception e) {
            return "";
        }
    }


    @SuppressLint("SetTextI18n")
    /** 初始化首页（模块状态卡片、设备信息） */
    private void initHomePage() {
        // 设备信息（都是常量，直接设置）
        if (mTvAndroidVersion != null) mTvAndroidVersion.setText(Build.VERSION.RELEASE);
        if (mTvManufacturer != null) mTvManufacturer.setText(Build.MANUFACTURER);
        if (mTvModel != null) mTvModel.setText(Build.MODEL);
    }

    /**
     * 快速检测模块激活状态，在 showPage 之前提交后台任务，
     * 使用 postAtFrontOfQueue 让回调尽可能在首帧渲染前执行，消除"未激活"闪变。
     */
    /** 通过 Binder IPC 轮询检测模块激活状态（最多 24 次，每次 5 秒） */
    private void checkModuleActive() {
        sExecutor.execute(() -> {
            String current = ConfigManager.readCurrentBootId();
            if (current.isEmpty()) {
                mHandler.postAtFrontOfQueue(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        updateModuleStatusCard(false);
                    }
                });
                return;
            }

            // 首次尝试（不 sleep，即刻查询）
            String json = getStatusViaBinder();
            if (json != null && json.contains(current)) {
                PermissionProvider.sModuleActive = true;
                String apiText = getXposedApiText(json);
                String finalApiText = !apiText.isEmpty() ? apiText : getString(R.string.info_xposed_api_unknown);
                mHandler.postAtFrontOfQueue(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        updateModuleStatusCard(true);
                        if (mTvXposedSdk != null) mTvXposedSdk.setText(finalApiText);
                    }
                });
                // 首次激活成功，触发配置同步
                if (!mInitialConfigSyncDone) {
                    mInitialConfigSyncDone = true;
                    PermissionProvider.requestConfigSync(MainActivity.this);
                }
                return;
            }

            // 首次失败 → 进入 24 × 5s 重试循环
            mHandler.postAtFrontOfQueue(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    updateModuleStatusCard(false);
                }
            });
            boolean active = false;
            String xposedApiText = getString(R.string.info_xposed_api_unknown);
            for (int i = 0; i < IPC_RETRY_MAX; i++) {
                json = getStatusViaBinder();
                if (json != null && json.contains(current)) {
                    active = true;
                    String apiText = getXposedApiText(json);
                    if (!apiText.isEmpty()) {
                        xposedApiText = apiText;
                    }
                    break;
                }
                if (i < IPC_RETRY_MAX - 1) {
                    try { Thread.sleep(IPC_RETRY_INTERVAL_MS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            boolean finalActive = active;
            if (finalActive) {
                PermissionProvider.sModuleActive = true;
                // 重试成功，触发配置同步
                if (!mInitialConfigSyncDone) {
                    mInitialConfigSyncDone = true;
                    PermissionProvider.requestConfigSync(MainActivity.this);
                }
            }
            String finalApiText = xposedApiText;
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateModuleStatusCard(finalActive);
                mTvXposedSdk.setText(finalApiText);
            });
        });
    }

    /** 更新模块状态卡片 UI */
    private void updateModuleStatusCard(boolean isActive) {
        if (mTvStatusTitle == null || mTvStatusDesc == null || mIvStatusIcon == null) return;
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

    // ──────────────────── 主题与设置页 ────────────────────────────

    /** 应用主题（不刷新 View，用于 onCreate 之前） */
    private void applyThemeNoView() {
        if (sCurrentTheme < 0) {
            sCurrentTheme = getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY_THEME, THEME_SYSTEM);
        }
        applyNightMode(sCurrentTheme);
    }

    /** 应用主题并刷新 UI */
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

    /** 切换主题（静态方法，供其他页面调用） */
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

    /** 应用夜间模式设置 */
    private static void applyNightMode(int theme) {
        switch (theme) {
            case THEME_LIGHT:  AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);          break;
            case THEME_DARK:   AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);         break;
            default:           AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM); break;
        }
    }

    /** 更新主题单选按钮选中状态 */
    private void updateThemeRadioButtons(int theme) {
        RadioButton rb0 = findViewById(R.id.rb_theme_light);
        RadioButton rb1 = findViewById(R.id.rb_theme_dark);
        RadioButton rb2 = findViewById(R.id.rb_theme_system);
        if (rb0 == null || rb1 == null || rb2 == null) return;
        rb0.setChecked(theme == THEME_LIGHT);
        rb1.setChecked(theme == THEME_DARK);
        rb2.setChecked(theme == THEME_SYSTEM);
    }

    /** 初始化主题单选按钮点击事件 */
    private void initThemeRadioButtons() {
        updateThemeRadioButtons(sCurrentTheme >= 0 ? sCurrentTheme
                : getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM));
    }

    @SuppressLint("SetTextI18n")
    /** 初始化设置页面（主题、开关、备份恢复、关于） */
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
                PermissionProvider.requestReadToastSettingSync(this);
            });
        }

        SwitchMaterial switchLsposedLog = findViewById(R.id.switch_lsposed_log_enabled);
        if (switchLsposedLog != null) {
            switchLsposedLog.setChecked(prefs.getBoolean("lsposed_log_enabled", true));
            switchLsposedLog.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("lsposed_log_enabled", isChecked).apply();
                PermissionProvider.requestLsposedLogSettingSync(this);
            });
        }

        View itemBackup = findViewById(R.id.item_backup_config);
        if (itemBackup != null) {
            itemBackup.setOnClickListener(v -> openBackupFolderPicker());
        }

        View itemRestore = findViewById(R.id.item_restore_config);
        if (itemRestore != null) {
            itemRestore.setOnClickListener(v -> openRestoreFilePicker());
        }

        View itemAbout = findViewById(R.id.item_about);
        if (itemAbout != null) {
            itemAbout.setOnClickListener(v -> openDetailActivity(AboutModuleActivity.class));
        }
    }

    /** 打开规则详情页面（带防抖） */
    private void openDetailActivity(Class<?> activityClass) {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastDetailActivityClickTime < DETAIL_ACTIVITY_CLICK_DEBOUNCE_MS) return;
        mLastDetailActivityClickTime = now;
        Intent intent = new Intent(this, activityClass);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    /** 设置主题选项点击事件 */
    private void setupThemeItem(int viewId, int theme) {
        View item = findViewById(viewId);
        if (item != null) {
            item.setOnClickListener(v -> {
                switchTheme(theme);
                updateThemeRadioButtons(theme);
            });
        }
    }

    // 备份：直接选择一个 zip 文件保存位置，系统会默认进入下载目录附近。
    /** 打开备份文件夹选择器 */
    private void openBackupFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, buildBackupFileName());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        putInitialDownloadUri(intent);
        mBackupFolderLauncher.launch(intent);
    }

    /** 打开恢复文件选择器 */
    private void openRestoreFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        putInitialDownloadUri(intent);
        mRestoreFileLauncher.launch(intent);
    }

    /** 设置初始下载目录 URI */
    private void putInitialDownloadUri(Intent intent) {
        try {
            Uri uri = DocumentsContract.buildRootUri(
                    "com.android.providers.downloads.documents",
                    "downloads");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
        } catch (Throwable ignored) {
        }
    }

    // 备份：把当前配置文件打包成 zip，写入用户选定的位置。
    /** 备份配置到指定文件夹（ZIP 格式） */
    private void backupConfigToFolder(Uri fileUri) {
        final Context appContext = getApplicationContext();
        sExecutor.execute(() -> {
            boolean success = false;
            OutputStream outputStream = null;
            try {
                outputStream = getContentResolver().openOutputStream(fileUri);
                if (outputStream == null) throw new IllegalStateException("打开备份文件失败");
                writeBackupZip(outputStream);
                success = true;
            } catch (Throwable e) {
                XLog.e("ClipboardGuard", "backupConfigToFolder failed", e);
            } finally {
                closeQuietly(outputStream);
            }
            boolean finalSuccess = success;
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(appContext,
                        finalSuccess ? R.string.backup_config_success : R.string.backup_config_failed,
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String buildBackupFileName() {
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        return "ClipboardGuard_" + time + ".zip";
    }

    // 按固定清单把配置文件写入 zip，避免把其它文件误打进去。
    /** 写入备份 ZIP 文件内容 */
    private void writeBackupZip(OutputStream outputStream) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            byte[] buffer = new byte[8192];
            for (String fileName : BACKUP_FILE_NAMES) {
                File file = new File(getFilesDir(), fileName);
                if (!file.exists()) continue;
                ZipEntry entry = new ZipEntry(fileName);
                zip.putNextEntry(entry);
                try (InputStream input = new FileInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    // 恢复：选择 zip 后解压到应用配置目录，只接受白名单文件名。
    /** 从 ZIP 文件恢复配置 */
    private void restoreConfigFromFile(Uri fileUri) {
        final Context appContext = getApplicationContext();
        sExecutor.execute(() -> {
            int restoredCount;
            InputStream inputStream = null;
            try {
                inputStream = getContentResolver().openInputStream(fileUri);
                if (inputStream == null) throw new IllegalStateException("打开恢复文件失败");
                restoredCount = restoreConfigZip(inputStream);
                if (restoredCount > 0) {
                    mWritePendingChanges.clear();
                    mReadPendingChanges.clear();
                    PermissionProvider.requestConfigSync(appContext);
                }
            } catch (Throwable e) {
                restoredCount = -1;
                XLog.e("ClipboardGuard", "restoreConfigFromFile failed", e);
            } finally {
                closeQuietly(inputStream);
            }
            int finalRestoredCount = restoredCount;
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalRestoredCount > 0) {
                    mHasLoadedApps = false;
                    refreshWritePermissions();
                    refreshReadPermissions();
                    loadAppsAsync();
                    Toast.makeText(appContext, R.string.restore_config_success, Toast.LENGTH_SHORT).show();
                } else if (finalRestoredCount == 0) {
                    Toast.makeText(appContext, R.string.restore_config_empty, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(appContext, R.string.restore_config_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // 逐个解压白名单文件到 files 目录，忽略压缩包中的其它内容。
    private int restoreConfigZip(InputStream inputStream) throws Exception {
        // 单个文件大小限制 5MB，防止恶意 ZIP 填满磁盘
        final long MAX_ENTRY_SIZE = 5 * 1024 * 1024L;
        int restoredCount = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String fileName = entry.getName();
                if (!entry.isDirectory() && isAllowedBackupFile(fileName)) {
                    File target = new File(getFilesDir(), fileName);
                    // 原子写入：先写临时文件，成功后再 rename，避免异常时留下半截配置
                    File tmpFile = new File(getFilesDir(), fileName + ".tmp");
                    try (OutputStream output = new FileOutputStream(tmpFile)) {
                        long totalBytes = 0;
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            totalBytes += read;
                            if (totalBytes > MAX_ENTRY_SIZE) {
                                throw new IOException("ZIP entry too large (>5MB): " + fileName);
                            }
                            output.write(buffer, 0, read);
                        }
                        output.flush();
                    }
                    // 写入成功，原子 rename 到目标文件
                    if (!tmpFile.renameTo(target)) {
                        // rename 失败时回退覆盖写
                        try (OutputStream output = new FileOutputStream(target)) {
                            try (InputStream tmpIn = new FileInputStream(tmpFile)) {
                                int read;
                                while ((read = tmpIn.read(buffer)) != -1) {
                                    output.write(buffer, 0, read);
                                }
                            }
                        }
                    }
                    // 清理临时文件
                    if (tmpFile.exists()) tmpFile.delete();
                    restoredCount++;
                }
                zip.closeEntry();
            }
        }
        return restoredCount;
    }

    // 只允许恢复这几个配置文件，防止目录穿越或误覆盖。
    private boolean isAllowedBackupFile(String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\")) return false;
        for (String allowedName : BACKUP_FILE_NAMES) {
            if (allowedName.equals(fileName)) return true;
        }
        return false;
    }

    /** 安静关闭 Closeable（忽略异常） */
    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    // ──────────────────── 应用列表加载与分类 ────────────────────────────

    /** 异步加载所有应用列表 */
    private void loadAppsAsync() {
        int generation = mLoadAppsGeneration.incrementAndGet();
        if (!mIsLoadingApps.compareAndSet(false, true)) {
            mLoadAppsQueued.set(true);
            return;
        }
        sExecutor.execute(() -> loadAllApps(generation));
    }

    /** 异步刷新权限状态 */
    private void refreshPermissionsAsync() {
        int generation = mRefreshPermissionsGeneration.incrementAndGet();
        if (!mIsRefreshingPermissions.compareAndSet(false, true)) {
            mRefreshPermissionsQueued.set(true);
            return;
        }
        sExecutor.execute(() -> {
            try {
                List<String[]> savedWritePerms = PermissionProvider.getAllWritePermissions(this);
                List<String[]> savedReadPerms = PermissionProvider.getAllReadPermissions(this);
                android.util.ArrayMap<String, Integer> writePermMap = new android.util.ArrayMap<>();
                for (String[] row : savedWritePerms) putPermissionRow(writePermMap, row);
                android.util.ArrayMap<String, Integer> readPermMap = new android.util.ArrayMap<>();
                for (String[] row : savedReadPerms) putPermissionRow(readPermMap, row);
                runOnUiThread(() -> {
                    mIsRefreshingPermissions.set(false);
                    if (isFinishing() || isDestroyed()) {
                        drainQueuedPermissionRefresh();
                        return;
                    }
                    if (generation != mRefreshPermissionsGeneration.get()) {
                        drainQueuedPermissionRefresh();
                        return;
                    }
                    refreshWritePermissions(writePermMap);
                    refreshReadPermissions(readPermMap);
                    drainQueuedPermissionRefresh();
                });
            } catch (Throwable e) {
                mIsRefreshingPermissions.set(false);
                XLog.e("ClipboardGuard", "refreshPermissionsAsync failed", e);
                drainQueuedPermissionRefresh();
            }
        });
    }

    private static HashSet<String> sCorePackages;

    /** 初始化核心包白名单 */
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

    /** 加载所有应用（带代次检查，防止过期请求覆盖新数据） */
    private void loadAllApps(int generation) {
        try {
            loadAllAppsInternal(generation);
        } catch (Throwable e) {
            mIsLoadingApps.set(false);
            XLog.e("ClipboardGuard", "loadAllApps failed", e);
            runOnUiThread(() -> {
                if (mWriteSwipeRefresh != null) mWriteSwipeRefresh.setRefreshing(false);
                if (mReadSwipeRefresh != null) mReadSwipeRefresh.setRefreshing(false);
            });
            drainQueuedAppLoad();
        }
    }

    /** 内部实现：加载所有应用并分类（用户/系统/核心） */
    private void loadAllAppsInternal(int generation) {
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
            mIsLoadingApps.set(false);
            if (isFinishing() || isDestroyed()) {
                if (mWriteSwipeRefresh != null) mWriteSwipeRefresh.setRefreshing(false);
                if (mReadSwipeRefresh != null) mReadSwipeRefresh.setRefreshing(false);
                drainQueuedAppLoad();
                return;
            }
            if (generation != mLoadAppsGeneration.get()) {
                if (mWriteSwipeRefresh != null) mWriteSwipeRefresh.setRefreshing(false);
                if (mReadSwipeRefresh != null) mReadSwipeRefresh.setRefreshing(false);
                drainQueuedAppLoad();
                return;
            }
            mHasLoadedApps = true;
            mWriteUserApps.clear();   mWriteUserApps.addAll(tmpWriteUser);
            mWriteSystemApps.clear(); mWriteSystemApps.addAll(tmpWriteSystem);
            mWriteCoreApps.clear();   mWriteCoreApps.addAll(tmpWriteCore);
            mReadUserApps.clear();    mReadUserApps.addAll(tmpReadUser);
            mReadSystemApps.clear();  mReadSystemApps.addAll(tmpReadSystem);
            mReadCoreApps.clear();    mReadCoreApps.addAll(tmpReadCore);

            refreshWritePermissions(writePermMap);
            refreshReadPermissions(readPermMap);
            applyWriteFilter();
            applyReadFilter();
            if (mWriteExpandableListView != null) mWriteExpandableListView.expandGroup(GROUP_USER);

            if (mWriteSwipeRefresh != null) mWriteSwipeRefresh.setRefreshing(false);
            if (mReadSwipeRefresh != null) mReadSwipeRefresh.setRefreshing(false);
            drainQueuedAppLoad();
        });
    }

    /** 处理排队的应用加载请求 */
    private void drainQueuedAppLoad() {
        if (mLoadAppsQueued.compareAndSet(true, false)) {
            loadAppsAsync();
        }
    }

    /** 处理排队的权限刷新请求 */
    private void drainQueuedPermissionRefresh() {
        if (mRefreshPermissionsQueued.compareAndSet(true, false)) {
            refreshPermissionsAsync();
        }
    }

    /** 按名称排序写入应用列表 */
    private static void sortWriteApps(List<AppItem> list) {
        list.sort((a, b) -> {
            if (a.isBlockedWrite != b.isBlockedWrite) return a.isBlockedWrite ? -1 : 1;
            return safeText(a.appName).compareToIgnoreCase(safeText(b.appName));
        });
    }

    /** 按名称排序读取应用列表 */
    private static void sortReadApps(List<AppItem> list) {
        list.sort((a, b) -> {
            if (a.isBlockedRead != b.isBlockedRead) return a.isBlockedRead ? -1 : 1;
            return safeText(a.appName).compareToIgnoreCase(safeText(b.appName));
        });
    }

    /** 排序写入应用列表（用户/系统/核心） */
    private void sortWriteAppLists() {
        sortWriteApps(mWriteUserApps);
        sortWriteApps(mWriteSystemApps);
        sortWriteApps(mWriteCoreApps);
    }

    /** 排序读取应用列表（用户/系统/核心） */
    private void sortReadAppLists() {
        sortReadApps(mReadUserApps);
        sortReadApps(mReadSystemApps);
        sortReadApps(mReadCoreApps);
    }

    // ──────────────────── 写入权限列表 ────────────────────────────

    /** 刷新写入权限状态 */
    private void refreshWritePermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllWritePermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) putPermissionRow(permMap, row);

        refreshWritePermissions(permMap);
    }

    /** 刷新写入权限状态（使用指定权限 Map） */
    private void refreshWritePermissions(android.util.ArrayMap<String, Integer> permMap) {

        for (AppItem item : mWriteUserApps)   applyWritePermToItem(item, permMap);
        for (AppItem item : mWriteSystemApps) applyWritePermToItem(item, permMap);
        for (AppItem item : mWriteCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlockedWrite = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        }
        sortWriteAppLists();
        applyWriteFilter();
    }

    /** 将写入权限应用到应用项 */
    private void applyWritePermToItem(AppItem item, android.util.ArrayMap<String, Integer> permMap) {
        Integer saved = permMap.get(item.packageName);
        item.isBlockedWrite = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        Integer pending = mWritePendingChanges.get(item.packageName);
        if (pending != null) {
            item.isBlockedWrite = (pending == PermissionDecision.PERMISSION_BLOCK);
        }
    }

    /** 应用写入列表搜索过滤 */
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

    /** 刷新读取权限状态 */
    private void refreshReadPermissions() {
        List<String[]> savedPerms = PermissionProvider.getAllReadPermissions(this);
        android.util.ArrayMap<String, Integer> permMap = new android.util.ArrayMap<>();
        for (String[] row : savedPerms) putPermissionRow(permMap, row);

        refreshReadPermissions(permMap);
    }

    /** 刷新读取权限状态（使用指定权限 Map） */
    private void refreshReadPermissions(android.util.ArrayMap<String, Integer> permMap) {

        for (AppItem item : mReadUserApps)   applyReadPermToItem(item, permMap);
        for (AppItem item : mReadSystemApps) applyReadPermToItem(item, permMap);
        for (AppItem item : mReadCoreApps) {
            Integer saved = permMap.get(item.packageName);
            item.isBlockedRead = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        }
        sortReadAppLists();
        applyReadFilter();
    }

    /** 将读取权限应用到应用项 */
    private void applyReadPermToItem(AppItem item, android.util.ArrayMap<String, Integer> permMap) {
        Integer saved = permMap.get(item.packageName);
        item.isBlockedRead = (saved != null && saved == PermissionDecision.PERMISSION_BLOCK);
        Integer pending = mReadPendingChanges.get(item.packageName);
        if (pending != null) {
            item.isBlockedRead = (pending == PermissionDecision.PERMISSION_BLOCK);
        }
    }

    /** 应用读取列表搜索过滤 */
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

    /** 将权限行添加到 Map */
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

    /** 全选/全不选写入应用 */
    private void setAllWriteApps(boolean blocked) {
        int perm = blocked ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE;
        for (AppItem i : mWriteUserApps)   { i.isBlockedWrite = blocked; mWritePendingChanges.put(i.packageName, perm); }
        for (AppItem i : mWriteSystemApps) { i.isBlockedWrite = blocked; mWritePendingChanges.put(i.packageName, perm); }
        applyWriteFilter();
    }

    /** 切换写入分组选择状态 */
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

    /** 保存写入拦截名单更改并广播同步 */
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

    /** 全选/全不选读取应用 */
    private void setAllReadApps(boolean blocked) {
        int perm = blocked ? PermissionDecision.PERMISSION_BLOCK : PermissionDecision.PERMISSION_IGNORE;
        for (AppItem i : mReadUserApps)   { i.isBlockedRead = blocked; mReadPendingChanges.put(i.packageName, perm); }
        for (AppItem i : mReadSystemApps) { i.isBlockedRead = blocked; mReadPendingChanges.put(i.packageName, perm); }
        applyReadFilter();
    }

    /** 切换读取分组选择状态 */
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

    /** 保存读取拦截名单更改并广播同步 */
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
    /** 初始化规则文件（确保文件存在） */
    private void initRuleFiles() {
        // 6 个文件操作用同一线程池并发执行，替代顺序 I/O
        String filesDir = getFilesDir().getPath();
        sExecutor.execute(() -> PermissionProvider.ensureBlocklistFile(filesDir + "/write_blocklist.txt"));
        sExecutor.execute(() -> PermissionProvider.ensureBlocklistFile(filesDir + "/read_blocklist.txt"));
        sExecutor.execute(() -> ensureEmptyJsonFile("write_rules.json"));
        sExecutor.execute(() -> ensureEmptyJsonFile("read_rules.json"));
        sExecutor.execute(() -> ensureEmptyJsonFile("write_default_rules.json"));
        sExecutor.execute(() -> ensureEmptyJsonFile("read_default_rules.json"));
    }

    /** 确保 JSON 文件存在，不存在则创建空规则文件 */
    private void ensureEmptyJsonFile(String fileName) {
        File file = new File(getFilesDir(), fileName);
        if (!file.exists()) {
            try {
                writeEmptyRuleConfigFile(file);
            } catch (Exception e) {
                XLog.e("ClipboardGuard", "initRuleFiles: failed to create " + fileName, e);
            }
        }
    }

    /** 写入空规则配置文件 */
    private void writeEmptyRuleConfigFile(File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("{\"enabled\":false,\"content_rules\":[]}".getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    // ──────────────────── 数据模型 ────────────────────────────

    /** 应用列表项数据模型 */
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

    /** 应用分组适配器（ExpandableListView） */
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
                if (elv == null) return;
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
