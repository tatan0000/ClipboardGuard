package com.android.clipboardguard;

import androidx.appcompat.app.AlertDialog;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.OnBackPressedCallback;
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
 * 写入规则详情管理页。
 *
 * 功能：
 * - 管理自定义写入规则（添加/编辑/删除/启用/禁用）
 * - 管理默认写入规则（内置广告关键词、电商口令等识别规则）
 * - 支持批量选择操作（全选/批量启用/批量禁用/批量删除）
 * - 每条规则可独立配置适用域（指定哪些应用触发该规则）
 *
 * 数据流：规则 JSON 文件 ↔ UI 操作 → 广播同步到 system_server
 * 自定义规则与默认规则分文件存储（write_rules.json / write_default_rules.json），
 * 加载到 Hook 侧时由 ContentRulesManager.mergeRulesForRuntime() 合并。
 */
public class WriteRulesDetailActivity extends AppCompatActivity {

    private RecyclerView mRvWriteRulesDetail;
    private SwitchCompat mSwitchWriteRulesEnabled;

    private boolean mWriteRulesEnabled = false;
    private final List<ContentRule> mWriteRules = new ArrayList<>();
    private WriteRulesAdapter mWriteRulesAdapter;

    private boolean mWriteRulesSelectionMode = false;
    private final Set<ContentRule> mWriteSelectedRules = new HashSet<>();

    private View mWriteBatchCard;
    private TextView mWriteSelectedCount;

    private boolean mShowDefaultRules = false;
    private final List<ContentRule> mWriteDefaultRules = new ArrayList<>();
    private WriteRulesAdapter mWriteDefaultRulesAdapter;
    private View mWriteDefaultBatchCard;
    private TextView mWriteDefaultSelectedCount;
    private final Set<ContentRule> mWriteDefaultSelectedRules = new HashSet<>();
    private boolean mWriteDefaultSelectionMode = false;
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
        setContentView(R.layout.activity_write_rules_detail);

