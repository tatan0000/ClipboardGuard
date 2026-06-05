package com.android.clipboardguard;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.AbsListView;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 读取规则适用域管理页。
 *
 * 为单条读取规则指定适用的应用列表（从读取拦截名单中选择）。
 * 用户可以勾选/取消勾选应用，保存后更新规则的 applicable_packages 字段，
 * 并通过广播通知 system_server 侧同步。
 *
 * 数据流：读取拦截名单中的应用 → 用户选择 → 更新规则 JSON → 广播同步
 */
public class ReadRuleAppsActivity extends AppCompatActivity {

    private static final int GROUP_USER   = 0;
    private static final int GROUP_SYSTEM = 1;

    public static final String EXTRA_RULE_INDEX      = "rule_index";
    public static final String EXTRA_IS_DEFAULT_RULE = "is_default_rule";
    public static final String EXTRA_RULE_NAME       = "rule_name";

    private ExpandableListView mExpandableListView;
    private FloatingActionButton mFab;
    private static final long FAB_AUTO_HIDE_DELAY = 4000L;

    private int     mRuleIndex     = -1;
    private String  mRuleFileName  = "read_rules.json";
    private String  mRuleName      = "";
    private final Set<String> mInitialChecked = new HashSet<>();
    private final Set<String> mCurrentChecked = new HashSet<>();

    private final List<AppItem> mUserApps   = new ArrayList<>();
    private final List<AppItem> mSystemApps = new ArrayList<>();
    private final List<AppItem> mFilteredUser   = new ArrayList<>();
    private final List<AppItem> mFilteredSystem = new ArrayList<>();
    private String mCurrentQuery = "";

    private AppGroupAdapter mAdapter;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean mDestroyed = false;

    private final Runnable mFabAutoHide = () -> {
        if (mFab != null && mFab.getVisibility() == View.VISIBLE) {
            mFab.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                    .setDuration(200)
                    .withEndAction(() -> mFab.setVisibility(View.GONE))
                    .start();
        }
    };

    private void resetFabAutoHide() {
        if (mFab == null) return;
        mHandler.removeCallbacks(mFabAutoHide);
        if (mFab.getVisibility() != View.VISIBLE) {
            mFab.setVisibility(View.VISIBLE);
            mFab.setAlpha(1f);
            mFab.setScaleX(1f);
            mFab.setScaleY(1f);
        }
        mHandler.postDelayed(mFabAutoHide, FAB_AUTO_HIDE_DELAY);
    }

