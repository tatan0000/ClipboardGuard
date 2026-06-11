package com.android.clipboardguard;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 读取规则详情管理页。
 *
 * 功能：
 * - 管理自定义读取规则（添加/编辑/删除/启用/禁用）
 * - 管理默认读取规则（内置手机号、身份证、银行卡等识别规则）
 * - 支持批量选择操作（全选/批量启用/批量禁用/批量删除）
 * - 每条规则可独立配置适用域（指定哪些应用触发该规则）
 *
 * 数据流：规则 JSON 文件 ↔ UI 操作 → 广播同步到 system_server
 * 自定义规则与默认规则分文件存储（read_rules.json / read_default_rules.json），
 * 加载到 Hook 侧时由 ContentRulesManager.mergeRulesForRuntime() 合并。
 */
public class ReadRulesDetailActivity extends AppCompatActivity {

    private RecyclerView mRvReadRulesDetail;
    private SwitchCompat mSwitchReadRulesEnabled;

    private boolean mReadRulesEnabled = false;
    private final List<ContentRule> mReadRules = new ArrayList<>();
    private ReadRulesAdapter mReadRulesAdapter;

    private boolean mReadRulesSelectionMode = false;
    private final Set<ContentRule> mReadSelectedRules = new HashSet<>();

    private View mReadBatchCard;
    private TextView mReadSelectedCount;

    private boolean mShowDefaultRules = false;
    private final List<ContentRule> mReadDefaultRules = new ArrayList<>();
    private ReadRulesAdapter mReadDefaultRulesAdapter;
    private View mReadDefaultBatchCard;
    private TextView mReadDefaultSelectedCount;
    private final Set<ContentRule> mReadDefaultSelectedRules = new HashSet<>();
    private boolean mReadDefaultSelectionMode = false;
    private View mContainerMainRules;
    private View mContainerDefaultRules;
    private Toolbar mToolbar;

    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService mExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean mDestroyed = false;

    private AlertDialog mCurrentRuleDialog;

    // Adapter 精确刷新类型：INSERT/REMOVE/CHANGE 传确切 position，FULL 走 notifyDataSetChanged
    private static final int REFRESH_INSERT = 1;
    private static final int REFRESH_REMOVE = 2;
    private static final int REFRESH_CHANGE = 3;
    private static final int REFRESH_FULL   = 4;