        View appBarView = findViewById(R.id.app_bar);
        if (appBarView != null) {
        ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0),
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarView);
        }

        applyStatusBarAdaptation();
        initToolbar();
        initViews();
        initWriteRulesDetailPage();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mWriteRulesSelectionMode) exitWriteSelectionMode();
                else if (mWriteDefaultSelectionMode) exitWriteDefaultSelectionMode();
                else if (mShowDefaultRules) showMainRulesPage();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从应用选择页返回后刷新规则（applicablePackages 可能已变更）
        if (mWriteRulesAdapter != null) {
            mHandler.post(this::loadWriteRulesSync);
        }
        if (mShowDefaultRules) {
            mExecutor.execute(this::loadDefaultWriteRulesAsync);
        }
    }

    private void initToolbar() {
        mToolbar = findViewById(R.id.toolbar);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.write_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
            mToolbar.setNavigationOnClickListener(v -> {
                if (mShowDefaultRules) showMainRulesPage();
                else finish();
            });
        } else if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.write_rules_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
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
        mContainerMainRules = findViewById(R.id.container_main_rules);
        mContainerDefaultRules = findViewById(R.id.container_default_rules);

        mRvWriteRulesDetail = findViewById(R.id.rv_write_rules_detail);
        mSwitchWriteRulesEnabled = findViewById(R.id.switch_write_rules_enabled);
        TextView tvWriteRulesHint = findViewById(R.id.tv_write_rules_hint);
        MaterialButton btnAddWriteRule = findViewById(R.id.btn_add_write_rule);

        mWriteBatchCard = findViewById(R.id.card_batch_actions_main);
        mWriteSelectedCount = findViewById(R.id.tv_selected_count);
        TextView btnSelectAll = findViewById(R.id.btn_select_all_rules);
        TextView writeDeleteSelected = findViewById(R.id.btn_delete_selected);
        TextView writeEnableSelected = findViewById(R.id.btn_enable_selected);
        TextView writeDisableSelected = findViewById(R.id.btn_disable_selected);

        if (tvWriteRulesHint != null) {
            tvWriteRulesHint.setText(R.string.rules_write_hint);
        }

        if (mSwitchWriteRulesEnabled != null) {
            mSwitchWriteRulesEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mWriteRulesEnabled = isChecked;
                saveEnabledOnly(isChecked);
            });
        }

        if (btnAddWriteRule != null) {
            btnAddWriteRule.setOnClickListener(v -> showEditWriteRuleDialog());
        }

        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> {
                if (mWriteSelectedRules.size() == mWriteRules.size()) mWriteSelectedRules.clear();
                else { mWriteSelectedRules.clear(); mWriteSelectedRules.addAll(mWriteRules); }
                if (mWriteRulesAdapter != null) mWriteRulesAdapter.refreshSelectionMode();
                updateWriteSelectedCount();
            });
        }

        if (writeDeleteSelected != null) writeDeleteSelected.setOnClickListener(v -> deleteWriteSelectedRules());
        if (writeEnableSelected != null) writeEnableSelected.setOnClickListener(v -> enableWriteSelectedRules(true));
        if (writeDisableSelected != null) writeDisableSelected.setOnClickListener(v -> enableWriteSelectedRules(false));

        View cardDefaultRules = findViewById(R.id.card_write_default_rules);
        if (cardDefaultRules != null) cardDefaultRules.setOnClickListener(v -> showDefaultRulesPage());

        RecyclerView rvDefaultRules = findViewById(R.id.rv_write_default_rules);
        mWriteDefaultBatchCard = findViewById(R.id.card_batch_actions_write_default);
        mWriteDefaultSelectedCount = findViewById(R.id.tv_default_selected_count);
        TextView btnEnableDefault = findViewById(R.id.btn_enable_selected_default);
        TextView btnDisableDefault = findViewById(R.id.btn_disable_selected_default);
        TextView btnSelectAllDefault = findViewById(R.id.btn_select_all_default);

        if (btnEnableDefault != null) btnEnableDefault.setOnClickListener(v -> enableWriteDefaultSelected(true));
        if (btnDisableDefault != null) btnDisableDefault.setOnClickListener(v -> enableWriteDefaultSelected(false));
        if (btnSelectAllDefault != null) btnSelectAllDefault.setOnClickListener(v -> toggleWriteDefaultSelectAll());

        if (mWriteDefaultRulesAdapter == null) {
            mWriteDefaultRulesAdapter = new WriteRulesAdapter(mWriteDefaultRules, true);
        }
        if (rvDefaultRules != null) {
            rvDefaultRules.setLayoutManager(new LinearLayoutManager(this));
            rvDefaultRules.setAdapter(mWriteDefaultRulesAdapter);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 页面初始化与切换
    // ═══════════════════════════════════════════════════════════════

    private void initWriteRulesDetailPage() {
        if (mWriteRulesAdapter == null) mWriteRulesAdapter = new WriteRulesAdapter(mWriteRules);
        if (mRvWriteRulesDetail != null) {
            mRvWriteRulesDetail.setLayoutManager(new LinearLayoutManager(this));
            mRvWriteRulesDetail.setAdapter(mWriteRulesAdapter);
        }
        mHandler.post(this::loadWriteRulesSync);
    }

    private void showDefaultRulesPage() {
        mShowDefaultRules = true;
        if (mContainerMainRules != null) mContainerMainRules.setVisibility(View.GONE);
        if (mContainerDefaultRules != null) mContainerDefaultRules.setVisibility(View.VISIBLE);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.default_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
        }
        mExecutor.execute(this::loadDefaultWriteRulesAsync);
    }

    private void showMainRulesPage() {
        mShowDefaultRules = false;
        exitWriteDefaultSelectionMode();
        if (mContainerMainRules != null) mContainerMainRules.setVisibility(View.VISIBLE);
        if (mContainerDefaultRules != null) mContainerDefaultRules.setVisibility(View.GONE);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.write_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
        }
    }

    // 加载默认规则：
    // 1. 文件不存在 → 模板初始化（名字 + 正则，适用域为空），写盘
    // 2. 文件存在 + 正则与模板一致 → 直接从文件加载
    // 3. 文件存在 + 正则与模板不同（App 更新改了正则） → 合并：
    //    用模板的正则，保留用户的 enabled + applicablePackages，写盘
    private void loadDefaultWriteRulesAsync() {
        File file = new File(getFilesDir(), "write_default_rules.json");

        if (file.exists()) {
            try {
                String content = readFile(file);
                JSONObject root = new JSONObject(content);
                JSONArray arr = root.optJSONArray("content_rules");
                if (arr == null || arr.length() == 0) {
                    if (!file.delete()) {
                        XLog.w("ClipboardGuard-Rules", "Failed to delete empty default write rules file");
                    }
                    initDefaultWriteRulesFromTemplate();
                } else {
                    // 加载文件中的规则，以 name 为 key
                    Map<String, ContentRule> fileRules = new LinkedHashMap<>();
                    for (int i = 0; i < arr.length(); i++) {
                        ContentRule rule = ContentRule.fromJson(arr.getJSONObject(i));
                        fileRules.put(rule.name, rule);
                    }

                    // 检查是否需要合并：模板中任一规则在文件中不存在或正则不同
                    boolean needsMerge = false;
                    String[][] template = getWriteDefaultRulesTemplate();
                    for (String[] ruleDef : template) {
                        ContentRule fileRule = fileRules.get(ruleDef[0]);
                        if (fileRule == null || !ruleDef[1].equals(fileRule.pattern)) {
                            needsMerge = true;
                            break;
                        }
                    }

                    if (needsMerge) {
                        mergeDefaultWriteRules(template, fileRules);
                        saveDefaultWriteRulesToFile();
                    } else {
                        // 正则完全一致，直接使用文件数据
                        mWriteDefaultRules.clear();
                        mWriteDefaultRules.addAll(fileRules.values());
                        for (ContentRule r : mWriteDefaultRules) r.isDefault = true;
                    }
                }
            } catch (Exception e) {
                XLog.e("ClipboardGuard-Rules", "loadDefaultWriteRules failed, fallback to template", e);
                if (!file.delete()) {
                    XLog.w("ClipboardGuard-Rules", "Failed to delete corrupted default write rules file");
                }
                initDefaultWriteRulesFromTemplate();
            }
        } else {
            initDefaultWriteRulesFromTemplate();
        }

        mHandler.post(this::refreshWriteDefaultRulesAdapter);
    }

    // 合并：模板提供名字和正则，文件提供 enabled 和 applicablePackages
    private void mergeDefaultWriteRules(String[][] template, Map<String, ContentRule> fileRules) {
        mWriteDefaultRules.clear();
        for (String[] ruleDef : template) {
            ContentRule oldRule = fileRules.get(ruleDef[0]);
            boolean enabled = oldRule != null && oldRule.enabled;
            ContentRule newRule = new ContentRule(ruleDef[0], ruleDef[1], enabled, true);
            if (oldRule != null && !oldRule.applicablePackages.isEmpty()) {
                newRule.applicablePackages.addAll(oldRule.applicablePackages);
            }
            mWriteDefaultRules.add(newRule);
        }
    }

    private void initDefaultWriteRulesFromTemplate() {
        mWriteDefaultRules.clear();
        for (String[] ruleDef : getWriteDefaultRulesTemplate()) {
            mWriteDefaultRules.add(new ContentRule(ruleDef[0], ruleDef[1], false, true));
        }
        saveDefaultWriteRulesToFile();
    }

    private String[][] getWriteDefaultRulesTemplate() {
        return new String[][] {
                {"广告关键词", "(?:推广|广告|秒杀|限时抢购|领券|优惠券)"},
                {"电商口令", "[￥$₴¢€£¥√|*#][A-Za-z0-9]{3,}[￥$₴¢€£¥√|*#]"}
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // 默认写入规则选择
    // ═══════════════════════════════════════════════════════════════

    private void enterWriteDefaultSelectionMode(ContentRule rule) {
        mWriteDefaultSelectionMode = true;
        mWriteDefaultSelectedRules.clear();
        mWriteDefaultSelectedRules.add(rule);
        if (mWriteDefaultRulesAdapter != null) mWriteDefaultRulesAdapter.refreshSelectionMode();
        if (mWriteDefaultBatchCard != null) mWriteDefaultBatchCard.setVisibility(View.VISIBLE);
        updateWriteDefaultSelectedCount();
    }

    private void exitWriteDefaultSelectionMode() {
        mWriteDefaultSelectionMode = false;
        mWriteDefaultSelectedRules.clear();
        if (mWriteDefaultRulesAdapter != null) mWriteDefaultRulesAdapter.refreshSelectionMode();
        if (mWriteDefaultBatchCard != null) mWriteDefaultBatchCard.setVisibility(View.GONE);
    }

    private void updateWriteDefaultSelectedCount() {
        if (mWriteDefaultSelectedCount != null)
            mWriteDefaultSelectedCount.setText(getString(R.string.selected_count, mWriteDefaultSelectedRules.size()));
    }

    private void enableWriteDefaultSelected(boolean enable) {
        if (mWriteDefaultSelectedRules.isEmpty()) return;
        for (ContentRule rule : mWriteDefaultSelectedRules) rule.enabled = enable;
        saveDefaultWriteRules(); // 用户操作，保存并广播
        refreshWriteDefaultRulesAdapter();
        exitWriteDefaultSelectionMode();
    }

    private void toggleWriteDefaultSelectAll() {
        if (mWriteDefaultSelectedRules.size() == mWriteDefaultRules.size()) mWriteDefaultSelectedRules.clear();
        else { mWriteDefaultSelectedRules.clear(); mWriteDefaultSelectedRules.addAll(mWriteDefaultRules); }
        if (mWriteDefaultRulesAdapter != null) mWriteDefaultRulesAdapter.refreshSelectionMode();
        updateWriteDefaultSelectedCount();
    }

    // ═══════════════════════════════════════════════════════════════
    // 保存方法（仅在用户操作时调用）
    // ═══════════════════════════════════════════════════════════════

    /** 仅更新 enabled 字段，不重写整个规则数组 */
    private void saveEnabledOnly(boolean enabled) {
        try {
            File file = new File(getFilesDir(), "write_rules.json");
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
                XLog.e("ClipboardGuard-Rules", "写入规则总开关保存失败");
                return;
            }
            notifyRulesChanged();
            XLog.i("ClipboardGuard-Rules", "写入规则总开关已" + (enabled ? "开启" : "关闭"));
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "写入规则总开关保存异常", e);
        }
    }

    private void saveWriteRules() {
        try {
            JSONObject root = new JSONObject();
            root.put("enabled", mWriteRulesEnabled);
            JSONArray arr = new JSONArray();
            for (ContentRule rule : mWriteRules) arr.put(rule.toJson());
            root.put("content_rules", arr);
            File file = new File(getFilesDir(), "write_rules.json");
            if (!writeFile(file, root.toString(2))) {
                XLog.e("ClipboardGuard-Rules", "写入规则保存失败，跳过同步");
                return;
            }

            notifyRulesChanged();
            XLog.i("ClipboardGuard-Rules", "写入规则已保存并同步，自定义规则数=" + arr.length());
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "写入规则保存异常", e);
        }
    }

    /** 用户操作默认规则后保存并广播 */
    private void saveDefaultWriteRules() {
        if (saveDefaultWriteRulesToFile()) {
            notifyRulesChanged();
            XLog.i("ClipboardGuard-Rules", "默认写入规则已保存并同步");
        }
    }

    /** 仅写入文件，不广播（用于首次初始化）。统一使用 { "enabled": ..., "content_rules": [...] } 格式 */
    private boolean saveDefaultWriteRulesToFile() {
        try {
            JSONArray arr = new JSONArray();
            boolean hasEnabled = false;
            for (ContentRule rule : mWriteDefaultRules) {
                arr.put(rule.toJson());
                if (rule.enabled) hasEnabled = true;
            }
            JSONObject root = new JSONObject();
            root.put("enabled", hasEnabled);
            root.put("content_rules", arr);
            File file = new File(getFilesDir(), "write_default_rules.json");
            boolean ok = writeFile(file, root.toString());
            if (ok) XLog.i("ClipboardGuard-Rules", "已写入默认写入规则文件");
            return ok;
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "saveDefaultWriteRulesToFile failed", e);
            return false;
        }
    }

    /**
     * 发送合并后的写入规则广播（自定义规则 + 启用的默认规则）
     */
    private void notifyRulesChanged() {
        PermissionProvider.broadcastRulesOnly(this, "write");
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

    private void showEditWriteRuleDialog() { showEditRuleDialog(null, false); }

    private void showEditRuleDialog(ContentRule rule, boolean isEdit) {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_rule, null);
        TextInputLayout tilName = dialogView.findViewById(R.id.til_rule_name);
        TextInputLayout tilPattern = dialogView.findViewById(R.id.til_rule_pattern);
        TextInputEditText etName = dialogView.findViewById(R.id.et_rule_name);
        TextInputEditText etPattern = dialogView.findViewById(R.id.et_rule_pattern);
        if (tilName == null || tilPattern == null || etName == null || etPattern == null) return;

        if (isEdit && rule != null) { etName.setText(rule.name); etPattern.setText(rule.pattern); }

        String title = isEdit ? getString(R.string.rules_dialog_title_edit) : getString(R.string.rules_dialog_title_add);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(title).setView(dialogView)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(android.R.string.cancel, null);

        mCurrentRuleDialog = builder.create();
        mCurrentRuleDialog.setCanceledOnTouchOutside(false);
        mCurrentRuleDialog.setOnDismissListener(dialog -> mCurrentRuleDialog = null);
        mCurrentRuleDialog.setOnShowListener(dialog -> {
            AlertDialog currentDialog = mCurrentRuleDialog;
            if (currentDialog == null || mDestroyed || isFinishing() || isDestroyed()) return;
            Button positiveButton = currentDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) positiveButton.setOnClickListener(v ->
                    attemptSaveRule(etName, etPattern, tilName, tilPattern, rule, isEdit));
            Button negativeButton = currentDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negativeButton != null) negativeButton.setOnClickListener(v -> currentDialog.dismiss());
        });
        mCurrentRuleDialog.show();

        TextWatcher textWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateSaveButtonState(etName, etPattern, tilName, tilPattern); }
        };
        etName.addTextChangedListener(textWatcher);
        etPattern.addTextChangedListener(textWatcher);
        updateSaveButtonState(etName, etPattern, tilName, tilPattern);
    }

    private void updateSaveButtonState(TextInputEditText etName, TextInputEditText etPattern,
                                       TextInputLayout tilName, TextInputLayout tilPattern) {
        if (mCurrentRuleDialog == null) return;
        Button saveButton = mCurrentRuleDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (saveButton == null) return;
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String pattern = etPattern.getText() != null ? etPattern.getText().toString().trim() : "";
        saveButton.setEnabled(!name.isEmpty() && !pattern.isEmpty());
        tilName.setError(null); tilPattern.setError(null);
    }

    private void attemptSaveRule(TextInputEditText etName, TextInputEditText etPattern,
                                 TextInputLayout tilName, TextInputLayout tilPattern,
                                 ContentRule rule, boolean isEdit) {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String pattern = etPattern.getText() != null ? etPattern.getText().toString().trim() : "";
        if (name.isEmpty()) { tilName.setError(getString(R.string.rules_name_required)); return; }
        tilName.setError(null);
        try { java.util.regex.Pattern.compile(pattern); tilPattern.setError(null); }
        catch (Exception e) { tilPattern.setError(getString(R.string.rules_regex_error)); shakeView(tilPattern); return; }

        // 检测危险正则模式（可能导致灾难性回溯）
        String dangerWarning = ContentRule.checkDangerousPattern(pattern);
        if (dangerWarning != null) {
            tilPattern.setError(dangerWarning);
            shakeView(tilPattern);
            return;
        }

        // 检查命名重复
        for (ContentRule r : mWriteRules) {
            if (r == rule) continue; // 编辑时跳过自身
            if (name.equals(r.name)) {
                tilName.setError(getString(R.string.rules_name_duplicate));
                shakeView(tilName);
                return;
            }
        }
        // 检查正则重复
        for (ContentRule r : mWriteRules) {
            if (r == rule) continue;
            if (pattern.equals(r.pattern)) {
                tilPattern.setError(getString(R.string.rules_pattern_duplicate));
                shakeView(tilPattern);
                return;
            }
        }

        if (isEdit && rule != null) {
            rule.name = name; rule.pattern = pattern; rule.compilePattern();
        } else {
            ContentRule newRule = new ContentRule(name, pattern, true);
            // 新建规则自动勾选当前拦截名单
            List<String> blocked = PermissionProvider.getBlockedWritePackagesDirect(this);
            if (!blocked.isEmpty()) newRule.applicablePackages.addAll(blocked);
            mWriteRules.add(newRule);
        }
        saveWriteRules(); // 用户操作，保存并广播
        if (isEdit && rule != null) {
            int pos = mWriteRules.indexOf(rule);
            if (pos >= 0) refreshWriteRulesAdapter(REFRESH_CHANGE, pos);
        } else {
            refreshWriteRulesAdapter(REFRESH_INSERT, mWriteRules.size() - 1);
        }
        if (mRvWriteRulesDetail != null) {
            mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (mCurrentRuleDialog != null && mCurrentRuleDialog.isShowing()) {
            mCurrentRuleDialog.dismiss();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 批量选择
    // ═══════════════════════════════════════════════════════════════

    private void enterWriteSelectionMode(ContentRule rule) {
        mWriteRulesSelectionMode = true; mWriteSelectedRules.clear(); mWriteSelectedRules.add(rule);
        if (mWriteRulesAdapter != null) mWriteRulesAdapter.refreshSelectionMode();
        if (mWriteBatchCard != null) mWriteBatchCard.setVisibility(View.VISIBLE);
        updateWriteSelectedCount();
    }
    private void exitWriteSelectionMode() {
        mWriteRulesSelectionMode = false; mWriteSelectedRules.clear();
        if (mWriteRulesAdapter != null) mWriteRulesAdapter.refreshSelectionMode();
        if (mWriteBatchCard != null) mWriteBatchCard.setVisibility(View.GONE);
    }
    private void updateWriteSelectedCount() {
        if (mWriteSelectedCount != null) mWriteSelectedCount.setText(getString(R.string.selected_count, mWriteSelectedRules.size()));
    }
    private void deleteWriteSelectedRules() {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        int count = mWriteSelectedRules.size();
        if (count == 0) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rules_delete_confirm_title)
                .setMessage(getString(R.string.rules_delete_selected_confirm, count))
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (mDestroyed || isFinishing() || isDestroyed()) return;
                    mWriteRules.removeAll(mWriteSelectedRules);
                    saveWriteRules(); // 用户操作，保存并广播
                    refreshWriteRulesAdapter(REFRESH_FULL, 0);
                    if (mRvWriteRulesDetail != null) {
                        mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    exitWriteSelectionMode();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void deleteWriteRule(ContentRule rule) {
        if (mDestroyed || isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.rules_delete_confirm)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (mDestroyed || isFinishing() || isDestroyed()) return;
                    int pos = mWriteRules.indexOf(rule);
                    mWriteRules.remove(rule);
                    saveWriteRules(); // 用户操作，保存并广播
                    refreshWriteRulesAdapter(pos >= 0 ? REFRESH_REMOVE : REFRESH_FULL, Math.max(pos, 0));
                    if (mRvWriteRulesDetail != null) {
                        mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void enableWriteSelectedRules(boolean enable) {
        if (mWriteSelectedRules.isEmpty()) return;
        for (ContentRule rule : mWriteSelectedRules) rule.enabled = enable;
        saveWriteRules(); // 用户操作，保存并广播
        refreshWriteRulesAdapter(REFRESH_FULL, 0);
        exitWriteSelectionMode();
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据加载
    // ═══════════════════════════════════════════════════════════════

    private void loadWriteRulesSync() {
        mExecutor.execute(() -> {
            boolean enabled = false;
            List<ContentRule> rules = new ArrayList<>();
            boolean shouldRewrite = false;
            try {
                File file = new File(getFilesDir(), "write_rules.json");
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
                XLog.e("ClipboardGuard-Rules", "loadWriteRulesSync failed", e);
            }

            if (shouldRewrite) {
                rewriteEmptyWriteRulesFile();
            }

            final boolean fe = enabled;
            final List<ContentRule> fr = new ArrayList<>(rules);
            mHandler.post(() -> {
                if (mDestroyed || isFinishing() || isDestroyed()) return;
                mWriteRulesEnabled = fe;
                mWriteRules.clear();
                mWriteRules.addAll(fr);
                // 设置开关状态，暂移除监听器防止触发保存
                if (mSwitchWriteRulesEnabled != null) {
                    mSwitchWriteRulesEnabled.setOnCheckedChangeListener(null);
                    mSwitchWriteRulesEnabled.setChecked(fe);
                    mSwitchWriteRulesEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        mWriteRulesEnabled = isChecked;
                        saveEnabledOnly(isChecked);
                    });
                }
                refreshWriteRulesAdapter(REFRESH_FULL, 0);
                if (mRvWriteRulesDetail != null)
                    mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
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
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void rewriteEmptyWriteRulesFile() {
        try {
            JSONObject root = new JSONObject();
            root.put("enabled", false);
            root.put("content_rules", new JSONArray());
            writeFile(new File(getFilesDir(), "write_rules.json"), root.toString(2));
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "rewriteEmptyWriteRulesFile failed", e);
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
                // rename 失败时回退覆盖写
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

    class WriteRulesAdapter extends RecyclerView.Adapter<WriteRulesAdapter.WriteRuleViewHolder> {
        private final List<ContentRule> mRulesList;
        private final boolean mIsDefaultRules;
        WriteRulesAdapter(List<ContentRule> rules) { this(rules, false); }
        WriteRulesAdapter(List<ContentRule> rules, boolean isDefaultRules) { mRulesList = rules; mIsDefaultRules = isDefaultRules; }

        @Override
        @androidx.annotation.NonNull
        public WriteRuleViewHolder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            return new WriteRuleViewHolder(getLayoutInflater().inflate(R.layout.item_content_rule, parent, false));
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull WriteRuleViewHolder holder, int position) {
            ContentRule rule = mRulesList.get(position);
            boolean selectionMode = mIsDefaultRules ? mWriteDefaultSelectionMode : mWriteRulesSelectionMode;
            Set<ContentRule> selectedSet = mIsDefaultRules ? mWriteDefaultSelectedRules : mWriteSelectedRules;
            boolean isSelected = selectedSet.contains(rule);

            if (selectionMode) {
                holder.layoutNormal.setVisibility(View.GONE);
                holder.layoutSelection.setVisibility(View.VISIBLE);
                holder.tvNameSel.setText(rule.name); holder.tvPatternSel.setText(rule.pattern);
                holder.tvRuleStatus.setText(rule.enabled ? "已启用" : "已禁用");
                holder.tvRuleStatus.setTextColor(rule.enabled ?
                        ContextCompat.getColor(WriteRulesDetailActivity.this, R.color.status_active) :
                        ContextCompat.getColor(WriteRulesDetailActivity.this, R.color.status_inactive));
                holder.cbSelected.setChecked(isSelected);
                holder.itemView.setOnClickListener(v -> {
                    if (isSelected) selectedSet.remove(rule); else selectedSet.add(rule);
                    notifyItemChanged(position);
                    if (mIsDefaultRules) updateWriteDefaultSelectedCount(); else updateWriteSelectedCount();
                });
                holder.itemView.setOnLongClickListener(null);
            } else {
                holder.layoutNormal.setVisibility(View.VISIBLE);
                holder.layoutSelection.setVisibility(View.GONE);
                holder.tvName.setText(rule.name); holder.tvPattern.setText(rule.pattern);
                holder.switchEnabled.setOnCheckedChangeListener(null);
                holder.switchEnabled.setChecked(rule.enabled);
                holder.switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
                    rule.enabled = checked;
                    if (checked && rule.applicablePackages.isEmpty()) {
                        // 开启且无适用域 → 默认使用当前拦截名单
                        List<String> blocked = PermissionProvider.getBlockedWritePackagesDirect(WriteRulesDetailActivity.this);
                        if (!blocked.isEmpty()) rule.applicablePackages.addAll(blocked);
                    }
                    // 用户操作开关，保存并广播
                    if (mIsDefaultRules) saveDefaultWriteRules();
                    else saveWriteRules();
                });
                holder.btnDelete.setVisibility(rule.isDefault ? View.GONE : View.VISIBLE);
                holder.btnEdit.setVisibility(rule.isDefault ? View.GONE : View.VISIBLE);
                holder.btnApps.setVisibility(View.VISIBLE); // 默认规则也显示应用按钮
                holder.btnEdit.setOnClickListener(v -> showEditRuleDialog(rule, true));
                holder.btnDelete.setOnClickListener(v -> { if (!mIsDefaultRules) deleteWriteRule(rule); });
                holder.btnApps.setOnClickListener(v -> {
                    int idx = mRulesList.indexOf(rule);
                    if (idx >= 0) {
                        Intent intent = new Intent(WriteRulesDetailActivity.this, WriteRuleAppsActivity.class);
                        intent.putExtra(WriteRuleAppsActivity.EXTRA_RULE_INDEX, idx);
                        intent.putExtra(WriteRuleAppsActivity.EXTRA_IS_DEFAULT_RULE, mIsDefaultRules);
                        intent.putExtra(WriteRuleAppsActivity.EXTRA_RULE_NAME, rule.name);
                        startActivity(intent);
                    }
                });
                if (mIsDefaultRules) {
                    holder.itemView.setOnClickListener(v -> showViewRuleDialog(rule));
                    holder.itemView.setOnLongClickListener(v -> { enterWriteDefaultSelectionMode(rule); return true; });
                } else {
                    holder.itemView.setOnClickListener(null);
                    holder.itemView.setOnLongClickListener(v -> { enterWriteSelectionMode(rule); return true; });
                }
            }
        }

        @Override public int getItemCount() { return mRulesList.size(); }
        void refreshSelectionMode() { notifyItemRangeChanged(0, getItemCount()); }
    
        class WriteRuleViewHolder extends RecyclerView.ViewHolder {
            View layoutNormal; SwitchCompat switchEnabled; TextView tvName, tvPattern; View btnEdit, btnDelete, btnApps;
            View layoutSelection; CheckBox cbSelected; TextView tvNameSel, tvPatternSel, tvRuleStatus;
            WriteRuleViewHolder(View itemView) {
                super(itemView);
                layoutNormal = itemView.findViewById(R.id.layout_normal);
                switchEnabled = itemView.findViewById(R.id.switch_rule_enabled);
                tvName = itemView.findViewById(R.id.tv_rule_name);
                tvPattern = itemView.findViewById(R.id.tv_rule_pattern);
                btnEdit = itemView.findViewById(R.id.btn_rule_edit);
                btnDelete = itemView.findViewById(R.id.btn_rule_delete);
                btnApps = itemView.findViewById(R.id.btn_rule_apps);
                layoutSelection = itemView.findViewById(R.id.layout_selection);
                cbSelected = itemView.findViewById(R.id.cb_rule_selected);
                tvNameSel = itemView.findViewById(R.id.tv_rule_name_sel);
                tvPatternSel = itemView.findViewById(R.id.tv_rule_pattern_sel);
                tvRuleStatus = itemView.findViewById(R.id.tv_rule_status);
            }
        }
    }

    private void refreshWriteRulesAdapter(int type, int pos) {
        if (mWriteRulesAdapter == null) return;
        switch (type) {
            case REFRESH_INSERT:
                mWriteRulesAdapter.notifyItemInserted(pos);
                break;
            case REFRESH_REMOVE:
                mWriteRulesAdapter.notifyItemRemoved(pos);
                break;
            case REFRESH_CHANGE:
                mWriteRulesAdapter.notifyItemChanged(pos);
                break;
            default:
                mWriteRulesAdapter.notifyDataSetChanged();
                break;
        }
    }

    private void refreshWriteDefaultRulesAdapter() {
        if (mWriteDefaultRulesAdapter != null) {
            mWriteDefaultRulesAdapter.refreshSelectionMode();
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