    private final LruCache<String, Drawable> mIconCache = new LruCache<>(2 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            return Math.max(1, value.getIntrinsicWidth()) * Math.max(1, value.getIntrinsicHeight()) * 4;
        }
    };

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rule_apps);

        mRuleIndex     = getIntent().getIntExtra(EXTRA_RULE_INDEX, -1);
        boolean isDefaultRule = getIntent().getBooleanExtra(EXTRA_IS_DEFAULT_RULE, false);
        mRuleName      = getIntent().getStringExtra(EXTRA_RULE_NAME);
        if (mRuleName == null) mRuleName = "";
        mRuleFileName  = isDefaultRule ? "read_default_rules.json" : "read_rules.json";
        if (mRuleIndex < 0) {
            finish();
            return;
        }

        View appBarView = findViewById(R.id.app_bar);
        if (appBarView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
                int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0), v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            ViewCompat.requestApplyInsets(appBarView);
        }

        applyStatusBarAdaptation();
        initToolbar();
        initViews();
        loadRuleAndApps();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            String title = (mRuleName != null && !mRuleName.isEmpty())
                    ? mRuleName + "规则  适用域"
                    : getString(R.string.rule_apps_title_read);
            toolbar.setTitle(title);
            toolbar.setNavigationIcon(R.drawable.ic_back);
            toolbar.setNavigationOnClickListener(v -> finish());
        }
    }

    private void applyStatusBarAdaptation() {
        int theme = getSharedPreferences("clipboardguard_prefs", MODE_PRIVATE)
                .getInt("theme", MainActivity.THEME_SYSTEM);
        boolean isDark = (theme == MainActivity.THEME_DARK)
                || (theme == MainActivity.THEME_SYSTEM && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        Window w = getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(isDark ? Color.BLACK : Color.WHITE);
        WindowInsetsController controller = w.getDecorView().getWindowInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    isDark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    private void initViews() {
        mExpandableListView = findViewById(R.id.expandable_list);
        mFab = findViewById(R.id.fab_save);

        TextView tvTip = findViewById(R.id.tv_tip);
        if (tvTip != null) tvTip.setText(R.string.rule_apps_tip);

        mAdapter = new AppGroupAdapter();
        if (mExpandableListView != null) {
            mExpandableListView.setAdapter(mAdapter);
            mExpandableListView.setOnGroupClickListener((parent, v, groupPos, id) -> true);
            mExpandableListView.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) resetFabAutoHide();
                }
                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {}
            });
        }

        EditText etSearch = findViewById(R.id.et_search);
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    mCurrentQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                    applyFilter();
                    resetFabAutoHide();
                }
            });
        }

        TextView btnSelectAll = findViewById(R.id.btn_select_all);
        TextView btnDeselectAll = findViewById(R.id.btn_deselect_all);
        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> {
                for (AppItem i : mUserApps)   if (!i.isCore) mCurrentChecked.add(i.packageName);
                for (AppItem i : mSystemApps) if (!i.isCore) mCurrentChecked.add(i.packageName);
                applyFilter();
                resetFabAutoHide();
            });
        }
        if (btnDeselectAll != null) {
            btnDeselectAll.setOnClickListener(v -> {
                mCurrentChecked.clear();
                applyFilter();
                resetFabAutoHide();
            });
        }

        if (mFab != null) {
            mFab.setOnClickListener(v -> {
                mFab.setEnabled(false);
                saveOnly();
                mHandler.postDelayed(() -> {
                    if (mFab != null) mFab.setEnabled(true);
                }, 500);
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据加载
    // ═══════════════════════════════════════════════════════════════

    /** 加载规则文件和拦截名单中的应用列表，合并后显示在 UI 上。 */
    private void loadRuleAndApps() {
        mExecutor.execute(() -> {
            loadRuleFromDisk();

            // 获取当前读取拦截的应用（默认勾选的基础）
            List<String[]> savedPerms = PermissionProvider.getAllReadPermissions(ReadRuleAppsActivity.this);
            Set<String> blockedPkgs = new HashSet<>();
            for (String[] row : savedPerms) {
                if (row != null && row.length >= 2 && "0".equals(row[1])) {
                    blockedPkgs.add(row[0]);
                }
            }

            Set<String> defaultChecked;
            if (!mInitialChecked.isEmpty()) {
                defaultChecked = new HashSet<>(mInitialChecked);
            } else {
                defaultChecked = blockedPkgs;
            }

            // 加载应用列表：只显示拦截名单里的应用（排除系统核心）
            PackageManager pm = getPackageManager();
            List<AppItem> tmpUser = new ArrayList<>();
            List<AppItem> tmpSystem = new ArrayList<>();
            String self = getPackageName();
            Set<String> corePkgs = getCorePackages();

            for (String pkg : blockedPkgs) {
                if (self.equals(pkg)) continue;
                if (isCoreSystemPackage(pkg, corePkgs)) continue;
                AppItem item = new AppItem();
                item.packageName = pkg;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    item.appName = pm.getApplicationLabel(info).toString();
                    item.isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                } catch (PackageManager.NameNotFoundException e) {
                    item.appName = pkg;
                    item.isSystem = false;
                }
                item.isCore = false;
                if (item.isSystem) tmpSystem.add(item);
                else tmpUser.add(item);
            }

            sortApps(tmpUser, defaultChecked);
            sortApps(tmpSystem, defaultChecked);

            Set<String> finalDefaultChecked = defaultChecked;
            runOnUiThread(() -> {
                if (mDestroyed || isFinishing() || isDestroyed()) return;
                mCurrentChecked.addAll(finalDefaultChecked);
                mUserApps.clear();   mUserApps.addAll(tmpUser);
                mSystemApps.clear(); mSystemApps.addAll(tmpSystem);
                applyFilter();
                if (mExpandableListView != null) {
                    mExpandableListView.expandGroup(GROUP_USER);
                    mExpandableListView.setSelection(0);
                }
                resetFabAutoHide();
            });
        });
    }

    /** 从磁盘读取规则文件，提取当前规则的适用域列表作为初始勾选状态。 */
    private void loadRuleFromDisk() {
        try {
            File file = new File(getFilesDir(), mRuleFileName);
            if (!file.exists()) return;
            String content = readFile(file);
            if (content.isEmpty()) return;

            JSONObject root = new JSONObject(content);
            JSONArray arr = root.optJSONArray("content_rules");
            if (arr != null && mRuleIndex < arr.length()) {
                JSONObject ruleObj = arr.getJSONObject(mRuleIndex);
                mRuleName = ruleObj.optString("name", "");
                JSONArray pkgs = ruleObj.optJSONArray("applicable_packages");
                if (pkgs != null) {
                    for (int i = 0; i < pkgs.length(); i++) {
                        String pkg = pkgs.optString(i, "");
                        if (!pkg.isEmpty()) mInitialChecked.add(pkg);
                    }
                }
            }
        } catch (Exception e) {
            XLog.e("ClipboardGuard", "loadRuleFromDisk failed", e);
        }
    }

    private Set<String> getCorePackages() {
        Set<String> core = new HashSet<>();
        Collections.addAll(core, getResources().getStringArray(R.array.global_whitelist_packages));
        return core;
    }

    private boolean isCoreSystemPackage(String pkgName, Set<String> corePackages) {
        if (corePackages.contains(pkgName)) return true;
        for (String core : corePackages) {
            if (pkgName.startsWith(core + ".")) return true;
        }
        return false;
    }

    private void sortApps(List<AppItem> list, Set<String> checked) {
        list.sort((a, b) -> {
            boolean aChecked = checked.contains(a.packageName);
            boolean bChecked = checked.contains(b.packageName);
            if (aChecked != bChecked) return aChecked ? -1 : 1;
            return safeText(a.appName).compareToIgnoreCase(safeText(b.appName));
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // 过滤
    // ═══════════════════════════════════════════════════════════════

    private void applyFilter() {
        final String query = mCurrentQuery;
        mExecutor.execute(() -> {
            List<AppItem> filteredUser = new ArrayList<>();
            List<AppItem> filteredSystem = new ArrayList<>();

            if (query.isEmpty()) {
                filteredUser.addAll(mUserApps);
                filteredSystem.addAll(mSystemApps);
            } else {
                for (AppItem i : mUserApps) if (matches(i)) filteredUser.add(i);
                for (AppItem i : mSystemApps) if (matches(i)) filteredSystem.add(i);
            }

            mHandler.post(() -> {
                if (mDestroyed) return;
                mFilteredUser.clear();
                mFilteredUser.addAll(filteredUser);
                mFilteredSystem.clear();
                mFilteredSystem.addAll(filteredSystem);
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
                if (!query.isEmpty() && mExpandableListView != null) {
                    mExpandableListView.expandGroup(GROUP_USER);
                    mExpandableListView.expandGroup(GROUP_SYSTEM);
                }
            });
        });
    }

    private boolean matches(AppItem item) {
        return safeText(item.appName).toLowerCase(Locale.ROOT).contains(mCurrentQuery)
                || safeText(item.packageName).toLowerCase(Locale.ROOT).contains(mCurrentQuery);
    }

    private AppItem getItem(int group, int child) {
        if (group == GROUP_USER && child < mFilteredUser.size()) return mFilteredUser.get(child);
        if (group == GROUP_SYSTEM && child < mFilteredSystem.size()) return mFilteredSystem.get(child);
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // 保存
    // ═══════════════════════════════════════════════════════════════

    /** 仅保存，不自动返回页面 */
    private void saveOnly() {
        mExecutor.execute(() -> {
            try {
                File file = new File(getFilesDir(), mRuleFileName);
                if (!file.exists()) {
                    mHandler.post(() -> {
                        if (mDestroyed || isFinishing() || isDestroyed()) return;
                        Toast.makeText(this, "规则文件不存在", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                String content = readFile(file);
                JSONObject root = new JSONObject(content);
                JSONArray arr = root.optJSONArray("content_rules");
                if (arr == null || mRuleIndex >= arr.length()) {
                    mHandler.post(() -> {
                        if (mDestroyed || isFinishing() || isDestroyed()) return;
                        Toast.makeText(this, "规则索引无效", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                JSONObject ruleObj = arr.getJSONObject(mRuleIndex);

                // 变更检测：新旧适用域对比
                JSONArray oldPkgs = ruleObj.optJSONArray("applicable_packages");
                JSONArray newPkgs = new JSONArray();
                for (String pkg : mCurrentChecked) {
                    newPkgs.put(pkg);
                }

                if (jsonArraysEqual(oldPkgs, newPkgs)) {
                    // 无变更：只弹 Toast
                    int count = mCurrentChecked.size();
                    mHandler.post(() -> {
                        if (mDestroyed || isFinishing() || isDestroyed()) return;
                        Toast.makeText(this,
                                count > 0 ? getString(R.string.rule_apps_saved, count)
                                        : getString(R.string.rule_apps_saved_none),
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 有变更：更新 applicable_packages + enabled → 写文件 → 广播
                ruleObj.put("applicable_packages", newPkgs);
                boolean shouldEnable = !mCurrentChecked.isEmpty();
                ruleObj.put("enabled", shouldEnable);
                arr.put(mRuleIndex, ruleObj);
                root.put("content_rules", arr);
                writeFile(file, root.toString(2));
                PermissionProvider.broadcastRulesOnly(this, "read");

                int count = mCurrentChecked.size();
                mHandler.post(() -> {
                    if (mDestroyed || isFinishing() || isDestroyed()) return;
                    Toast.makeText(this,
                            count > 0 ? getString(R.string.rule_apps_saved, count)
                                    : getString(R.string.rule_apps_saved_none),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                XLog.e("ClipboardGuard", "saveOnly failed", e);
                mHandler.post(() -> {
                    if (mDestroyed || isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════════

    private boolean jsonArraysEqual(JSONArray a, JSONArray b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        java.util.Set<String> setA = new java.util.HashSet<>();
        java.util.Set<String> setB = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) setA.add(a.optString(i, ""));
        for (int i = 0; i < b.length(); i++) setB.add(b.optString(i, ""));
        return setA.equals(setB);
    }

    // ═══════════════════════════════════════════════════════════════
    // 文件工具
    // ═══════════════════════════════════════════════════════════════

    private String readFile(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(fis))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void writeFile(File file, String content) {
        File tmpFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (!tmpFile.renameTo(file)) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            }
        } catch (Exception e) {
            XLog.e("ClipboardGuard", "writeFile failed: " + file.getName(), e);
        } finally {
            if (tmpFile.exists() && !tmpFile.delete()) {
                XLog.w("ClipboardGuard", "Failed to delete tmp file: " + tmpFile.getName());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Adapter
    // ═══════════════════════════════════════════════════════════════

    class AppGroupAdapter extends BaseExpandableListAdapter {

        @Override public int getGroupCount()                        { return 2; }
        @Override public int getChildrenCount(int g)                {
            return g == GROUP_USER ? mFilteredUser.size() : mFilteredSystem.size();
        }
        @Override public Object getGroup(int g)                     { return g; }
        @Override public Object getChild(int g, int c)              { return getItem(g, c); }
        @Override public long getGroupId(int g)                     { return g; }
        @Override public long getChildId(int g, int c)             { return c; }
        @Override public boolean hasStableIds()                     { return false; }
        @Override public boolean isChildSelectable(int g, int c)   { return true; }

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
            List<AppItem> list = isUser ? mFilteredUser : mFilteredSystem;

            int checked = 0;
            for (AppItem i : list) {
                if (mCurrentChecked.contains(i.packageName)) checked++;
            }

            h.tvArrow.setText(expanded ? "▲" : "▼");
            h.tvTitle.setText((isUser ? getString(R.string.group_user_apps) : getString(R.string.group_system_apps))
                    + "  " + list.size() + " 个"
                    + (checked > 0 ? "（已选 " + checked + "）" : ""));

            h.cbSelectAll.setVisibility(View.VISIBLE);
            boolean allChecked = checked == list.size() && !list.isEmpty();
            boolean noneChecked = checked == 0;

            if (!list.isEmpty() && allChecked) {
                h.cbSelectAll.setChecked(true);
                h.cbSelectAll.setSelected(false);
            } else if (!list.isEmpty() && noneChecked) {
                h.cbSelectAll.setChecked(false);
                h.cbSelectAll.setSelected(false);
            } else {
                h.cbSelectAll.setChecked(false);
                h.cbSelectAll.setSelected(true);
            }

            final int groupPos = g;
            h.cbSelectAll.setOnClickListener(v -> v.post(() -> {
                boolean select = h.cbSelectAll.isChecked();
                List<AppItem> targetList = groupPos == GROUP_USER ? mFilteredUser : mFilteredSystem;
                for (AppItem item : targetList) {
                    if (select) mCurrentChecked.add(item.packageName);
                    else mCurrentChecked.remove(item.packageName);
                }
                notifyDataSetChanged();
                resetFabAutoHide();
            }));
            h.cbSelectAll.setFocusable(false);

            convert.setOnClickListener(v -> {
                if (mExpandableListView == null) return;
                if (mExpandableListView.isGroupExpanded(groupPos))
                    mExpandableListView.collapseGroup(groupPos);
                else
                    mExpandableListView.expandGroup(groupPos);
                resetFabAutoHide();
            });

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

            Drawable icon = mIconCache.get(item.packageName);
            if (icon == null) {
                try {
                    icon = getPackageManager().getApplicationIcon(item.packageName);
                    mIconCache.put(item.packageName, icon);
                } catch (PackageManager.NameNotFoundException e) {
                    icon = ContextCompat.getDrawable(ReadRuleAppsActivity.this, R.drawable.ic_app_default);
                }
            }
            h.ivIcon.setImageDrawable(icon);
            h.tvName.setText(item.appName);
            h.tvPkg.setText(item.packageName);
            h.cbBlock.setVisibility(View.VISIBLE);
            h.cbBlock.setChecked(mCurrentChecked.contains(item.packageName));

            convert.setOnClickListener(v -> {
                if (mCurrentChecked.contains(item.packageName)) {
                    mCurrentChecked.remove(item.packageName);
                } else {
                    mCurrentChecked.add(item.packageName);
                }
                notifyDataSetChanged();
                resetFabAutoHide();
            });

            return convert;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ViewHolder
    // ═══════════════════════════════════════════════════════════════

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
        TextView tvName, tvPkg;
        CheckBox cbBlock;
        ChildViewHolder(View v) {
            ivIcon = v.findViewById(R.id.iv_icon);
            tvName = v.findViewById(R.id.tv_app_name);
            tvPkg = v.findViewById(R.id.tv_package_name);
            cbBlock = v.findViewById(R.id.cb_block);
        }
    }

    static class AppItem {
        String packageName;
        String appName;
        boolean isSystem;
        boolean isCore;
    }

    private static String safeText(String text) {
        return text != null ? text : "";
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期清理
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mHandler.removeCallbacksAndMessages(null);
        mHandler.removeCallbacks(mFabAutoHide);
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        super.onDestroy();
    }
}