    // ═══════════════════════════════════════════════════════════════
    // 生命周期与基础初始化
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_read_rules_detail);

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
        initReadRulesDetailPage();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mReadRulesSelectionMode) exitReadSelectionMode();
                else if (mReadDefaultSelectionMode) exitReadDefaultSelectionMode();
                else if (mShowDefaultRules) showMainRulesPage();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从应用选择页返回后刷新规则（applicablePackages 可能已变更）
        if (mReadRulesAdapter != null) {
            mHandler.post(this::loadReadRulesSync);
        }
        if (mShowDefaultRules) {
            mExecutor.execute(this::loadDefaultReadRulesAsync);
        }
    }

    private void initToolbar() {
        mToolbar = findViewById(R.id.toolbar);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.read_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
            mToolbar.setNavigationOnClickListener(v -> {
                if (mShowDefaultRules) showMainRulesPage(); else finish();
            });
        } else if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.read_rules_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void applyStatusBarAdaptation() {
        int theme = getSharedPreferences("clipboardguard_prefs", MODE_PRIVATE).getInt("theme", MainActivity.THEME_SYSTEM);
        boolean isDark = (theme == MainActivity.THEME_DARK)
                || (theme == MainActivity.THEME_SYSTEM && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        Window w = getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(isDark ? Color.BLACK : Color.WHITE);
        WindowInsetsController c = w.getDecorView().getWindowInsetsController();
        if (c != null) c.setSystemBarsAppearance(isDark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    }

    private void initViews() {
        mContainerMainRules = findViewById(R.id.container_main_rules);
        mContainerDefaultRules = findViewById(R.id.container_default_rules);

        mRvReadRulesDetail = findViewById(R.id.rv_read_rules_detail);
        mSwitchReadRulesEnabled = findViewById(R.id.switch_read_rules_enabled);
        TextView tvReadRulesHint = findViewById(R.id.tv_read_rules_hint);
        MaterialButton btnAddReadRule = findViewById(R.id.btn_add_read_rule);
        if (tvReadRulesHint != null) {
            tvReadRulesHint.setText(R.string.rules_read_hint);
        }

        mReadBatchCard = findViewById(R.id.card_batch_actions_main);
        mReadSelectedCount = findViewById(R.id.tv_selected_count);
        TextView btnSelectAll = findViewById(R.id.btn_select_all_rules);
        TextView readDeleteSelected = findViewById(R.id.btn_delete_selected);
        TextView readEnableSelected = findViewById(R.id.btn_enable_selected);
        TextView readDisableSelected = findViewById(R.id.btn_disable_selected);

        if (mSwitchReadRulesEnabled != null) {
            mSwitchReadRulesEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mReadRulesEnabled = isChecked;
                saveEnabledOnly(isChecked);
            });
        }
        if (btnAddReadRule != null) {
            btnAddReadRule.setOnClickListener(v -> showEditReadRuleDialog());
        }

        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> {
                if (mReadSelectedRules.size() == mReadRules.size()) mReadSelectedRules.clear();
                else { mReadSelectedRules.clear(); mReadSelectedRules.addAll(mReadRules); }
                if (mReadRulesAdapter != null) mReadRulesAdapter.refreshSelectionMode();
                updateReadSelectedCount();
            });
        }

        if (readDeleteSelected != null) readDeleteSelected.setOnClickListener(v -> deleteReadSelectedRules());
        if (readEnableSelected != null) readEnableSelected.setOnClickListener(v -> enableReadSelectedRules(true));
        if (readDisableSelected != null) readDisableSelected.setOnClickListener(v -> enableReadSelectedRules(false));

        View cardDefaultRules = findViewById(R.id.card_read_default_rules);
        if (cardDefaultRules != null) cardDefaultRules.setOnClickListener(v -> showDefaultRulesPage());

        RecyclerView rvDefaultRules = findViewById(R.id.rv_read_default_rules);
        mReadDefaultBatchCard = findViewById(R.id.card_batch_actions_read_default);
        mReadDefaultSelectedCount = findViewById(R.id.tv_default_selected_count);
        TextView btnEnableDefault = findViewById(R.id.btn_enable_selected_default);
        TextView btnDisableDefault = findViewById(R.id.btn_disable_selected_default);
        TextView btnSelectAllDefault = findViewById(R.id.btn_select_all_default);

        if (btnEnableDefault != null) btnEnableDefault.setOnClickListener(v -> enableReadDefaultSelected(true));
        if (btnDisableDefault != null) btnDisableDefault.setOnClickListener(v -> enableReadDefaultSelected(false));
        if (btnSelectAllDefault != null) btnSelectAllDefault.setOnClickListener(v -> toggleReadDefaultSelectAll());

        if (mReadDefaultRulesAdapter == null) mReadDefaultRulesAdapter = new ReadRulesAdapter(mReadDefaultRules, true);
        if (rvDefaultRules != null) {
            rvDefaultRules.setLayoutManager(new LinearLayoutManager(this));
            rvDefaultRules.setAdapter(mReadDefaultRulesAdapter);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 页面初始化与切换
    // ═══════════════════════════════════════════════════════════════

    private void initReadRulesDetailPage() {
        if (mReadRulesAdapter == null) mReadRulesAdapter = new ReadRulesAdapter(mReadRules);
        if (mRvReadRulesDetail != null) {
            mRvReadRulesDetail.setLayoutManager(new LinearLayoutManager(this));
            mRvReadRulesDetail.setAdapter(mReadRulesAdapter);
        }
        mHandler.post(this::loadReadRulesSync);
    }

    private void showDefaultRulesPage() {
        mShowDefaultRules = true;
        if (mContainerMainRules != null) mContainerMainRules.setVisibility(View.GONE);
        if (mContainerDefaultRules != null) mContainerDefaultRules.setVisibility(View.VISIBLE);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.default_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
        }
        mExecutor.execute(this::loadDefaultReadRulesAsync);
    }

    private void showMainRulesPage() {
        mShowDefaultRules = false;
        exitReadDefaultSelectionMode();
        if (mContainerMainRules != null) mContainerMainRules.setVisibility(View.VISIBLE);
        if (mContainerDefaultRules != null) mContainerDefaultRules.setVisibility(View.GONE);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.read_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
        }
    }

    // 加载默认规则：
    // 1. 文件不存在 → 模板初始化（名字 + 正则，适用域为空），写盘
    // 2. 文件存在 + 正则与模板一致 → 直接从文件加载
    // 3. 文件存在 + 正则与模板不同（App 更新改了正则） → 合并：
    //    用模板的正则，保留用户的 enabled + applicablePackages，写盘
    private void loadDefaultReadRulesAsync() {
        File file = new File(getFilesDir(), "read_default_rules.json");

        if (file.exists()) {
            try {
                String content = readFile(file);
                JSONObject root = new JSONObject(content);
                JSONArray arr = root.optJSONArray("content_rules");
                if (arr == null || arr.length() == 0) {
                    if (!file.delete()) {
                        XLog.w("ClipboardGuard-Rules", "Failed to delete empty default rules file");
                    }
                    initDefaultReadRulesFromTemplate();
                } else {
                    // 加载文件中的规则，以 name 为 key
                    Map<String, ContentRule> fileRules = new LinkedHashMap<>();
                    for (int i = 0; i < arr.length(); i++) {
                        ContentRule rule = ContentRule.fromJson(arr.getJSONObject(i));
                        fileRules.put(rule.name, rule);
                    }

                    // 检查是否需要合并：模板中任一规则在文件中不存在或正则不同
                    boolean needsMerge = false;
                    String[][] template = getReadDefaultRulesTemplate();
                    for (String[] ruleDef : template) {
                        ContentRule fileRule = fileRules.get(ruleDef[0]);
                        if (fileRule == null || !ruleDef[1].equals(fileRule.pattern)) {
                            needsMerge = true;
                            break;
                        }
                    }

                    if (needsMerge) {
                        mergeDefaultReadRules(template, fileRules);
                        saveDefaultReadRulesToFile();
                    } else {
                        // 正则完全一致，直接使用文件数据
                        mReadDefaultRules.clear();
                        mReadDefaultRules.addAll(fileRules.values());
                        for (ContentRule r : mReadDefaultRules) r.isDefault = true;
                    }
                }
            } catch (Exception e) {
                XLog.e("ClipboardGuard-Rules", "loadDefaultReadRules failed, fallback to template", e);
                if (!file.delete()) {
                    XLog.w("ClipboardGuard-Rules", "Failed to delete corrupted default rules file");
                }
                initDefaultReadRulesFromTemplate();
            }
        } else {
            initDefaultReadRulesFromTemplate();
        }

        mHandler.post(this::refreshReadDefaultRulesAdapter);
    }

    // 合并：模板提供名字和正则，文件提供 enabled 和 applicablePackages
    private void mergeDefaultReadRules(String[][] template, Map<String, ContentRule> fileRules) {
        mReadDefaultRules.clear();
        for (String[] ruleDef : template) {
            ContentRule oldRule = fileRules.get(ruleDef[0]);
            boolean enabled = oldRule != null && oldRule.enabled;
            ContentRule newRule = new ContentRule(ruleDef[0], ruleDef[1], enabled, true);
            if (oldRule != null && !oldRule.applicablePackages.isEmpty()) {
                newRule.applicablePackages.addAll(oldRule.applicablePackages);
            }
            mReadDefaultRules.add(newRule);
        }
    }

    private void initDefaultReadRulesFromTemplate() {
        mReadDefaultRules.clear();
        for (String[] ruleDef : getReadDefaultRulesTemplate()) {
            mReadDefaultRules.add(new ContentRule(ruleDef[0], ruleDef[1], false, true));
        }
        saveDefaultReadRulesToFile();
    }

    private String[][] getReadDefaultRulesTemplate() {
        return new String[][] {
                {"手机号码", "(?<!\\d)1[3-9]\\d{9}(?!\\d)"},
                {"身份证号", "(?<!\\d)[1-9]\\d{5}\\d{4}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)"},
                // 银行卡号先用正则初筛，运行时还会通过 Luhn 校验降低快递单号误判。
                {"银行卡号", "(?<![A-Za-z0-9])(?:[1-9]\\d{12,18}|[1-9]\\d{3}(?:[- ]?\\d{4}){2,3}[- ]?\\d{1,3})(?![A-Za-z0-9])"},
                {"邮箱地址", "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9._%+-])"},
                // 快递单号不做中文前缀匹配，主要覆盖英文前缀和常见纯数字长度。
                {"快递单号", "(?<![A-Za-z0-9])(?:[A-Z]{2}[0-9]{9}[A-Z]{2}|[A-Z]{2}[0-9]{10,13}|[0-9]{12,16}|[0-9]{18}|[0-9]{20})(?![A-Za-z0-9])"}
                //{"验证码", "(?:验证码|校验码|动态码)[：:\\s]*[0-9A-Za-z]{4,8}"}
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // 默认读取规则选择
    // ═══════════════════════════════════════════════════════════════

    private void enterReadDefaultSelectionMode(ContentRule rule) {
        mReadDefaultSelectionMode = true;
        mReadDefaultSelectedRules.clear();
        mReadDefaultSelectedRules.add(rule);
        if (mReadDefaultRulesAdapter != null) mReadDefaultRulesAdapter.refreshSelectionMode();
        if (mReadDefaultBatchCard != null) mReadDefaultBatchCard.setVisibility(View.VISIBLE);
        updateReadDefaultSelectedCount();
    }

    private void exitReadDefaultSelectionMode() {
        mReadDefaultSelectionMode = false;
        mReadDefaultSelectedRules.clear();
        if (mReadDefaultRulesAdapter != null) mReadDefaultRulesAdapter.refreshSelectionMode();
        if (mReadDefaultBatchCard != null) mReadDefaultBatchCard.setVisibility(View.GONE);
    }

    private void updateReadDefaultSelectedCount() {
        if (mReadDefaultSelectedCount != null)
            mReadDefaultSelectedCount.setText(getString(R.string.selected_count, mReadDefaultSelectedRules.size()));
    }

    private void enableReadDefaultSelected(boolean enable) {
        if (mReadDefaultSelectedRules.isEmpty()) return;
        for (ContentRule rule : mReadDefaultSelectedRules) rule.enabled = enable;
        saveDefaultReadRules();   // 用户操作，保存并广播
        refreshReadDefaultRulesAdapter();
        exitReadDefaultSelectionMode();
    }

    private void toggleReadDefaultSelectAll() {
        if (mReadDefaultSelectedRules.size() == mReadDefaultRules.size()) mReadDefaultSelectedRules.clear();
        else {
            mReadDefaultSelectedRules.clear();
            mReadDefaultSelectedRules.addAll(mReadDefaultRules);
        }
        if (mReadDefaultRulesAdapter != null) mReadDefaultRulesAdapter.refreshSelectionMode();
        updateReadDefaultSelectedCount();
    }

    // ═══════════════════════════════════════════════════════════════
    // 保存方法
    // ═══════════════════════════════════════════════════════════════

    /** 仅更新 enabled 字段，不重写整个规则数组 */
    private void saveEnabledOnly(boolean enabled) {
        try {
            File file = new File(getFilesDir(), "read_rules.json");
            JSONObject root;
            if (file.exists()) {
                String content = readFile(file);
                root = content.isEmpty() ? new JSONObject() : new JSONObject(content);
            } else {
                root = new JSONObject();
            }
            root.put("enabled", enabled);
            if (!root.has("content_rules")) root.put("content_rules", new JSONArray());
            if (!writeFile(file, root.toString(2))) {
                XLog.e("ClipboardGuard-Rules", "读取规则总开关保存失败");
                return;
            }
            notifyRulesChanged();
            XLog.i("ClipboardGuard-Rules", "读取规则总开关已" + (enabled ? "开启" : "关闭"));
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "读取规则总开关保存异常", e);
        }
    }

    private void saveReadRules() {
        // 文件 I/O 和广播移到后台线程，避免阻塞主线程导致卡顿
        mExecutor.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                root.put("enabled", mReadRulesEnabled);
                JSONArray arr = new JSONArray();
                for (ContentRule rule : mReadRules) arr.put(rule.toJson());
                root.put("content_rules", arr);
                File file = new File(getFilesDir(), "read_rules.json");
                if (!writeFile(file, root.toString(2))) {
                    XLog.e("ClipboardGuard-Rules", "读取规则保存失败，跳过同步");
                    return;
                }

                notifyRulesChanged();
                XLog.i("ClipboardGuard-Rules", "读取规则已保存并同步，自定义规则数=" + arr.length());
            } catch (Exception e) {
                XLog.e("ClipboardGuard-Rules", "读取规则保存异常", e);
            }
        });
    }

    /** 用户操作默认规则时调用：写入文件 + 广播 */
    private void saveDefaultReadRules() {
        // 文件 I/O 和广播移到后台线程
        mExecutor.execute(() -> {
            if (saveDefaultReadRulesToFile()) {
                notifyRulesChanged();
                XLog.i("ClipboardGuard-Rules", "默认读取规则已保存并同步");
            }
        });
    }

    /** 仅写入文件，不广播（首次初始化等场景）。统一使用 { "enabled": ..., "content_rules": [...] } 格式 */
    private boolean saveDefaultReadRulesToFile() {
        try {
            JSONArray arr = new JSONArray();
            boolean hasEnabled = false;
            for (ContentRule rule : mReadDefaultRules) {
                arr.put(rule.toJson());
                if (rule.enabled) hasEnabled = true;
            }
            JSONObject root = new JSONObject();
            root.put("enabled", hasEnabled);
            root.put("content_rules", arr);
            File file = new File(getFilesDir(), "read_default_rules.json");
            boolean ok = writeFile(file, root.toString());
            if (ok) XLog.i("ClipboardGuard-Rules", "已写入默认读取规则文件");
            return ok;
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "saveDefaultReadRulesToFile failed", e);
            return false;
        }
    }

    /**
     * 通知 system_server 更新配置（自定义规则与默认规则分文件同步）。
     */
    private void notifyRulesChanged() {
        PermissionProvider.broadcastRulesOnly(this, "read");
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框
    // ═══════════════════════════════════════════════════════════════

    private void showViewRuleDialog(ContentRule rule) {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_view_rule, null);
        TextView tvName = dialogView.findViewById(R.id.tv_rule_name);
        TextView tvPattern = dialogView.findViewById(R.id.tv_rule_pattern);
        TextView tvStatus = dialogView.findViewById(R.id.tv_rule_status);
        if (tvName == null || tvPattern == null || tvStatus == null) return;
        tvName.setText(rule.name);
        tvPattern.setText(rule.pattern);
        tvStatus.setText(rule.enabled ? "已启用" : "已禁用");
        tvStatus.setTextColor(rule.enabled ?
                ContextCompat.getColor(this, R.color.status_active) :
                ContextCompat.getColor(this, R.color.status_inactive));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rules_dialog_title_view)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showEditReadRuleDialog() {
        showEditRuleDialog(null, false);
    }

    private void showEditRuleDialog(ContentRule rule, boolean isEdit) {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_rule, null);
        TextInputLayout tilName = dialogView.findViewById(R.id.til_rule_name);
        TextInputLayout tilPattern = dialogView.findViewById(R.id.til_rule_pattern);
        TextInputEditText etName = dialogView.findViewById(R.id.et_rule_name);
        TextInputEditText etPattern = dialogView.findViewById(R.id.et_rule_pattern);
        if (tilName == null || tilPattern == null || etName == null || etPattern == null) return;
        if (isEdit && rule != null) {
            etName.setText(rule.name);
            etPattern.setText(rule.pattern);
        }
        String title = isEdit ? getString(R.string.rules_dialog_title_edit) : getString(R.string.rules_dialog_title_add);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(title).setView(dialogView).setPositiveButton(R.string.save, null).setNegativeButton(android.R.string.cancel, null);
        mCurrentRuleDialog = builder.create();
        mCurrentRuleDialog.setCanceledOnTouchOutside(false);
        mCurrentRuleDialog.setOnDismissListener(dialog -> mCurrentRuleDialog = null);
        mCurrentRuleDialog.setOnShowListener(dialog -> {
            AlertDialog currentDialog = mCurrentRuleDialog;
            if (currentDialog == null || mDestroyed || isFinishing() || isDestroyed()) return;
            Button pb = currentDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pb != null) pb.setOnClickListener(v -> attemptSaveRule(etName, etPattern, tilName, tilPattern, rule, isEdit));
            Button nb = currentDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (nb != null) nb.setOnClickListener(v -> currentDialog.dismiss());
        });
        mCurrentRuleDialog.show();
        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSaveButtonState(etName, etPattern, tilName, tilPattern); }
        };
        etName.addTextChangedListener(tw);
        etPattern.addTextChangedListener(tw);
        updateSaveButtonState(etName, etPattern, tilName, tilPattern);
    }

    private void updateSaveButtonState(TextInputEditText en, TextInputEditText ep, TextInputLayout tn, TextInputLayout tp) {
        if (mCurrentRuleDialog == null) return;
        Button sb = mCurrentRuleDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (sb == null) return;
        String n = en.getText() != null ? en.getText().toString().trim() : "";
        String p = ep.getText() != null ? ep.getText().toString().trim() : "";
        sb.setEnabled(!n.isEmpty() && !p.isEmpty());
        tn.setError(null);
        tp.setError(null);
    }

    private void attemptSaveRule(TextInputEditText etName, TextInputEditText etPattern,
                                 TextInputLayout tilName, TextInputLayout tilPattern, ContentRule rule, boolean isEdit) {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String pattern = etPattern.getText() != null ? etPattern.getText().toString().trim() : "";
        if (name.isEmpty()) {
            tilName.setError(getString(R.string.rules_name_required));
            return;
        }
        tilName.setError(null);
        try {
            java.util.regex.Pattern.compile(pattern);
            tilPattern.setError(null);
        } catch (Exception e) {
            tilPattern.setError(getString(R.string.rules_regex_error));
            shakeView(tilPattern);
            return;
        }

        // 检测危险正则模式（可能导致灾难性回溯）
        String dangerWarning = ContentRule.checkDangerousPattern(pattern);
        if (dangerWarning != null) {
            tilPattern.setError(dangerWarning);
            shakeView(tilPattern);
            return;
        }

        // 检查命名重复
        for (ContentRule r : mReadRules) {
            if (r == rule) continue; // 编辑时跳过自身
            if (name.equals(r.name)) {
                tilName.setError(getString(R.string.rules_name_duplicate));
                shakeView(tilName);
                return;
            }
        }
        // 检查正则重复
        for (ContentRule r : mReadRules) {
            if (r == rule) continue;
            if (pattern.equals(r.pattern)) {
                tilPattern.setError(getString(R.string.rules_pattern_duplicate));
                shakeView(tilPattern);
                return;
            }
        }
        if (isEdit && rule != null) {
            rule.name = name;
            rule.pattern = pattern;
            rule.compilePattern();
        } else {
            ContentRule newRule = new ContentRule(name, pattern, true);
            // 新建规则自动勾选当前拦截名单
            List<String> blocked = PermissionProvider.getBlockedReadPackagesDirect(this);
            if (!blocked.isEmpty()) newRule.applicablePackages.addAll(blocked);
            mReadRules.add(newRule);
        }
        saveReadRules();
        if (isEdit && rule != null) {
            int pos = mReadRules.indexOf(rule);
            if (pos >= 0) refreshReadRulesAdapter(REFRESH_CHANGE, pos);
        } else {
            refreshReadRulesAdapter(REFRESH_INSERT, mReadRules.size() - 1);
        }
        if (mRvReadRulesDetail != null) {
            mRvReadRulesDetail.setVisibility(mReadRules.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (mCurrentRuleDialog != null && mCurrentRuleDialog.isShowing()) {
            mCurrentRuleDialog.dismiss();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 批量选择
    // ═══════════════════════════════════════════════════════════════

    private void enterReadSelectionMode(ContentRule rule) {
        mReadRulesSelectionMode = true;
        mReadSelectedRules.clear();
        mReadSelectedRules.add(rule);
        if (mReadRulesAdapter != null) mReadRulesAdapter.refreshSelectionMode();
        if (mReadBatchCard != null) mReadBatchCard.setVisibility(View.VISIBLE);
        updateReadSelectedCount();
    }

    private void exitReadSelectionMode() {
        mReadRulesSelectionMode = false;
        mReadSelectedRules.clear();
        if (mReadRulesAdapter != null) mReadRulesAdapter.refreshSelectionMode();
        if (mReadBatchCard != null) mReadBatchCard.setVisibility(View.GONE);
    }

    private void updateReadSelectedCount() {
        if (mReadSelectedCount != null)
            mReadSelectedCount.setText(getString(R.string.selected_count, mReadSelectedRules.size()));
    }

    private void deleteReadSelectedRules() {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        int count = mReadSelectedRules.size();
        if (count == 0) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rules_delete_confirm_title)
                .setMessage(getString(R.string.rules_delete_selected_confirm, count))
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (mDestroyed || isFinishing() || isDestroyed()) return;
                    mReadRules.removeAll(mReadSelectedRules);
                    saveReadRules();
                    refreshReadRulesAdapter(REFRESH_FULL, 0);
                    if (mRvReadRulesDetail != null) {
                        mRvReadRulesDetail.setVisibility(mReadRules.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    exitReadSelectionMode();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteReadRule(ContentRule rule) {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.rules_delete_confirm)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (mDestroyed || isFinishing() || isDestroyed()) return;
                    int pos = mReadRules.indexOf(rule);
                    mReadRules.remove(rule);
                    saveReadRules();
                    refreshReadRulesAdapter(pos >= 0 ? REFRESH_REMOVE : REFRESH_FULL, Math.max(pos, 0));
                    if (mRvReadRulesDetail != null) {
                        mRvReadRulesDetail.setVisibility(mReadRules.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void enableReadSelectedRules(boolean enable) {
        if (mReadSelectedRules.isEmpty()) return;
        for (ContentRule rule : mReadSelectedRules) rule.enabled = enable;
        saveReadRules();
        refreshReadRulesAdapter(REFRESH_FULL, 0);
        exitReadSelectionMode();
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据加载
    // ═══════════════════════════════════════════════════════════════

    private void loadReadRulesSync() {
        mExecutor.execute(() -> {
            boolean enabled = false;
            List<ContentRule> rules = new ArrayList<>();
            boolean shouldRewrite = false;
            try {
                File file = new File(getFilesDir(), "read_rules.json");
                if (file.exists()) {
                    String content = readFile(file);
                    if (!content.isEmpty()) {
                        JSONObject root = new JSONObject(content);
                        enabled = root.optBoolean("enabled", false);
                        JSONArray arr = root.optJSONArray("content_rules");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                ContentRule rule = ContentRule.fromJson(arr.getJSONObject(i));
                                if (!rule.isDefault) rules.add(rule);
                            }
                        }
                    } else {
                        shouldRewrite = true;
                    }
                } else {
                    shouldRewrite = true;
                }
            } catch (Exception e) {
                shouldRewrite = true;
                XLog.e("ClipboardGuard-Rules", "loadReadRulesSync failed", e);
            }

            if (shouldRewrite) {
                rewriteEmptyReadRulesFile();
            }
            final boolean fe = enabled;
            final List<ContentRule> fr = new ArrayList<>(rules);
            mHandler.post(() -> {
                if (mDestroyed || isFinishing() || isDestroyed()) return;
                mReadRulesEnabled = fe;
                mReadRules.clear();
                mReadRules.addAll(fr);
                // 静默设置开关，避免触发保存
                if (mSwitchReadRulesEnabled != null) {
                    mSwitchReadRulesEnabled.setOnCheckedChangeListener(null);
                    mSwitchReadRulesEnabled.setChecked(fe);
                    mSwitchReadRulesEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        mReadRulesEnabled = isChecked;
                        saveEnabledOnly(isChecked);
                    });
                }
                refreshReadRulesAdapter(REFRESH_FULL, 0);
                if (mRvReadRulesDetail != null)
                    mRvReadRulesDetail.setVisibility(mReadRules.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    private void shakeView(View view) {
        view.animate()
                .translationX(20).setDuration(50)
                .withEndAction(() -> view.animate()
                        .translationX(-20).setDuration(50)
                        .withEndAction(() -> view.animate()
                                .translationX(10).setDuration(50)
                                .withEndAction(() -> view.animate()
                                        .translationX(0).setDuration(50)
                                        .start())
                                .start())
                        .start())
                .start();
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

    private void rewriteEmptyReadRulesFile() {
        try {
            JSONObject root = new JSONObject();
            root.put("enabled", false);
            root.put("content_rules", new JSONArray());
            writeFile(new File(getFilesDir(), "read_rules.json"), root.toString(2));
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "rewriteEmptyReadRulesFile failed", e);
        }
    }

    private boolean writeFile(File file, String content) {
        File tmpFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            // 原子 rename：防止 write 中途被 shutdownNow 中断导致文件损坏
            if (!tmpFile.renameTo(file)) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            }
            return true;
        } catch (Exception e) {
            XLog.e("ClipboardGuard", "writeFile failed: " + file.getName(), e);
            return false;
        } finally {
            if (tmpFile.exists() && !tmpFile.delete()) {
                XLog.w("ClipboardGuard", "Failed to delete tmp file: " + tmpFile.getName());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Adapter
    // ═══════════════════════════════════════════════════════════════

    class ReadRulesAdapter extends RecyclerView.Adapter<ReadRulesAdapter.ReadRuleViewHolder> {
        private final List<ContentRule> mRulesList;
        private final boolean mIsDefaultRules;

        ReadRulesAdapter(List<ContentRule> rules) {
            this(rules, false);
        }

        ReadRulesAdapter(List<ContentRule> rules, boolean isDefaultRules) {
            mRulesList = rules;
            mIsDefaultRules = isDefaultRules;
        }

        @Override
        @androidx.annotation.NonNull
        public ReadRuleViewHolder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            return new ReadRuleViewHolder(getLayoutInflater().inflate(R.layout.item_content_rule, parent, false));
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull ReadRuleViewHolder holder, int position) {
            ContentRule rule = mRulesList.get(position);
            boolean sm = mIsDefaultRules ? mReadDefaultSelectionMode : mReadRulesSelectionMode;
            Set<ContentRule> ss = mIsDefaultRules ? mReadDefaultSelectedRules : mReadSelectedRules;
            boolean isSel = ss.contains(rule);

            if (sm) {
                holder.layoutNormal.setVisibility(View.GONE);
                holder.layoutSelection.setVisibility(View.VISIBLE);
                holder.tvNameSel.setText(rule.name);
                holder.tvPatternSel.setText(rule.pattern);
                holder.tvRuleStatus.setText(rule.enabled ? "已启用" : "已禁用");
                holder.tvRuleStatus.setTextColor(rule.enabled ?
                        ContextCompat.getColor(ReadRulesDetailActivity.this, R.color.status_active) :
                        ContextCompat.getColor(ReadRulesDetailActivity.this, R.color.status_inactive));
                holder.cbSelected.setChecked(isSel);
                holder.itemView.setOnClickListener(v -> {
                    if (isSel) ss.remove(rule); else ss.add(rule);
                    notifyItemChanged(position);
                    if (mIsDefaultRules) updateReadDefaultSelectedCount(); else updateReadSelectedCount();
                });
                holder.itemView.setOnLongClickListener(null);
            } else {
                holder.layoutNormal.setVisibility(View.VISIBLE);
                holder.layoutSelection.setVisibility(View.GONE);
                holder.tvName.setText(rule.name);
                holder.tvPattern.setText(rule.pattern);
                holder.switchEnabled.setOnCheckedChangeListener(null);
                holder.switchEnabled.setChecked(rule.enabled);
                holder.switchEnabled.setOnCheckedChangeListener((btn, ch) -> {
                    rule.enabled = ch;
                    if (ch && rule.applicablePackages.isEmpty()) {
                        // 开启且无适用域 → 默认使用当前拦截名单
                        List<String> blocked = PermissionProvider.getBlockedReadPackagesDirect(ReadRulesDetailActivity.this);
                        if (!blocked.isEmpty()) rule.applicablePackages.addAll(blocked);
                    }
                    if (mIsDefaultRules) saveDefaultReadRules(); else saveReadRules();
                });
                holder.btnDelete.setVisibility(rule.isDefault ? View.GONE : View.VISIBLE);
                holder.btnEdit.setVisibility(rule.isDefault ? View.GONE : View.VISIBLE);
                holder.btnApps.setVisibility(View.VISIBLE); // 默认规则也显示应用按钮
                holder.btnEdit.setOnClickListener(v -> showEditRuleDialog(rule, true));
                holder.btnDelete.setOnClickListener(v -> { if (!mIsDefaultRules) deleteReadRule(rule); });
                holder.btnApps.setOnClickListener(v -> {
                    int idx = mRulesList.indexOf(rule);
                    if (idx >= 0) {
                        Intent intent = new Intent(ReadRulesDetailActivity.this, ReadRuleAppsActivity.class);
                        intent.putExtra(ReadRuleAppsActivity.EXTRA_RULE_INDEX, idx);
                        intent.putExtra(ReadRuleAppsActivity.EXTRA_IS_DEFAULT_RULE, mIsDefaultRules);
                        intent.putExtra(ReadRuleAppsActivity.EXTRA_RULE_NAME, rule.name);
                        startActivity(intent);
                    }
                });
                if (mIsDefaultRules) {
                    holder.itemView.setOnClickListener(v -> showViewRuleDialog(rule));
                    holder.itemView.setOnLongClickListener(v -> { enterReadDefaultSelectionMode(rule); return true; });
                } else {
                    holder.itemView.setOnClickListener(null);
                    holder.itemView.setOnLongClickListener(v -> { enterReadSelectionMode(rule); return true; });
                }
            }
        }

        @Override
        public int getItemCount() {
            return mRulesList.size();
        }

        void refreshSelectionMode() {
            notifyItemRangeChanged(0, getItemCount());
        }

        class ReadRuleViewHolder extends RecyclerView.ViewHolder {
            View layoutNormal;
            SwitchCompat switchEnabled;
            TextView tvName, tvPattern;
            View btnEdit, btnDelete, btnApps;
            View layoutSelection;
            CheckBox cbSelected;
            TextView tvNameSel, tvPatternSel, tvRuleStatus;

            ReadRuleViewHolder(View iv) {
                super(iv);
                layoutNormal = iv.findViewById(R.id.layout_normal);
                switchEnabled = iv.findViewById(R.id.switch_rule_enabled);
                tvName = iv.findViewById(R.id.tv_rule_name);
                tvPattern = iv.findViewById(R.id.tv_rule_pattern);
                btnEdit = iv.findViewById(R.id.btn_rule_edit);
                btnDelete = iv.findViewById(R.id.btn_rule_delete);
                btnApps = iv.findViewById(R.id.btn_rule_apps);
                layoutSelection = iv.findViewById(R.id.layout_selection);
                cbSelected = iv.findViewById(R.id.cb_rule_selected);
                tvNameSel = iv.findViewById(R.id.tv_rule_name_sel);
                tvPatternSel = iv.findViewById(R.id.tv_rule_pattern_sel);
                tvRuleStatus = iv.findViewById(R.id.tv_rule_status);
            }
        }
    }

    private void refreshReadRulesAdapter(int type, int pos) {
        if (mReadRulesAdapter == null) return;
        switch (type) {
            case REFRESH_INSERT:
                mReadRulesAdapter.notifyItemInserted(pos);
                break;
            case REFRESH_REMOVE:
                mReadRulesAdapter.notifyItemRemoved(pos);
                break;
            case REFRESH_CHANGE:
                mReadRulesAdapter.notifyItemChanged(pos);
                break;
            default:
                mReadRulesAdapter.notifyDataSetChanged();
                break;
        }
    }

    private void refreshReadDefaultRulesAdapter() {
        if (mReadDefaultRulesAdapter != null) {
            mReadDefaultRulesAdapter.refreshSelectionMode();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期清理
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (mCurrentRuleDialog != null && mCurrentRuleDialog.isShowing()) mCurrentRuleDialog.dismiss();
        mCurrentRuleDialog = null;
        super.onDestroy();
    }
}
